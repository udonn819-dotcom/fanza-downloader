package com.udonn.fanzadrive

import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 予約の結果: 予約した / すでに保存待ち（重複） / 一時停止中なので再開後に処理 */
enum class Queued { QUEUED, DUPLICATE, PAUSED }

/** 保存待ちの台帳の1件 */
class Job(val key: String, val json: JSONObject) {
    val url: String get() = json.getString(DownloadWorker.KEY_URL)
    fun str(k: String): String? = json.optString(k).ifBlank { null }
    val title get() = str(DownloadWorker.KEY_TITLE_HINT)
    val cid get() = str(DownloadWorker.KEY_CID)
    val label get() = title ?: cid ?: "作品"
}

/** 処理中の作品の様子（「保存状況」画面に表示する） */
data class ActiveJob(val label: String, val text: String, val percent: Int)

/**
 * 保存の処理役（アプリ全体で1つだけ動く）。
 * 保存待ちの台帳から2件ずつ順番に取り出し、
 * 「ダウンロード → 作品タイトルにリネーム → 保存（ドライブ／本体）」を行う。
 * アプリを閉じても、画面を消しても処理は続き、通信が切れたら少し待って続きから再試行する。
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val ctx get() = applicationContext
    private var lastNotify = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    /** 通知は1つだけ（全体の件数・完了・失敗・残りを表示） */
    private fun foregroundInfo() = ForegroundInfo(
        Notifier.SUMMARY_ID,
        Notifier.summary(ctx),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun stopped() = isStopped || Prefs.paused(ctx)

    private fun report(job: Job, text: String, done: Long, total: Long, force: Boolean = false) {
        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
        val sizeText = when {
            total > 0 -> "  ${mb(done)} / ${mb(total)}"
            done > 0 -> "  ${mb(done)}"
            else -> ""
        }
        active[job.key] = ActiveJob(job.label, text + sizeText, percent)
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        runCatching { setForegroundAsync(foregroundInfo()) }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 古いバージョンで1作品ずつ予約されていた分は、台帳に移して終わる
        if (inputData.getString(KEY_URL) != null) {
            migrateLegacy()
            return@withContext Result.success()
        }
        runCatching { setForeground(foregroundInfo()) }
        while (!stopped()) {
            coroutineScope { repeat(PARALLEL) { launch { lane() } } }
            // 終わり際に追加された作品を取りこぼさないよう、もう一度確認
            if (stopped() || nextWaitMs() == null) break
        }
        if (!stopped() && JobQueue.size(ctx) == 0) Notifier.finished(ctx)
        Result.success()
    }

    /** 1つのレーン。台帳から1件取り出して処理、を繰り返す（レーンは2本＝同時に2件） */
    private suspend fun lane() {
        while (!stopped()) {
            val job = claim()
            if (job == null) {
                val wait = nextWaitMs() ?: return
                delay(wait.coerceIn(1000, 10_000))
                continue
            }
            try {
                runJob(job)
            } finally {
                active.remove(job.key)
                runCatching { setForegroundAsync(foregroundInfo()) }
            }
        }
    }

    /** 次に処理する作品を選ぶ。途中まで進んでいる作品 → 古い順 */
    private fun claim(): Job? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val candidates = JobQueue.all(ctx)
            .filter { (k, j) -> k !in active.keys && j.optLong(J_RETRY_AT) <= now }
            .map { (k, j) -> Job(k, j) }
        for (job in candidates) {
            // 重複の自動除外: 同じ作品が先に保存済みになっていたら台帳から外す
            if (!job.json.optBoolean(KEY_FORCE) && isSaved(ctx, job.url, job.cid, job.title)) {
                JobQueue.remove(ctx, job.key)
            }
        }
        val job = candidates
            .filter { JobQueue.contains(ctx, it.key) }
            .sortedWith(compareByDescending<Job> { hasPartial(it.key) }.thenBy { it.json.optLong(J_SEQ) })
            .firstOrNull() ?: return null
        active[job.key] = ActiveJob(job.label, "準備中…", -1)
        job
    }

    /** まだ処理していない作品があれば、それが処理できるようになるまでの時間（無ければ null） */
    private fun nextWaitMs(): Long? {
        val now = System.currentTimeMillis()
        return JobQueue.all(ctx).filter { it.key !in active.keys }.values
            .minOfOrNull { maxOf(0L, it.optLong(J_RETRY_AT) - now) }
    }

    private fun hasPartial(key: String) = File(JobQueue.jobDir(ctx, key), "data.bin").let { it.exists() && it.length() > 0 }

    private fun runJob(job: Job) {
        val jobDir = JobQueue.jobDir(ctx, job.key).apply { mkdirs() }
        try {
            val name = process(job, jobDir)
            Prefs.markDone(ctx, job.key)
            job.title?.let { Prefs.markDone(ctx, "t:$it") }
            Prefs.markDone(ctx, historyKey(job.url, job.cid))
            JobQueue.remove(ctx, job.key)
            Prefs.addStat(ctx, Prefs.ST_DONE, 1)
            JobLog.add(ctx, true, name, null)
        } catch (e: Exception) {
            when {
                stopped() -> Unit // 一時停止・アップデート等。途中のファイルは残して続きから再開する
                e is PermanentException -> fail(job, e.message ?: "不明なエラー")
                e is IOException -> retryLater(job, e.message ?: "通信エラー")
                else -> fail(job, "予期しないエラー: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }
    }

    /** 通信エラーなどは少し待ってから続きを再試行（30秒→1分→2分→4分） */
    private fun retryLater(job: Job, message: String) {
        val attempts = job.json.optInt(J_ATTEMPTS) + 1
        if (attempts >= MAX_ATTEMPTS) {
            fail(job, "通信エラー（${attempts}回失敗）: $message")
            return
        }
        val wait = minOf(30_000L shl (attempts - 1), 30 * 60_000L)
        job.json.put(J_ATTEMPTS, attempts)
            .put(J_RETRY_AT, System.currentTimeMillis() + wait)
            .put(J_LAST_ERROR, "通信エラー（${attempts}回目）: $message")
        JobQueue.put(ctx, job.key, job.json)
    }

    private fun fail(job: Job, message: String) {
        val data = File(JobQueue.jobDir(ctx, job.key), "data.bin")
        val msg = if (data.exists() && data.length() > 0) {
            // URLの期限切れなどで失敗しても、途中までのデータは残す。押し直すと続きから再開できる
            JobQueue.keep(ctx, job.key, job.title, job.cid)
            "$message\n→ 途中まで（${mb(data.length())}）のデータは残してあります。「ダウンロードする」を押し直すか「一括保存」で続きから再開します"
        } else {
            JobQueue.remove(ctx, job.key)
            message
        }
        Prefs.addStat(ctx, Prefs.ST_FAILED, 1)
        JobLog.add(ctx, false, job.label, msg)
    }

    private fun migrateLegacy() {
        val url = inputData.getString(KEY_URL) ?: return
        val key = inputData.getString(KEY_HISTORY) ?: url
        val title = inputData.getString(KEY_TITLE_HINT)?.ifBlank { null }
        val cid = inputData.getString(KEY_CID)?.ifBlank { null }
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!JobQueue.contains(ctx, key) && (force || !isSaved(ctx, url, cid, title))) {
            JobQueue.put(ctx, key, jobJson(url, inputData.getString(KEY_UA), inputData.getString(KEY_REFERER), inputData.getString(KEY_CD), cid, title, force))
        }
        ensureRunner(ctx)
    }

    /** 途中で失敗して再試行になっても、済んだ工程はやり直さないよう状態をファイルに残す */
    private fun process(job: Job, jobDir: File): String {
        val stateFile = File(jobDir, "state.json")
        val state = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        fun saveState() = stateFile.writeText(state.toString())
        val data = File(jobDir, "data.bin")
        val url = job.url

        // ① ダウンロード
        if (!state.optBoolean("downloaded")) {
            download(job, url, data, state, ::saveState)
            state.put("downloaded", true)
            saveState()
        }

        // ② 作品タイトルを調べてファイル名を決める
        if (!state.has("fileName")) {
            state.put("fileName", decideFileName(job, url, state))
            saveState()
        }
        val fileName = state.getString("fileName")
        val ext = FileNames.extensionOf(fileName) ?: "zip"
        val mime = state.optString("mime").takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: FileNames.mimeFromExtension(ext)

        // ③ スマホ本体に保存（設定ON、またはドライブ未設定の場合）
        val drive = Prefs.driveEnabled(ctx)
        if ((Prefs.saveLocal(ctx) || !drive) && !state.optBoolean("localDone")) {
            report(job, "本体に保存中", 0, 0, force = true)
            saveToDownloads(data, fileName, mime)
            state.put("localDone", true)
            saveState()
        }

        // ④ Googleドライブに保存
        if (drive && !state.optBoolean("driveDone")) {
            val uploader = DriveUploader.create(ctx)
            report(job, "ドライブを確認中", 0, 0, force = true)
            val existing = uploader.existingSize(fileName)
            if (existing == null || existing != data.length()) {
                uploader.upload(
                    data, fileName, mime,
                    savedSession = state.optString("uploadSession").ifBlank { null },
                    onSession = { session ->
                        if (session == null) state.remove("uploadSession") else state.put("uploadSession", session)
                        saveState()
                    },
                    shouldStop = { stopped() },
                ) { done, total -> report(job, "ドライブへ保存中", done, total) }
            }
            state.put("driveDone", true)
            saveState()
        }
        return fileName + if (drive) "（Googleドライブ）" else "（Download/FANZA）"
    }

    /**
     * ダウンロード。途中まで落としたファイルがあれば、続きから（Rangeリクエスト）再開する。
     * 続きからの再開は、まず前回実際にファイルを受け取ったURL（配信サーバー）で試し、
     * ダメなら作品を押したときのURLで試す。
     */
    private fun download(job: Job, url: String, out: File, state: JSONObject, saveState: () -> Unit) {
        Http.ensureAgeCheckCookie()
        val have = if (out.exists()) out.length() else 0L
        val finalUrl = state.optString("finalUrl")
        if (have > 0 && finalUrl.startsWith("http") && finalUrl != url) {
            try {
                if (downloadFrom(job, finalUrl, out, have, state, saveState, fallbackAllowed = true)) return
            } catch (e: PermanentException) {
                // 配信サーバーのURLは期限切れ。作品を押したときのURLで続きを試す
            }
        }
        downloadFrom(job, url, out, have, state, saveState, fallbackAllowed = false)
    }

    /** @return 完了したら true。fallbackAllowed のとき、続きからに非対応なら false を返して別のURLに任せる */
    private fun downloadFrom(
        job: Job,
        url: String,
        out: File,
        have: Long,
        state: JSONObject,
        saveState: () -> Unit,
        fallbackAllowed: Boolean,
    ): Boolean {
        val builder = Request.Builder().url(url)
            .header("User-Agent", job.str(KEY_UA) ?: Http.DESKTOP_UA)
        job.str(KEY_REFERER)?.takeIf { it.startsWith("http") }?.let { builder.header("Referer", it) }
        if (have > 0) builder.header("Range", "bytes=$have-")

        Http.client.newCall(builder.build()).execute().use { resp ->
            // 416 = もう全部ダウンロード済み
            if (resp.code == 416 && have > 0 && state.optLong("total", -1) == have) return true
            if (!resp.isSuccessful) {
                val msg = "ダウンロード失敗 HTTP ${resp.code}"
                throw if (resp.code in 400..499 && resp.code != 408 && resp.code != 429) PermanentException(msg) else IOException(msg)
            }
            val body = resp.body ?: throw IOException("空の応答")
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            if (mime == "text/html") {
                throw PermanentException("ファイルではなくWebページが返されました。ログインが切れているか、ダウンロードURLの期限が切れています（作品の「ダウンロードする」を押し直してください）")
            }
            val resume = resp.code == 206 && have > 0
            if (have > 0 && !resume && fallbackAllowed) return false
            if (!resume || !state.has("mime")) {
                state.put("mime", mime)
                state.put("contentDisposition", resp.header("Content-Disposition").orEmpty())
            }
            state.put("finalUrl", resp.request.url.toString())
            val length = body.contentLength()
            val total = if (length < 0) -1 else if (resume) have + length else length
            state.put("total", total)
            saveState()

            // 続きから受け取れたかどうかを「保存状況」に表示する
            val label = when {
                resume -> "ダウンロード中（${mb(have)}の続きから再開）"
                have > 0 -> "ダウンロード中（配信サーバーが続きからに非対応のため最初から）"
                else -> "ダウンロード中"
            }
            var done = if (resume) have else 0L
            body.byteStream().use { input ->
                java.io.FileOutputStream(out, resume).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (stopped()) throw IOException("中断されました")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        report(job, label, done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("ダウンロードが途中で切れました（続きから再開します）")
            return true
        }
    }

    private fun decideFileName(job: Job, url: String, state: JSONObject): String {
        val finalUrl = state.optString("finalUrl")
        val cd = state.optString("contentDisposition").ifBlank { job.str(KEY_CD) }
        val original = FileNames.fromContentDisposition(cd) ?: FileNames.fromUrl(finalUrl) ?: FileNames.fromUrl(url)
        val ext = FileNames.extensionOf(original) ?: FileNames.extensionFromMime(state.optString("mime")) ?: "zip"
        val originalBase = original?.substringBeforeLast('.')

        val cid = job.cid
            ?: FileNames.findCid(original, finalUrl, url)

        report(job, "作品タイトルを取得中", 0, 0, force = true)
        val title = cid?.let { TitleResolver.resolve(it) }
            ?: job.title
            ?: originalBase
            ?: cid
            ?: "FANZA作品_${System.currentTimeMillis()}"

        // 1作品が複数ファイルに分かれている場合（d_123456_02.zip など）は区別できるよう番号を残す
        val partSuffix = if (cid != null && originalBase != null && originalBase.contains(cid)) {
            originalBase.replace(cid, "").trim('_', '-', ' ', '.')
        } else ""
        val base = FileNames.sanitize(title) + if (partSuffix.isNotEmpty()) " (${FileNames.sanitize(partSuffix, 40)})" else ""
        return "$base.$ext"
    }

    private fun saveToDownloads(src: File, fileName: String, mime: String) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FANZA")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("本体への保存に失敗しました")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw IOException("本体への保存に失敗しました")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e.message)
        }
    }

    private fun mb(bytes: Long) = String.format("%.1fMB", bytes / 1024.0 / 1024.0)

    companion object {
        private const val MAX_ATTEMPTS = 6
        private const val PARALLEL = 2
        private const val RUNNER = "fanza_runner"
        private val lock = Any()
        private val seq = AtomicLong(System.currentTimeMillis() * 1000)

        /** 今処理中の作品（「保存状況」画面が読む） */
        val active = ConcurrentHashMap<String, ActiveJob>()

        const val KEY_URL = "url"
        const val KEY_UA = "ua"
        const val KEY_REFERER = "referer"
        const val KEY_CD = "content_disposition"
        const val KEY_CID = "cid"
        const val KEY_TITLE_HINT = "title_hint"
        const val KEY_HISTORY = "history_key"
        const val KEY_FORCE = "force"

        // 台帳の各作品に記録する情報
        private const val J_SEQ = "seq"
        private const val J_ATTEMPTS = "attempts"
        private const val J_RETRY_AT = "retry_at"
        const val J_LAST_ERROR = "last_error"

        fun historyKey(url: String, cid: String?): String = cid?.takeIf { it.isNotBlank() } ?: url.substringBefore('?')

        /** 作品を見分けるための名前。作品ID → 作品名 → URL の順に使う */
        fun jobKey(url: String, cid: String?, title: String?): String =
            cid?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }?.let { "t:$it" } ?: url.substringBefore('?')

        fun isSaved(c: Context, url: String, cid: String?, title: String?): Boolean =
            Prefs.isDone(c, jobKey(url, cid, title)) || Prefs.isDone(c, historyKey(url, cid)) ||
                (!title.isNullOrBlank() && Prefs.isDone(c, "t:$title"))

        private fun jobJson(
            url: String, userAgent: String?, referer: String?, contentDisposition: String?,
            cid: String?, titleHint: String?, force: Boolean,
        ) = JSONObject()
            .put(KEY_URL, url)
            .put(KEY_UA, userAgent.orEmpty())
            .put(KEY_REFERER, referer.orEmpty())
            .put(KEY_CD, contentDisposition?.take(1000).orEmpty())
            .put(KEY_CID, cid.orEmpty())
            .put(KEY_TITLE_HINT, titleHint?.take(300).orEmpty())
            .put(KEY_FORCE, force)
            .put(J_SEQ, seq.incrementAndGet())

        /**
         * 保存を予約する（台帳に追加し、処理役を起こす）。
         * 同じ作品がすでに保存待ちなら追加しない（重複の自動除外）。
         */
        fun enqueue(
            context: Context,
            url: String,
            userAgent: String?,
            referer: String?,
            contentDisposition: String?,
            cid: String?,
            titleHint: String?,
            force: Boolean = false,
        ): Queued {
            var key = jobKey(url, cid, titleHint)
            if (JobQueue.contains(context, key)) return Queued.DUPLICATE
            // 前に失敗して途中までのデータを残してある作品なら、そのデータを引き継いで続きから
            if (!JobQueue.jobDir(context, key).exists()) {
                JobQueue.findKept(context, cid, titleHint)?.let { key = it }
                if (JobQueue.contains(context, key)) return Queued.DUPLICATE
            }
            JobQueue.dropKept(context, key)
            // 前回分が全部終わっていたら、通知の件数を数え直す
            if (JobQueue.size(context) == 0) {
                Prefs.resetStats(context)
                Notifier.clearFinished(context)
            }
            JobQueue.put(context, key, jobJson(url, userAgent, referer, contentDisposition, cid, titleHint, force))
            if (Prefs.paused(context)) return Queued.PAUSED
            ensureRunner(context)
            return Queued.QUEUED
        }

        /** 処理役が動いていなければ起こす（すでに動いていれば何もしない） */
        fun ensureRunner(context: Context) {
            if (Prefs.paused(context) || JobQueue.size(context) == 0) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (Prefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RUNNER, ExistingWorkPolicy.KEEP, request)
        }

        /** アプリ起動時・アップデート後に、止まっていた保存待ちを再開する */
        fun restoreQueue(context: Context) = ensureRunner(context)

        /** 一時停止: 処理を止める（途中のファイルと台帳は残す） */
        fun pauseAll(context: Context) {
            Prefs.setPaused(context, true)
            WorkManager.getInstance(context).cancelUniqueWork(RUNNER)
            if (JobQueue.size(context) > 0) Notifier.paused(context)
        }

        /** 再開: 途中から続きを処理する */
        fun resumeAll(context: Context) {
            Prefs.setPaused(context, false)
            Notifier.clearFinished(context)
            ensureRunner(context)
        }

        /** 通信エラーで待機中の作品を、待たずにすぐ再試行する */
        fun retryNow(context: Context) {
            JobQueue.all(context).forEach { (k, j) ->
                if (j.has(J_RETRY_AT)) JobQueue.put(context, k, j.put(J_RETRY_AT, 0L))
            }
            ensureRunner(context)
        }

        const val TAG = "fanza_download"
    }
}

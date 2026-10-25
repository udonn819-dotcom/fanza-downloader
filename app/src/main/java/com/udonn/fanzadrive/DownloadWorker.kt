package com.udonn.fanzadrive

import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Environment
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 予約の結果: 予約した / すでに保存待ち（重複） / 一時停止中なので再開後に処理 */
enum class Queued { QUEUED, DUPLICATE, PAUSED }

/**
 * バックグラウンドで「ダウンロード → 作品タイトルにリネーム → 保存（ドライブ／本体）」を行う。
 * アプリを閉じても、画面を消しても処理は続き、通信が切れたら自動で再試行する。
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val ctx get() = applicationContext
    private var lastNotify = 0L
    private var label = "FANZA作品"

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    /** 通知は全作品で共通の1つ（全体の件数・完了・失敗・残りだけを表示） */
    private fun foregroundInfo() = ForegroundInfo(
        Notifier.SUMMARY_ID,
        Notifier.summary(ctx),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private suspend fun report(text: String, done: Long, total: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        val percent = if (total > 0) ((done * 100) / total).toInt() else null
        val sizeText = if (total > 0) "${mb(done)} / ${mb(total)}" else mb(done)
        runCatching { setForeground(foregroundInfo()) }
        runCatching { setProgress(workDataOf(P_TEXT to "$text  $sizeText", P_PERCENT to (percent ?: -1))) }
    }

    /** suspend できない場所（アップロード中のコールバック）から通知を更新する */
    private fun reportBlocking(text: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        val percent = if (total > 0) ((done * 100) / total).toInt() else null
        runCatching { setForegroundAsync(foregroundInfo()) }
        runCatching { setProgressAsync(workDataOf(P_TEXT to "$text  ${mb(done)} / ${mb(total)}", P_PERCENT to (percent ?: -1))) }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val key = inputData.getString(KEY_HISTORY) ?: url
        val titleHint = inputData.getString(KEY_TITLE_HINT)?.takeIf { it.isNotBlank() }
        label = titleHint ?: "FANZA作品"

        // 重複の自動除外: 同じ作品が先に保存済みになっていたら何もしない
        if (!inputData.getBoolean(KEY_FORCE, false) &&
            (Prefs.isDone(ctx, key) || (titleHint != null && Prefs.isDone(ctx, "t:$titleHint")))
        ) {
            JobQueue.remove(ctx, key)
            Prefs.addStat(ctx, Prefs.ST_TOTAL, -1)
            notifyIfAllFinished()
            return@withContext Result.success(workDataOf(OUT_RESULT to "$label（保存済みのため重複をスキップ）"))
        }
        // 古いバージョンで予約された分も台帳に載せ、一時停止・アップデート後に再開できるようにする
        if (!JobQueue.contains(ctx, key)) {
            JobQueue.put(
                ctx, key,
                JSONObject().put(KEY_URL, url).put(KEY_UA, inputData.getString(KEY_UA).orEmpty())
                    .put(KEY_REFERER, inputData.getString(KEY_REFERER).orEmpty()).put(KEY_CD, inputData.getString(KEY_CD).orEmpty())
                    .put(KEY_CID, inputData.getString(KEY_CID).orEmpty()).put(KEY_TITLE_HINT, titleHint.orEmpty())
                    .put(KEY_FORCE, inputData.getBoolean(KEY_FORCE, false)),
            )
            Prefs.addStat(ctx, Prefs.ST_TOTAL, 1)
        }
        runCatching { setForeground(foregroundInfo()) }

        // 大きな作品が一度に何件も重なると空き容量が足りなくなるので、同時に処理するのは2件まで
        runCatching { setProgress(workDataOf(P_TEXT to "順番待ち（同時に2件まで処理します）", P_PERCENT to -1)) }
        // 途中まで進んでいる作品（アップデートや一時停止で止まったもの）を優先して先に再開する
        val partial = File(JobQueue.jobDir(ctx, key), "data.bin").let { it.exists() && it.length() > 0 }
        if (partial) {
            resumeWaiting.incrementAndGet()
            try {
                slots.acquire()
            } finally {
                resumeWaiting.decrementAndGet()
            }
        } else {
            while (resumeWaiting.get() > 0) kotlinx.coroutines.delay(1000)
            slots.acquire()
        }
        try {
            work(url, key, titleHint)
        } finally {
            slots.release()
        }
    }

    private suspend fun work(url: String, key: String, titleHint: String?): Result {
        val jobDir = JobQueue.jobDir(ctx, key).apply { mkdirs() }
        return try {
            val name = process(url, jobDir)
            Prefs.markDone(ctx, key)
            titleHint?.let { Prefs.markDone(ctx, "t:$it") }
            Prefs.markDone(ctx, historyKey(url, inputData.getString(KEY_CID)))
            JobQueue.remove(ctx, key)
            Prefs.setLastError(ctx, id.toString(), null)
            Prefs.addStat(ctx, Prefs.ST_DONE, 1)
            notifyIfAllFinished()
            Result.success(workDataOf(OUT_RESULT to name))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 一時停止・中止。途中のファイルは残して続きから再開できるようにする
        } catch (e: Exception) {
            when {
                // 一時停止やアップデートで止められた場合は、途中のファイルを残したまま終了
                isStopped -> Result.retry()
                e is PermanentException -> fail(key, e.message ?: "不明なエラー")
                e is IOException && runAttemptCount < MAX_ATTEMPTS -> {
                    // 少し待って自動で再試行（続きから）。理由は「保存状況」画面で確認できる
                    Prefs.setLastError(ctx, id.toString(), "通信エラー（${runAttemptCount + 1}回目）: ${e.message}")
                    Result.retry()
                }
                e is IOException -> fail(key, "通信エラー: ${e.message}")
                else -> fail(key, "予期しないエラー: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }
    }

    private fun fail(key: String, message: String): Result {
        val data = File(JobQueue.jobDir(ctx, key), "data.bin")
        val msg = if (data.exists() && data.length() > 0) {
            // URLの期限切れなどで失敗しても、途中までのデータは残す。押し直すと続きから再開できる
            JobQueue.keep(ctx, key, inputData.getString(KEY_TITLE_HINT), inputData.getString(KEY_CID))
            "$message\n→ 途中まで（${mb(data.length())}）のデータは残してあります。作品の「ダウンロードする」を押し直すか「一括保存」で続きから再開します"
        } else {
            JobQueue.remove(ctx, key)
            message
        }
        Prefs.setLastError(ctx, id.toString(), null)
        Prefs.addStat(ctx, Prefs.ST_FAILED, 1)
        notifyIfAllFinished()
        return Result.failure(workDataOf(OUT_ERROR to msg))
    }

    /** 保存待ちが全部なくなったら、まとめの通知を出す */
    private fun notifyIfAllFinished() {
        if (JobQueue.size(ctx) == 0) Notifier.finished(ctx)
    }

    /** 途中で失敗して再試行になっても、済んだ工程はやり直さないよう状態をファイルに残す */
    private suspend fun process(url: String, jobDir: File): String {
        val stateFile = File(jobDir, "state.json")
        val state = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        fun saveState() = stateFile.writeText(state.toString())
        val data = File(jobDir, "data.bin")

        // ① ダウンロード
        if (!state.optBoolean("downloaded")) {
            download(url, data, state, ::saveState)
            state.put("downloaded", true)
            saveState()
        }

        // ② 作品タイトルを調べてファイル名を決める
        if (!state.has("fileName")) {
            state.put("fileName", decideFileName(url, state))
            saveState()
        }
        val fileName = state.getString("fileName")
        val ext = FileNames.extensionOf(fileName) ?: "zip"
        val mime = state.optString("mime").takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: FileNames.mimeFromExtension(ext)
        label = fileName

        // ③ スマホ本体に保存（設定ON、またはドライブ未設定の場合）
        val drive = Prefs.driveEnabled(ctx)
        if ((Prefs.saveLocal(ctx) || !drive) && !state.optBoolean("localDone")) {
            report("本体に保存中", 0, 0, force = true)
            saveToDownloads(data, fileName, mime)
            state.put("localDone", true)
            saveState()
        }

        // ④ Googleドライブに保存
        if (drive && !state.optBoolean("driveDone")) {
            val uploader = DriveUploader.create(ctx)
            report("ドライブを確認中", 0, 0, force = true)
            val existing = uploader.existingSize(fileName)
            if (existing == null || existing != data.length()) {
                uploader.upload(
                    data, fileName, mime,
                    savedSession = state.optString("uploadSession").ifBlank { null },
                    onSession = { session ->
                        if (session == null) state.remove("uploadSession") else state.put("uploadSession", session)
                        saveState()
                    },
                    shouldStop = { isStopped },
                ) { done, total -> reportBlocking("ドライブへ保存中", done, total) }
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
    private suspend fun download(url: String, out: File, state: JSONObject, saveState: () -> Unit) {
        Http.ensureAgeCheckCookie()
        val have = if (out.exists()) out.length() else 0L
        val finalUrl = state.optString("finalUrl")
        if (have > 0 && finalUrl.startsWith("http") && finalUrl != url) {
            try {
                if (downloadFrom(finalUrl, out, have, state, saveState, fallbackAllowed = true)) return
            } catch (e: PermanentException) {
                // 配信サーバーのURLは期限切れ。作品を押したときのURLで続きを試す
            }
        }
        downloadFrom(url, out, have, state, saveState, fallbackAllowed = false)
    }

    /** @return 完了したら true。fallbackAllowed のとき、続きからに非対応なら false を返して別のURLに任せる */
    private suspend fun downloadFrom(
        url: String,
        out: File,
        have: Long,
        state: JSONObject,
        saveState: () -> Unit,
        fallbackAllowed: Boolean,
    ): Boolean {
        val builder = Request.Builder().url(url)
            .header("User-Agent", inputData.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: Http.DESKTOP_UA)
        inputData.getString(KEY_REFERER)?.takeIf { it.startsWith("http") }?.let { builder.header("Referer", it) }
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
                        if (isStopped) throw IOException("中断されました")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        report(label, done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("ダウンロードが途中で切れました（続きから再開します）")
            return true
        }
    }

    private suspend fun decideFileName(url: String, state: JSONObject): String {
        val finalUrl = state.optString("finalUrl")
        val cd = state.optString("contentDisposition").ifBlank { inputData.getString(KEY_CD) }
        val original = FileNames.fromContentDisposition(cd) ?: FileNames.fromUrl(finalUrl) ?: FileNames.fromUrl(url)
        val ext = FileNames.extensionOf(original) ?: FileNames.extensionFromMime(state.optString("mime")) ?: "zip"
        val originalBase = original?.substringBeforeLast('.')

        val cid = inputData.getString(KEY_CID)?.takeIf { it.isNotBlank() }
            ?: FileNames.findCid(original, finalUrl, url)

        report("作品タイトルを取得中", 0, 0, force = true)
        val title = cid?.let { TitleResolver.resolve(it) }
            ?: inputData.getString(KEY_TITLE_HINT)?.takeIf { it.isNotBlank() }
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
        private const val MAX_ATTEMPTS = 5
        private val slots = Semaphore(2)
        private val resumeWaiting = java.util.concurrent.atomic.AtomicInteger(0)
        const val KEY_URL = "url"
        const val KEY_UA = "ua"
        const val KEY_REFERER = "referer"
        const val KEY_CD = "content_disposition"
        const val KEY_CID = "cid"
        const val KEY_TITLE_HINT = "title_hint"
        const val KEY_HISTORY = "history_key"

        const val KEY_FORCE = "force"

        fun historyKey(url: String, cid: String?): String = cid?.takeIf { it.isNotBlank() } ?: url.substringBefore('?')

        /** 作品を見分けるための名前。作品ID → 作品名 → URL の順に使う */
        fun jobKey(url: String, cid: String?, title: String?): String =
            cid?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }?.let { "t:$it" } ?: url.substringBefore('?')

        fun isSaved(c: Context, url: String, cid: String?, title: String?): Boolean =
            Prefs.isDone(c, jobKey(url, cid, title)) || Prefs.isDone(c, historyKey(url, cid)) ||
                (!title.isNullOrBlank() && Prefs.isDone(c, "t:$title"))

        /**
         * 保存処理を予約する。
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
            Prefs.addStat(context, Prefs.ST_TOTAL, 1)
            val job = JSONObject()
                .put(KEY_URL, url)
                .put(KEY_UA, userAgent.orEmpty())
                .put(KEY_REFERER, referer.orEmpty())
                .put(KEY_CD, contentDisposition?.take(1000).orEmpty())
                .put(KEY_CID, cid.orEmpty())
                .put(KEY_TITLE_HINT, titleHint?.take(300).orEmpty())
                .put(KEY_FORCE, force)
            JobQueue.put(context, key, job)
            if (Prefs.paused(context)) return Queued.PAUSED
            schedule(context, key, job)
            return Queued.QUEUED
        }

        private fun schedule(context: Context, key: String, job: JSONObject) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (Prefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val title = job.optString(KEY_TITLE_HINT).ifBlank { null }
            val cid = job.optString(KEY_CID).ifBlank { null }
            val url = job.getString(KEY_URL)
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag(TITLE_TAG_PREFIX + (title ?: cid ?: url.substringBefore('?').takeLast(60)))
                .addTag(KEY_TAG_PREFIX + key)
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_UA to job.optString(KEY_UA),
                        KEY_REFERER to job.optString(KEY_REFERER),
                        KEY_CD to job.optString(KEY_CD),
                        KEY_CID to cid,
                        KEY_TITLE_HINT to title,
                        KEY_HISTORY to key,
                        KEY_FORCE to job.optBoolean(KEY_FORCE),
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl:$key", ExistingWorkPolicy.KEEP, request)
        }

        /** 台帳にある保存待ちをすべて予約し直す（アプリ起動時・アップデート後・再開時） */
        fun restoreQueue(context: Context) {
            if (Prefs.paused(context)) return
            JobQueue.all(context).forEach { (key, job) -> schedule(context, key, job) }
        }

        /** 一時停止: 処理中・待機中をすべて止める（途中のファイルと台帳は残す） */
        fun pauseAll(context: Context) {
            Prefs.setPaused(context, true)
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            if (JobQueue.size(context) > 0) Notifier.paused(context)
        }

        /** 再開: 台帳から予約し直す。ダウンロードは途中から続く */
        fun resumeAll(context: Context) {
            Prefs.setPaused(context, false)
            Notifier.clearFinished(context)
            restoreQueue(context)
        }

        /**
         * 重複の整理（バックグラウンドスレッドで呼ぶこと）。
         * 古いバージョンで二重に予約された作品や、すでに保存済みの作品の待機分を取り消す。
         * @return 取り消した件数
         */
        fun removeDuplicates(context: Context): Int {
            val wm = WorkManager.getInstance(context)
            val infos = wm.getWorkInfosByTag(TAG).get().filter { !it.state.isFinished }
            var removed = 0
            infos.groupBy { info -> info.tags.firstOrNull { it.startsWith(TITLE_TAG_PREFIX) } ?: info.id.toString() }
                .forEach { (tag, group) ->
                    val title = tag.removePrefix(TITLE_TAG_PREFIX)
                    val alreadySaved = Prefs.isDone(context, "t:$title") || Prefs.isDone(context, title)
                    val keep = if (alreadySaved) null else group.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: group.first()
                    for (info in group) {
                        if (info == keep || (alreadySaved && info.state == WorkInfo.State.RUNNING)) continue
                        wm.cancelWorkById(info.id)
                        info.tags.firstOrNull { it.startsWith(KEY_TAG_PREFIX) }?.let {
                            val key = it.removePrefix(KEY_TAG_PREFIX)
                            // 残す側と同じ作品の台帳は消さない
                            if ((keep == null || KEY_TAG_PREFIX + key !in keep.tags) && JobQueue.contains(context, key)) {
                                JobQueue.remove(context, key)
                                Prefs.addStat(context, Prefs.ST_TOTAL, -1)
                            }
                        }
                        removed++
                    }
                }
            return removed
        }

        const val TAG = "fanza_download"
        const val TITLE_TAG_PREFIX = "title:"
        const val KEY_TAG_PREFIX = "key:"
        const val P_TEXT = "progress_text"
        const val P_PERCENT = "progress_percent"
        const val OUT_RESULT = "result"
        const val OUT_ERROR = "error"
    }
}

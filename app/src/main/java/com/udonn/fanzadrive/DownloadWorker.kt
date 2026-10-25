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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * バックグラウンドで「ダウンロード → 作品タイトルにリネーム → 保存（ドライブ／本体）」を行う。
 * アプリを閉じても、画面を消しても処理は続き、通信が切れたら自動で再試行する。
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val ctx get() = applicationContext
    private val progressId = id.hashCode()
    private var lastNotify = 0L
    private var label = "FANZA作品"

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("準備中…", null)

    private fun foregroundInfo(text: String, percent: Int?) = ForegroundInfo(
        progressId,
        Notifier.progress(ctx, label, text, percent),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private suspend fun report(text: String, done: Long, total: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        val percent = if (total > 0) ((done * 100) / total).toInt() else null
        val sizeText = if (total > 0) "${mb(done)} / ${mb(total)}" else mb(done)
        runCatching { setForeground(foregroundInfo("$text  $sizeText", percent)) }
    }

    /** suspend できない場所（アップロード中のコールバック）から通知を更新する */
    private fun reportBlocking(text: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        val percent = if (total > 0) ((done * 100) / total).toInt() else null
        runCatching { setForegroundAsync(foregroundInfo("$text  ${mb(done)} / ${mb(total)}", percent)) }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val historyKey = inputData.getString(KEY_HISTORY) ?: url
        label = inputData.getString(KEY_TITLE_HINT)?.takeIf { it.isNotBlank() } ?: "FANZA作品"
        runCatching { setForeground(foregroundInfo("準備中…", null)) }

        val jobDir = File(ctx.noBackupFilesDir, "jobs/$id").apply { mkdirs() }
        try {
            val name = process(url, jobDir)
            Prefs.markDone(ctx, historyKey)
            jobDir.deleteRecursively()
            Notifier.result(ctx, progressId + 1, "保存しました", name, true)
            Result.success()
        } catch (e: PermanentException) {
            jobDir.deleteRecursively()
            Notifier.result(ctx, progressId + 1, "保存できませんでした: $label", e.message ?: "", false)
            Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                jobDir.deleteRecursively()
                Notifier.result(ctx, progressId + 1, "保存できませんでした: $label", "通信エラー: ${e.message}", false)
                Result.failure()
            }
        }
    }

    /** 途中で失敗して再試行になっても、済んだ工程はやり直さないよう状態をファイルに残す */
    private suspend fun process(url: String, jobDir: File): String {
        val stateFile = File(jobDir, "state.json")
        val state = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        fun saveState() = stateFile.writeText(state.toString())
        val data = File(jobDir, "data.bin")

        // ① ダウンロード
        if (!state.optBoolean("downloaded")) {
            download(url, data, state)
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
            val uploader = DriveUploader(Prefs.gasUrl(ctx), Prefs.gasKey(ctx))
            report("ドライブを確認中", 0, 0, force = true)
            val existing = uploader.existingSize(fileName)
            if (existing == null || existing != data.length()) {
                uploader.upload(data, fileName, mime) { done, total -> reportBlocking("ドライブへ保存中", done, total) }
            }
            state.put("driveDone", true)
            saveState()
        }
        return fileName + if (drive) "（Googleドライブ）" else "（Download/FANZA）"
    }

    private suspend fun download(url: String, out: File, state: JSONObject) {
        Http.ensureAgeCheckCookie()
        val builder = Request.Builder().url(url)
            .header("User-Agent", inputData.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: Http.DESKTOP_UA)
        inputData.getString(KEY_REFERER)?.takeIf { it.startsWith("http") }?.let { builder.header("Referer", it) }

        Http.client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = "ダウンロード失敗 HTTP ${resp.code}"
                throw if (resp.code in 400..499 && resp.code != 408 && resp.code != 429) PermanentException(msg) else IOException(msg)
            }
            val body = resp.body ?: throw IOException("空の応答")
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            if (mime == "text/html") {
                throw PermanentException("ファイルではなくWebページが返されました。ログインが切れている可能性があります（アプリで再ログインしてください）")
            }
            state.put("mime", mime)
            state.put("finalUrl", resp.request.url.toString())
            state.put("contentDisposition", resp.header("Content-Disposition").orEmpty())

            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (isStopped) throw IOException("中断されました")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        report("ダウンロード中", done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("ダウンロードが途中で切れました")
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
        const val KEY_URL = "url"
        const val KEY_UA = "ua"
        const val KEY_REFERER = "referer"
        const val KEY_CD = "content_disposition"
        const val KEY_CID = "cid"
        const val KEY_TITLE_HINT = "title_hint"
        const val KEY_HISTORY = "history_key"

        fun historyKey(url: String, cid: String?): String = cid?.takeIf { it.isNotBlank() } ?: url.substringBefore('?')

        /** 保存処理を予約する。同じ作品が二重に予約されないようにする */
        fun enqueue(
            context: Context,
            url: String,
            userAgent: String?,
            referer: String?,
            contentDisposition: String?,
            cid: String?,
            titleHint: String?,
        ) {
            val key = historyKey(url, cid)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (Prefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_UA to userAgent,
                        KEY_REFERER to referer,
                        KEY_CD to contentDisposition?.take(1000),
                        KEY_CID to cid,
                        KEY_TITLE_HINT to titleHint?.take(300),
                        KEY_HISTORY to key,
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl:$key", ExistingWorkPolicy.KEEP, request)
        }

        const val TAG = "fanza_download"
    }
}

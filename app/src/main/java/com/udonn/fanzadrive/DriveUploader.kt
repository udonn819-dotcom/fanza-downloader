package com.udonn.fanzadrive

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Googleドライブ側の窓口。「同名ファイルの確認」と「アップロード用URLの発行」を担当する */
interface DriveBackend {
    /** 同名ファイルが既にドライブにあればそのサイズを返す（無ければ null） */
    fun existingSize(name: String): Long?

    /** ドライブの「再開可能アップロード用URL」を発行する */
    fun createSession(name: String, mime: String, size: Long): String
}

/**
 * 方式① API方式: Googleアカウントでログインし、アプリから直接 Google Drive API を使う。
 * 保存先はマイドライブ直下の「FANZA同人」フォルダ（無ければアプリが作成）。
 */
class ApiBackend(private val context: Context) : DriveBackend {
    private val http = Http.plainClient
    private val token by lazy { GoogleAuth.accessToken(context) }
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun call(builder: Request.Builder): JSONObject {
        val req = builder.header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = "Drive API エラー HTTP ${resp.code}: ${body.take(200)}"
                throw if (resp.code == 401 || resp.code == 403 && body.contains("accessNotConfigured")) PermanentException(msg) else IOException(msg)
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun search(q: String, fields: String): JSONArray {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files($fields)")
            .addQueryParameter("spaces", "drive")
            .build()
        return call(Request.Builder().url(url)).optJSONArray("files") ?: JSONArray()
    }

    /** 保存先フォルダのIDを返す（無ければ作成） */
    fun folderId(): String {
        val saved = Prefs.driveFolderId(context)
        if (saved.isNotBlank()) {
            val ok = runCatching {
                !call(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$saved?fields=id,trashed")).optBoolean("trashed")
            }.getOrDefault(false)
            if (ok) return saved
        }
        val found = search(
            "name = '${escape(FOLDER_NAME)}' and mimeType = '$FOLDER_MIME' and 'root' in parents and trashed = false", "id"
        )
        val id = if (found.length() > 0) {
            found.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME)
            call(
                Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id")
                    .post(meta.toString().toRequestBody(json))
            ).getString("id")
        }
        Prefs.setDriveFolderId(context, id)
        return id
    }

    override fun existingSize(name: String): Long? {
        val files = search("name = '${escape(name)}' and '${folderId()}' in parents and trashed = false", "id,size")
        return if (files.length() > 0) files.getJSONObject(0).optString("size").toLongOrNull() ?: -1 else null
    }

    override fun createSession(name: String, mime: String, size: Long): String {
        val meta = JSONObject().put("name", name).put("mimeType", mime).put("parents", JSONArray().put(folderId()))
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", size.toString())
            .post(meta.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { resp ->
            val location = resp.header("Location")
            if (!resp.isSuccessful || location == null) {
                throw IOException("アップロード開始に失敗 HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
            return location
        }
    }

    /** 検索条件の中で ' と \ を正しく扱う */
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        const val FOLDER_NAME = "FANZA同人"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}

/**
 * 方式② GAS方式: Google Apps Script（GAS）を中継役にしてGoogleドライブへ保存する。
 * GASを経由するのは最初の依頼だけで、ファイル本体はドライブへ直接送る。
 */
class GasBackend(private val gasUrl: String, private val key: String) : DriveBackend {

    private val http = Http.plainClient

    private fun callGas(payload: JSONObject): JSONObject {
        payload.put("key", key)
        val req = Request.Builder()
            .url(gasUrl)
            .post(payload.toString().toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        // GASは302リダイレクトで結果を返すので、OkHttpが自動でたどる
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GASエラー HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw PermanentException("GASの応答が不正です。ウェブアプリの「アクセスできるユーザー」が「全員」になっているか確認してください")
        }
        if (!json.optBoolean("ok")) {
            throw PermanentException("GASエラー: " + json.optString("error", "不明なエラー"))
        }
        return json
    }

    /** 接続テスト。保存先フォルダ名を返す */
    fun ping(): String = callGas(JSONObject().put("action", "ping")).optString("folder")

    override fun existingSize(name: String): Long? {
        val json = callGas(JSONObject().put("action", "exists").put("name", name))
        return if (json.optBoolean("exists")) json.optLong("size", -1) else null
    }

    override fun createSession(name: String, mime: String, size: Long): String =
        callGas(
            JSONObject().put("action", "start").put("name", name).put("mimeType", mime).put("size", size)
        ).getString("uploadUrl")
}

/** 発行されたアップロード用URLへ、ファイルを8MBずつ分割して送る（途中で切れても続きから再開） */
class DriveUploader(private val backend: DriveBackend) {

    private val http = Http.plainClient

    fun existingSize(name: String): Long? = backend.existingSize(name)

    fun upload(file: File, name: String, mime: String, onProgress: (Long, Long) -> Unit) {
        val total = file.length()
        val session = backend.createSession(name, mime, total)

        val mediaType = mime.toMediaType()
        var offset = 0L
        var failures = 0
        while (offset < total) {
            val len = minOf(CHUNK, total - offset)
            val req = Request.Builder()
                .url(session)
                .put(FileRangeBody(file, offset, len, mediaType))
                .header("Content-Range", "bytes $offset-${offset + len - 1}/$total")
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 200 || resp.code == 201 -> offset = total
                        resp.code == 308 -> offset = parseRangeEnd(resp.header("Range"))
                        resp.code in 500..599 -> throw IOException("HTTP ${resp.code}")
                        // 404/410 はアップロード用URLの期限切れ。作業全体を最初からやり直す
                        else -> throw UploadFailed("ドライブへのアップロード失敗 HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    }
                }
                failures = 0
                onProgress(offset, total)
            } catch (e: UploadFailed) {
                throw e
            } catch (e: IOException) {
                // 通信の瞬断など: 少し待って、どこまで届いたか確認してから続きを送る
                if (++failures > 5) throw e
                Thread.sleep(2000L * failures)
                offset = queryOffset(session, total) ?: offset
            }
        }
    }

    private fun queryOffset(session: String, total: Long): Long? = runCatching {
        val req = Request.Builder().url(session)
            .put(ByteArray(0).toRequestBody(null))
            .header("Content-Range", "bytes */$total")
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200, 201 -> total
                308 -> parseRangeEnd(resp.header("Range"))
                else -> null
            }
        }
    }.getOrNull()

    /** "bytes=0-12345" → 12346（次に送るべき位置）。ヘッダーが無ければ0から */
    private fun parseRangeEnd(range: String?): Long =
        range?.substringAfter('-', "")?.toLongOrNull()?.plus(1) ?: 0L

    private class UploadFailed(msg: String) : IOException(msg)

    private class FileRangeBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val type: MediaType,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    companion object {
        fun create(c: Context): DriveUploader = DriveUploader(
            if (Prefs.driveMode(c) == Prefs.MODE_API) ApiBackend(c) else GasBackend(Prefs.gasUrl(c), Prefs.gasKey(c))
        )

        /** 1回に送るサイズ（Googleの仕様で256KBの倍数である必要がある） */
        private const val CHUNK = 8L * 1024 * 1024
    }
}

/** 再試行しても直らないエラー（設定ミスなど） */
class PermanentException(message: String) : IOException(message)

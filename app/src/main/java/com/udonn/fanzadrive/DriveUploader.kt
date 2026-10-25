package com.udonn.fanzadrive

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Google Apps Script（GAS）を中継役にしてGoogleドライブへ保存する。
 *
 * 1. アプリ → GAS に「このファイル名で保存したい」と依頼
 * 2. GAS がドライブの「再開可能アップロード用URL」を発行して返す
 * 3. アプリがそのURLへファイル本体を直接、分割送信する
 *
 * GASを経由するのは最初の依頼だけなので、数百MBの大きなファイルでも保存できる。
 * （Google Cloud Console での面倒な登録作業も不要）
 */
class DriveUploader(private val gasUrl: String, private val key: String) {

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

    /** 同名ファイルが既にドライブにあればそのサイズを返す（無ければ null） */
    fun existingSize(name: String): Long? {
        val json = callGas(JSONObject().put("action", "exists").put("name", name))
        return if (json.optBoolean("exists")) json.optLong("size", -1) else null
    }

    fun upload(file: File, name: String, mime: String, onProgress: (Long, Long) -> Unit) {
        val total = file.length()
        val session = callGas(
            JSONObject().put("action", "start").put("name", name).put("mimeType", mime).put("size", total)
        ).getString("uploadUrl")

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
        /** 1回に送るサイズ（Googleの仕様で256KBの倍数である必要がある） */
        private const val CHUNK = 8L * 1024 * 1024
    }
}

/** 再試行しても直らないエラー（設定ミスなど） */
class PermanentException(message: String) : IOException(message)

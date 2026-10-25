package com.udonn.fanzadrive

import android.webkit.MimeTypeMap
import java.net.URLDecoder

object FileNames {
    /** FANZA同人の作品ID（例: d_123456）を文字列から探す */
    private val CID_REGEX = Regex("""(?<![A-Za-z0-9_])(d_[a-z0-9]*\d[a-z0-9]*)""")

    fun findCid(vararg sources: String?): String? =
        sources.firstNotNullOfOrNull { s -> s?.let { CID_REGEX.find(it)?.groupValues?.get(1) } }

    /** Content-Disposition ヘッダーから元のファイル名を取り出す */
    fun fromContentDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let {
            val charset = it.groupValues[1].ifBlank { "UTF-8" }
            return runCatching { URLDecoder.decode(it.groupValues[2].trim().trim('"'), charset) }.getOrNull()
        }
        Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(cd)?.let { return decodeMaybe(it.groupValues[1]) }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { return decodeMaybe(it.groupValues[1].trim()) }
        return null
    }

    fun fromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/')
        return if (last.contains('.')) decodeMaybe(last) else null
    }

    private fun decodeMaybe(s: String): String =
        if (s.contains('%')) runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s) else s

    fun extensionOf(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return null
        return ext.takeIf { it.length in 1..5 && it.all { ch -> ch.isLetterOrDigit() } }
    }

    fun extensionFromMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        if (mime.contains("zip")) return "zip"
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    }

    fun mimeFromExtension(ext: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

    /**
     * ファイル名に使えない記号を全角に置き換え、長すぎる場合は切り詰める。
     * Androidのファイル名は最大255バイト（日本語1文字=3バイト）なので余裕を持たせる。
     */
    fun sanitize(title: String, maxBytes: Int = 180): String {
        val replaced = buildString {
            for (ch in title) {
                append(
                    when (ch) {
                        '\\' -> '＼'; '/' -> '／'; ':' -> '：'; '*' -> '＊'; '?' -> '？'
                        '"' -> '”'; '<' -> '＜'; '>' -> '＞'; '|' -> '｜'
                        else -> if (ch.code < 0x20) ' ' else ch
                    }
                )
            }
        }.replace(Regex("""\s+"""), " ").trim().trim('.').trim()
        var result = replaced
        while (result.toByteArray(Charsets.UTF_8).size > maxBytes) result = result.dropLast(1)
        return result.trim().ifEmpty { "FANZA作品" }
    }
}

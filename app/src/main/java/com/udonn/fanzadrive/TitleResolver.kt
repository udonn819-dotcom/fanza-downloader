package com.udonn.fanzadrive

import android.text.Html
import okhttp3.Request

/** 作品ID（d_123456 など）から、FANZA同人の作品ページを見て正式タイトルを取得する */
object TitleResolver {

    fun resolve(cid: String): String? {
        Http.ensureAgeCheckCookie()
        val url = "https://www.dmm.co.jp/dc/doujin/-/detail/=/cid=$cid/"
        val html = runCatching {
            val req = Request.Builder().url(url).header("User-Agent", Http.DESKTOP_UA).build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return null
        return extract(html)
    }

    fun extract(html: String): String? {
        val candidates = listOfNotNull(
            // PC版作品ページの見出し（セール表示などの付属ラベルは除去）
            Regex("""<h1[^>]*class="[^"]*productTitle[^"]*"[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
                ?.replace(Regex("""<span[^>]*(campaign|label|icon)[^>]*>.*?</span>""", RegexOption.DOT_MATCHES_ALL), ""),
            Regex("""<meta[^>]+property="og:title"[^>]+content="([^"]*)"""").find(html)?.groupValues?.get(1),
            Regex("""<meta[^>]+content="([^"]*)"[^>]+property="og:title"""").find(html)?.groupValues?.get(1),
            Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1),
        )
        return candidates.asSequence().map { clean(it) }.firstOrNull { it.isNotBlank() && !isGeneric(it) }
    }

    private fun clean(raw: String): String {
        var s = Html.fromHtml(raw.replace(Regex("<[^>]+>"), ""), Html.FROM_HTML_MODE_LEGACY).toString()
        s = s.replace(Regex("""\s+"""), " ").trim()
        // 末尾の「 - FANZA同人」「｜FANZA」などのサイト名を除去
        s = s.replace(Regex("""\s*[-–|｜/]\s*(FANZA|DMM|ＦＡＮＺＡ).*$"""), "")
        // 先頭のセール表記（【50%OFF】など）を除去
        s = s.replace(Regex("""^(【[^】]*(OFF|ＯＦＦ|セール|割引|ポイント|還元|限定)[^】]*】\s*)+"""), "")
        return s.trim()
    }

    private fun isGeneric(s: String): Boolean =
        s.contains("年齢認証") || s.contains("ログイン") || s == "FANZA" || s.startsWith("FANZA同人")
}

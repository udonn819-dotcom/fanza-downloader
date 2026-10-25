package com.udonn.fanzadrive

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    /** PC版サイトを表示させるためのパソコン用ブラウザ（Windows版Chrome）のUser-Agent */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /**
     * アプリ内ブラウザ（WebView）でログインした状態のCookieをそのまま使う通信クライアント。
     * これによりバックグラウンドでも「ログイン済み」としてダウンロードできる。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Googleドライブ用（FANZAのCookieは送らない） */
    val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    fun ensureAgeCheckCookie() {
        val cm = CookieManager.getInstance()
        for (host in listOf("https://www.dmm.co.jp", "https://dlsoft.dmm.co.jp", "https://book.dmm.co.jp")) {
            cm.setCookie(host, "age_check_done=1; Domain=.dmm.co.jp; Path=/")
        }
        cm.flush()
    }
}

private class WebViewCookieJar : CookieJar {
    private val cm get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) cm.setCookie(url.toString(), cookie.toString())
        cm.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cm.getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

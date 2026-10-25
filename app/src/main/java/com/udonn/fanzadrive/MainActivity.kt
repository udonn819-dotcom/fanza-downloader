package com.udonn.fanzadrive

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar

    /** 直前にタップした作品の情報（ダウンロードURLだけでは作品が分からない場合の手がかり） */
    private var lastClickTitle: String? = null
    private var lastClickCid: String? = null
    private var lastClickAt = 0L

    private var autoScannedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifier.createChannels(this)
        requestNotificationPermission()

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        Http.ensureAgeCheckCookie()

        setupDesktopMode(webView)
        webView.addJavascriptInterface(Bridge(), "FanzaDrive")
        webView.webViewClient = MainClient()
        webView.webChromeClient = MainChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, _, _ ->
            onDownloadRequested(url, userAgent, contentDisposition, webView.url)
        }

        findViewById<Button>(R.id.btnLibrary).setOnClickListener { webView.loadUrl(LIBRARY_URL) }
        findViewById<Button>(R.id.btnBulk).setOnClickListener { scanPage(auto = false) }
        findViewById<Button>(R.id.btnReload).setOnClickListener { webView.reload() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(LIBRARY_URL)

        if (!Prefs.driveEnabled(this) && savedInstanceState == null) {
            Toast.makeText(this, "「設定」からGoogleドライブ連携を設定できます（未設定の場合は本体のDownload/FANZAに保存）", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    /**
     * FANZA同人はPC版でないとダウンロードできないため、
     * パソコンのChromeになりすまし、画面もPCと同じ横幅で表示する。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupDesktopMode(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = Http.DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        view.setInitialScale(1)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ---------------------------------------------------------------- ダウンロード

    private fun onDownloadRequested(url: String, userAgent: String?, contentDisposition: String?, referer: String?) {
        if (!url.startsWith("http")) {
            Toast.makeText(this, "この形式のダウンロードには対応していません", Toast.LENGTH_LONG).show()
            return
        }
        val recent = System.currentTimeMillis() - lastClickAt < 60_000
        val cid = FileNames.findCid(url, contentDisposition) ?: lastClickCid.takeIf { recent }
        val hint = lastClickTitle.takeIf { recent && (lastClickCid == null || lastClickCid == cid) }
        DownloadWorker.enqueue(this, url, userAgent ?: Http.DESKTOP_UA, referer, contentDisposition, cid, hint)
        val name = hint ?: cid ?: "作品"
        Toast.makeText(this, "「$name」をバックグラウンドで保存します", Toast.LENGTH_SHORT).show()
    }

    /** 表示中のページからダウンロードリンクを探し、まだ保存していない作品をまとめて予約する */
    private fun scanPage(auto: Boolean) {
        webView.evaluateJavascript(SCAN_JS) { raw ->
            val items = runCatching { JSONArray(JSONArray("[$raw]").getString(0)) }.getOrNull() ?: JSONArray()
            var queued = 0
            var skipped = 0
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val url = o.optString("url")
                val cid = o.optString("cid").ifBlank { null } ?: FileNames.findCid(url)
                if (Prefs.isDone(this, DownloadWorker.historyKey(url, cid))) {
                    skipped++
                    continue
                }
                DownloadWorker.enqueue(this, url, Http.DESKTOP_UA, webView.url, null, cid, o.optString("title").ifBlank { null })
                queued++
            }
            when {
                queued > 0 -> Toast.makeText(this, "${queued}件の保存を予約しました（保存済み${skipped}件はスキップ）", Toast.LENGTH_LONG).show()
                !auto && items.length() == 0 -> AlertDialog.Builder(this)
                    .setTitle("ダウンロードリンクが見つかりません")
                    .setMessage("購入済み一覧を下までスクロールして全作品を表示させてから、もう一度お試しください。\n\n見つからない場合は、各作品の「ダウンロード」ボタンを直接タップしてください（タップした作品は自動で保存されます）。")
                    .setPositiveButton("OK", null).show()
                !auto -> Toast.makeText(this, "未保存の作品はありません（${skipped}件保存済み）", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onItemClick(title: String?, cid: String?) {
            lastClickTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            lastClickCid = cid?.trim()?.takeIf { it.isNotEmpty() }
            lastClickAt = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- WebView

    private inner class MainClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!url.startsWith("http")) return true // アプリ起動リンク等は無視
            // スマホ版ページ（/s/）に飛ばされたらPC版へ戻す
            val pc = toPcUrl(url)
            if (pc != url) {
                view.loadUrl(pc)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
            CookieManager.getInstance().flush()
            view.evaluateJavascript(CLICK_CAPTURE_JS, null)
            if (url != null && url.contains("mylibrary") && Prefs.autoScan(this@MainActivity) && autoScannedUrl != url) {
                autoScannedUrl = url
                // 一覧は読み込み後にJavaScriptで描画されるので少し待ってから探す
                view.postDelayed({ scanPage(auto = true) }, 4000)
            }
        }
    }

    private inner class MainChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
        }

        /** 別タブで開くリンク（target=_blank）も、このアプリ内で処理する */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = WebView(this@MainActivity)
            setupDesktopMode(popup)
            popup.setDownloadListener { url, ua, cd, _, _ ->
                onDownloadRequested(url, ua, cd, webView.url)
                popup.destroy()
            }
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    openFromPopup(request.url.toString(), popup)
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank") {
                        v.stopLoading()
                        openFromPopup(url, popup)
                    }
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun openFromPopup(url: String, popup: WebView) {
        if (url.startsWith("http")) webView.loadUrl(toPcUrl(url))
        popup.post { popup.destroy() }
    }

    private fun toPcUrl(url: String): String =
        url.replace("://www.dmm.co.jp/dc/doujin/s/", "://www.dmm.co.jp/dc/doujin/")

    // ---------------------------------------------------------------- 設定

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val editUrl = v.findViewById<EditText>(R.id.editGasUrl).apply { setText(Prefs.gasUrl(this@MainActivity)) }
        val editKey = v.findViewById<EditText>(R.id.editGasKey).apply { setText(Prefs.gasKey(this@MainActivity)) }
        val swLocal = v.findViewById<MaterialSwitch>(R.id.switchLocal).apply { isChecked = Prefs.saveLocal(this@MainActivity) }
        val swWifi = v.findViewById<MaterialSwitch>(R.id.switchWifi).apply { isChecked = Prefs.wifiOnly(this@MainActivity) }
        val swAuto = v.findViewById<MaterialSwitch>(R.id.switchAuto).apply { isChecked = Prefs.autoScan(this@MainActivity) }
        val info = v.findViewById<TextView>(R.id.textInfo)
        info.text = "保存済み: ${Prefs.doneCount(this)}件\n※ドライブ連携が未設定のときは、本体の Download/FANZA フォルダに保存されます。"

        v.findViewById<Button>(R.id.btnTestGas).setOnClickListener {
            val url = editUrl.text.toString().trim()
            val key = editKey.text.toString().trim()
            info.text = "テスト中…"
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    runCatching { "成功！ 保存先フォルダ: " + DriveUploader(url, key).ping() }
                        .getOrElse { "失敗: ${it.message}" }
                }
                info.text = msg
            }
        }
        v.findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            Prefs.clearDone(this)
            info.text = "履歴をリセットしました"
        }

        AlertDialog.Builder(this)
            .setTitle("設定")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                Prefs.save(
                    this, editUrl.text.toString(), editKey.text.toString(),
                    swLocal.isChecked, swWifi.isChecked, swAuto.isChecked,
                )
                Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    companion object {
        const val LIBRARY_URL = "https://www.dmm.co.jp/dc/doujin/-/mylibrary/"

        /** 作品が並んでいる「枠」を探すためのセレクタ */
        private const val ITEM_SELECTOR =
            "li, tr, article, [class*=\"item\"], [class*=\"Item\"], [class*=\"product\"], [class*=\"Product\"], [class*=\"card\"], [class*=\"Card\"]"
        private const val TITLE_SELECTOR =
            "[class*=\"title\"], [class*=\"Title\"], h2, h3, h4, a[href*=\"detail\"], img[alt]"

        private const val HELPERS_JS = """
            function fdCid(s){ var m=(s||'').match(/(?:^|[^A-Za-z0-9_])(d_[a-z0-9]*\d[a-z0-9]*)/); return m?m[1]:''; }
            function fdInfo(el){
              var item=el.closest('$ITEM_SELECTOR'), t='', c=fdCid(el.href||el.getAttribute('data-url')||'');
              for(var depth=0; item && depth<4 && (!t || !c); depth++){
                if(!c){ var l=item.querySelector('a[href*="d_"]'); if(l) c=fdCid(l.href); }
                if(!t){
                  var te=item.querySelector('$TITLE_SELECTOR');
                  if(te){ t=(te.tagName=='IMG'?te.alt:(te.innerText||te.textContent)||'').trim(); }
                  if(/^(ダウンロード|download)$/i.test(t)) t='';
                }
                item=item.parentElement ? item.parentElement.closest('$ITEM_SELECTOR') : null;
              }
              return {title:t.split('\n')[0].trim(), cid:c};
            }
        """

        /** タップした作品のタイトルを記録する（リネーム用の手がかり） */
        private const val CLICK_CAPTURE_JS = """
            (function(){
              if(window.__fdInstalled) return; window.__fdInstalled=true;
              $HELPERS_JS
              document.addEventListener('click', function(e){
                var el=e.target.closest('a, button, [role="button"], [data-url]');
                if(!el) return;
                var i=fdInfo(el);
                if(window.FanzaDrive) FanzaDrive.onItemClick(i.title, i.cid);
              }, true);
            })();
        """

        /** ページ内のダウンロードリンクを一覧にする */
        private const val SCAN_JS = """
            (function(){
              $HELPERS_JS
              var out=[], seen={};
              document.querySelectorAll('a[href], [data-url]').forEach(function(a){
                var h=a.href||a.getAttribute('data-url')||'';
                if(!/^https?:/.test(h) || !/download|\.zip(\?|$)/i.test(h) || seen[h]) return;
                seen[h]=1;
                var i=fdInfo(a);
                out.push({url:h, cid:i.cid, title:i.title});
              });
              return JSON.stringify(out);
            })();
        """
    }
}

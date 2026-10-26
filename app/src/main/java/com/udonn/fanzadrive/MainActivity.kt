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
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var btnBulk: Button

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
        btnBulk = findViewById(R.id.btnBulk)
        btnBulk.setOnClickListener { scanPage(auto = false) }
        findViewById<Button>(R.id.btnStatus).setOnClickListener { showStatus() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { webView.reload() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(LIBRARY_URL)

        // 起動時に、重複した保存待ちを自動で取り消し、止まっていた保存待ちを再開する
        updateStatusButton()
        tidyUp(showResult = false)
        askBatteryExemptionOnce()

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
        val name = hint ?: cid ?: "作品"
        fun go(force: Boolean) {
            val r = DownloadWorker.enqueue(this, url, userAgent ?: Http.DESKTOP_UA, referer, contentDisposition, cid, hint, force)
            val msg = when (r) {
                Queued.QUEUED -> "「$name」をバックグラウンドで保存します（進み具合は「保存状況」で確認できます）"
                Queued.DUPLICATE -> "「$name」はすでに保存待ちです（重複のため追加しません）"
                Queued.PAUSED -> "一時停止中です。「$name」は再開後に保存します"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        if (DownloadWorker.isSaved(this, url, cid, hint)) {
            AlertDialog.Builder(this)
                .setTitle("保存済みの作品です")
                .setMessage("「$name」は保存済みです。もう一度保存しますか？")
                .setPositiveButton("もう一度保存") { _, _ -> go(force = true) }
                .setNegativeButton("やめる", null)
                .show()
        } else {
            go(force = false)
        }
    }

    /**
     * 「一括保存」。購入済みページでは左の作品一覧を上から順に自動でタップし、
     * 右側に出る「ダウンロード」ボタンを押していく。それ以外のページではリンクを探す。
     * 実行中にもう一度押すと停止する。
     */
    private fun scanPage(auto: Boolean) {
        bulkAuto = auto
        val js = BULK_JS.replace("__DONE_TITLES__", JSONArray(Prefs.doneTitles(this)).toString())
        webView.evaluateJavascript(js) { raw ->
            when (raw?.trim('"')) {
                "started" -> if (!auto) Toast.makeText(this, "購入済み作品を順番に確認します（もう一度押すと停止）", Toast.LENGTH_LONG).show()
                "stopping" -> Toast.makeText(this, "停止します…", Toast.LENGTH_SHORT).show()
                else -> scanLinks(auto)
            }
        }
    }

    /** ページ内の直接ダウンロードリンクを探して予約する */
    private fun scanLinks(auto: Boolean) {
        webView.evaluateJavascript(SCAN_JS) { raw ->
            val items = runCatching { JSONArray(JSONArray("[$raw]").getString(0)) }.getOrNull() ?: JSONArray()
            var queued = 0
            var skipped = 0
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val url = o.optString("url")
                val cid = o.optString("cid").ifBlank { null } ?: FileNames.findCid(url)
                val title = o.optString("title").ifBlank { null }
                if (DownloadWorker.isSaved(this, url, cid, title) ||
                    DownloadWorker.enqueue(this, url, Http.DESKTOP_UA, webView.url, null, cid, title) == Queued.DUPLICATE
                ) {
                    skipped++
                    continue
                }
                queued++
            }
            when {
                queued > 0 -> Toast.makeText(this, "${queued}件の保存を予約しました（保存済み・保存待ちの${skipped}件はスキップ）", Toast.LENGTH_LONG).show()
                !auto && items.length() == 0 -> AlertDialog.Builder(this)
                    .setTitle("ダウンロードリンクが見つかりません")
                    .setMessage("上の「購入済み」を押して購入済み作品の一覧を開いてから、もう一度お試しください。\n\nそれでも見つからない場合は、作品をタップして右側の「ダウンロード」ボタンを直接押してください（押した作品は自動で保存されます）。\n\n改善のため、「設定」→「ページ情報を保存」で作ったファイルを開発者に送っていただけると助かります。")
                    .setPositiveButton("OK", null).show()
                !auto -> Toast.makeText(this, "未保存の作品はありません（${skipped}件保存済み）", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var bulkAuto = false
    private var bulkQueued = 0

    private inner class Bridge {
        @JavascriptInterface
        fun onItemClick(title: String?, cid: String?) {
            val t = title?.trim()?.takeIf { it.isNotEmpty() }
            val c = cid?.trim()?.takeIf { it.isNotEmpty() }
            // ダウンロードボタン等で作品名が取れなかったときは、直前に選んだ作品の情報を残す
            if (t == null && c == null) return
            lastClickTitle = t
            lastClickCid = c
            lastClickAt = System.currentTimeMillis()
        }

        /** 一括保存中に見つけたダウンロードリンク */
        @JavascriptInterface
        fun onBulkLink(url: String, title: String?) = runOnUiThread {
            val cid = FileNames.findCid(url)
            val t = title?.ifBlank { null }
            if (DownloadWorker.isSaved(this@MainActivity, url, cid, t)) return@runOnUiThread
            DownloadWorker.enqueue(this@MainActivity, url, Http.DESKTOP_UA, webView.url, null, cid, t)
            bulkQueued++
        }

        @JavascriptInterface
        fun onBulkProgress(index: Int, total: Int) = runOnUiThread {
            btnBulk.text = "停止 ($index/$total)"
        }

        @JavascriptInterface
        fun onBulkDone(checked: Int, skipped: Int, found: Int) = runOnUiThread {
            btnBulk.text = "一括保存"
            val msg = "確認完了: ${checked}件を確認、${found}件の保存を開始しました（保存済み${skipped}件はスキップ）"
            if (!bulkAuto || found > 0) Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            if (!bulkAuto && checked > 0 && found == 0) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("ダウンロードボタンが見つかりませんでした")
                    .setMessage("作品をタップしたときに表示される「ダウンロード」ボタンを自動で見つけられませんでした。\n\nお手数ですが、作品を1つタップして右側にダウンロードボタンが出た状態で「設定」→「ページ情報を保存」を押し、できたファイルを開発者に送ってください。対応します。\n\n（それまでは、作品をタップ→「ダウンロード」を押せば自動で保存されます）")
                    .setPositiveButton("OK", null).show()
            }
            bulkQueued = 0
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

    /** 「Googleでログイン」で開いたGoogleの許可画面から戻ってきたときの処理 */
    private val googleLoginLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val result = runCatching {
            Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data)
        }.getOrElse {
            showLoginMessage("ログインがキャンセルされました")
            return@registerForActivityResult
        }
        finishGoogleLogin(result)
    }

    private var settingsAccountView: TextView? = null

    private fun startGoogleLogin() {
        showLoginMessage("Googleに接続中…")
        Identity.getAuthorizationClient(this).authorize(GoogleAuth.request(null))
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    googleLoginLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    finishGoogleLogin(result)
                }
            }
            .addOnFailureListener { showLoginMessage("ログイン失敗: " + GoogleAuth.translate(it).message) }
    }

    private fun finishGoogleLogin(result: AuthorizationResult) {
        lifecycleScope.launch {
            val email = withContext(Dispatchers.IO) { runCatching { GoogleAuth.fetchEmail(result) } }
            email.onSuccess {
                Prefs.setGoogleAccount(this@MainActivity, it)
                Prefs.setDriveMode(this@MainActivity, Prefs.MODE_API)
                showLoginMessage("ログイン中: $it")
                Toast.makeText(this@MainActivity, "Googleドライブ連携が完了しました", Toast.LENGTH_SHORT).show()
            }.onFailure { showLoginMessage("ログイン失敗: ${it.message}") }
        }
    }

    private fun showLoginMessage(msg: String) {
        settingsAccountView?.text = msg
        if (settingsAccountView == null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * 表示中のページの構造（HTML）を Download/FANZA に保存する。
     * 自動処理がうまく動かないときに、開発者がページの作りを確認するためのもの。
     */
    private fun dumpPage(info: TextView) {
        webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val html = JSONArray("[$raw]").getString(0)
                        val name = "page_info_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(java.util.Date()) + ".html"
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/html")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/FANZA")
                        }
                        val uri = contentResolver.insert(
                            android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                        ) ?: error("保存できませんでした")
                        contentResolver.openOutputStream(uri)!!.use { out ->
                            out.write("<!-- ${webView.url} -->\n".toByteArray())
                            out.write(html.toByteArray())
                        }
                        name
                    }
                }
                info.text = result.fold(
                    { "Download/FANZA/$it に保存しました。このファイルを開発者に送ってください。" },
                    { "保存に失敗しました: ${it.message}" },
                )
            }
        }
    }

    /** 「保存状況」: 処理中・順番待ち・完了・失敗を一覧表示する（1秒ごとに更新） */
    private fun showStatus() {
        val text = TextView(this).apply {
            setPadding(48, 24, 48, 24)
            textSize = 13f
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        val handler = android.os.Handler(mainLooper)
        val refresh = object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    text.text = withContext(Dispatchers.IO) { statusText() }
                }
                handler.postDelayed(this, 1000)
            }
        }
        refresh.run()
        val paused = Prefs.paused(this)
        AlertDialog.Builder(this)
            .setTitle("保存状況")
            .setView(scroll)
            .setPositiveButton("閉じる", null)
            .setNegativeButton(if (paused) "▶ 再開" else "⏸ 一時停止") { _, _ ->
                if (paused) DownloadWorker.resumeAll(this) else DownloadWorker.pauseAll(this)
                updateStatusButton()
                Toast.makeText(
                    this,
                    if (paused) "再開しました（途中から続きます）" else "一時停止しました（途中までのデータは残ります）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNeutralButton("整理") { _, _ -> tidyUp(showResult = true) }
            .setOnDismissListener { handler.removeCallbacks(refresh) }
            .show()
    }

    /** 完了・失敗の表示を消し、不要な一時ファイルを削除し、待機中の作品をすぐ再試行する */
    private fun tidyUp(showResult: Boolean) {
        val app = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (showResult) {
                        JobLog.clear(app)
                        WorkManager.getInstance(app).pruneWork()
                        DownloadWorker.retryNow(app)
                    }
                    JobQueue.cleanOrphans(app)
                    DownloadWorker.restoreQueue(app)
                }
            }
            if (showResult) Toast.makeText(this@MainActivity, "完了・失敗の表示を消し、待機中の作品をすぐ再試行します", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- バッテリー最適化

    /**
     * バッテリー最適化の対象から外れていると、アップデート直後や画面オフ中でも
     * Androidに止められずに保存処理を続けられる（外れていないと約10分ごとに中断される）。
     */
    private fun isBatteryExempt(): Boolean =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        }.onFailure {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun askBatteryExemptionOnce() {
        val sp = getSharedPreferences("fanza_prefs", MODE_PRIVATE)
        if (isBatteryExempt() || sp.getBoolean("battery_asked", false)) return
        sp.edit().putBoolean("battery_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle("バックグラウンドで止まらないようにする")
            .setMessage("大きな作品の保存を、画面を消している間やアプリのアップデート直後も止めずに続けるため、このアプリを「バッテリー最適化」の対象から外すことをおすすめします。\n\n次の画面で「許可」を押してください。（後から「設定」でも変更できます）")
            .setPositiveButton("設定する") { _, _ -> requestBatteryExemption() }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun updateStatusButton() {
        findViewById<Button>(R.id.btnStatus).text = if (Prefs.paused(this)) "⏸停止中" else "保存状況"
    }

    private fun statusText(): String {
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            sb.append("⚠ 通知が許可されていません。スマホの設定→アプリ→FANZA Drive保存→通知 をONにすると、進み具合や完了が通知で分かります。\n\n")
        }
        if (!isBatteryExempt()) {
            sb.append("⚠ バッテリー最適化が有効なため、画面オフ中やアップデート後に保存が中断されやすくなっています。「設定」→「バッテリー最適化を解除」がおすすめです。\n\n")
        }
        sb.append("保存先: ").append(
            when {
                !Prefs.driveEnabled(this) -> "本体の Download/FANZA（ドライブ未設定）"
                Prefs.saveLocal(this) -> "Googleドライブ ＋ 本体の Download/FANZA"
                else -> "Googleドライブ"
            }
        ).append("\n")

        val queue = JobQueue.all(this)
        val log = JobLog.all(this)
        val done = log.count { it.ok }
        val failed = log.filter { !it.ok }
        sb.append("残り ${queue.size}件 ・ 完了 ${done}件 ・ 失敗 ${failed.size}件\n\n")

        if (Prefs.paused(this)) sb.append("⏸ 一時停止中です。下の「▶ 再開」で続きから再開します。\n\n")

        // 処理中（同時に2件まで）
        val active = DownloadWorker.active
        if (active.isNotEmpty()) {
            sb.append("【処理中】\n")
            active.values.forEach { a ->
                sb.append("⏬ ").append(a.label).append("\n　").append(a.text)
                if (a.percent >= 0) sb.append("（").append(a.percent).append("%）")
                sb.append("\n\n")
            }
        }

        // 順番待ち
        val waiting = queue.filter { it.key !in active.keys }
            .entries.sortedBy { it.value.optLong("seq") }
        if (waiting.isNotEmpty()) {
            sb.append("【順番待ち ${waiting.size}件】\n")
            waiting.take(50).forEach { (_, j) ->
                val label = j.optString(DownloadWorker.KEY_TITLE_HINT).ifBlank { j.optString(DownloadWorker.KEY_CID).ifBlank { "作品" } }
                sb.append("⏳ ").append(label).append("\n")
                j.optString(DownloadWorker.J_LAST_ERROR).ifBlank { null }?.let {
                    sb.append("　").append(it).append("\n　→ 少し待って自動で再試行します（「整理」ですぐ再試行）\n")
                }
            }
            if (waiting.size > 50) sb.append("…ほか ${waiting.size - 50}件\n")
            sb.append("\n")
        }

        val keptCount = JobQueue.keptCount(this)
        if (keptCount > 0) {
            sb.append("📦 途中までのデータを残してある作品: ${keptCount}件（ダウンロードを押し直すと続きから再開）\n\n")
        }

        if (failed.isNotEmpty()) {
            sb.append("【失敗 ${failed.size}件】\n")
            failed.takeLast(30).reversed().forEach { sb.append("❌ ").append(it.title).append("\n　").append(it.message ?: "").append("\n") }
            sb.append("\n")
        }
        if (done > 0) {
            sb.append("【完了（新しい順・最大30件）】\n")
            log.filter { it.ok }.takeLast(30).reversed().forEach { sb.append("✅ ").append(it.title).append("\n") }
        }
        if (queue.isEmpty() && log.isEmpty() && active.isEmpty()) {
            sb.append("\nまだ保存の予約はありません。\n作品をタップ→「ダウンロードする」を押すとここに表示されます。")
        }
        return sb.toString()
    }

    /**
     * 「PCへ引き継ぐ」: スマホで保存済みの作品の一覧をファイルに書き出す。
     * PCの拡張機能で読み込むと、保存済みの作品を飛ばして残りだけをPCに保存できる。
     * Download/FANZA に保存し、Googleドライブ連携中ならドライブの「FANZA同人」フォルダにも置く。
     */
    private fun exportForPc(info: TextView) {
        info.text = "書き出し中…"
        val app = applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val queue = JobQueue.all(app).values
                    val json = org.json.JSONObject()
                        .put("app", "FanzaDrive")
                        .put("version", 1)
                        .put("exported_at", System.currentTimeMillis())
                        .put("done_titles", JSONArray(Prefs.doneTitles(app)))
                        .put("done_cids", JSONArray(Prefs.doneCids(app)))
                        .put("pending_titles", JSONArray(queue.map { it.optString(DownloadWorker.KEY_TITLE_HINT) }.filter { it.isNotBlank() }))
                    val bytes = json.toString(1).toByteArray()
                    val name = "FANZA_PC引き継ぎ_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/FANZA")
                    }
                    val uri = contentResolver.insert(
                        android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                    ) ?: error("保存できませんでした")
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    var where = "スマホの Download/FANZA/$name"
                    if (Prefs.driveEnabled(app)) {
                        val tmp = java.io.File(cacheDir, name).apply { writeBytes(bytes) }
                        runCatching {
                            DriveUploader.create(app).upload(tmp, name, "application/json", null, {}, { false }) { _, _ -> }
                            where += "\nGoogleドライブの「FANZA同人」フォルダ/$name"
                        }
                        tmp.delete()
                    }
                    Triple(where, Prefs.doneTitles(app).size, queue.size)
                }
            }
            result.onSuccess { (where, doneCount, pending) ->
                info.text = "書き出しました（保存済み ${doneCount}件）:\n$where"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("PCへ引き継ぐファイルを作りました")
                    .setMessage(
                        "保存場所:\n$where\n\nPCのChrome拡張機能「FANZA同人 一括保存」でこのファイルを読み込むと、スマホで保存済みの ${doneCount}件 を飛ばして残りだけをPCに保存します。\n\n" +
                            "スマホの保存待ち（${pending}件）は、二重にダウンロードしないよう一時停止するのがおすすめです。"
                    )
                    .setPositiveButton("スマホ側を一時停止") { _, _ ->
                        DownloadWorker.pauseAll(this@MainActivity)
                        updateStatusButton()
                    }
                    .setNeutralButton("保存待ちを取り消す") { _, _ -> DownloadWorker.cancelAll(this@MainActivity) }
                    .setNegativeButton("そのまま", null)
                    .show()
            }.onFailure { info.text = "書き出しに失敗しました: ${it.message}" }
        }
    }

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val radioApi = v.findViewById<RadioButton>(R.id.radioApi)
        val radioGas = v.findViewById<RadioButton>(R.id.radioGas)
        val groupApi = v.findViewById<View>(R.id.groupApi)
        val groupGas = v.findViewById<View>(R.id.groupGas)
        val textAccount = v.findViewById<TextView>(R.id.textAccount)
        val editUrl = v.findViewById<EditText>(R.id.editGasUrl).apply { setText(Prefs.gasUrl(this@MainActivity)) }
        val editKey = v.findViewById<EditText>(R.id.editGasKey).apply { setText(Prefs.gasKey(this@MainActivity)) }
        val swLocal = v.findViewById<MaterialSwitch>(R.id.switchLocal).apply { isChecked = Prefs.saveLocal(this@MainActivity) }
        val swWifi = v.findViewById<MaterialSwitch>(R.id.switchWifi).apply { isChecked = Prefs.wifiOnly(this@MainActivity) }
        val swAuto = v.findViewById<MaterialSwitch>(R.id.switchAuto).apply { isChecked = Prefs.autoScan(this@MainActivity) }
        val info = v.findViewById<TextView>(R.id.textInfo)
        info.text = "保存済み: ${Prefs.doneCount(this)}件\n※ドライブ連携が未設定のときは、本体の Download/FANZA フォルダに保存されます。"

        val account = Prefs.googleAccount(this)
        textAccount.text = if (account.isBlank()) "未ログイン" else "ログイン中: $account"
        settingsAccountView = textAccount

        fun updateGroups() {
            groupApi.visibility = if (radioApi.isChecked) View.VISIBLE else View.GONE
            groupGas.visibility = if (radioGas.isChecked) View.VISIBLE else View.GONE
        }
        (if (Prefs.driveMode(this) == Prefs.MODE_API) radioApi else radioGas).isChecked = true
        updateGroups()
        radioApi.setOnCheckedChangeListener { _, _ -> updateGroups() }
        radioGas.setOnCheckedChangeListener { _, _ -> updateGroups() }

        v.findViewById<Button>(R.id.btnGoogleLogin).setOnClickListener { startGoogleLogin() }
        v.findViewById<Button>(R.id.btnTestGas).setOnClickListener {
            val url = editUrl.text.toString().trim()
            val key = editKey.text.toString().trim()
            info.text = "テスト中…"
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    runCatching { "成功！ 保存先フォルダ: " + GasBackend(url, key).ping() }
                        .getOrElse { "失敗: ${it.message}" }
                }
                info.text = msg
            }
        }
        v.findViewById<Button>(R.id.btnDumpPage).setOnClickListener { dumpPage(info) }
        v.findViewById<Button>(R.id.btnExportPc).setOnClickListener { exportForPc(info) }
        v.findViewById<Button>(R.id.btnCancelAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("保存待ちをすべて取り消しますか？")
                .setMessage("スマホで保存待ちになっている ${JobQueue.size(this)}件 を取り消します（保存済みの作品はそのままです）。PCで続きを保存する場合などに使ってください。")
                .setPositiveButton("取り消す") { _, _ ->
                    DownloadWorker.cancelAll(this)
                    info.text = "保存待ちをすべて取り消しました"
                }
                .setNegativeButton("やめる", null)
                .show()
        }
        v.findViewById<Button>(R.id.btnBattery).apply {
            text = if (isBatteryExempt()) "バッテリー最適化: 解除済み ✓" else "バッテリー最適化を解除（止まりにくくする）"
            setOnClickListener { requestBatteryExemption() }
        }
        val btnKept = v.findViewById<Button>(R.id.btnClearKept)
        btnKept.text = "失敗した作品の途中データを削除（${JobQueue.keptCount(this)}件・" +
            String.format("%.0fMB", JobQueue.keptBytes(this) / 1024.0 / 1024.0) + "）"
        btnKept.setOnClickListener {
            JobQueue.clearKept(this)
            btnKept.text = "失敗した作品の途中データを削除（0件）"
            info.text = "途中データを削除しました"
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
                Prefs.setDriveMode(this, if (radioApi.isChecked) Prefs.MODE_API else Prefs.MODE_GAS)
                Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .setOnDismissListener { settingsAccountView = null }
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
                if(window.__fdBulkRunning) return;
                var el=e.target.closest('a, button, [role="button"], [data-url], li, [class*="item"], [class*="Item"]');
                if(!el) return;
                var i=fdInfo(el);
                if(window.FanzaDrive) FanzaDrive.onItemClick(i.title, i.cid);
              }, true);
            })();
        """

        /**
         * 購入済みページの自動処理:
         * 「購入済み作品」の一覧から作品を1つずつタップ → 右側に出た「ダウンロード」を探す →
         * リンクならURLをアプリへ渡し、ボタンなら押す（押すとアプリのダウンロード検知が働く）
         */
        private const val BULK_JS = """
            (function(){
              if(window.__fdBulkRunning){ window.__fdStop=true; return 'stopping'; }
              var DONE=__DONE_TITLES__;
              function vis(el){ var r=el.getBoundingClientRect(); return r.width>0 && r.height>0; }
              function txt(el){ return ((el && (el.innerText||el.textContent))||'').trim(); }
              function sleep(ms){ return new Promise(function(r){ setTimeout(r,ms); }); }

              // 作品には必ず「コミック」「CG」「ボイス」などの分類ラベルが付いているので、それを目印にする
              var CAT=/^(コミック|CG|ボイス|ゲーム|動画|音声|ASMR|写真集?|小説|ノベル|その他|ツール|イラスト|マンガ|アニメ|単話)(・AI)?$/;
              var LEFT=window.innerWidth*0.4;
              function isLibrary(){
                if(location.href.indexOf('mylibrary')>=0) return true;
                return [].some.call(document.querySelectorAll('body *'), function(e){
                  return e.children.length==0 && /^購入済み(作品|商品)/.test(txt(e));
                });
              }
              // 画面左側の作品一覧にある分類ラベル
              function leftLabels(){
                var all=[].filter.call(document.querySelectorAll('body *'), function(e){
                  if(e.children.length!=0 || !CAT.test(txt(e))) return false;
                  var r=e.getBoundingClientRect();
                  return r.width>0 && r.left<LEFT;
                });
                // 一覧のラベルは縦一列に並ぶので、一番多い横位置のものだけを残す（右側のおすすめ等を除外）
                var count={}, best=null;
                all.forEach(function(e){ var x=Math.round(e.getBoundingClientRect().left/8); count[x]=(count[x]||0)+1; if(best===null||count[x]>count[best]) best=x; });
                return all.filter(function(e){ return Math.abs(Math.round(e.getBoundingClientRect().left/8)-best)<=1; });
              }
              // ラベルから親をたどり、ラベルを1つだけ含む一番大きな枠＝1作品
              function findItems(){
                var labels=leftLabels(), out=[];
                labels.forEach(function(l){
                  var el=l;
                  while(el.parentElement && el.parentElement!==document.body){
                    var p=el.parentElement, n=0;
                    for(var k=0;k<labels.length && n<2;k++) if(p.contains(labels[k])) n++;
                    if(n>=2 || p.getBoundingClientRect().width>window.innerWidth*0.5) break;
                    el=p;
                  }
                  if(out.indexOf(el)<0) out.push(el);
                });
                return out;
              }
              function itemTitle(item){
                var lines=txt(item).split('\n').map(function(s){return s.trim();}).filter(function(s){
                  return s && !CAT.test(s) && !/^\d{4}年/.test(s);
                });
                var best=''; lines.forEach(function(s){ if(s.length>best.length) best=s; });
                return best;
              }
              function cidOf(el){
                var m=(el.outerHTML||'').match(/(?:^|[^A-Za-z0-9_])(d_[a-z0-9]*\d[a-z0-9]*)/);
                return m?m[1]:'';
              }
              // 一覧より右側にある「ダウンロード」ボタン・リンク
              function findDownloads(listRight){
                var res=[];
                document.querySelectorAll('a, button, [role="button"], input[type="button"], input[type="submit"], div, span, p').forEach(function(e){
                  var r=e.getBoundingClientRect();
                  if(r.width==0 || r.height==0 || r.left<listRight) return;
                  var t=(txt(e)||e.value||e.title||'').replace(/\s+/g,'');
                  var h=e.getAttribute('href')||'';
                  if(!(/^ダウンロード/.test(t) || (e.tagName=='A' && /download/i.test(h)))) return;
                  if(t.length>40 || /(アプリ|ビューア|ビューワ|ソフト|方法|ヘルプ|について)/.test(t)) return;
                  var c=e.closest('a, button, [role="button"]')||e;
                  if(res.indexOf(c)<0) res.push(c);
                });
                // 外側の枠と内側のボタンが両方入っていたら内側だけ残す
                return res.filter(function(e){ return !res.some(function(o){ return o!==e && e.contains(o); }); });
              }
              function key(list){ return list.map(function(e){ return e.outerHTML; }).join('|'); }

              if(!isLibrary() || findItems().length==0) return 'nolist';
              window.__fdBulkRunning=true; window.__fdStop=false;

              (async function(){
                var checked=0, skipped=0, found=0;
                try{
                  // 下までスクロールして全作品を読み込ませる
                  var items=findItems(), prev=-1, tries=0;
                  while(items.length!==prev && tries<30 && !window.__fdStop){
                    prev=items.length; tries++;
                    if(items.length) items[items.length-1].scrollIntoView();
                    window.scrollTo(0, document.body.scrollHeight);
                    await sleep(1500);
                    items=findItems();
                  }
                  var listRight=0;
                  items.forEach(function(it){ listRight=Math.max(listRight, it.getBoundingClientRect().right); });
                  for(var i=0;i<items.length && !window.__fdStop;i++){
                    var item=items[i], title=itemTitle(item), cid=cidOf(item);
                    FanzaDrive.onBulkProgress(i+1, items.length);
                    checked++;
                    if(title && DONE.indexOf(title)>=0){ skipped++; continue; }
                    var before=key(findDownloads(listRight));
                    item.scrollIntoView({block:'center'});
                    var target=item.querySelector('a[href]:not([href^="javascript"])')||item.querySelector('a,[role="button"]')||item;
                    target.click();
                    // 右側の表示が切り替わるのを最大8秒待つ
                    var dls=[];
                    for(var w=0; w<16; w++){
                      await sleep(500);
                      dls=findDownloads(listRight);
                      if(dls.length && key(dls)!==before) break;
                    }
                    for(var d=0; d<dls.length; d++){
                      var el=dls[d], href=el.href||'';
                      // 1作品に複数のダウンロードがある場合は (1)(2)… を付けて別々に保存
                      var t=dls.length>1 ? title+' ('+(d+1)+')' : title;
                      FanzaDrive.onItemClick(t, cid);
                      if(/^https?:/.test(href) && !/#$/.test(href)){
                        FanzaDrive.onBulkLink(href, t);
                      }else{
                        el.click();
                        await sleep(3000);
                      }
                      found++;
                    }
                  }
                }catch(err){ console.log('fanza-drive bulk error', err); }
                window.__fdBulkRunning=false;
                FanzaDrive.onBulkDone(checked, skipped, found);
              })();
              return 'started';
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

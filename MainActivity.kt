package chartink.app

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * Single-activity WebView wrapper for https://chartink.com/dashboard/488230.
 * All data, login, screeners and charts are the original Chartink website; the app only adds
 * native chrome, session persistence, connectivity/status handling and the refresh controller.
 */
class MainActivity : Activity(), RefreshController.Host {

    private enum class Conn(val label: String, val color: Int) {
        CONNECTED("CONNECTED", 0xFF16A34A.toInt()),
        REFRESHING("REFRESHING", 0xFF2563EB.toInt()),
        OFFLINE("OFFLINE", 0xFFDC2626.toInt()),
        UNREACHABLE("UNREACHABLE", 0xFFDC2626.toInt()),
        DELAYED("DATA DELAYED", 0xFFD97706.toInt()),
    }

    // ---- views / state -----------------------------------------------------------------------
    private lateinit var toolbar: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var backBtn: ImageButton
    private lateinit var refreshBtn: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var container: FrameLayout
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorDetail: TextView
    private lateinit var bottomBar: LinearLayout
    private val navItems = ArrayList<LinearLayout>()
    private val navLabels = ArrayList<TextView>()
    private var navBack: LinearLayout? = null
    private var navForward: LinearLayout? = null
    private var spinner: ObjectAnimator? = null

    override var webView: WebView? = null
    private val popups = ArrayList<Pair<Dialog, WebView>>()

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var calendar: MarketCalendar
    private lateinit var controller: RefreshController
    private lateinit var network: NetworkMonitor
    private val handler = Handler(Looper.getMainLooper())

    private var pageLoading = false
    private var loadError: String? = null       // non-null while the error overlay is showing
    private var failedUrl: String? = null
    private var resumed = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: Array<String?>? = null
    private var downX = 0f
    private var downY = 0f

    private val statusTick = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 10_000) }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("chatink", MODE_PRIVATE)
        calendar = MarketCalendar.parse(resources.openRawResource(R.raw.market_calendar).bufferedReader().use { it.readText() })
        controller = RefreshController(calendar, this)
        network = NetworkMonitor(this) { online -> onNetworkChanged(online) }

        buildUi()
        webView = createWebView(popup = false).also { container.addView(it, 0) }

        val restored = savedInstanceState?.let { webView!!.restoreState(it) } != null
        if (!restored) webView!!.loadUrl(prefs.getString(KEY_LAST_DASHBOARD, null) ?: Config.HOME_URL)
        updateNav(); updateStatus()
    }

    override fun onStart() {
        super.onStart()
        network.start()
        webView?.setNetworkAvailable(network.online)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        webView?.onResume()
        webView?.resumeTimers()
        controller.onForeground()
        handler.post(statusTick)
        updateStatus()
    }

    override fun onPause() {
        resumed = false
        controller.onBackground()                 // nothing refreshes while backgrounded
        handler.removeCallbacks(statusTick)
        webView?.onPause()
        webView?.pauseTimers()
        CookieManager.getInstance().flush()       // persist the login session
        super.onPause()
    }

    override fun onStop() {
        network.stop()
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller.onBackground()
        network.stop()
        spinner?.cancel()
        popups.toList().forEach { (d, _) -> d.dismiss() }
        fileCallback?.onReceiveValue(null); fileCallback = null
        webView?.let { container.removeView(it); it.destroy() }
        webView = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)   // manifest handles rotation: the page is NOT reloaded
        applyBarSizes()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    // ---- RefreshController.Host --------------------------------------------------------------

    override fun isOnline(): Boolean = network.online

    override fun canAutoRefresh(): Boolean =
        resumed && loadError == null && !pageLoading && popups.isEmpty() &&
            webView?.visibility == View.VISIBLE && UrlPolicy.isDashboard(webView?.url)

    override fun onRefreshStateChanged() = updateStatus()

    private fun onNetworkChanged(online: Boolean) {
        webView?.setNetworkAvailable(online)
        if (online && loadError != null) retry()  // recover the page that failed while offline
        updateStatus()
    }

    // ---- UI ----------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun ripple(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return tv.resourceId
    }

    private fun tint() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(0xFF1F2937.toInt(), 0xFFB0B7C3.toInt()),
    )

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        imageTintList = tint()
        setBackgroundResource(ripple())
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
    }

    private fun navItem(res: Int, label: String, onClick: () -> Unit): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            setBackgroundResource(ripple())
            setOnClickListener { onClick() }
        }
        val icon = ImageView(this).apply { setImageResource(res); imageTintList = tint() }
        val text = TextView(this).apply {
            this.text = label; textSize = 10f; setTextColor(0xFF4B5563.toInt()); gravity = Gravity.CENTER
        }
        item.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        item.addView(text)
        navItems += item; navLabels += text
        return item
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE); setPadding(dp(2), 0, dp(2), 0)
        }
        backBtn = iconButton(R.drawable.ic_back, "Back") { webView?.takeIf { it.canGoBack() }?.goBack() }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        titles.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111827.toInt()); maxLines = 1
        })
        statusView = TextView(this).apply {
            textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(0xFF6B7280.toInt())
        }
        titles.addView(statusView)
        refreshBtn = iconButton(R.drawable.ic_refresh, "Refresh") { manualRefresh() }
        toolbar.addView(backBtn, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        toolbar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(refreshBtn, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.toolbar_height)))
        root.addView(View(this).apply { setBackgroundColor(0xFFE5E7EB.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        container = FrameLayout(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressTintList = ColorStateList.valueOf(0xFF2563EB.toInt())
        }
        container.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))

        errorOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE)
            setPadding(dp(32), dp(32), dp(32), dp(32)); visibility = View.GONE; isClickable = true
        }
        errorOverlay.addView(TextView(this).apply {
            text = "Can't load Chartink"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111827.toInt()); gravity = Gravity.CENTER
        })
        errorDetail = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF4B5563.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16))
        }
        errorOverlay.addView(errorDetail)
        errorOverlay.addView(Button(this).apply { text = "Retry"; setOnClickListener { retry() } })
        container.addView(errorOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(View(this).apply { setBackgroundColor(0xFFE5E7EB.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE) }
        navBack = navItem(R.drawable.ic_back, "Back") { webView?.takeIf { it.canGoBack() }?.goBack() }
        navForward = navItem(R.drawable.ic_forward, "Forward") { webView?.takeIf { it.canGoForward() }?.goForward() }
        val navHome = navItem(R.drawable.ic_home, "Home") { goHome() }
        val navRefresh = navItem(R.drawable.ic_refresh, "Refresh") { manualRefresh() }
        for (n in listOf(navBack!!, navForward!!, navHome, navRefresh)) {
            bottomBar.addView(n, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.bottom_bar_height)))

        setContentView(root)
        applyBarSizes()
    }

    /** Compact bars in landscape (values-land overrides the dimens); labels hidden there. */
    private fun applyBarSizes() {
        toolbar.layoutParams.height = resources.getDimensionPixelSize(R.dimen.toolbar_height)
        bottomBar.layoutParams.height = resources.getDimensionPixelSize(R.dimen.bottom_bar_height)
        val labels = resources.getBoolean(R.bool.show_nav_labels)
        navLabels.forEach { it.visibility = if (labels) View.VISIBLE else View.GONE }
        toolbar.requestLayout(); bottomBar.requestLayout()
    }

    private fun updateNav() {
        val wv = webView
        val canBack = wv?.canGoBack() == true
        val canFwd = wv?.canGoForward() == true
        backBtn.isEnabled = canBack
        navBack?.isEnabled = canBack; navBack?.alpha = if (canBack) 1f else 0.4f
        navForward?.isEnabled = canFwd; navForward?.alpha = if (canFwd) 1f else 0.4f
    }

    private fun connState(): Conn = when {
        !network.online -> Conn.OFFLINE
        loadError != null -> Conn.UNREACHABLE
        controller.refreshing -> Conn.REFRESHING
        controller.isDataDelayed() -> Conn.DELAYED
        else -> Conn.CONNECTED
    }

    private fun updateStatus() {
        val state = connState()
        val now = System.currentTimeMillis()
        val ts = controller.dataTimestamp
        // Prefer the timestamp the page itself shows; otherwise say plainly that this is the app's own sync time.
        val updated = when {
            ts != null -> "Data " + TimestampParser.label(ts, now)
            controller.lastSyncMs > 0 -> "Synced " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(controller.lastSyncMs))
            else -> null
        }
        val sb = SpannableStringBuilder()
        val head = "● ${state.label}"
        sb.append(head)
        sb.setSpan(ForegroundColorSpan(state.color), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        listOfNotNull(updated, controller.autoLabel()).forEach { sb.append("  ·  ").append(it) }
        statusView.text = sb

        val spinning = state == Conn.REFRESHING
        if (spinning && spinner == null) {
            spinner = ObjectAnimator.ofFloat(refreshBtn, View.ROTATION, 0f, 360f).apply {
                duration = 900; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
            }
        } else if (!spinning && spinner != null) {
            spinner?.cancel(); spinner = null; refreshBtn.rotation = 0f
        }
    }

    // ---- actions -----------------------------------------------------------------------------

    private fun manualRefresh() {
        if (loadError != null) retry() else controller.refreshNow()
    }

    private fun goHome() {
        loadError?.let { hideError() }
        webView?.loadUrl(Config.HOME_URL)
    }

    private fun retry() {
        val wv = webView ?: return
        hideError()
        val target = failedUrl
        if (target != null) wv.loadUrl(target) else wv.reload()
    }

    private fun showError(message: String, url: String?) {
        loadError = message; failedUrl = url ?: failedUrl
        errorDetail.text = message
        errorOverlay.visibility = View.VISIBLE
        progress.visibility = View.GONE
        controller.onLoadFailed()
        updateStatus()
    }

    private fun hideError() {
        loadError = null
        errorOverlay.visibility = View.GONE
        updateStatus()
    }

    private fun openExternal(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show() }
    }

    /** true = handled here (do not load in the WebView). */
    private fun handleNavigation(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> when {
                !UrlPolicy.isInApp(uri) -> { openExternal(uri); true }
                uri.scheme == "http" -> { webView?.loadUrl(UrlPolicy.upgradeToHttps(uri).toString()); true }
                else -> false
            }
            "about", "blob", "data", "javascript" -> false
            "tel", "mailto", "sms", "geo" -> { openExternal(uri); true }
            else -> true // file:, content:, intent:, custom schemes are blocked
        }
    }

    // ---- WebView -----------------------------------------------------------------------------

    private fun createWebView(popup: Boolean): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.setBackgroundColor(Color.WHITE)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false     // only user-gesture popups
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            safeBrowsingEnabled = true
            // Fit wide tables/charts to the screen instead of scrolling sideways; pinch zoom stays available.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(wv, true) }
        wv.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) }
        if (popup) {
            wv.webViewClient = PopupClient()
        } else {
            wv.webViewClient = MainClient()
            wv.webChromeClient = MainChrome()
            val slop = ViewConfiguration.get(this).scaledTouchSlop
            wv.setOnTouchListener { _, ev ->                    // a tap (not a scroll) means the user changed page state
                controller.markTouch()
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                    MotionEvent.ACTION_UP -> if (abs(ev.x - downX) < slop && abs(ev.y - downY) < slop) controller.markInteracted()
                }
                false
            }
        }
        if (popup) wv.webChromeClient = PopupChrome()
        return wv
    }

    private fun saveLastDashboard(url: String?) {
        if (UrlPolicy.isDashboard(url)) prefs.edit().putString(KEY_LAST_DASHBOARD, url).apply()
    }

    private open inner class BaseClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            if (request.isForMainFrame) handleNavigation(request.url) else false

        // Never bypass certificate validation.
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            onSecureFail(error.url)
        }

        open fun onSecureFail(url: String?) {}
    }

    private inner class MainClient : BaseClient() {
        private val empty = ByteArray(0)

        /** Drop third-party ads/trackers: fewer requests, so the dashboard becomes usable sooner. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host?.lowercase() ?: return null
            if (Config.BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") })
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(empty))
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageLoading = true
            if (loadError != null) hideError()
            progress.visibility = View.VISIBLE
            updateNav(); updateStatus()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pageLoading = false
            progress.visibility = View.GONE
            saveLastDashboard(url)
            CookieManager.getInstance().flush()
            updateNav()
            if (loadError == null) controller.onPageLoaded()
            updateStatus()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            saveLastDashboard(url); updateNav()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val desc = error.description?.toString().orEmpty()
            if (desc.contains("ERR_ABORTED")) return             // navigation handed off / superseded
            showError(
                if (network.online) "Chartink didn't respond ($desc). Check your connection and try again."
                else "You're offline. The page will reload when the connection returns.",
                request.url.toString(),
            )
        }

        override fun onSecureFail(url: String?) =
            showError("A secure connection to Chartink could not be established, so the page was not loaded.", url)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val last = view.url ?: prefs.getString(KEY_LAST_DASHBOARD, null) ?: Config.HOME_URL
            container.removeView(view); view.destroy()
            webView = createWebView(popup = false).also { container.addView(it, 0); it.loadUrl(last) }
            return true
        }
    }

    private inner class PopupClient : BaseClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            // Inside a popup (checkout / sign-in windows) any https page may load, other schemes are handled as usual.
            return if (uri.scheme == "https") false else handleNavigation(uri)
        }
    }

    private open inner class BaseChrome : WebChromeClient() {
        private fun dialog(url: String?) = Uri.parse(url ?: "").host ?: "Chartink"

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (isFinishing) { result.cancel(); return true }
            AlertDialog.Builder(this@MainActivity).setTitle(dialog(url)).setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }.show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (isFinishing) { result.cancel(); return true }
            AlertDialog.Builder(this@MainActivity).setTitle(dialog(url)).setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }.show()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (isFinishing) { result.cancel(); return true }
            val input = EditText(this@MainActivity).apply { setText(defaultValue) }
            AlertDialog.Builder(this@MainActivity).setTitle(dialog(url)).setMessage(message).setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }.show()
            return true
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(params.createIntent(), REQ_FILE)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null; callback.onReceiveValue(null); false
            }
        }
    }

    private inner class MainChrome : BaseChrome() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        // Popups (e.g. payment / sign-in windows) open in an in-app dialog so window.opener keeps working.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture || isFinishing) return false
            val popupView = createWebView(popup = true)
            val dlg = Dialog(this@MainActivity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            dlg.setContentView(FrameLayout(this@MainActivity).apply { addView(popupView) })
            val entry = Pair(dlg, popupView)
            dlg.setOnDismissListener { popups.remove(entry); popupView.destroy(); updateStatus() }
            dlg.setOnKeyListener { d, key, ev ->
                if (key == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                    if (popupView.canGoBack()) popupView.goBack() else d.dismiss()
                    true
                } else false
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popupView
            resultMsg.sendToTarget()
            popups += entry
            dlg.show()
            updateStatus()
            return true
        }
    }

    private inner class PopupChrome : BaseChrome() {
        override fun onCloseWindow(window: WebView) {
            popups.firstOrNull { it.second === window }?.first?.dismiss()
        }
    }

    // ---- file upload / download --------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun startDownload(url: String, ua: String?, contentDisposition: String?, mime: String?) {
        if (!url.startsWith("http")) {
            Toast.makeText(this, "This kind of download isn't supported in the app", Toast.LENGTH_LONG).show(); return
        }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = arrayOf(url, ua, contentDisposition, mime)
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            ua?.let { addRequestHeader("User-Agent", it) }
            mime?.let { setMimeType(it) }
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        try {
            getSystemService(DownloadManager::class.java).enqueue(req)
            Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val p = pendingDownload; pendingDownload = null
            if (p != null && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDownload(p[0]!!, p[1], p[2], p[3])
            else if (p != null) Toast.makeText(this, "Storage permission is needed to save downloads", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val KEY_LAST_DASHBOARD = "last_dashboard_url"
        const val REQ_FILE = 1001
        const val REQ_STORAGE = 1002
    }
}

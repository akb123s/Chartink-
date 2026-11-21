package chartink.app

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Drives the auto refresh (Config.REFRESH_INTERVAL_MS) and the manual refresh button.
 *
 * Strategy (in order):
 *  1. PARTIAL  - click Chartink's own visible widget refresh buttons, so the site re-fetches its data
 *                itself (no page reload; scroll, filters, sorting and session are untouched).
 *  2. FALLBACK - if the page has no such buttons, reload the dashboard and restore the scroll position.
 *                A reload resets in-page UI state (e.g. a sort you tapped), so AUTOMATIC fallback reloads
 *                only run while the page is untouched since it loaded; after you interact, auto refresh
 *                pauses until you press Refresh yourself.
 *
 * Guards: foreground only, NSE session open, online, dashboard visible, one cycle at a time,
 * and never while the user is typing in a field or a dialog is open.
 */
class RefreshController(
    private val calendar: MarketCalendar,
    private val host: Host,
) {
    interface Host {
        val webView: WebView?
        fun isOnline(): Boolean

        /** Dashboard is the current page, fully loaded, visible, with no error overlay or popup. */
        fun canAutoRefresh(): Boolean
        fun onRefreshStateChanged()
    }

    enum class Mode { UNKNOWN, PARTIAL, FALLBACK }

    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    private var generation = 0
    private var reloadInFlight = false
    private var pendingScroll: IntArray? = null

    var refreshing = false
        private set
    var mode = Mode.UNKNOWN
        private set
    var lastSyncMs = 0L
        private set
    var dataTimestamp: TimestampParser.Parsed? = null
        private set
    var userInteracted = false
        private set
    private var lastTouchMs = 0L
    private var ineffectivePartial = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            autoTick()
            handler.postDelayed(this, Config.REFRESH_INTERVAL_MS)
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    fun onForeground() {
        foreground = true
        handler.removeCallbacks(tick)
        val sinceSync = if (lastSyncMs == 0L) 0L else System.currentTimeMillis() - lastSyncMs
        handler.postDelayed(tick, (Config.REFRESH_INTERVAL_MS - sinceSync).coerceIn(1_000L, Config.REFRESH_INTERVAL_MS))
    }

    /** Stops all timers and abandons any in-flight cycle (nothing runs while backgrounded). */
    fun onBackground() {
        foreground = false
        generation++
        handler.removeCallbacksAndMessages(null)
        reloadInFlight = false
        if (refreshing) { refreshing = false; host.onRefreshStateChanged() }
    }

    fun markInteracted() { userInteracted = true }

    /** Any touch on the page (scroll or tap); auto reload waits until the screen has been idle for a moment. */
    fun markTouch() { lastTouchMs = System.currentTimeMillis() }

    private fun isIdle() = System.currentTimeMillis() - lastTouchMs >= Config.IDLE_BEFORE_RELOAD_MS

    /** The page failed to load (offline / server / TLS): end the cycle instead of waiting for the timeout. */
    fun onLoadFailed() {
        reloadInFlight = false
        pendingScroll = null
        if (refreshing) finishCycle(generation, success = false)
    }

    fun isDataDelayed(nowMs: Long = System.currentTimeMillis()): Boolean {
        val ts = dataTimestamp ?: return false
        return calendar.isOpenAt(nowMs) && nowMs - ts.epochMs > Config.DELAYED_AFTER_MS
    }

    /** Called from WebViewClient.onPageFinished for the main frame. */
    fun onPageLoaded() {
        userInteracted = false
        lastSyncMs = System.currentTimeMillis()
        if (reloadInFlight) {
            reloadInFlight = false
            restoreScroll(generation)
            finishCycle(generation, success = true)
        }
        // Widgets load asynchronously after onPageFinished, so look for a timestamp twice.
        val gen = generation
        handler.postDelayed({ if (gen == generation) captureTimestamp(null) }, 2_500)
        handler.postDelayed({ if (gen == generation) captureTimestamp(null) }, 8_000)
        host.onRefreshStateChanged()
    }

    // ---- refresh -----------------------------------------------------------------------------

    fun refreshNow() = refresh(manual = true)

    private fun autoTick() {
        if (refreshing) return
        if (Config.REFRESH_ONLY_WHEN_MARKET_OPEN && !calendar.isOpen()) return
        if (!host.isOnline()) return
        if (!host.canAutoRefresh()) return
        // Skip if something (page load, manual refresh) already synced within the last 20 s: avoids back-to-back requests.
        if (System.currentTimeMillis() - lastSyncMs < Config.REFRESH_INTERVAL_MS * 2 / 3) return
        refresh(manual = false)
    }

    private fun refresh(manual: Boolean) {
        val wv = host.webView ?: return
        if (refreshing) return                       // never overlap requests
        if (!host.isOnline()) { host.onRefreshStateChanged(); return }

        val gen = ++generation
        if (!UrlPolicy.isDashboard(wv.url)) {
            if (manual) startReload(wv, gen)         // other pages: a plain reload on explicit request
            return
        }
        if (ineffectivePartial >= Config.MAX_INEFFECTIVE_PARTIAL) {   // clicking has proven useless: reload directly
            mode = Mode.FALLBACK
            if (manual || isIdle()) startReload(wv, gen)
            return
        }
        setRefreshing(true)
        armTimeout(gen)
        wv.evaluateJavascript(PageScripts.refreshStart(force = manual)) { raw ->
            if (gen != generation) return@evaluateJavascript
            when (PageScripts.parseObject(raw)?.optString("status")) {
                "CLICKED" -> { mode = Mode.PARTIAL; poll(gen, System.currentTimeMillis()) }
                "NONE" -> {
                    mode = Mode.FALLBACK
                    if (manual || isIdle()) startReload(wv, gen) else finishCycle(gen, success = false)
                }
                else -> finishCycle(gen, success = false) // BUSY, ERROR or page not ready: skip this cycle
            }
        }
    }

    private fun poll(gen: Int, startedAt: Long) {
        handler.postDelayed({
            if (gen != generation) return@postDelayed
            val wv = host.webView ?: return@postDelayed finishCycle(gen, false)
            wv.evaluateJavascript(PageScripts.poll()) { raw ->
                if (gen != generation) return@evaluateJavascript
                val obj = PageScripts.parseObject(raw)
                val done = obj?.optBoolean("done", true) ?: true
                if (done || System.currentTimeMillis() - startedAt > Config.REFRESH_TIMEOUT_MS) {
                    val changed = (obj?.optInt("net", 0) ?: 0) > 0 || (obj?.optInt("mut", 0) ?: 0) > 0
                    if (changed) { ineffectivePartial = 0; finishCycle(gen, true) }
                    else {
                        // The clicked buttons did nothing visible (wrong buttons / no data request): count it and reload now.
                        ineffectivePartial++
                        if (isIdle()) startReload(wv, gen) else finishCycle(gen, false)
                    }
                } else poll(gen, startedAt)
            }
        }, 400)
    }

    private fun startReload(wv: WebView, gen: Int) {
        pendingScroll = intArrayOf(wv.scrollX, wv.scrollY)
        reloadInFlight = true
        setRefreshing(true)
        armTimeout(gen)
        wv.reload()
    }

    /** Re-apply the scroll position a few times: dashboard content renders after onPageFinished. */
    private fun restoreScroll(gen: Int) {
        val target = pendingScroll ?: return
        pendingScroll = null
        for (delay in longArrayOf(0, 350, 900, 1_800)) {
            handler.postDelayed({
                val wv = host.webView
                if (gen == generation && wv != null && wv.scrollY == 0 && target[1] > 0) wv.scrollTo(target[0], target[1])
            }, delay)
        }
    }

    private fun armTimeout(gen: Int) {
        handler.postDelayed({
            if (gen == generation && refreshing) { reloadInFlight = false; finishCycle(gen, success = false) }
        }, Config.REFRESH_TIMEOUT_MS + 5_000)
    }

    private fun finishCycle(gen: Int, success: Boolean) {
        if (gen != generation) return
        if (success) lastSyncMs = System.currentTimeMillis()
        refreshing = false
        host.onRefreshStateChanged()
        if (success) captureTimestamp(null)
    }

    private fun setRefreshing(v: Boolean) { refreshing = v; host.onRefreshStateChanged() }

    private fun captureTimestamp(done: (() -> Unit)?) {
        val wv = host.webView
        if (wv == null) { done?.invoke(); return }
        wv.evaluateJavascript(PageScripts.timestamps()) { raw ->
            dataTimestamp = TimestampParser.newest(PageScripts.parseStringList(raw), System.currentTimeMillis())
            host.onRefreshStateChanged()
            done?.invoke()
        }
    }

    /** Short description of what the auto refresh is doing, shown in the status line. */
    fun autoLabel(): String = when {
        Config.REFRESH_ONLY_WHEN_MARKET_OPEN && !calendar.isOpen() -> "Market closed"
        !host.canAutoRefresh() -> "Auto paused"
        mode == Mode.FALLBACK -> "Auto ${Config.REFRESH_INTERVAL_MS / 1000}s (reload)"
        else -> "Auto ${Config.REFRESH_INTERVAL_MS / 1000}s"
    }
}

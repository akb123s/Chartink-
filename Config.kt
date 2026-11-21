package chartink.app

/** App-wide constants. The dashboard URL is fixed by requirement and must not change. */
object Config {
    const val HOME_URL = "https://chartink.com/dashboard/488230"

    /** Auto refresh cadence while the NSE session is open. */
    const val REFRESH_INTERVAL_MS = 20_000L

    /** Set to false to keep auto refresh running outside NSE hours (useful for testing on weekends). */
    const val REFRESH_ONLY_WHEN_MARKET_OPEN = true

    /**
     * A page reload resets in-page UI state (e.g. a sort you tapped). Auto reload therefore waits until
     * you have not touched the screen for this long, instead of pausing for good after the first tap.
     */
    const val IDLE_BEFORE_RELOAD_MS = 6_000L

    /** After this many partial refreshes that changed nothing on the page, stop clicking and just reload. */
    const val MAX_INEFFECTIVE_PARTIAL = 2

    /** Third-party ad / tracker hosts blocked in the main WebView to cut page load time. */
    val BLOCKED_HOSTS = listOf(
        "googlesyndication.com", "doubleclick.net", "googletagmanager.com", "google-analytics.com",
        "googleadservices.com", "adservice.google.com", "connect.facebook.net", "facebook.net",
        "hotjar.com", "clarity.ms", "taboola.com", "outbrain.com", "criteo.com", "adnxs.com",
    )

    /** Hard stop for a single refresh cycle so a stuck request can never block the next one. */
    const val REFRESH_TIMEOUT_MS = 20_000L

    /**
     * "DATA DELAYED" is shown only when the page itself shows a timestamp that is older than this
     * while the market is open. Intraday scans run on candles, so some lag is normal.
     */
    const val DELAYED_AFTER_MS = 15 * 60_000L
}

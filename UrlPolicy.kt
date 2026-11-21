package chartink.app

import android.net.Uri
import java.util.Locale

/** Decides which navigations stay inside the app. Pure string logic apart from Uri parsing. */
object UrlPolicy {
    private val CHARTINK_HOSTS = listOf("chartink.com")

    /** Hosts (and their sub-domains) other than Chartink that may load inside the app (payment checkout). */
    private val EXTRA_IN_APP_HOSTS = listOf("razorpay.com")

    private fun hostMatches(host: String?, roots: List<String>): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return roots.any { h == it || h.endsWith(".$it") }
    }

    fun isChartink(uri: Uri): Boolean = hostMatches(uri.host, CHARTINK_HOSTS)

    fun isInApp(uri: Uri): Boolean =
        isChartink(uri) || hostMatches(uri.host, EXTRA_IN_APP_HOSTS)

    /** Pages where the 30 s auto refresh may run: Chartink dashboards only. */
    fun isDashboard(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || !isChartink(uri)) return false
        val path = uri.path ?: return false
        return path == "/dashboard" || path.startsWith("/dashboard/")
    }

    /** http -> https for Chartink links (cleartext traffic is disabled for the app). */
    fun upgradeToHttps(uri: Uri): Uri =
        if (uri.scheme == "http") uri.buildUpon().scheme("https").build() else uri
}

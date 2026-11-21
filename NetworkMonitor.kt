package chartink.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/** Reports whether the device has an internet-capable default network. Callbacks arrive on the main thread. */
class NetworkMonitor(context: Context, private val onChange: (Boolean) -> Unit) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    var online: Boolean = currentlyOnline()
        private set

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun update(value: Boolean) {
        if (value != online) { online = value; onChange(value) }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            update(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        override fun onLost(network: Network) = update(false)
    }

    fun start() {
        if (registered) return
        online = currentlyOnline()
        cm.registerDefaultNetworkCallback(callback, handler)
        registered = true
    }

    fun stop() {
        if (!registered) return
        try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) {}
        registered = false
    }
}

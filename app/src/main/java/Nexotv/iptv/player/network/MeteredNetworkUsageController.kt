package Nexotv.iptv.player.network
import Nexotv.iptv.player.*
import Nexotv.iptv.player.common.formatByteSize
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.edit

/** Detecta redes medidas y acumula el tráfico recibido por este proceso mientras están activas. */
internal class MeteredNetworkUsageController(private val activity: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity: ConnectivityManager?
        get() = activity.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastRxBytes = TrafficStats.getUidRxBytes(Process.myUid())
    private var warnedThisSession = false
    private var registered = false

    private val sampler = object : Runnable {
        override fun run() {
            sample()
            if (registered) handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    fun start() {
        if (registered) return
        val manager = connectivity ?: return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateFromNetwork(network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    updateMetered(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                }
            }

            override fun onLost(network: Network) {
                handler.postDelayed({ if (registered && connectivity?.activeNetwork == null) updateMetered(false) }, 1_000L)
                // Esperar a onAvailable/onCapabilitiesChanged evita quitar el límite durante un handoff.
            }
        }
        callback = networkCallback
        runCatching { manager.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess {
                registered = true
                manager.activeNetwork?.let(::updateFromNetwork)
                handler.removeCallbacks(sampler)
                handler.post(sampler)
            }
            .onFailure {
                callback = null
            }
    }

    fun stop() {
        if (!registered) return
        registered = false
        handler.removeCallbacks(sampler)
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun updateFromNetwork(network: Network) {
        val capabilities = runCatching { connectivity?.getNetworkCapabilities(network) }.getOrNull() ?: return
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        handler.post {
            updateMetered(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }
    }

    private fun updateMetered(metered: Boolean) {
        if (activity.isMeteredNetwork == metered) return
        activity.isMeteredNetwork = metered
        activity.applyMeteredNetworkPolicy(metered)
        if (!metered) {
            warnedThisSession = false
            return
        }
        if (!warnedThisSession) {
            warnedThisSession = true
            activity.showTvToast(
                "Red móvil medida: consumo acumulado ${formatByteSize(currentUsageBytes())}. Calidad limitada a 2,2 Mbps.",
                long = true,
            )
        }
    }

    private fun sample() {
        val current = TrafficStats.getUidRxBytes(Process.myUid())
        if (current >= 0L && lastRxBytes >= 0L && activity.isMeteredNetwork) {
            val delta = (current - lastRxBytes).coerceAtLeast(0L)
            if (delta > 0L) {
                val preferences = activity.getSharedPreferences(MainActivity.PREFS, 0)
                preferences.edit {
                    putLong(MainActivity.KEY_METERED_RX_BYTES, currentUsageBytes() + delta)
                }
            }
        }
        if (current >= 0L) lastRxBytes = current
    }

    private fun currentUsageBytes(): Long =
        activity.getSharedPreferences(MainActivity.PREFS, 0)
            .getLong(MainActivity.KEY_METERED_RX_BYTES, 0L)
            .coerceAtLeast(0L)


    private companion object {
        const val SAMPLE_INTERVAL_MS = 15_000L
    }
}



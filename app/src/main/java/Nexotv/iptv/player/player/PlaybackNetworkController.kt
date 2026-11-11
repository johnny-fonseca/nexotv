package Nexotv.iptv.player.player
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.*
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Owns the process-wide network callback used by live playback.
 * Playback state changes remain in MainActivity for this incremental phase.
 */
internal class PlaybackNetworkController(
    context: Context,
    private val onNetworkLost: () -> Unit,
    private val onNetworkAvailable: (networkChanged: Boolean) -> Unit,
) {
    val connectivity: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var isRegistered = false
        private set

    private var validatedNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            val activeNetwork = connectivity.activeNetwork
            if (validatedNetwork == network) validatedNetwork = null
            if (activeNetwork == null || activeNetwork == network) onNetworkLost()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (connectivity.activeNetwork == network &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                val networkChanged = validatedNetwork != null && validatedNetwork != network
                validatedNetwork = network
                onNetworkAvailable(networkChanged)
            }
        }
    }

    fun register() {
        if (isRegistered) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            connectivity.registerDefaultNetworkCallback(callback)
        } else {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, callback)
        }
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } finally {
            isRegistered = false
            validatedNetwork = null
        }
    }
}

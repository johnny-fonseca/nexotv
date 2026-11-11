package Nexotv.iptv.player.network

import Nexotv.iptv.player.player.NetworkTuning
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Fotografía de la interfaz Wi-Fi activa.
 *
 * Los campos valen [UNKNOWN] (0) cuando el sistema no los informa: en Android 12+ parte de estos
 * datos puede llegar redactado según el permiso y el fabricante, y el HUD debe distinguir
 * "sin datos" de "dato malo".
 */
internal data class WifiLinkSnapshot(
    val frequencyMhz: Int = UNKNOWN,
    val rssiDbm: Int = UNKNOWN,
    val linkSpeedMbps: Int = UNKNOWN,
    val standard: Int = UNKNOWN,
) {
    /** Texto corto para el HUD; omite los campos desconocidos en lugar de inventarlos. */
    fun describe(): String {
        val parts = buildList {
            wifiBandLabel(frequencyMhz)?.let(::add)
            frequencyMhz.takeIf { it > 0 }?.let { add("$it MHz") }
            rssiDbm.takeIf(::isKnownRssi)?.let { add("$it dBm (${wifiRssiQuality(it)})") }
            linkSpeedMbps.takeIf { it > 0 }?.let { add("$it Mbps") }
            wifiStandardLabel(standard)?.let(::add)
        }
        return if (parts.isEmpty()) NO_DATA else parts.joinToString(" · ")
    }

    companion object {
        const val UNKNOWN = 0
        const val NO_DATA = "datos no disponibles"
    }
}

/**
 * Valores de `ScanResult.WIFI_STANDARD_*`.
 *
 * Se copian como constantes locales para que la lectura funcione en API 23 y para no depender de
 * constantes que llegaron con Android 11.
 */
private const val STANDARD_LEGACY = 1
private const val STANDARD_11N = 4
private const val STANDARD_11AC = 5
private const val STANDARD_11AX = 6
private const val STANDARD_11AD = 7
private const val STANDARD_11BE = 8

/** true si el valor de RSSI es una medición real y no un relleno del driver. */
internal fun isKnownRssi(dbm: Int): Boolean = dbm in NetworkTuning.WIFI_RSSI_VALID_RANGE

/** Banda del canal a partir de su frecuencia, o null si la frecuencia no es utilizable. */
internal fun wifiBandLabel(frequencyMhz: Int): String? = when (frequencyMhz) {
    in NetworkTuning.WIFI_2GHZ_RANGE -> "2,4 GHz"
    in NetworkTuning.WIFI_5GHZ_RANGE -> "5 GHz"
    in NetworkTuning.WIFI_6GHZ_RANGE -> "6 GHz"
    else -> null
}

/** Calidad de la señal en palabras: es lo que se lee a 3 m de distancia. */
internal fun wifiRssiQuality(dbm: Int): String = when {
    dbm >= NetworkTuning.WIFI_RSSI_GOOD_DBM -> "buena"
    dbm >= NetworkTuning.WIFI_RSSI_FAIR_DBM -> "regular"
    dbm >= NetworkTuning.WIFI_RSSI_WEAK_DBM -> "débil"
    else -> "muy débil"
}

/** Estándar Wi-Fi (Wi-Fi 4/5/6…), o null si el sistema no lo informa. */
internal fun wifiStandardLabel(standard: Int): String? = when (standard) {
    STANDARD_LEGACY -> "802.11a/b/g"
    STANDARD_11N -> "Wi-Fi 4 (11n)"
    STANDARD_11AC -> "Wi-Fi 5 (11ac)"
    STANDARD_11AX -> "Wi-Fi 6 (11ax)"
    STANDARD_11AD -> "WiGig (11ad)"
    STANDARD_11BE -> "Wi-Fi 7 (11be)"
    else -> null
}

/**
 * Lee banda, frecuencia, RSSI, velocidad de enlace y estándar de la red Wi-Fi activa.
 *
 * Requiere `ACCESS_WIFI_STATE` (permiso normal, declarado en el manifiesto) y `ACCESS_NETWORK_STATE`
 * (ya presente). No consulta SSID ni BSSID, así que no necesita permiso de ubicación.
 *
 * En Android 10+ se usa `NetworkCapabilities.transportInfo`, que es la vía recomendada desde
 * Android 12; `WifiManager.getConnectionInfo()` queda como respaldo para API 23–28 y para los
 * equipos que no rellenan `transportInfo`.
 */
internal object WifiLinkInfo {
    private const val TAG = "WifiLinkInfo"

    /** Devuelve la foto de la interfaz Wi-Fi activa, o null si la red activa no es Wi-Fi. */
    fun read(context: Context): WifiLinkSnapshot? = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.let { manager ->
            runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
        }
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            // Por cable (o sin red): no hay datos de Wi-Fi que mostrar.
            null
        } else {
            val info = wifiInfoFromCapabilities(capabilities) ?: wifiInfoFromManager(context)
            info?.let { snapshotOf(it, downstreamKbps(capabilities)) }
        }
    } catch (error: Exception) {
        Log.w(TAG, "No se pudo leer el estado del Wi-Fi", error)
        null
    }

    private fun wifiInfoFromCapabilities(capabilities: NetworkCapabilities): WifiInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { capabilities.transportInfo as? WifiInfo }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun wifiInfoFromManager(context: Context): WifiInfo? = try {
        (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
    } catch (error: Exception) {
        Log.w(TAG, "getConnectionInfo() no disponible", error)
        null
    }

    /** Velocidad de bajada que estima el sistema; solo se usa si el Wi-Fi no informa la de enlace. */
    private fun downstreamKbps(capabilities: NetworkCapabilities): Int = try {
        capabilities.linkDownstreamBandwidthKbps
    } catch (_: Exception) {
        0
    }

    @Suppress("DEPRECATION")
    private fun snapshotOf(info: WifiInfo, fallbackDownstreamKbps: Int): WifiLinkSnapshot {
        val linkSpeedMbps = info.linkSpeed.takeIf { it > 0 }
            ?: (fallbackDownstreamKbps / 1_000).takeIf { it > 0 }
            ?: WifiLinkSnapshot.UNKNOWN
        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { info.wifiStandard }.getOrDefault(WifiLinkSnapshot.UNKNOWN)
        } else {
            WifiLinkSnapshot.UNKNOWN
        }
        return WifiLinkSnapshot(
            frequencyMhz = info.frequency.coerceAtLeast(0),
            rssiDbm = info.rssi.takeIf(::isKnownRssi) ?: WifiLinkSnapshot.UNKNOWN,
            linkSpeedMbps = linkSpeedMbps,
            standard = standard,
        )
    }
}

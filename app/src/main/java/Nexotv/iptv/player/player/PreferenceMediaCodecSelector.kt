package Nexotv.iptv.player.player
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import android.util.Log
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Reordena (nunca elimina) los códecs para que el hardware siga siendo la opción segura por defecto. */
internal class PreferenceMediaCodecSelector(private val preferences: DecoderPreferences) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        )
        if (codecs.size < 2 || !prefiereSoftware(mimeType)) return codecs
        return codecs.sortedBy { it.hardwareAccelerated }
    }

    private fun prefiereSoftware(mimeType: String): Boolean =
        try {
            preferences.forMimeType(mimeType) == DecoderMode.SOFTWARE
        } catch (e: Exception) {
            // Ante un fallo al leer la preferencia se conserva el orden por defecto (hardware primero)
            Log.w(TAG, "No se pudo leer la preferencia de decodificador para $mimeType", e)
            false
        }

    private companion object {
        const val TAG = "CodecSelector"
    }
}
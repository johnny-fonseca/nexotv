@file:Suppress("PackageName")

// AudioOffset.kt
package Nexotv.iptv.player.player
import Nexotv.iptv.player.*
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.common.util.UnstableApi

internal class AudioOffsetHolder {

    @Volatile
    var offsetMs: Long = 0L
        set(value) {
            field = value.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        }

    internal companion object {
        const val MIN_OFFSET_MS: Long = -10_000L
        const val MAX_OFFSET_MS: Long = 10_000L
        const val MICROS_PER_MILLI: Long = 1_000L
    }
}

@UnstableApi
internal class AudioOffsetSink(
    sink: AudioSink,
    private val offset: AudioOffsetHolder
) : ForwardingAudioSink(sink) {

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position == CURRENT_POSITION_NOT_SET) return position

        val offsetUs = offset.offsetMs * AudioOffsetHolder.MICROS_PER_MILLI
        return safeAdd(position, offsetUs)
    }

    private fun safeAdd(position: Long, offsetUs: Long): Long {
        val result = position + offsetUs
        // Se detecta desbordamiento aritmético y se conserva la posición original
        val overflow = ((position xor result) and (offsetUs xor result)) < 0L
        return if (overflow) position else result
    }
}

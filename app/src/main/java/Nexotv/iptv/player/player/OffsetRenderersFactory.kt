// Nexotv/iptv/player/OffsetRenderersFactory.kt
package Nexotv.iptv.player.player
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

@UnstableApi
internal class OffsetRenderersFactory(
    context: Context,
    private val offset: AudioOffsetHolder,
    private val passthrough: Boolean,
    decoders: DecoderPreferences,
) : DefaultRenderersFactory(context) {

    init {
        setMediaCodecSelector(PreferenceMediaCodecSelector(decoders))
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val baseSink = createBaseSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            ?: return null
        return wrapWithOffset(baseSink)
    }

    private fun createBaseSink(
        context: Context,
        floatOutput: Boolean,
        playbackParams: Boolean,
    ): AudioSink? {
        if (!passthrough) return defaultSink(context, floatOutput, playbackParams)

        return try {
            buildPassthroughSink(context, floatOutput, playbackParams)
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al crear el sink passthrough; se usa el sink por defecto", e)
            defaultSink(context, floatOutput, playbackParams)
        }
    }

    private fun defaultSink(
        context: Context,
        floatOutput: Boolean,
        playbackParams: Boolean,
    ): AudioSink? = try {
        super.buildAudioSink(context, floatOutput, playbackParams)
    } catch (e: Exception) {
        Log.e(TAG, "Fallo al crear el sink de audio por defecto", e)
        null
    }

    private fun buildPassthroughSink(
        context: Context,
        floatOutput: Boolean,
        playbackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(floatOutput)
        .setEnableAudioOutputPlaybackParameters(playbackParams)
        .build()

    private fun wrapWithOffset(sink: AudioSink): AudioSink = try {
        AudioOffsetSink(sink, offset)
    } catch (e: Exception) {
        Log.e(TAG, "Fallo al aplicar el offset de audio; se devuelve el sink sin offset", e)
        sink
    }

    private companion object {
        const val TAG = "OffsetRenderersFactory"
    }
}

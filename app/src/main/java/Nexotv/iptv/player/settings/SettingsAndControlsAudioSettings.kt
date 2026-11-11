@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings

import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

internal const val AUDIO_OFFSET_LIMIT_MS = 500L

internal fun MainActivity.persistAudioOffset(offsetMs: Long) {
    audioOffset.offsetMs = offsetMs.coerceIn(-AUDIO_OFFSET_LIMIT_MS, AUDIO_OFFSET_LIMIT_MS)
    appPreferences().edit { putLong(MainActivity.KEY_AUDIO_OFFSET, audioOffset.offsetMs) }
}

// ---------- Sincronización de audio ----------

internal fun MainActivity.showAudioSyncPicker() {
    val offsets = (-300..300 step 50).toList()
    val labels = offsets.map {
        when {
            it == 0 -> "0 ms (sin desfase)"
            it > 0 -> "+$it ms"
            else -> "$it ms"
        }
    }
    val stored = appPreferences().safeLong(MainActivity.KEY_AUDIO_OFFSET, 0L)
    val selected = offsets.indices.minByOrNull { kotlin.math.abs(offsets[it] - stored) }
        ?: offsets.indexOf(0).coerceAtLeast(0)
    showStyledChoiceDialog(
        "Ajustar si el sonido va desfasado", "AUDIO",
        labels.map { it to "" }, selected,
    ) { index ->
        val offset = offsets.getOrNull(index)?.toLong() ?: return@showStyledChoiceDialog
        persistAudioOffset(offset)
        flashStatus("Desfase de audio: ${audioOffset.offsetMs} ms")
    }
}

@UnstableApi
internal fun MainActivity.restoreAudioOffset() {
    audioOffset.offsetMs = appPreferences()
        .safeLong(MainActivity.KEY_AUDIO_OFFSET, 0L)
        .coerceIn(-AUDIO_OFFSET_LIMIT_MS, AUDIO_OFFSET_LIMIT_MS)
}

internal fun MainActivity.adjustSync(deltaMs: Long) {
    persistAudioOffset(audioOffset.offsetMs + deltaMs)
    flashStatus("Desfase de audio: ${audioOffset.offsetMs} ms")
}

// ---------- Sonido, surround y silencio ----------

internal fun MainActivity.toggleSurroundPreference() {
    val enabled = flipBoolean(MainActivity.KEY_SURROUND, false)
    if (enabled) {
        manualAudioSelection = false
        onTracksChanged(player.currentTracks)
    } else if (surroundOverrideActive) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        surroundOverrideActive = false
    }
    flashStatus("Audio surround automático ${if (enabled) "activado" else "desactivado"}")
}

internal fun MainActivity.togglePassthrough() {
    val enabled = flipBoolean(MainActivity.KEY_PASSTHROUGH, false)
    rebuildPlayer("Audio passthrough ${if (enabled) "activado" else "desactivado"}")
}

internal fun MainActivity.toggleMute() {
    val muted = player.volume > 0f
    player.volume = if (muted) 0f else 1f
    appPreferences().edit { putBoolean(MainActivity.KEY_MUTED, muted) }
    flashStatus(if (muted) "Audio silenciado" else "Audio activado")
}

internal fun MainActivity.adjustVolume(delta: Int) {
    val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val direction = when {
        delta > 0 -> AudioManager.ADJUST_RAISE
        delta < 0 -> AudioManager.ADJUST_LOWER
        else -> AudioManager.ADJUST_SAME
    }
    runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
}


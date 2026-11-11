@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings


import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

private const val TRACK_INDEX_OFF = -1
private const val TRACK_INDEX_AUTO = -2

private data class TrackChoice(val label: String, val group: Tracks.Group?, val index: Int)

// ---------- Pistas de audio, video y subtítulos ----------

private fun trackTitle(type: Int, group: Tracks.Group, index: Int, position: Int): String {
    val format = group.getTrackFormat(index)
    return listOfNotNull(
        if (group.isTrackSelected(index)) "✓ Activa" else null,
        format.label?.takeIf(String::isNotBlank),
        format.language?.takeIf(String::isNotBlank),
        when {
            type == C.TRACK_TYPE_AUDIO && format.channelCount > 0 -> "${format.channelCount} canales"
            type == C.TRACK_TYPE_VIDEO && format.width > 0 -> "${format.width}×${format.height}"
            else -> null
        },
        if (type == C.TRACK_TYPE_VIDEO && format.bitrate > 0) "${format.bitrate / 1_000} kb/s" else null,
        format.sampleMimeType?.substringAfter('/')?.uppercase(),
    ).distinct().joinToString(" · ").ifBlank { "Pista $position" }
}

private fun MainActivity.applyTrackChoice(type: Int, choice: TrackChoice) {
    val prefs = appPreferences()
    val builder = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(type)
        .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && choice.index == TRACK_INDEX_OFF)
    if (choice.index >= 0) {
        val group = choice.group ?: return
        builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.index))
        if (type == C.TRACK_TYPE_VIDEO) builder.setMaxVideoBitrate(Int.MAX_VALUE)
    } else if (type == C.TRACK_TYPE_VIDEO) {
        builder.setMaxVideoBitrate(
            if (prefs.bufferProfile() == BufferSizes.PROFILE_HOTSPOT) BufferSizes.HOTSPOT_MAX_VIDEO_BITRATE
            else Int.MAX_VALUE,
        )
    }
    if (type == C.TRACK_TYPE_AUDIO) {
        manualAudioSelection = choice.index >= 0
        surroundOverrideActive = false
        val group = choice.group
        if (choice.index >= 0 && group != null) {
            prefs.edit {
                putString(
                    MainActivity.KEY_AUDIO_TRACK,
                    audioSignature(group.getTrackFormat(choice.index)),
                )
            }
        } else {
            prefs.edit { remove(MainActivity.KEY_AUDIO_TRACK) }
        }
    }
    player.trackSelectionParameters = builder.build()
    if (type == C.TRACK_TYPE_VIDEO) {
        flashStatus(
            if (choice.index >= 0) "Calidad seleccionada: ${choice.label}"
            else "Calidad de video automática",
        )
    }
}

internal fun MainActivity.showTrackPicker(type: Int) {
    val choices = mutableListOf<TrackChoice>()
    if (type == C.TRACK_TYPE_TEXT) choices += TrackChoice("Desactivados", null, TRACK_INDEX_OFF)

    val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val selectedAudio = audioGroups
        .flatMap { group ->
            (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) }
        }
        .firstOrNull()
    val savedAudio = appPreferences().safeString(MainActivity.KEY_AUDIO_TRACK, null)
    val savedAvailable = savedAudio == null || audioGroups.any { group ->
        (0 until group.length).any {
            group.isTrackSupported(it) && audioSignature(group.getTrackFormat(it)) == savedAudio
        }
    }
    val autoLabel = when {
        type != C.TRACK_TYPE_AUDIO -> "Automático"
        !savedAvailable -> "Automático · pista guardada no disponible"
        savedAudio != null -> "Automático"
        selectedAudio != null -> "✓ Automático · actual: ${audioFormatSummary(selectedAudio)}"
        else -> "✓ Automático"
    }
    choices += TrackChoice(autoLabel, null, TRACK_INDEX_AUTO)

    player.currentTracks.groups.filter { it.type == type }.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            choices += TrackChoice(trackTitle(type, group, index, choices.size), group, index)
        }
    }

    val minimumChoices = if (type == C.TRACK_TYPE_TEXT) 2 else 1
    if (choices.size <= minimumChoices) {
        showTvToast("Este stream no ofrece pistas alternativas")
        return
    }

    val title = when (type) {
        C.TRACK_TYPE_AUDIO -> "Pista de audio"
        C.TRACK_TYPE_VIDEO -> "Calidad de video"
        else -> "Subtítulos"
    }
    val markedIndex = choices.indexOfFirst { it.label.startsWith("✓") }
    val activeChoice = when {
        markedIndex >= 0 -> markedIndex
        type == C.TRACK_TYPE_TEXT && type in player.trackSelectionParameters.disabledTrackTypes ->
            choices.indexOfFirst { it.index == TRACK_INDEX_OFF }.coerceAtLeast(0)
        else -> choices.indexOfFirst { it.index == TRACK_INDEX_AUTO }.coerceAtLeast(0)
    }

    showStyledChoiceDialog(title, "REPRODUCCIÓN", choices.map { it.label to "" }, activeChoice) { selected ->
        val choice = choices.getOrNull(selected) ?: return@showStyledChoiceDialog
        applyTrackChoice(type, choice)
    }
    if (type == C.TRACK_TYPE_AUDIO && !savedAvailable) {
        flashStatus("La pista de audio guardada no está disponible; se usa la pista automática")
    }
}

internal fun audioFormatSummary(format: Format): String =
    listOfNotNull(
        format.language?.takeIf(String::isNotBlank),
        format.channelCount.takeIf { it > 0 }?.let { "$it ch" },
        format.sampleMimeType?.substringAfter('/'),
    ).joinToString(" · ")



// SurroundAudio.kt
package Nexotv.iptv.player.player
import Nexotv.iptv.player.*
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride

/** Elige una pista de audio compatible solo si tiene más canales que la seleccionada. */
internal object SurroundAudio {

    fun pick(tracks: Tracks): TrackSelectionOverride? = try {
        selectBest(tracks)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    private fun selectBest(tracks: Tracks): TrackSelectionOverride? {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return null

        val selectedChannels = audioGroups
            .flatMap { group -> indicesOf(group) { group.isTrackSelected(it) } }
            .maxOfOrNull { (group, index) -> channelsOf(group, index) }
            ?: 0

        val best = audioGroups
            .flatMap { group ->
                indicesOf(group) { group.isTrackSupported(it) }
                    .map { (g, index) -> Candidate(g, index, channelsOf(g, index)) }
            }
            .maxByOrNull { it.channels }
            ?: return null

        if (best.channels <= selectedChannels) return null

        return TrackSelectionOverride(best.group.mediaTrackGroup, best.index)
    }

    private inline fun indicesOf(
        group: Tracks.Group,
        predicate: (Int) -> Boolean
    ): List<Pair<Tracks.Group, Int>> =
        (0 until group.length).filter(predicate).map { group to it }

    // Un recuento desconocido (Format.NO_VALUE) se trata como 0 canales
    private fun channelsOf(group: Tracks.Group, index: Int): Int =
        group.getTrackFormat(index).channelCount.coerceAtLeast(0)

    private data class Candidate(
        val group: Tracks.Group,
        val index: Int,
        val channels: Int
    )
}
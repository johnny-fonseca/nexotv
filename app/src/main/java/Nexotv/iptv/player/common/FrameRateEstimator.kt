// File: Nexotv/iptv/player/common/FrameRateEstimator.kt
package Nexotv.iptv.player.common

import Nexotv.iptv.player.tv.*

/** Estimates frame rate from presentation timestamps when HLS omits it. */
internal class FrameRateEstimator(private val samples: Int = 12) {

    private val deltas = ArrayDeque<Long>(samples.coerceAtLeast(1) + 1)
    private var previousUs: Long? = null
    private var complete = false

    init {
        require(samples > 0) { "samples must be greater than 0, was $samples" }
    }

    @Suppress("unused")
    val isComplete: Boolean
        @Synchronized get() = complete

    @Synchronized
    fun onFrame(presentationTimeUs: Long): Float? {
        if (complete) return null

        val previous = previousUs
        previousUs = presentationTimeUs

        if (previous != null) {
            val delta = presentationTimeUs - previous
            // Un delta no positivo o desbordado (resta con overflow) invalida la ventana acumulada
            val overflowed = (presentationTimeUs > previous && delta <= 0L) ||
                    (presentationTimeUs < previous && delta >= 0L)
            if (delta <= 0L || overflowed) {
                deltas.clear()
            } else {
                deltas.addLast(delta)
                while (deltas.size > samples) deltas.removeFirst()
            }
        }

        if (deltas.size < samples) return null

        val medianUs = deltas.sorted()[deltas.size / 2]
        if (medianUs <= 0L) {
            deltas.clear()
            return null
        }

        val fps = 1_000_000f / medianUs.toFloat()
        if (!fps.isFinite() || fps <= 0f) {
            deltas.clear()
            return null
        }

        complete = true
        return fps
    }

    @Synchronized
    fun reset() {
        deltas.clear()
        previousUs = null
        complete = false
    }
}
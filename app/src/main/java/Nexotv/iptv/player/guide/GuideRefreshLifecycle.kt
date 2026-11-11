// File: Nexotv/iptv/player/guide/GuideRefreshLifecycle.kt
package Nexotv.iptv.player.guide

import java.util.concurrent.atomic.AtomicReference

/**
 * Controla la vigencia de trabajos de refresco de guía independientes de Android.
 * Invalidar el controlador hace que cualquier resultado ya iniciado sea obsoleto.
 *
 * Seguro para uso concurrente: generación e invalidación se mantienen en una única
 * instantánea inmutable que se actualiza de forma atómica (compare-and-set).
 */
internal class GuideRefreshLifecycle {

    private class State(
        val generation: Long,
        val invalidated: Boolean,
    )

    private val state = AtomicReference(State(generation = 0L, invalidated = false))

    internal val generation: Long
        get() = state.get().generation

    internal fun begin(): Long {
        while (true) {
            val snapshot = state.get()
            check(!snapshot.invalidated) {
                "No se puede iniciar un refresco después de invalidar su ciclo de vida"
            }
            check(snapshot.generation < Long.MAX_VALUE) {
                "Se agotó el espacio de generaciones del ciclo de vida del refresco"
            }
            val next = State(generation = snapshot.generation + 1L, invalidated = false)
            if (state.compareAndSet(snapshot, next)) {
                return next.generation
            }
        }
    }

    internal fun invalidate() {
        while (true) {
            val snapshot = state.get()
            val nextGeneration =
                if (snapshot.generation == Long.MAX_VALUE) snapshot.generation else snapshot.generation + 1L
            if (snapshot.invalidated && nextGeneration == snapshot.generation) {
                return
            }
            val next = State(generation = nextGeneration, invalidated = true)
            if (state.compareAndSet(snapshot, next)) {
                return
            }
        }
    }

    internal fun isCurrent(requestGeneration: Long): Boolean {
        val snapshot = state.get()
        return !snapshot.invalidated && requestGeneration == snapshot.generation
    }
}
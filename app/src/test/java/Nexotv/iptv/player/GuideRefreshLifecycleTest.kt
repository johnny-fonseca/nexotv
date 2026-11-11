package Nexotv.iptv.player



import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.guide.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideRefreshLifecycleTest {
    @Test
    fun destroyingActivityInvalidatesRefreshStartedDuringDownload() {
        val lifecycle = GuideRefreshLifecycle()
        val request = lifecycle.begin()

        lifecycle.invalidate()

        assertFalse(lifecycle.isCurrent(request))
    }

    @Test
    fun lateSuccessAndFailureFromDestroyedActivityAreBothStale() {
        val lifecycle = GuideRefreshLifecycle()
        val request = lifecycle.begin()

        lifecycle.invalidate()

        // Ambos callbacks consultan el mismo contrato antes de tocar estado o UI.
        assertFalse(lifecycle.isCurrent(request))
        assertFalse(lifecycle.isCurrent(request))
    }

    @Test
    fun newerRefreshMakesOlderResponseStale() {
        val lifecycle = GuideRefreshLifecycle()
        val firstRequest = lifecycle.begin()
        val secondRequest = lifecycle.begin()

        assertFalse(lifecycle.isCurrent(firstRequest))
        assertTrue(lifecycle.isCurrent(secondRequest))
        assertNotEquals(firstRequest, secondRequest)
    }

    @Test
    fun invalidationIsIdempotentAndPreventsFutureRefreshes() {
        val lifecycle = GuideRefreshLifecycle()
        lifecycle.begin()

        lifecycle.invalidate()
        lifecycle.invalidate()

        assertFalse(lifecycle.isCurrent(0L))
        try {
            lifecycle.begin()
            throw AssertionError("begin() debía rechazar un ciclo de vida invalidado")
        } catch (_: IllegalStateException) {
            // Estado esperado: una Activity destruida no puede iniciar otro refresco.
        }
    }
}


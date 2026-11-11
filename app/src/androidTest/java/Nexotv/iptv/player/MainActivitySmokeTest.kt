package Nexotv.iptv.player


import Nexotv.iptv.player.guide.*
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun playerScreenRendersWithoutOpeningTheLiveStream() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("debug_player_status", true)
            putExtra("debug_player_status_kind", "offline")
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertNotNull(content)
                assertTrue(content.childCount > 0)
                assertTrue(activity.statusHost.childCount >= 4)
                assertTrue(activity.statusCardIsActionable)
            }
        }
    }

    @Test
    fun errorCardFocusStartsOnRetryAndMovesToSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("debug_player_status", true)
            putExtra("debug_player_status_kind", "offline")
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("Reintentar", activity.window.decorView.findFocus()?.let(::buttonText))
            }

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("Ajustes", activity.window.decorView.findFocus()?.let(::buttonText))
            }
        }
    }

    @Test
    fun destroyingActivityCancelsPendingGuideRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var activity: MainActivity? = null

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            putExtra("debug_player_status", true)
            putExtra("debug_player_status_kind", "offline")
        }).use { scenario ->
            scenario.onActivity { current ->
                activity = current
                current.refreshGuide(silent = true, deferWhilePlaying = false)
                assertNotNull(current.guideRefreshTask)
                assertTrue(current.guideLoading)
            }
        }

        val destroyedActivity = requireNotNull(activity)
        assertTrue(destroyedActivity.guideRefreshExecutor.isShutdown)
        assertNull(destroyedActivity.guideRefreshTask)
        assertFalse(destroyedActivity.guideLoading)
    }

    private fun buttonText(view: View): String? = (view as? Button)?.text?.toString()
}


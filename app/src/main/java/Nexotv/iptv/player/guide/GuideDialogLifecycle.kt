package Nexotv.iptv.player.guide
import Nexotv.iptv.player.activity.*



import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.menu.showMainMenu
import Nexotv.iptv.player.activity.setTvDialogBounds
import Nexotv.iptv.player.activity.consumeMenuOrigin
import Nexotv.iptv.player.tv.animateMenuSurface
import Nexotv.iptv.player.activity.applyWindowBlur
import Nexotv.iptv.player.tv.focusFirstWhenReady

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.util.UnstableApi

private const val GUIDE_DIALOG_TICK_MS = 1_000L
private const val GUIDE_DIALOG_ERROR_TAG = "GUIDE_ERROR"
private const val GUIDE_CLOCK_PATTERN = "h:mm a EEE d MMM"
private const val GUIDE_CLOCK_LANGUAGE_TAG = "es-CR"
private const val GUIDE_CLOCK_TIME_ZONE = "GMT-06:00"

@UnstableApi
internal class GuideDialogLifecycle(
    private val activity: MainActivity,
    private val rootView: View,
    private val clockText: TextView,
    private val programsContainer: ViewGroup,
    private val fallbackFocus: View,
    private val preferredFocus: () -> View?,
    private val onTick: (Long) -> Unit,
    private val onShown: (AlertDialog) -> Unit,
    private val onDismissed: () -> Unit,
    private val onRedRecord: () -> Unit,
) {
    private fun isActivityGone(): Boolean = activity.isFinishing || activity.isDestroyed

    private fun logFailure(operation: String, error: Throwable) {
        DiagnosticTracker.log(GUIDE_DIALOG_ERROR_TAG, "dialogLifecycle.$operation: ${error.javaClass.simpleName}")
    }

    fun showIfAlive(): AlertDialog? {
        if (isActivityGone()) return null
        return try {
            buildAndShow()
        } catch (e: Exception) {
            logFailure("show", e)
            null
        }
    }

    private fun buildAndShow(): AlertDialog {
        (rootView.parent as? ViewGroup)?.removeView(rootView)
        val dialog = AlertDialog.Builder(activity).setView(rootView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        runCatching { activity.setTvDialogBounds(dialog, 1f, 1f, Gravity.CENTER) }
            .onFailure { logFailure("bounds", it) }
        dialog.setOnCancelListener {
            try {
                if (!activity.popScreenBackStack()) {
                    activity.consumeMenuOrigin { origin -> activity.showMainMenu(origin) {} }
                }
            } catch (e: Exception) {
                logFailure("cancel", e)
            }
        }
        val ticker = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || isActivityGone()) return
                val now = System.currentTimeMillis()
                try {
                    clockText.text = activity.formatGuideClock(now)
                    onTick(now)
                } catch (e: Exception) {
                    logFailure("tick", e)
                }
                if (dialog.isShowing && !isActivityGone()) {
                    activity.handler.postDelayed(this, GUIDE_DIALOG_TICK_MS)
                }
            }
        }
        dialog.setOnShowListener {
            try {
                dialog.window?.let { activity.applyWindowBlur(it, activity.dp(22)) }
            } catch (e: Exception) {
                logFailure("blur", e)
            }
            runCatching { activity.animateMenuSurface(rootView) }
                .onFailure { logFailure("animate", it) }
            activity.handler.removeCallbacks(ticker)
            ticker.run()
            runCatching {
                focusFirstWhenReady(programsContainer, fallback = fallbackFocus, preferred = preferredFocus())
            }.onFailure { logFailure("focus", it) }
            try {
                onShown(dialog)
            } catch (e: Exception) {
                logFailure("onShown", e)
            }
        }
        dialog.setOnDismissListener {
            activity.handler.removeCallbacks(ticker)
            try {
                onDismissed()
            } catch (e: Exception) {
                logFailure("onDismissed", e)
            }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event != null &&
                event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_PROG_RED || keyCode == KeyEvent.KEYCODE_MEDIA_RECORD)
            ) {
                try {
                    onRedRecord()
                } catch (e: Exception) {
                    logFailure("redRecord", e)
                }
                true
            } else false
        }
        dialog.show()
        return dialog
    }
}

@UnstableApi
private fun MainActivity.formatGuideClock(nowMs: Long): String = try {
    java.text.SimpleDateFormat(GUIDE_CLOCK_PATTERN, java.util.Locale.forLanguageTag(GUIDE_CLOCK_LANGUAGE_TAG))
        .apply { timeZone = java.util.TimeZone.getTimeZone(GUIDE_CLOCK_TIME_ZONE) }
        .format(java.util.Date(nowMs))
} catch (_: Exception) {
    ""
}

private fun MainActivity.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt().coerceAtLeast(0)
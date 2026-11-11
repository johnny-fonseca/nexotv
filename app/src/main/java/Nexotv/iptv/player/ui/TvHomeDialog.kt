@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvHomeDialog.kt
package Nexotv.iptv.player.ui

import Nexotv.iptv.player.R
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Full-screen dialog that hosts [TvHomeScreen]. It is opaque, has no window animation and
 * owns the Back policy: an open layer -> visible hint -> exit confirmation. No focusable
 * control is covered by an invisible layer.
 */
internal class TvHomeDialog(
    context: Context,
    private val stateProvider: () -> TvUiState,
    private val actions: TvHomeActions,
    private val initialDestination: TvHomeDestination?,
    private val confirmExit: Boolean,
    private val onExit: () -> Unit,
    private val onClosed: () -> Unit,
    private val onStopped: () -> Unit,
) : Dialog(context, R.style.TvUiHomeDialogTheme), TvKeyHandler {

    private val handler = Handler(Looper.getMainLooper())
    private val backStack = TvBackStack()
    private val exitWindowMs = TvTokens.integer(context, R.integer.tv_ui_exit_window_ms).toLong()
    private val tickMs = TvTokens.integer(context, R.integer.tv_ui_home_tick_ms).toLong()

    private lateinit var screen: TvHomeScreen
    private lateinit var router: TvKeyRouter
    private lateinit var exitGuard: TvExitGuard
    private var closed = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!isShowing) return
            refresh()
            handler.postDelayed(this, tickMs)
        }
    }

    private val hideHint = Runnable {
        if (::screen.isInitialized) screen.hideExitHint()
        if (::exitGuard.isInitialized) exitGuard.disarm()
    }

    init {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.let { window ->
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.setDimAmount(0f)
        }
        hideSystemBars()

        screen = TvHomeScreen(context, actions, initialDestination)
        setContentView(screen)
        router = TvKeyRouter(
            this,
            TvTokens.integer(context, R.integer.tv_ui_long_ok_ms).toLong(),
        )
        exitGuard = TvExitGuard(
            windowMs = exitWindowMs,
            onHint = {
                screen.showExitHint()
                scheduleHideHint()
            },
        )
        refresh()
    }

    override fun onStart() {
        super.onStart()
        window?.let { window ->
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        hideSystemBars()
        if (::screen.isInitialized) {
            screen.attachFocus()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
    }

    fun refresh() {
        if (::screen.isInitialized) screen.render(stateProvider())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && ::screen.isInitialized && screen.isExitHintVisible()) {
            // The hint is informational: the triggering D-pad/OK event continues normally.
            screen.hideExitHint()
            handler.removeCallbacks(hideHint)
            if (::exitGuard.isInitialized) exitGuard.disarm()
        }
        if (::router.isInitialized && router.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (backStack.onBack()) return
        if (TvExitGuard.shouldExit(confirmExit, exitGuard)) {
            exitApp()
        }
    }

    private fun scheduleHideHint() {
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, exitWindowMs)
    }

    private fun exitApp() {
        if (closed) return
        handler.removeCallbacks(hideHint)
        onExit()
        dismiss()
    }

    override fun dismiss() {
        if (::screen.isInitialized) screen.saveScrollState()
        super.dismiss()
        notifyClosed()
    }

    private fun notifyClosed() {
        if (closed) return
        closed = true
        onClosed()
    }

    private fun hideSystemBars() {
        window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hideHint)
        if (::router.isInitialized) router.release()
        if (::screen.isInitialized) {
            screen.saveScrollState()
            screen.detachFocus()
        }
        onStopped()
        super.onStop()
    }

    // TvKeyHandler: directions and OK stay native so the focused control activates itself.
    override fun onTvKey(key: TvKey, event: KeyEvent): Boolean = when (key) {
        TvKey.PLAY_PAUSE, TvKey.PLAY, TvKey.PAUSE -> {
            if (event.action == KeyEvent.ACTION_DOWN) actions.onTogglePlayback()
            true
        }
        // R1: one configured stream. CHANNEL_UP/DOWN are silent no-ops, no banner or toast.
        TvKey.CHANNEL_UP, TvKey.CHANNEL_DOWN -> true
        TvKey.MENU -> true
        else -> false
    }

    override fun ownsOk(): Boolean = false
}

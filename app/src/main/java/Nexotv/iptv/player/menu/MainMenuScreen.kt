// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreen.kt
// Punto de entrada del menú principal: diálogo, ticker de refresco y ciclo de vida.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu

import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.guide.*
import android.app.AlertDialog
import android.app.ActivityManager
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import Nexotv.iptv.player.MainActivity.*
import Nexotv.iptv.player.activity.PermissionFlows

// Tiempos y formatos
private const val MENU_SECOND_TICK_MS = 1000L
private const val MENU_MINUTE_MS = 60_000L
private const val MENU_MIN_TICK_MS = 250L
private const val MENU_FULL_REFRESH_INTERVAL_MS = 30_000L
private const val MENU_RECORDING_STARTED_KEY = "recording_started_at"
private const val MENU_DEFAULT_SECONDS_PER_MINUTE = 60
private const val MENU_DEFAULT_MINUTES_PER_HOUR = 60
private const val MENU_DEFAULT_SECONDS_PER_HOUR = 3600

internal fun MainActivity.showMainMenu(focusDestination: MenuDestination? = null, moreAction: () -> Unit) {
    if (isFinishing || isDestroyed) return
    // P1: single entry point and switch. While home_v2_enabled is false the legacy menu runs.
    if (TvHomeEntry.isEnabled(this)) {
        TvHomeEntry.show(this, focusDestination)
        return
    }
    val uiBuildStartedAt = android.os.SystemClock.elapsedRealtime()
    menuReturnFocus = currentFocus
    val state = MenuScreenState()
    val formats = MenuScreenFormats(this)
    val menuScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    var dialogRef: AlertDialog? = null
    lateinit var cleanupMenu: () -> Unit

    fun dismissMenu() {
        try {
            dialogRef?.dismiss()
        } catch (e: Exception) {
            menuScreenReport("cerrarMenu", e)
        }
    }

    fun dismissForDestination(block: () -> Unit) {
        state.launchingDestination = true
        try {
            block()
        } catch (e: Exception) {
            Log.e(MENU_SCREEN_TAG, "No se pudo abrir el destino del menú", e)
            state.launchingDestination = false
            showTvToast(getString(R.string.tv_menu_open_failed), long = true)
            return
        }
        cleanupMenu()
        dismissMenu()
    }

    fun launchDestination(block: () -> Unit) {
        dismissForDestination(block)
    }

    val rowFactory = MainMenuDestinationRowFactory(this) { action -> launchDestination { action() } }

    val rawLive = rowFactory.createTab(getString(R.string.tv_menu_live), R.drawable.tv_menu_live) {
        clearScreenBackStack()
        menuScreenToggleLivePlayback()
    }
    val rawGuide = rowFactory.createTab(
        getString(R.string.tv_menu_guide), R.drawable.tv_menu_guide
    ) {
        menuScreenOpenFrom(MenuDestination.GUIDE, moreAction) { showGuide() }
    }
    val rawSearch = rowFactory.createTab(
        getString(R.string.tv_menu_search), R.drawable.tv_menu_search
    ) {
        menuScreenOpenFrom(MenuDestination.SEARCH, moreAction) { showGuideSearch() }
    }
    val rawRecordings = rowFactory.createTab(
        getString(R.string.tv_menu_recordings), R.drawable.tv_menu_recordings
    ) {
        menuScreenOpenFrom(MenuDestination.RECORDINGS, moreAction) { showRecordingLibrary() }
    }
    val rawMore = rowFactory.createTab(
        getString(R.string.tv_menu_more), R.drawable.tv_menu_more
    ) {
        menuScreenOpenFrom(MenuDestination.MORE, moreAction) { moreAction() }
    }
    val liveRow = MenuScreenRow(rawLive.view, rawLive.subtitle)
    val guideRow = MenuScreenRow(rawGuide.view, rawGuide.subtitle)
    val searchRow = MenuScreenRow(rawSearch.view, rawSearch.subtitle)
    val recordingsRow = MenuScreenRow(rawRecordings.view, rawRecordings.subtitle)
    val moreRow = MenuScreenRow(rawMore.view, rawMore.subtitle)

    // Navegación vertical con botones de tamaño estable para el control remoto.
    val navigation = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP
        clipChildren = false
        clipToPadding = false
        listOf(liveRow.view, guideRow.view, searchRow.view, recordingsRow.view, moreRow.view).forEach { tab ->
            addView(tab, LinearLayout.LayoutParams(MENU_MATCH, dp(58)))
        }
    }
    val topBar = menuScreenBuildTopBar(navigation) {
        menuScreenGuard("actualizarGuia") { refreshGuide(silent = false) }
    }
    val hero = menuScreenBuildHero {
        dismissMenu()
        menuScreenGuard("reproduccion") { menuScreenToggleLivePlayback() }
    }

    val btnActions = menuScreenActionButton(getString(R.string.tv_menu_action_actions), Color.WHITE, cyanAccent = false)
    val btnRemind = menuScreenActionButton(getString(R.string.tv_menu_action_remind), getColor(R.color.tv_turquoise), cyanAccent = true)
    val btnNextGuide = menuScreenActionButton(getString(R.string.tv_menu_action_guide), Color.WHITE, cyanAccent = false)
    val btnNextSchedule = menuScreenActionButton(getString(R.string.tv_menu_action_schedule), Color.WHITE, cyanAccent = false)
    val btnRecordings = menuScreenActionButton(getString(R.string.tv_menu_action_recordings), Color.WHITE, cyanAccent = false)
    val btnRecordNow = menuScreenActionButton(getString(R.string.tv_menu_action_record), Color.WHITE, cyanAccent = false)

    val nextCard = menuScreenBuildInfoCard(
        R.drawable.tv_icon_reminder, MenuScreenPalette.reminderAccent, getString(R.string.tv_menu_card_next_header), btnNextGuide, btnNextSchedule
    )
    val recCard = menuScreenBuildInfoCard(
        R.drawable.tv_icon_database, MenuScreenPalette.reminderAccent, getString(R.string.tv_menu_card_recording_header), btnRecordings, btnRecordNow
    )
    val liveDetails = menuScreenBuildLiveDetails(btnActions, btnRemind)
    val content = menuScreenBuildContent(hero, liveDetails, nextCard, recCard)

    val root = menuScreenBuildRoot(topBar.root, content)
    val ui = MenuScreenViews(
        root, topBar, liveRow, guideRow, searchRow, recordingsRow, moreRow,
        hero, nextCard, recCard, btnActions, btnRemind, btnRecordings, btnRecordNow,
        btnNextGuide, btnNextSchedule, liveDetails.title, liveDetails.subtitle
    )

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    menuScreenWireActions(
        ui = ui,
        state = state,
        moreAction = moreAction,
        launch = { block -> launchDestination(block) },
        refresh = { refresh() }
    )
    menuScreenWireFocus(ui)

    // Reubica la misma vista del reproductor en el hero para mostrar la señal real del canal.
    val playerHost = MenuScreenPlayerHost(this, hero.card, hero.studioImage)
    playerHost.attach()

    val dialog = AlertDialog.Builder(this).setView(root).create()
    dialogRef = dialog
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    setTvDialogBounds(dialog, 1f, 1f, Gravity.CENTER)

    val previousRefreshVisibleGuide = refreshVisibleGuide
    val previousRefreshVisibleMainMenu = refreshVisibleMainMenu
    refreshVisibleGuide = {
        runOnUiThread {
            if (dialog.isShowing && !isFinishing && !isDestroyed) {
                menuScreenGuard("refrescoGuiaVisible") { refresh() }
            }
        }
    }
    refreshVisibleMainMenu = {
        if (dialog.isShowing && !isFinishing && !isDestroyed) {
            menuScreenGuard("refrescoEventoReproductor") { refresh() }
        }
    }

    lateinit var ticker: Runnable

    fun tickerDelayMs(now: Long): Long {
        val general = state.lastGeneralSnapshot
        val recordingStartedAt = state.lastMenuSnapshot?.recordingStartedAt ?: 0L
        // The elapsed label and the hero progress are presented at one-second resolution.
        val secondResolution = recordingStartedAt > 0L || general?.airing != null
        val alignedMinuteDelay = (MENU_MINUTE_MS - now % MENU_MINUTE_MS).coerceAtLeast(MENU_MIN_TICK_MS)
        val baseDelay = if (secondResolution) MENU_SECOND_TICK_MS else alignedMinuteDelay
        if (state.fullRefreshInFlight) return baseDelay

        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val refreshDelay = if (state.lastFullRefreshAt > 0L) {
            (MENU_FULL_REFRESH_INTERVAL_MS - (nowElapsed - state.lastFullRefreshAt))
                .coerceAtLeast(1L)
        } else Long.MAX_VALUE
        val boundaryDelay = general?.nextBoundaryMs?.let { boundary ->
            (boundary - now).coerceAtLeast(1L)
        } ?: Long.MAX_VALUE
        return minOf(baseDelay, refreshDelay, boundaryDelay)
    }

    fun scheduleTicker() {
        handler.removeCallbacks(ticker)
        if (dialog.isShowing && !isFinishing && !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            handler.postDelayed(ticker, tickerDelayMs(System.currentTimeMillis()))
        }
    }

    ticker = object : Runnable {
        override fun run() {
            if (!dialog.isShowing || isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                handler.removeCallbacks(this)
                return
            }

            val now = System.currentTimeMillis()
            menuScreenGuard("relojMenu") { menuScreenApplyClock(ui, formats, state, now) }
            val boundaryReached = state.lastGeneralSnapshot?.nextBoundaryMs?.let { now >= it } == true
            val refreshDue = state.lastFullRefreshAt > 0L &&
                android.os.SystemClock.elapsedRealtime() - state.lastFullRefreshAt >= MENU_FULL_REFRESH_INTERVAL_MS
            if (!state.fullRefreshInFlight && (boundaryReached || refreshDue)) refresh()
            scheduleTicker()
        }
    }

    lateinit var menuLifecycleObserver: LifecycleEventObserver
    menuLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> handler.removeCallbacks(ticker)
            Lifecycle.Event.ON_START -> if (dialog.isShowing && !isFinishing && !isDestroyed) {
                refresh()
                scheduleTicker()
            }
            Lifecycle.Event.ON_RESUME -> if (state.awaitingExactAlarmSettingsReturn) {
                state.awaitingExactAlarmSettingsReturn = false
                menuScreenGuard("resultadoPermisoAlarmas") {
                    showTvToast(getString(
                        if (PermissionFlows.canScheduleExactAlarms(this)) {
                            R.string.tv_menu_exact_alarm_granted
                        } else {
                            R.string.tv_menu_exact_alarm_denied
                        }
                    ), long = true)
                }
            }
            Lifecycle.Event.ON_DESTROY -> cleanupMenu()
            else -> Unit
        }
    }
    lifecycle.addObserver(menuLifecycleObserver)

    var menuCleaned = false
    cleanupMenu = {
        if (!menuCleaned) {
            menuCleaned = true
            playerHost.restore()
            refreshVisibleGuide = previousRefreshVisibleGuide
            refreshVisibleMainMenu = previousRefreshVisibleMainMenu
            handler.removeCallbacks(ticker)
            lifecycle.removeObserver(menuLifecycleObserver)
            refreshRequests.close()
            menuScope.cancel()
        }
    }

    dialog.setOnShowListener {
        menuScreenGuard("relojInicial") {
            menuScreenApplyClock(ui, formats, state, System.currentTimeMillis())
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.let { window ->
            val supportsWindowBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)?.isCrossWindowBlurEnabled == true &&
                (getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == false
            if (supportsWindowBlur) {
                menuScreenGuard("desenfoque") { applyWindowBlur(window, dp(22)) }
            }
        }
        menuScreenGuard("animacionMenu") { animateMenuSurface(root) }
        hero.studioImage.post {
            if (dialog.isShowing && hero.studioImage.isAttachedToWindow) {
                animateTvFocusShine(hero.studioImage)
            }
        }

        val initialFocus = when (focusDestination) {
            MenuDestination.GUIDE -> guideRow.view
            MenuDestination.SEARCH -> searchRow.view
            MenuDestination.RECORDINGS -> recordingsRow.view
            MenuDestination.MORE -> moreRow.view
            null -> liveRow.view
        }
        initialFocus.post {
            if (initialFocus.isAttachedToWindow && dialog.isShowing) initialFocus.requestFocus()
        }
    }

    dialog.setOnDismissListener {
        cleanupMenu()
        // This view reference is only for returning focus after a true close; navigation
        // back to the menu is restored from the MenuDestination argument above.
        val restore = menuReturnFocus
        menuReturnFocus = null
        if (!state.launchingDestination) {
            menuOrigin = null
            clearScreenBackStack()
            menuScreenGuard("restaurarFoco") {
                if (restore?.isAttachedToWindow == true && restore.isFocusable) {
                    restore.requestFocus()
                } else {
                    statusHost.requestFocus()
                }
            }
        }
    }

    menuScope.launch {
        for (ignored in refreshRequests) {
            try {
                if (!dialog.isShowing || isFinishing || isDestroyed ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) continue
                state.fullRefreshInFlight = true
                val generalSnapshot = menuScreenSnapshot(state)
                val recordingSnapshot = withContext(Dispatchers.IO) {
                    menuScreenReadSnapshot(generalSnapshot.now, generalSnapshot.next)
                }
                if (!dialog.isShowing || isFinishing || isDestroyed ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) continue
                val applyStartedAt = android.os.SystemClock.elapsedRealtime()
                menuScreenRefresh(ui, state, formats, generalSnapshot, recordingSnapshot)
                menuScreenValidateFocusTargetsIfDebug(this@showMainMenu, ui)
                state.lastFullRefreshAt = android.os.SystemClock.elapsedRealtime()
                UiPerformanceProfiler.recordUpdate("main_menu", root, applyStartedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                menuScreenReport("refrescoMenu", e)
            } finally {
                state.fullRefreshInFlight = false
                scheduleTicker()
            }
        }
    }

    try {
        menuScreenApplyClock(ui, formats, state, System.currentTimeMillis())
        dialog.show()
        UiPerformanceProfiler.recordBuild("main_menu", root, uiBuildStartedAt)
        // Present the menu before requesting its initial snapshot and preparing the optional guide.
        // Playback/connectivity stay on Main; schedule and recording data are read by the worker.
        dialog.window?.decorView?.postOnAnimation {
            if (dialog.isShowing && !isFinishing && !isDestroyed) {
                menuScreenGuard("refrescoInicialDiferido") { refresh() }
                menuScreenGuard("cargarGuiaDiferido") { menuScreenEnsureGuideLoaded() }
                menuScreenGuard("refrescoTrasCargarGuia") { refresh() }
            }
        }
    } catch (e: Exception) {
        Log.e(MENU_SCREEN_TAG, "No se pudo mostrar el menú principal", e)
        cleanupMenu()
        menuReturnFocus = null
        return
    }
    handler.removeCallbacks(ticker)
    scheduleTicker()
}

internal fun MainActivity.recordingElapsed(): String? {
    val elapsedMs = recordingElapsedMs() ?: return null
    val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs)
    return MenuScreenFormats(this).formatRecordingElapsed(elapsedMs)
}

internal fun MainActivity.recordingElapsedMs(): Long? {
    val startedAt = menuScreenGuardOr("inicioGrabacion", 0L) {
        menuScreenPrefs().getLong(MENU_RECORDING_STARTED_KEY, 0L)
    }
    if (startedAt <= 0L) return null
    return (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
}



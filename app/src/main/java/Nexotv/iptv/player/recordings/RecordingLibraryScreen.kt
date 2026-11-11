@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
// ============================================================================
// Archivo: RecordingLibraryScreen.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.diagnostics.*
import android.app.AlertDialog
import android.content.Context.*
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import java.util.Date
import Nexotv.iptv.player.MainActivity.*

/**
 * Pantalla "Mis grabaciones" para Android TV.
 * Resume la última grabación y da acceso a la biblioteca completa,
 * la agenda programada y el destino de almacenamiento.
 */
internal fun MainActivity.showRecordingLibrary() {
    val uiBuildStartedAt = android.os.SystemClock.elapsedRealtime()
    lateinit var dialog: AlertDialog
    lateinit var refreshViews: () -> Unit
    val state = RecordingLibraryState()

    val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)

    val recordingData = RecordingLibraryDataSource(
        activity = this,
        directory = recordingDirectory(),
        treeUri = prefs.getString(MainActivity.KEY_RECORDING_TREE, null),
        guidePrograms = guidePrograms,
        scheduledPrograms = { ProgramSchedule.all(this@showRecordingLibrary) },
    )

    fun reloadData() {
        state.recordings = safeCall("cargar grabaciones", emptyList()) { recordingData.loadEntries() }
        state.scheduled = safeCall("cargar agenda", emptyList()) { recordingData.loadScheduled() }
    }
    reloadData()

    fun openLibraryBrowser() {
        safeCall("abrir biblioteca", Unit) {
            showRecordingLibraryBrowser {
                reloadData()
                refreshViews()
            }
        }
    }

    fun navigateBackToMenu() {
        safeCall("volver al menú", Unit) {
            if (!popScreenBackStack()) {
                consumeMenuOrigin { origin ->
                    showMainMenu(origin) {}
                }
            }
        }
    }

    fun dismissDialogSafely() {
        safeCall("cerrar diálogo", Unit) {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    fun playLatest(fromStart: Boolean) {
        val entry = state.latestRecording ?: return
        dismissDialogSafely()
        if (fromStart) {
            safeCall("resetear posición", Unit) {
                entry.parts.firstOrNull()?.let {
                    HomeScreenChannel.saveResumePosition(this@showRecordingLibrary, it.name, 0L)
                }
            }
        }
        safeCall("reproducir grabación", Unit) { playRecording(entry) }
    }

    val formats = createRecordingLibraryDateFormats(prefs)

    // Secciones 1 a 6: construcción de vistas
    val topBar = createRecordingLibraryTopBar(
        onOpenLibrary = { openLibraryBrowser() },
    )
    val leftPanel = createRecordingLibraryLeftPanel(
        state = state,
        onOpenLibrary = { openLibraryBrowser() },
        refresh = { refreshViews() },
    )
    val hero = createRecordingLibraryHeroCard(
        onPlay = { playLatest(fromStart = false) },
    )
    val lowerCards = createRecordingLibraryLowerCards(
        state = state,
        onPlayLatest = { fromStart -> playLatest(fromStart) },
        onOpenLibrary = { openLibraryBrowser() },
        refresh = { refreshViews() },
    )
    val deleteBanner = createRecordingLibraryDeleteBanner(
        prefs = prefs,
        state = state,
        reloadData = { reloadData() },
        refresh = { refreshViews() },
    )
    val rootSurface = createRecordingLibraryRoot(topBar, leftPanel, hero, lowerCards, deleteBanner)

    // Sección 7: actualización y enlace de datos
    val ui = RecordingLibraryScreenContext(
        prefs = prefs,
        state = state,
        recordingData = recordingData,
        formats = formats,
        topBar = topBar,
        leftPanel = leftPanel,
        hero = hero,
        lowerCards = lowerCards,
        deleteBanner = deleteBanner,
        reloadData = { reloadData() },
        refreshViews = { refreshViews() },
    )
    refreshViews = {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        refreshRecordingLibraryViews(ui)
        UiPerformanceProfiler.recordUpdate("recordings", rootSurface, startedAt)
    }

    // Sección 8: navegación con D-pad
    wireRecordingLibraryFocus(topBar, leftPanel, hero, lowerCards, deleteBanner)

    // =========================================================================
    // 9. DIÁLOGO, CICLO DE VIDA Y RELOJ
    // =========================================================================
    dialog = AlertDialog.Builder(this).setView(rootSurface).create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    setTvDialogBounds(dialog, 1f, 1f, Gravity.CENTER)

    dialog.setOnKeyListener { _, key, event ->
        if (event.action == KeyEvent.ACTION_UP && key == KeyEvent.KEYCODE_BACK) {
            when {
                state.pendingDeleteEntry != null -> {
                    state.pendingDeleteEntry = null
                    refreshViews()
                }
                state.showScheduledView -> {
                    state.showScheduledView = false
                    refreshViews()
                }
                else -> {
                    dismissDialogSafely()
                    navigateBackToMenu()
                }
            }
            true
        } else {
            false
        }
    }

    dialog.setOnCancelListener { navigateBackToMenu() }

    val ticker = object : Runnable {
        override fun run() {
            if (!dialog.isShowing || isFinishing || isDestroyed) return
            safeCall("tick reloj", Unit) { topBar.clockText.text = formats.dateFormat.format(Date()) }
            handler.postDelayed(this, CLOCK_TICK_MS)
        }
    }

    dialog.setOnShowListener {
        safeCall("aplicar blur", Unit) { dialog.window?.let { applyWindowBlur(it, dp(22)) } }
        safeCall("animar surface", Unit) { animateMenuSurface(rootSurface) }
        hero.heroImage.post {
            if (dialog.isShowing && hero.heroImage.isAttachedToWindow) {
                animateTvFocusShine(hero.heroImage)
            }
        }
        refreshViews()
        hero.playButton.post {
            if (hero.playButton.visibility == View.VISIBLE) hero.playButton.requestFocus()
            else leftPanel.rowLibraryView.requestFocus()
        }
    }

    dialog.setOnDismissListener { handler.removeCallbacks(ticker) }

    try {
        dialog.show()
        UiPerformanceProfiler.recordBuild("recordings", rootSurface, uiBuildStartedAt)
        handler.post(ticker)
    } catch (e: Exception) {
        Log.e(LOG_TAG, "No se pudo mostrar el diálogo de grabaciones", e)
        navigateBackToMenu()
    }
}

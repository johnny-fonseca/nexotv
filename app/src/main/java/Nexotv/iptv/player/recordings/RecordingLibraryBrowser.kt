// RecordingLibraryBrowser.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.app.AlertDialog
import android.content.Context.*
import android.view.Gravity
import android.view.KeyEvent
import Nexotv.iptv.player.MainActivity.*

/**
 * Navegador del catálogo completo ("Biblioteca completa de grabaciones").
 * Se abre como ventana dedicada con orden, búsqueda, datos técnicos,
 * sinopsis y acciones remotas.
 *
 * Este archivo solo orquesta: construye las vistas (RecordingBrowser*.kt),
 * crea el diálogo y gestiona teclas, reloj y ciclo de vida.
 */
internal fun MainActivity.showRecordingLibraryBrowser(onDismissed: () -> Unit = {}) {
    if (isFinishing || isDestroyed) {
        onDismissed()
        return
    }

    lateinit var dialog: AlertDialog
    lateinit var refreshCatalogUi: () -> Unit

    val activity = this
    val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)

    // ---- Datos y estado ----
    val state = RecordingBrowserState()
    val recordingData = RecordingLibraryDataSource(
        activity = this,
        directory = recordingDirectory(),
        treeUri = prefs.getString(MainActivity.KEY_RECORDING_TREE, null),
        guidePrograms = guidePrograms,
        scheduledPrograms = { ProgramSchedule.all(activity) },
    )
    state.recordings = recordingBrowserLoadEntries(recordingData)

    val formats = RecordingBrowserFormats(
        use24HourClock = prefs.getString(MainActivity.KEY_CLOCK_FORMAT, "12-hour") == "24-hour"
    )

    // Se resuelven al hacer clic, igual que en el original.
    val onRefresh: () -> Unit = { refreshCatalogUi() }
    val onDismiss: () -> Unit = { dialog.dismiss() }

    // ---- Vistas ----
    val topBar = createRecordingBrowserTopBar(state, onRefresh)
    val catalog = createRecordingBrowserCatalogPanel()
    val detail = createRecordingBrowserDetailPanel(state, onRefresh, onDismiss)
    val deleteBanner = createRecordingBrowserDeleteBanner(state, onRefresh)
    val rootSurface = createRecordingBrowserLayout(topBar, catalog, detail, deleteBanner)

    val views = RecordingBrowserViews(
        topBar = topBar,
        catalog = catalog,
        detail = detail,
        deleteBanner = deleteBanner,
        root = rootSurface,
    )
    val ctx = RecordingBrowserContext(
        prefs = prefs,
        state = state,
        data = recordingData,
        formats = formats,
        views = views,
        dismissDialog = onDismiss,
        refresh = onRefresh,
    )

    refreshCatalogUi = { refreshRecordingBrowser(ctx) }
    bindRecordingBrowserFocusNavigation(views)

    // ---- Diálogo ----
    dialog = AlertDialog.Builder(this).setView(rootSurface).create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    setTvDialogBounds(dialog, 1f, 1f, Gravity.CENTER)

    dialog.setOnKeyListener { _, key, event ->
        if (event.action == KeyEvent.ACTION_UP && key == KeyEvent.KEYCODE_BACK) {
            when {
                state.pendingDeleteEntry != null -> {
                    state.pendingDeleteEntry = null
                    refreshCatalogUi()
                }
                state.searchQuery.isNotBlank() -> {
                    state.searchQuery = ""
                    state.focusedIndex = 0
                    refreshCatalogUi()
                }
                else -> dialog.dismiss()
            }
            true
        } else false
    }

    val ticker = object : Runnable {
        override fun run() {
            if (!dialog.isShowing || isFinishing || isDestroyed) return
            topBar.clockTv.text =
                recordingBrowserFormatDate(formats.dateFormat, System.currentTimeMillis())
            handler.postDelayed(this, 1000L)
        }
    }

    dialog.setOnShowListener {
        dialog.window?.let { runCatching { applyWindowBlur(it, dp(22)) } }
        animateMenuSurface(rootSurface)
        refreshCatalogUi()
        val items = catalog.itemsLayout
        val firstItem = (0 until items.childCount)
            .map(items::getChildAt)
            .firstOrNull { it.isFocusable }
        if (firstItem != null) {
            firstItem.post { firstItem.requestFocus() }
        } else {
            topBar.searchPill.post { topBar.searchPill.requestFocus() }
        }
    }

    dialog.setOnDismissListener {
        handler.removeCallbacks(ticker)
        onDismissed()
    }

    dialog.show()
    handler.post(ticker)
}

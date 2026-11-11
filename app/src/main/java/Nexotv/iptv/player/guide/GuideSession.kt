// GuideSession.kt
package Nexotv.iptv.player.guide

import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import android.app.AlertDialog
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Posee el estado compartido de la guía y sus referencias de ciclo de vida.
 *
 * TEMP: conserva una referencia no estática a MainActivity sólo para crear los
 * stores existentes. Las pantallas, diálogos y refrescos siguen usando los
 * puentes de MainActivity para preservar foco y orden de actualización.
 */
internal class GuideSession(private val activity: MainActivity) {

    /** Par inmutable programas + índice; se publica de forma atómica para que los lectores nunca vean un estado mezclado. */
    private class GuideProgramSnapshot(
        val programs: List<TvProgram>,
        val index: GuideProgramTimeIndex,
    )

    private val snapshotLock = Any()
    private val emptyTimeIndex = GuideProgramTimeIndex(emptyList())

    @Volatile
    private var snapshot = GuideProgramSnapshot(emptyList(), emptyTimeIndex)

    internal val guideProgramTimeIndex: GuideProgramTimeIndex
        get() = snapshot.index

    internal var guidePrograms: List<TvProgram>
        get() = snapshot.programs
        set(value) {
            synchronized(snapshotLock) {
                if (snapshot.programs === value) return
                val index = try {
                    GuideProgramTimeIndex(value)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo construir el índice temporal de la guía", e)
                    emptyTimeIndex
                }
                snapshot = GuideProgramSnapshot(value, index)
            }
        }

    internal fun guideProgramAt(timestampMs: Long): TvProgram? =
        try {
            snapshot.index.at(timestampMs)
        } catch (e: Exception) {
            null
        }

    internal val guideProgramStore by lazy { GuideProgramStore(activity) }
    internal val guideSearchHistory by lazy { GuideSearchHistory(activity) }

    internal val guideRefreshLifecycle = GuideRefreshLifecycle()

    @Volatile internal var guideDialog: AlertDialog? = null
    @Volatile internal var refreshVisibleGuide: (() -> Unit)? = null
    @Volatile internal var refreshVisibleMainMenu: (() -> Unit)? = null
    @Volatile internal var guideLoading = false
    @Volatile internal var guideRefreshLastRequestAtMs = 0L
    @Volatile internal var guideLoadError: String? = null
    @Volatile internal var guideRefreshDeferred = false
    @Volatile internal var deferredGuideCoverageDateKey: Int? = null

    @Volatile internal var guideSearchDialog: AlertDialog? = null
    @Volatile internal var guideSearchInput: EditText? = null
    @Volatile internal var guideSearchResultsContainer: LinearLayout? = null

    /** Suelta diálogos, vistas y callbacks que retienen la Activity; no altera programas ni stores. */
    internal fun releaseUiReferences() {
        guideDialog = null
        refreshVisibleGuide = null
        refreshVisibleMainMenu = null
        guideSearchDialog = null
        guideSearchInput = null
        guideSearchResultsContainer = null
    }

    private companion object {
        const val TAG = "GuideSession"
    }
}
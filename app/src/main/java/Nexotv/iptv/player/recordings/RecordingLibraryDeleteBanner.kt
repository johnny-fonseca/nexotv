// ============================================================================
// Archivo: RecordingLibraryDeleteBanner.kt
// ============================================================================
package Nexotv.iptv.player.recordings





import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import Nexotv.iptv.player.MainActivity.*

internal class RecordingLibraryDeleteBanner(
    val card: LinearLayout,
    val deleteConfirmTitle: TextView,
    val btnKeep: TextView,
    val btnConfirmDelete: TextView,
)

/** Banner de confirmación de eliminación (se añade al contenedor derecho en el ensamblado). */
internal fun MainActivity.createRecordingLibraryDeleteBanner(
    prefs: SharedPreferences,
    state: RecordingLibraryState,
    reloadData: () -> Unit,
    refresh: () -> Unit,
): RecordingLibraryDeleteBanner {
    val deleteConfirmCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuRedWarningBox(this@createRecordingLibraryDeleteBanner)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        visibility = View.GONE
    }
    val deleteConfirmTitle = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
    }
    val deleteConfirmSub = TextView(this).apply {
        text = "Confirmación: La grabación se eliminará de forma permanente."
        setTextColor(COLOR_ACCENT_RED_SOFT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    }
    val btnKeep = TextView(this).apply {
        text = "Conservar"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = menuDarkButton(this@createRecordingLibraryDeleteBanner)
        setPadding(dp(14), dp(5), dp(14), dp(5))
        applyTvFocus(this)
        setOnClickListener {
            state.pendingDeleteEntry = null
            refresh()
        }
    }
    val btnConfirmDelete = TextView(this).apply {
        text = "Eliminar"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = menuRedButton(this@createRecordingLibraryDeleteBanner)
        setPadding(dp(16), dp(5), dp(16), dp(5))
        applyTvFocus(this)
        setOnClickListener {
            val del = state.pendingDeleteEntry ?: return@setOnClickListener
            val activeFileId = safeCall("leer archivo activo", null as String?) {
                prefs.getString(MainActivity.KEY_RECORDING_FILE, null)
            }
            val isActiveFile = activeFileId != null && del.parts.any { it.id == activeFileId }
            try {
                if (recordingPlayback && isActiveFile) {
                    recordingPlayback = false
                    startStream()
                }
                val deletedAll = safeCall("eliminar archivos", false) { deleteRecordingFiles(del) }
                if (!deletedAll) {
                    showTvToast("No se pudieron eliminar todas las partes. Comprueba el acceso a la carpeta USB.", long = true)
                }
                safeCall("invalidar resumen", Unit) { invalidateRecordingSummary() }
                safeCall("sincronizar canal", Unit) {
                    HomeScreenChannel.syncRecordings(this@createRecordingLibraryDeleteBanner)
                }
                if (isActiveFile) {
                    safeCall("limpiar prefs", Unit) {
                        prefs.edit().remove(MainActivity.KEY_RECORDING_FILE).apply()
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error al eliminar la grabación", e)
                showTvToast("No se pudo eliminar la grabación.", long = true)
            }
            reloadData()
            state.pendingDeleteEntry = null
            refresh()
        }
    }
    deleteConfirmCard.addView(ImageView(this).apply {
        setImageResource(R.drawable.tv_icon_warning)
        imageTintList = ColorStateList.valueOf(COLOR_ACCENT_RED)
    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
    deleteConfirmCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(deleteConfirmTitle)
        addView(deleteConfirmSub)
    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
    deleteConfirmCard.addView(btnKeep, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(8) })
    deleteConfirmCard.addView(btnConfirmDelete, LinearLayout.LayoutParams(-2, dp(30)))

    return RecordingLibraryDeleteBanner(
        card = deleteConfirmCard,
        deleteConfirmTitle = deleteConfirmTitle,
        btnKeep = btnKeep,
        btnConfirmDelete = btnConfirmDelete,
    )
}



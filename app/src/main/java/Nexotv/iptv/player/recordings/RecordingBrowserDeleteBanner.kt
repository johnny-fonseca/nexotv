// RecordingBrowserDeleteBanner.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import Nexotv.iptv.player.MainActivity.*

internal class RecordingBrowserDeleteBanner(
    val card: LinearLayout,
    val titleTv: TextView,
    val btnKeep: TextView,
    val btnConfirmDelete: TextView,
)

internal fun MainActivity.createRecordingBrowserDeleteBanner(
    state: RecordingBrowserState,
    onRefresh: () -> Unit,
): RecordingBrowserDeleteBanner {
    val activity = this

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuRedWarningBox(activity)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        visibility = View.GONE
    }
    val titleTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
    }
    val subTv = TextView(this).apply {
        text = "Confirmación: La grabación se eliminará permanentemente del almacenamiento."
        setTextColor(0xFFFCA5A5.toInt())
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
        background = menuDarkButton(activity)
        setPadding(dp(14), dp(5), dp(14), dp(5))
        applyTvFocus(this)
        setOnClickListener {
            state.pendingDeleteEntry = null
            onRefresh()
        }
    }
    // Sin listener al crearse: refreshRecordingBrowser lo asigna cuando hay un borrado pendiente.
    val btnConfirmDelete = TextView(this).apply {
        text = "Eliminar"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = menuRedButton(activity)
        setPadding(dp(16), dp(5), dp(16), dp(5))
        applyTvFocus(this)
    }

    card.addView(ImageView(this).apply {
        setImageResource(R.drawable.tv_icon_warning)
        imageTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
    card.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(titleTv)
        addView(subTv)
    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
    card.addView(btnKeep, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    card.addView(btnConfirmDelete, LinearLayout.LayoutParams(-2, -2))

    return RecordingBrowserDeleteBanner(
        card = card,
        titleTv = titleTv,
        btnKeep = btnKeep,
        btnConfirmDelete = btnConfirmDelete,
    )
}

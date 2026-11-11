// RecordingBrowserLayout.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import Nexotv.iptv.player.MainActivity.*

private fun MainActivity.createRecordingBrowserFooter(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0xEE09101C.toInt())
            setStroke(dp(1), 0xFF172538.toInt())
        }
        setPadding(dp(14), 0, dp(14), 0)

        val backKeyBadge = TextView(context).apply {
            text = "ATRÁS"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(0xFF152234.toInt())
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        addView(backKeyBadge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        addView(TextView(context).apply {
            text = "Volver a Mis grabaciones"
            setTextColor(0xFF8D9EB1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) })

        addView(TextView(context).apply {
            text = "Reproducir grabación"
            setTextColor(0xFF8D9EB1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) })

        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        addView(TextView(context).apply {
            text = "ⓘ Catálogo completo de grabaciones │ ${BuildConfig.BRAND_NAME} ${BuildConfig.CHANNEL_NAME}"
            setTextColor(0xFF5F7287.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
    }
}

/**
 * Ensambla barra superior, cuerpo (catálogo 58% · detalle 42%),
 * banner de borrado y footer en la superficie raíz del diálogo.
 */
internal fun MainActivity.createRecordingBrowserLayout(
    topBar: RecordingBrowserTopBar,
    catalog: RecordingBrowserCatalogPanel,
    detail: RecordingBrowserDetailPanel,
    deleteBanner: RecordingBrowserDeleteBanner,
): LinearLayout {
    val activity = this

    val mainBody = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = true
        clipToPadding = false
        addView(catalog.root, LinearLayout.LayoutParams(0, -1, 0.58f))
        addView(detail.root, LinearLayout.LayoutParams(0, -1, 0.42f).apply { marginStart = dp(14) })
    }

    val footerBar = createRecordingBrowserFooter()

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = menuBackdrop(activity)
        setPadding(dp(28), dp(18), dp(28), dp(12))
        clipChildren = true
        clipToPadding = false
        addView(topBar.root, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        addView(mainBody, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(deleteBanner.card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(footerBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
}

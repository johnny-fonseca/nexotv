// ============================================================================
// Archivo: RecordingLibraryLayout.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 5. BARRA INFERIOR */
private fun MainActivity.createRecordingLibraryFooter(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0xEE09101C.toInt())
            setStroke(dp(1), 0xFF172538.toInt())
        }
        setPadding(dp(14), 0, dp(14), 0)

        val backKeyBadge = TextView(this@createRecordingLibraryFooter).apply {
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
        addView(TextView(this@createRecordingLibraryFooter).apply {
            text = "Menú principal"
            setTextColor(COLOR_FOOTER_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) })

        addView(TextView(this@createRecordingLibraryFooter).apply {
            text = "Reproducir última grabación"
            setTextColor(COLOR_FOOTER_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) })

        val recStatus = SpannableString("● REC indica grabando").apply {
            setSpan(ForegroundColorSpan(getColor(R.color.tv_live)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(COLOR_FOOTER_LABEL), 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        addView(TextView(this@createRecordingLibraryFooter).apply {
            text = recStatus
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }, LinearLayout.LayoutParams(-2, -2))

        addView(View(this@createRecordingLibraryFooter), LinearLayout.LayoutParams(0, 0, 1f))

        addView(TextView(this@createRecordingLibraryFooter).apply {
            text = "ⓘ Mis grabaciones │ ${BuildConfig.BRAND_NAME} ${BuildConfig.CHANNEL_NAME}"
            setTextColor(COLOR_FOOTER_HINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
    }

/**
 * Ensambla el área derecha (hero + tarjetas inferiores + banner de eliminación),
 * el cuerpo principal (4), el footer (5) y la superficie raíz (6).
 */
internal fun MainActivity.createRecordingLibraryRoot(
    topBar: RecordingLibraryTopBar,
    leftPanel: RecordingLibraryLeftPanel,
    hero: RecordingLibraryHeroCard,
    lowerCards: RecordingLibraryLowerCards,
    deleteBanner: RecordingLibraryDeleteBanner,
): LinearLayout {
    // 3. ÁREA DERECHA (65%)
    val rightContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    rightContainer.addView(hero.card, LinearLayout.LayoutParams(-1, 0, 0.58f))
    rightContainer.addView(lowerCards.root, LinearLayout.LayoutParams(-1, 0, 0.42f).apply { topMargin = dp(10) })
    rightContainer.addView(deleteBanner.card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // 4. CUERPO PRINCIPAL
    val mainBody = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(leftPanel.root, LinearLayout.LayoutParams(0, -1, 0.35f))
        addView(rightContainer, LinearLayout.LayoutParams(0, -1, 0.65f).apply { marginStart = dp(14) })
    }

    // 5. BARRA INFERIOR
    val footerBar = createRecordingLibraryFooter()

    // 6. SUPERFICIE RAÍZ
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = menuBackdrop(this@createRecordingLibraryRoot)
        setPadding(dp(28), dp(18), dp(28), dp(12))
        clipChildren = false
        clipToPadding = false
        addView(topBar.root, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        addView(mainBody, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(footerBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
}

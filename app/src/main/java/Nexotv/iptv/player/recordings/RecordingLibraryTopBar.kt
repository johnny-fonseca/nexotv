// ============================================================================
// Archivo: RecordingLibraryTopBar.kt
// ============================================================================
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

internal class RecordingLibraryTopBar(
    val root: LinearLayout,
    val brandSubtitleTv: TextView,
    val storageStatusText: TextView,
    val storageStatusBadge: TextView,
    val storagePill: LinearLayout,
    val libraryPillText: TextView,
    val openLibraryPill: LinearLayout,
    val clockText: TextView,
)

/** 1. BARRA SUPERIOR */
internal fun MainActivity.createRecordingLibraryTopBar(
    onOpenLibrary: () -> Unit,
): RecordingLibraryTopBar {
    val topBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    val brandSubtitleTv = TextView(this).apply {
        text = "Mis grabaciones"
        setTextColor(COLOR_TEXT_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }

    val brandTexts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@createRecordingLibraryTopBar).apply {
            text = "${BuildConfig.BRAND_NAME} · ${BuildConfig.CHANNEL_NAME}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(brandSubtitleTv)
    }

    val brandBox = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(menuBrandBadge(this@createRecordingLibraryTopBar), LinearLayout.LayoutParams(
            dp(MENU_BRAND_BADGE_SIZE_DP), dp(MENU_BRAND_BADGE_SIZE_DP)
        ).apply {
            marginEnd = dp(10)
        })
        addView(brandTexts, LinearLayout.LayoutParams(-2, -2))
    }
    topBar.addView(brandBox, LinearLayout.LayoutParams(0, -2, 1f))

    val storageStatusText = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(COLOR_TEXT_SOFT)
    }
    val storageStatusBadge = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }
    val storagePill = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(this@createRecordingLibraryTopBar)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isFocusable = true
        isClickable = true
        applyTvFocus(this, scaleTo = 1.04f)
        setOnClickListener { safeCall("abrir destino", Unit) { showRecordingDestination() } }
        addView(ImageView(this@createRecordingLibraryTopBar).apply {
            setImageResource(R.drawable.tv_icon_database)
            imageTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
        addView(storageStatusText)
        addView(TextView(this@createRecordingLibraryTopBar).apply {
            text = "│"
            setTextColor(COLOR_HERO_DIVIDER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6); marginStart = dp(6) })
        addView(storageStatusBadge)
    }
    topBar.addView(storagePill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })

    val libraryPillText = TextView(this).apply {
        text = "Ver biblioteca ›"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(COLOR_TEXT_SOFT)
    }
    val openLibraryPill = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(this@createRecordingLibraryTopBar)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isFocusable = true
        isClickable = true
        applyTvFocus(this, scaleTo = 1.04f)
        addView(ImageView(this@createRecordingLibraryTopBar).apply {
            setImageResource(R.drawable.tv_menu_recordings)
            imageTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
        addView(libraryPillText)
        setOnClickListener { onOpenLibrary() }
    }
    topBar.addView(openLibraryPill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })

    val clockText = TextView(this).apply {
        setTextColor(COLOR_TEXT_SOFT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    val clockPill = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(this@createRecordingLibraryTopBar)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        addView(ImageView(this@createRecordingLibraryTopBar).apply {
            setImageResource(R.drawable.tv_icon_clock)
            imageTintList = ColorStateList.valueOf(COLOR_TEXT_SOFT)
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
        addView(clockText)
    }
    topBar.addView(clockPill, LinearLayout.LayoutParams(-2, -2))

    return RecordingLibraryTopBar(
        root = topBar,
        brandSubtitleTv = brandSubtitleTv,
        storageStatusText = storageStatusText,
        storageStatusBadge = storageStatusBadge,
        storagePill = storagePill,
        libraryPillText = libraryPillText,
        openLibraryPill = openLibraryPill,
        clockText = clockText,
    )
}

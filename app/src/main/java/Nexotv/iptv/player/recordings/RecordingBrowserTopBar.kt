// RecordingBrowserTopBar.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.app.AlertDialog
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

internal class RecordingBrowserTopBar(
    val root: LinearLayout,
    val subtitleTv: TextView,
    val sortLabelTv: TextView,
    val sortPill: LinearLayout,
    val searchLabelTv: TextView,
    val searchPill: LinearLayout,
    val clockTv: TextView,
)

internal fun MainActivity.createRecordingBrowserTopBar(
    state: RecordingBrowserState,
    onRefresh: () -> Unit,
): RecordingBrowserTopBar {
    val activity = this

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = true
        clipToPadding = false
    }

    val subtitleTv = TextView(this).apply {
        setTextColor(0xFF8294A8.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }

    val brandTexts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = "Biblioteca completa de grabaciones"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(subtitleTv)
    }

    val brandBox = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(menuBrandBadge(this@createRecordingBrowserTopBar), LinearLayout.LayoutParams(
            dp(MENU_BRAND_BADGE_SIZE_DP), dp(MENU_BRAND_BADGE_SIZE_DP)
        ).apply {
            marginEnd = dp(10)
        })
        addView(brandTexts, LinearLayout.LayoutParams(-2, -2))
    }
    root.addView(brandBox, LinearLayout.LayoutParams(0, -2, 1f))

    // ---- Orden ----
    val sortLabelTv = TextView(this).apply {
        text = "Orden: Recientes"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFFDCE4EE.toInt())
    }
    val sortPill = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(activity)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isFocusable = true
        isClickable = true
        applyTvFocus(this, scaleTo = 1.04f)
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.tv_icon_refresh)
            imageTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
        addView(sortLabelTv)

        setOnClickListener {
            state.sortMode = (state.sortMode + 1) % RECORDING_BROWSER_SORT_MODE_COUNT
            state.focusedIndex = 0
            onRefresh()
        }
    }
    root.addView(sortPill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })

    // ---- Filtro ----
    val searchLabelTv = TextView(this).apply {
        text = "Filtrar…"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFFDCE4EE.toInt())
    }
    val searchPill = LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(activity)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isFocusable = true
        isClickable = true
        applyTvFocus(this, scaleTo = 1.04f)
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.tv_icon_search)
            imageTintList = ColorStateList.valueOf(0xFFDCE4EE.toInt())
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
        addView(searchLabelTv)

        setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            val input = android.widget.EditText(activity).apply {
                hint = "Escribe para filtrar biblioteca…"
                setText(state.searchQuery)
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF8899AA.toInt())
                setSingleLine(true)
            }
            AlertDialog.Builder(activity)
                .setTitle("Filtrar por nombre")
                .setView(input)
                .setPositiveButton("Filtrar") { _, _ ->
                    state.searchQuery = input.text?.toString().orEmpty().trim()
                    state.focusedIndex = 0
                    onRefresh()
                }
                .setNeutralButton("Limpiar") { _, _ ->
                    state.searchQuery = ""
                    state.focusedIndex = 0
                    onRefresh()
                }
                .setNegativeButton("Cerrar", null)
                .showForTv()
        }
    }
    root.addView(searchPill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })

    // ---- Reloj ----
    val clockTv = TextView(this).apply {
        setTextColor(0xFFDCE4EE.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    val clockPill = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(activity)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.tv_icon_clock)
            imageTintList = ColorStateList.valueOf(0xFFDCE4EE.toInt())
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
        addView(clockTv)
    }
    root.addView(clockPill, LinearLayout.LayoutParams(-2, -2))

    return RecordingBrowserTopBar(
        root = root,
        subtitleTv = subtitleTv,
        sortLabelTv = sortLabelTv,
        sortPill = sortPill,
        searchLabelTv = searchLabelTv,
        searchPill = searchPill,
        clockTv = clockTv,
    )
}

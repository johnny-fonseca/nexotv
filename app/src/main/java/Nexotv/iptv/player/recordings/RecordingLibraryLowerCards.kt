// ============================================================================
// Archivo: RecordingLibraryLowerCards.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal class RecordingLibraryLowerCards(
    val root: LinearLayout,
    val card1HeaderTitle: TextView,
    val card1TitleTv: TextView,
    val card1SubtitleTv: TextView,
    val card1DetailsTv: TextView,
    val card1ContentBox: LinearLayout,
    val card1ScheduledList: LinearLayout,
    val card1ButtonsRow: LinearLayout,
    val btnPlayPrimary: TextView,
    val btnPlayStart: TextView,
    val btnDelete: TextView,
    val libraryTotalSummaryTv: TextView,
    val tvSpaceLabel: TextView,
    val tvProgressBar: ProgressBar,
    val usbSpaceLabel: TextView,
    val usbProgressBar: ProgressBar,
    val btnOpenFullLibrary: TextView,
    val btnChangeUsb: TextView,
)

/** 3.2 Tarjetas inferiores (42% de alto) */
internal fun MainActivity.createRecordingLibraryLowerCards(
    state: RecordingLibraryState,
    onPlayLatest: (Boolean) -> Unit, // parámetro: fromStart
    onOpenLibrary: () -> Unit,
    refresh: () -> Unit,
): RecordingLibraryLowerCards {
    val lowerCards = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    // Tarjeta 1: detalle y acciones de la última grabación
    val card1HeaderTitle = TextView(this).apply {
        text = "ÚLTIMA GRABACIÓN GUARDADA"
        setTextColor(COLOR_TEXT_LABEL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    val card1TitleTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    val card1SubtitleTv = TextView(this).apply {
        setTextColor(0xFF91A5BA.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val card1DetailsTv = TextView(this).apply {
        setTextColor(0xFFCFDAE6.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setLineSpacing(dp(2).toFloat(), 1.04f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x33172536)
            setStroke(dp(1), 0x332E4359)
        }
        setPadding(dp(10), dp(7), dp(10), dp(7))
    }

    val card1ScheduledList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    val btnPlayPrimary = createActionButton("Continuar").apply {
        setOnClickListener { onPlayLatest(false) }
    }
    val btnPlayStart = createActionButton("Desde inicio").apply {
        setOnClickListener { onPlayLatest(true) }
    }
    val btnDelete = createActionButton("Eliminar").apply {
        setOnClickListener {
            state.latestRecording?.let { entry ->
                state.pendingDeleteEntry = entry
                refresh()
            }
        }
    }

    val card1ButtonsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(btnPlayPrimary, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
        addView(btnPlayStart, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
        addView(btnDelete, LinearLayout.LayoutParams(0, dp(36), 1f))
    }

    val card1ContentBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(card1TitleTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        addView(card1SubtitleTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        addView(card1DetailsTv, LinearLayout.LayoutParams(-1, -2))
    }

    val card1 = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = createCardBackground(0x5544D6A0)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        clipChildren = false
        clipToPadding = false

        val header = LinearLayout(this@createRecordingLibraryLowerCards).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@createRecordingLibraryLowerCards).apply {
                setImageResource(R.drawable.tv_menu_recordings)
                imageTintList = ColorStateList.valueOf(0xFF55E6AE.toInt())
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
            addView(card1HeaderTitle)
        }
        addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        addView(card1ContentBox, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(card1ScheduledList, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(card1ButtonsRow, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(5) })
    }
    lowerCards.addView(card1, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) })

    // Tarjeta 2: biblioteca y almacenamiento
    val card2HeaderTitle = TextView(this).apply {
        text = "BIBLIOTECA Y ALMACENAMIENTO"
        setTextColor(COLOR_TEXT_LABEL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val tvSpaceLabel = createSmallLabel()
    val tvProgressBar = createStorageBar(Color.WHITE)
    val usbSpaceLabel = createSmallLabel()
    val usbProgressBar = createStorageBar(getColor(R.color.tv_turquoise))
    val libraryTotalSummaryTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    val btnOpenFullLibrary = createActionButton("Biblioteca ›", bold = true).apply {
        setOnClickListener { onOpenLibrary() }
    }
    val btnChangeUsb = createActionButton("Destino USB").apply {
        setOnClickListener { safeCall("abrir destino", Unit) { showRecordingDestination() } }
    }

    val card2ButtonsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(btnOpenFullLibrary, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
        addView(btnChangeUsb, LinearLayout.LayoutParams(0, dp(36), 1f))
    }

    val card2 = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = createCardBackground(0x552BD4E8)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        clipChildren = false
        clipToPadding = false

        val header = LinearLayout(this@createRecordingLibraryLowerCards).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@createRecordingLibraryLowerCards).apply {
                setImageResource(R.drawable.tv_icon_database)
                imageTintList = ColorStateList.valueOf(0xFF55D9E8.toInt())
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
            addView(card2HeaderTitle)
        }
        addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        addView(libraryTotalSummaryTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        addView(tvSpaceLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        addView(tvProgressBar, LinearLayout.LayoutParams(-1, dp(4)).apply { bottomMargin = dp(5) })
        addView(usbSpaceLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        addView(usbProgressBar, LinearLayout.LayoutParams(-1, dp(4)).apply { bottomMargin = dp(5) })
        addView(View(this@createRecordingLibraryLowerCards), LinearLayout.LayoutParams(0, 0, 1f))
        addView(card2ButtonsRow, LinearLayout.LayoutParams(-1, dp(36)))
    }
    lowerCards.addView(card2, LinearLayout.LayoutParams(0, -1, 1f))

    return RecordingLibraryLowerCards(
        root = lowerCards,
        card1HeaderTitle = card1HeaderTitle,
        card1TitleTv = card1TitleTv,
        card1SubtitleTv = card1SubtitleTv,
        card1DetailsTv = card1DetailsTv,
        card1ContentBox = card1ContentBox,
        card1ScheduledList = card1ScheduledList,
        card1ButtonsRow = card1ButtonsRow,
        btnPlayPrimary = btnPlayPrimary,
        btnPlayStart = btnPlayStart,
        btnDelete = btnDelete,
        libraryTotalSummaryTv = libraryTotalSummaryTv,
        tvSpaceLabel = tvSpaceLabel,
        tvProgressBar = tvProgressBar,
        usbSpaceLabel = usbSpaceLabel,
        usbProgressBar = usbProgressBar,
        btnOpenFullLibrary = btnOpenFullLibrary,
        btnChangeUsb = btnChangeUsb,
    )
}

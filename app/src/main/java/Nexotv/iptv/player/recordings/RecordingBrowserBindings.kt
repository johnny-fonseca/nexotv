// RecordingBrowserBindings.kt
package Nexotv.iptv.player.recordings





import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.guide.GuideProgramMeta

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import Nexotv.iptv.player.MainActivity.*

// =============================================================================
// DETALLE
// =============================================================================
private fun MainActivity.updateRecordingBrowserDetail(
    ctx: RecordingBrowserContext,
    entry: RecordingEntry?,
) {
    val detail = ctx.views.detail
    ctx.state.activeEntry = entry

    if (entry == null) {
        detail.image.setImageResource(R.drawable.recording_placeholder)
        detail.image.tag = null
        detail.badgeTv.text = "• VACÍO"
        detail.storagePillTv.text = "TV"
        detail.titleTv.text = "No hay grabaciones seleccionadas"
        detail.metaTv.text = "0 grabaciones"
        detail.descTv.text = "Comienza a grabar programas de ${BuildConfig.CHANNEL_NAME} para verlos aquí."
        detail.progressBar.progress = 0
        detail.progressLabel.text = "0% visto"
        detail.btnPlay.visibility = View.GONE
        detail.btnPlayStart.visibility = View.GONE
        detail.btnDelete.visibility = View.GONE
        return
    }

    detail.btnPlay.visibility = View.VISIBLE
    detail.btnPlayStart.visibility = View.VISIBLE
    detail.btnDelete.visibility = View.VISIBLE

    val program = recordingBrowserProgramOf(ctx.data, entry)
    runCatching { loadRecordingArtwork(detail.image, entry, program) }
    val durationMs = recordingBrowserDurationOf(entry)
    val savedMs = recordingBrowserSavedPositionOf(entry)
    val percent = recordingBrowserPercentOf(savedMs, durationMs)
    val storageType = if (recordingBrowserIsUsbEntry(entry)) "USB" else "TV"

    detail.badgeTv.text = "• GRABACIÓN"
    detail.storagePillTv.text = "$storageType · ${formatBytes(entry.bytes)}"
    detail.titleTv.text = recordingBrowserTitleOf(ctx.data, entry)

    val durationText = runCatching { ctx.data.formatDurationHuman(durationMs) }.getOrDefault("")
    detail.metaTv.text =
        "${recordingBrowserFormatDate(ctx.formats.detailDateFormat, entry.modified)} · $durationText · $storageType"
    detail.descTv.text = program?.let { GuideProgramMeta.description(it, "") }.orEmpty()
    detail.descTv.visibility = if (detail.descTv.text.isNotBlank()) View.VISIBLE else View.GONE

    detail.progressBar.progress = percent
    detail.progressLabel.text = if (percent > 0) {
        "$percent% reproducido · Quedan ${(durationMs - savedMs).coerceAtLeast(0L) / 60_000L} min"
    } else {
        "Sin empezar · Duración completa"
    }
    detail.btnPlay.text = if (percent > 0) "Seguir viendo" else "Reproducir"
}

// =============================================================================
// FILA DEL CATÁLOGO
// =============================================================================
private fun MainActivity.buildRecordingBrowserRow(
    ctx: RecordingBrowserContext,
    entry: RecordingEntry,
    idx: Int,
): LinearLayout {
    val activity = this
    val views = ctx.views
    val program = recordingBrowserProgramOf(ctx.data, entry)
    val cardTitle = recordingBrowserTitleOf(ctx.data, entry)
    val durMs = recordingBrowserDurationOf(entry)
    val pct = recordingBrowserPercentOf(recordingBrowserSavedPositionOf(entry), durMs)
    val storageType = if (recordingBrowserIsUsbEntry(entry)) "USB" else "TV"
    val durationText = runCatching { ctx.data.formatDurationHuman(durMs) }.getOrDefault("")

    return LinearLayout(activity).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuRowBackground(activity)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        clipChildren = true
        clipToPadding = false

        val thumbBox = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFF09121F.toInt())
            }
            clipToOutline = true
            val thumbImage = ImageView(activity).apply {
                setImageResource(R.drawable.recording_placeholder)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            addView(thumbImage, FrameLayout.LayoutParams(-1, -1))
            runCatching { activity.loadRecordingArtwork(thumbImage, entry, program) }
        }
        if (pct > 0) {
            thumbBox.addView(TextView(activity).apply {
                text = "$pct%"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(getColor(R.color.tv_turquoise))
                }
                setPadding(dp(4), dp(1), dp(4), dp(1))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(4), 0, 0, dp(4))
            })
        }
        addView(thumbBox, LinearLayout.LayoutParams(dp(76), dp(48)).apply { marginEnd = dp(12) })

        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = cardTitle
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(activity).apply {
                val stamp = recordingBrowserFormatDate(ctx.formats.rowDateFormat, entry.modified)
                text = "$stamp · $durationText · ${formatBytes(entry.bytes)} · $storageType"
                setTextColor(0xFF8899AA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        addView(textCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })

        setOnFocusChangeListener { v, focused ->
            v.animate().cancel()
            v.animate().scaleX(1f).scaleY(1f).translationZ(if (focused) 6f else 0f)
                .setDuration(150L).setInterpolator(DecelerateInterpolator(2.2f)).withLayer().start()
            if (focused) {
                ctx.state.focusedIndex = idx
                updateRecordingBrowserDetail(ctx, entry)
                views.detail.btnPlay.nextFocusLeftId = v.id
                views.detail.btnPlayStart.nextFocusLeftId = v.id
                views.detail.btnDelete.nextFocusLeftId = v.id
                views.catalog.scroll.post { views.catalog.scroll.smoothScrollTo(0, v.top) }
            }
        }

        setOnClickListener {
            ctx.dismissDialog()
            recordingBrowserSafePlay(entry)
        }
    }
}

// =============================================================================
// NAVEGACIÓN DE FOCO DEL CATÁLOGO (se recablea en cada refresco)
// =============================================================================
private fun MainActivity.wireRecordingBrowserCatalogFocus(ctx: RecordingBrowserContext) {
    val views = ctx.views
    val items = views.catalog.itemsLayout
    val sortPill = views.topBar.sortPill
    val searchPill = views.topBar.searchPill
    val detail = views.detail

    val childCount = items.childCount
    for (i in 0 until childCount) {
        val row = items.getChildAt(i)
        row.nextFocusUpId = if (i > 0) items.getChildAt(i - 1).id else sortPill.id
        row.nextFocusDownId = if (i < childCount - 1) items.getChildAt(i + 1).id else row.id
        row.nextFocusLeftId = row.id
        row.nextFocusRightId = detail.btnPlay.id
    }
    if (childCount > 0) {
        sortPill.nextFocusDownId = items.getChildAt(0).id
        val focusedRow = items.getChildAt(ctx.state.focusedIndex.coerceIn(0, childCount - 1))
        detail.btnPlay.nextFocusLeftId = focusedRow.id
        detail.btnPlayStart.nextFocusLeftId = focusedRow.id
        detail.btnDelete.nextFocusLeftId = focusedRow.id
    } else {
        sortPill.nextFocusDownId = searchPill.id
        detail.btnPlay.nextFocusLeftId = detail.btnPlay.id
        detail.btnPlayStart.nextFocusLeftId = detail.btnPlayStart.id
        detail.btnDelete.nextFocusLeftId = detail.btnDelete.id
    }
}

// =============================================================================
// BORRADO
// =============================================================================
private fun MainActivity.confirmRecordingBrowserDelete(
    ctx: RecordingBrowserContext,
    entry: RecordingEntry,
) {
    val activity = this
    val prefs = ctx.prefs
    val items = ctx.views.catalog.itemsLayout

    val currentFileId = prefs.getString(MainActivity.KEY_RECORDING_FILE, null)
    val wasPlaying = recordingPlayback && entry.parts.any { it.id == currentFileId }
    if (wasPlaying) {
        recordingPlayback = false
        runCatching { startStream() }
    }
    val deleted = runCatching { deleteRecordingFiles(entry) }.getOrDefault(false)
    if (!deleted) {
        showTvToast("No se pudieron eliminar todas las partes. Comprueba el acceso a la carpeta USB.", long = true)
    }
    if (entry.parts.any { it.id == prefs.getString(MainActivity.KEY_RECORDING_FILE, null) }) {
        prefs.edit().remove(MainActivity.KEY_RECORDING_FILE).apply()
    }
    runCatching { invalidateRecordingSummary() }
    runCatching { HomeScreenChannel.syncRecordings(activity) }
    ctx.state.recordings = recordingBrowserLoadEntries(ctx.data)
    ctx.state.pendingDeleteEntry = null
    ctx.refresh()

    if (items.childCount > 0) {
        items.getChildAt(ctx.state.focusedIndex.coerceIn(0, items.childCount - 1))
            ?.let { row -> row.post { row.requestFocus() } }
    } else {
        ctx.views.topBar.searchPill.requestFocus()
    }
}

// =============================================================================
// REFRESCO COMPLETO
// =============================================================================
internal fun MainActivity.refreshRecordingBrowser(ctx: RecordingBrowserContext) {
    if (isFinishing || isDestroyed) return

    val activity = this
    val state = ctx.state
    val top = ctx.views.topBar
    val catalog = ctx.views.catalog
    val banner = ctx.views.deleteBanner

    top.clockTv.text = recordingBrowserFormatDate(ctx.formats.dateFormat, System.currentTimeMillis())

    top.sortLabelTv.text = when (state.sortMode) {
        RECORDING_BROWSER_SORT_ALPHA -> "Orden: Nombre (A-Z)"
        RECORDING_BROWSER_SORT_SIZE -> "Orden: Tamaño (Mayor)"
        else -> "Orden: Recientes"
    }
    val query = state.searchQuery
    top.searchLabelTv.text = if (query.isNotBlank()) "«$query»" else "Filtrar…"

    val filtered = if (query.isBlank()) state.recordings else {
        state.recordings.filter { entry ->
            recordingBrowserTitleOf(ctx.data, entry).contains(query, ignoreCase = true) ||
                    recordingBrowserProgramOf(ctx.data, entry)?.description.orEmpty()
                        .contains(query, ignoreCase = true)
        }
    }
    val sortedList = when (state.sortMode) {
        RECORDING_BROWSER_SORT_ALPHA ->
            filtered.sortedBy { recordingBrowserTitleOf(ctx.data, it).lowercase(ctx.formats.locale) }
        RECORDING_BROWSER_SORT_SIZE -> filtered.sortedByDescending { it.bytes }
        else -> filtered.sortedByDescending { it.modified }
    }

    top.subtitleTv.text =
        "${sortedList.size} grabaciones disponibles · ${formatBytes(sortedList.sumOf(RecordingEntry::bytes))}"
    catalog.countTv.text = "${sortedList.size} grabaciones guardadas"

    catalog.itemsLayout.removeAllViews()

    if (sortedList.isEmpty()) {
        catalog.itemsLayout.addView(TextView(activity).apply {
            text = if (query.isNotBlank()) "No se encontraron grabaciones con «$query»."
            else "Todavía no hay grabaciones en la biblioteca."
            setTextColor(0xFF8899AA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(30), dp(10), dp(30))
        })
        updateRecordingBrowserDetail(ctx, null)
    } else {
        if (state.focusedIndex !in sortedList.indices) state.focusedIndex = 0
        updateRecordingBrowserDetail(ctx, sortedList[state.focusedIndex])
        sortedList.forEachIndexed { idx, entry ->
            val row = buildRecordingBrowserRow(ctx, entry, idx)
            catalog.itemsLayout.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            )
            row.animateListItemEntrance(idx)
        }
    }
    wireRecordingBrowserCatalogFocus(ctx)

    val pending = state.pendingDeleteEntry
    if (pending != null) {
        banner.titleTv.text = "¿Eliminar «${recordingBrowserTitleOf(ctx.data, pending)}»?"
        banner.card.visibility = View.VISIBLE
        banner.btnConfirmDelete.setOnClickListener { confirmRecordingBrowserDelete(ctx, pending) }
        banner.btnKeep.requestFocus()
    } else {
        banner.card.visibility = View.GONE
    }
}




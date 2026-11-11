// ============================================================================
// Archivo: RecordingLibraryBindings.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.guide.GuideProgramMeta

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import Nexotv.iptv.player.MainActivity.*

/**
 * Agrupa todo lo que necesitan los bind* y refreshViews:
 * preferencias, estado, fuente de datos, formatos, vistas y callbacks.
 */
internal class RecordingLibraryScreenContext(
    val prefs: SharedPreferences,
    val state: RecordingLibraryState,
    val recordingData: RecordingLibraryDataSource,
    val formats: RecordingLibraryDateFormats,
    val topBar: RecordingLibraryTopBar,
    val leftPanel: RecordingLibraryLeftPanel,
    val hero: RecordingLibraryHeroCard,
    val lowerCards: RecordingLibraryLowerCards,
    val deleteBanner: RecordingLibraryDeleteBanner,
    val reloadData: () -> Unit,
    val refreshViews: () -> Unit,
)

private fun MainActivity.entryDurationMs(entry: RecordingEntry): Long =
    safeLong({ entry.parts.sumOf(::durationFor) })

private fun MainActivity.entrySavedMs(entry: RecordingEntry): Long = safeLong({
    entry.parts.firstOrNull()?.let { HomeScreenChannel.resumePosition(this@entrySavedMs, it.name) } ?: 0L
})

private fun MainActivity.bindStorage(ui: RecordingLibraryScreenContext) {
    val directory = safeCall("directorio de grabación", null as java.io.File?) { recordingDirectory() }
    val tvUsable = directory?.let { dir -> safeLong({ dir.usableSpace }) } ?: 0L
    val tvTotal = directory?.let { dir -> safeLong({ dir.totalSpace }) } ?: 0L
    val treeUri = safeCall("URI de destino", null as Uri?) {
        ui.prefs.getString(MainActivity.KEY_RECORDING_TREE, null)?.let(Uri::parse)
    }

    if (treeUri != null) {
        val usbFree = safeLong({ UsbCaptureOutput.freeSpace(this@bindStorage, treeUri) ?: 0L })
        ui.topBar.storageStatusText.text = "USB: ${formatBytes(usbFree)} libres"
        ui.topBar.storageStatusBadge.text = "USB Activo"
        ui.lowerCards.usbSpaceLabel.text = "USB · ${formatBytes(usbFree)} libres"
        val usbTotal = safeLong({ UsbCaptureOutput.totalSpace(this@bindStorage, treeUri) ?: 0L })
        ui.lowerCards.usbProgressBar.progress = usedPercent(usbFree, usbTotal)
    } else {
        ui.topBar.storageStatusText.text = "TV: ${formatBytes(tvUsable)} libres"
        ui.topBar.storageStatusBadge.text = "Memoria TV"
        ui.lowerCards.usbSpaceLabel.text = "USB · Inactivo"
        ui.lowerCards.usbProgressBar.progress = 0
    }
    ui.lowerCards.tvSpaceLabel.text = "TV · ${formatBytes(tvUsable)} libres"
    ui.lowerCards.tvProgressBar.progress = usedPercent(tvUsable, tvTotal)
    ui.leftPanel.setRowStorageSubtitle(if (treeUri != null) "Destino USB activo" else "Memoria interna TV")
}

private fun MainActivity.bindRecordButton(ui: RecordingLibraryScreenContext) {
    val recording = safeCall("estado grabación", false) { isRecording() }
    if (recording) {
        ui.leftPanel.recordTitle.text = "Detener grabación"
        ui.leftPanel.recordSubtitle.text = "Grabando ${BuildConfig.CHANNEL_NAME} · pulsa OK"
    } else {
        ui.leftPanel.recordTitle.text = "Grabar en vivo"
        ui.leftPanel.recordSubtitle.text = "${BuildConfig.CHANNEL_NAME} ahora mismo"
    }
}

private fun MainActivity.bindCounts(ui: RecordingLibraryScreenContext) {
    val (savedCount, savedBytes) = safeCall("resumen biblioteca", 0 to 0L) { recordingLibrarySummary() }
    val scheduledCount = ui.state.scheduled.size
    ui.leftPanel.leftPanelSubtitleTv.text = "$savedCount guardadas · ${formatBytes(savedBytes)}"
    ui.topBar.brandSubtitleTv.text = "$savedCount grabaciones · $scheduledCount en agenda"
    ui.leftPanel.setRowLibrarySubtitle("Ver catálogo de $savedCount grabaciones")
    ui.leftPanel.setRowScheduledSubtitle("$scheduledCount programaciones activas")
    ui.lowerCards.libraryTotalSummaryTv.text = "$savedCount grabaciones guardadas · ${formatBytes(savedBytes)}"
    ui.topBar.libraryPillText.text = "Biblioteca ($savedCount) ›"
}

private fun MainActivity.buildScheduledRow(
    ui: RecordingLibraryScreenContext,
    item: ScheduledProgram,
): LinearLayout =
    LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuRowBackground(this@buildScheduledRow)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        applyTvFocus(this)

        addView(ImageView(this@buildScheduledRow).apply {
            setImageResource(if (item.recording) R.drawable.tv_menu_record else R.drawable.tv_icon_reminder)
            imageTintList = ColorStateList.valueOf(
                if (item.recording) getColor(R.color.tv_live) else COLOR_ACCENT_AMBER
            )
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) })

        val textCol = LinearLayout(this@buildScheduledRow).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@buildScheduledRow).apply {
                text = item.program.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@buildScheduledRow).apply {
                val kind = if (item.recording) "Grabación" else "Aviso"
                text = "${ui.formats.shortDateFormat.format(Date(item.program.startMs))} · $kind"
                setTextColor(COLOR_SCHEDULED_SUBTITLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            })
        }
        addView(textCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })

        val btnCancel = TextView(this@buildScheduledRow).apply {
            text = "Cancelar"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = menuDarkButton(this@buildScheduledRow)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            applyTvFocus(this)
            setOnClickListener {
                safeCall("confirmar cancelación", Unit) {
                    AlertDialog.Builder(this@buildScheduledRow)
                        .setTitle(item.program.title)
                        .setMessage("¿Cancelar esta programación?")
                        .setNegativeButton("Conservar", null)
                        .setPositiveButton("Cancelar programación") { _, _ ->
                            safeCall("eliminar programación", Unit) {
                                ProgramSchedule.remove(this@buildScheduledRow, item.program)
                            }
                            ui.reloadData()
                            refreshVisibleGuide?.invoke()
                            ui.refreshViews()
                        }
                        .showForTv()
                }
            }
        }
        addView(btnCancel, LinearLayout.LayoutParams(-2, dp(26)))
    }

private fun wireScheduledRows(ui: RecordingLibraryScreenContext) {
    val list = ui.lowerCards.card1ScheduledList
    val rowCount = list.childCount
    if (rowCount == 0) return
    ui.leftPanel.rowScheduledView.nextFocusRightId = list.getChildAt(0).id
    for (i in 0 until rowCount) {
        val r = list.getChildAt(i)
        r.nextFocusUpId = if (i > 0) list.getChildAt(i - 1).id else ui.leftPanel.rowScheduledView.id
        r.nextFocusDownId = if (i < rowCount - 1) list.getChildAt(i + 1).id else r.id
        r.nextFocusLeftId = ui.leftPanel.rowScheduledView.id
        r.nextFocusRightId = ui.hero.playButton.id
    }
}

private fun MainActivity.bindScheduledCard(ui: RecordingLibraryScreenContext) {
    val cards = ui.lowerCards
    cards.card1HeaderTitle.text = "AGENDA DE EMISIONES"
    cards.card1HeaderTitle.setTextColor(getColor(R.color.tv_turquoise))
    cards.card1ContentBox.visibility = View.GONE
    cards.card1ScheduledList.visibility = View.VISIBLE
    cards.card1ButtonsRow.visibility = View.GONE
    cards.card1ScheduledList.removeAllViews()

    if (ui.state.scheduled.isEmpty()) {
        cards.card1ScheduledList.addView(TextView(this).apply {
            text = "No hay recordatorios ni grabaciones programadas."
            setTextColor(COLOR_SCHEDULED_SUBTITLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        })
        return
    }
    ui.state.scheduled.take(MAX_SCHEDULED_ROWS).forEachIndexed { index, item ->
        val row = buildScheduledRow(ui, item)
        cards.card1ScheduledList.addView(
            row,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        )
        row.animateListItemEntrance(index)
    }
    wireScheduledRows(ui)
}

private fun MainActivity.bindLatestCard(ui: RecordingLibraryScreenContext, latest: RecordingEntry?) {
    val cards = ui.lowerCards
    ui.leftPanel.rowScheduledView.nextFocusRightId = ui.hero.playButton.id
    cards.card1HeaderTitle.text = "ÚLTIMA GRABACIÓN GUARDADA"
    cards.card1HeaderTitle.setTextColor(COLOR_ACCENT_GREEN)
    cards.card1ContentBox.visibility = View.VISIBLE
    cards.card1ScheduledList.visibility = View.GONE
    cards.card1ButtonsRow.visibility = View.VISIBLE

    if (latest == null) {
        cards.card1TitleTv.text = "No hay grabaciones guardadas"
        cards.card1SubtitleTv.text = "Tus capturas de ${BuildConfig.CHANNEL_NAME} aparecerán aquí para reproducir cuando desees."
        cards.card1DetailsTv.text = "Usa «Grabar en vivo» para comenzar a grabar."
        cards.btnPlayPrimary.visibility = View.GONE
        cards.btnPlayStart.visibility = View.GONE
        cards.btnDelete.visibility = View.GONE
        return
    }

    val prog = safeCall("buscar programa guía", null as TvProgram?) { ui.recordingData.findGuideProgram(latest) }
    val durationMs = entryDurationMs(latest)
    val percent = playbackPercent(entrySavedMs(latest), durationMs)
    val storageType = if (latest.parts.firstOrNull()?.file == null) "USB" else "TV"

    cards.card1TitleTv.text = prog?.title ?: ui.recordingData.recordingTitle(latest)
    cards.card1SubtitleTv.text = "${ui.recordingData.formatDurationHuman(durationMs)} · ${formatBytes(latest.bytes)} · $storageType"
    cards.card1DetailsTv.text = prog?.let { GuideProgramMeta.description(it, "") }.orEmpty()
    cards.card1DetailsTv.visibility = if (cards.card1DetailsTv.text.isNotBlank()) View.VISIBLE else View.GONE

    cards.btnPlayPrimary.visibility = View.VISIBLE
    cards.btnPlayStart.visibility = View.VISIBLE
    cards.btnDelete.visibility = View.VISIBLE
    cards.btnPlayPrimary.text = if (percent > 0) "Continuar · $percent%" else "Reproducir"
}

private fun MainActivity.bindHero(ui: RecordingLibraryScreenContext, latest: RecordingEntry?) {
    val hero = ui.hero
    if (latest == null) {
        hero.heroImage.setImageResource(R.drawable.recording_placeholder)
        hero.heroImage.tag = null
        hero.heroBadgeTv.text = "● VACÍO"
        hero.heroDatePillTv.text = "Sin grabaciones"
        hero.heroFormatPillTv.text = "Memoria lista"
        hero.heroTitleTv.text = "No hay grabaciones guardadas"
        hero.heroSubtitleTv.text = "Comienza a grabar ${BuildConfig.CHANNEL_NAME} en vivo para encontrar tus programas guardados aquí."
        hero.heroProgressFill.layoutParams = FrameLayout.LayoutParams(0, -1)
        hero.playButton.visibility = View.GONE
        return
    }

    hero.playButton.visibility = View.VISIBLE
    val prog = safeCall("buscar programa guía", null as TvProgram?) { ui.recordingData.findGuideProgram(latest) }
    safeCall("cargar arte", Unit) { loadRecordingArtwork(hero.heroImage, latest, prog) }
    val durationMs = entryDurationMs(latest)
    val percent = playbackPercent(entrySavedMs(latest), durationMs)
    val storageType = if (latest.parts.firstOrNull()?.file == null) "USB" else "TV"
    val activeFileId = safeCall("archivo activo", null as String?) {
        ui.prefs.getString(MainActivity.KEY_RECORDING_FILE, null)
    }
    val recordingNow = safeCall("estado grabación", false) { isRecording() }
    val isActivelyRecording = recordingNow && activeFileId != null && latest.parts.any { it.id == activeFileId }

    hero.heroBadgeTv.text = if (isActivelyRecording) "● GRABANDO EN VIVO" else "● ÚLTIMA GRABACIÓN"
    hero.heroDatePillTv.text = ui.formats.heroDateFormat.format(Date(latest.modified))
    hero.heroFormatPillTv.text = "$storageType · ${formatBytes(latest.bytes)}"

    hero.heroTitleTv.text = prog?.title ?: ui.recordingData.recordingTitle(latest)
    val desc = prog?.let { GuideProgramMeta.description(it, "") }.orEmpty()
    val durationText = ui.recordingData.formatDurationHuman(durationMs)
    val progressText = if (percent > 0) "$percent% visto · $durationText" else "Duración $durationText"
    hero.heroSubtitleTv.text = listOf(progressText, desc.takeIf(String::isNotBlank)).filterNotNull().joinToString(" · ")

    val minFill = if (percent > 0) HERO_MIN_FILL_DP else 0
    val fillDp = (HERO_TRACK_WIDTH_DP * (percent / 100f)).toInt().coerceIn(minFill, HERO_TRACK_WIDTH_DP)
    hero.heroProgressFill.layoutParams = FrameLayout.LayoutParams(dp(fillDp), -1)
}

private fun MainActivity.bindDeleteBanner(ui: RecordingLibraryScreenContext) {
    val del = ui.state.pendingDeleteEntry
    if (del != null) {
        ui.deleteBanner.deleteConfirmTitle.text = "¿Eliminar «${ui.recordingData.recordingTitle(del)}»?"
        ui.deleteBanner.card.visibility = View.VISIBLE
        ui.deleteBanner.btnKeep.requestFocus()
    } else {
        ui.deleteBanner.card.visibility = View.GONE
    }
}

/** Equivalente al antiguo `refreshViews` local de showRecordingLibrary(). */
internal fun MainActivity.refreshRecordingLibraryViews(ui: RecordingLibraryScreenContext) {
    if (isFinishing || isDestroyed) return
    try {
        ui.topBar.clockText.text = ui.formats.dateFormat.format(Date())
        bindStorage(ui)
        bindRecordButton(ui)
        bindCounts(ui)

        ui.state.latestRecording = ui.state.recordings.firstOrNull()
        val latest = ui.state.latestRecording

        if (ui.state.showScheduledView) bindScheduledCard(ui) else bindLatestCard(ui, latest)
        bindHero(ui, latest)
        bindDeleteBanner(ui)
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Error al actualizar la pantalla de grabaciones", e)
    }
}

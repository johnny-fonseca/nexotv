// ==================== app/src/main/java/Nexotv/iptv/player/guide/GuideScreen.kt ====================
@file:Suppress("PackageName")
package Nexotv.iptv.player.guide

import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val GUIDE_TAG = "GuideScreen"
private const val GUIDE_DAYS = 5
private const val GUIDE_MAX_REFRESH_PASSES = 3
private const val GUIDE_DEFAULT_REMINDER_LEAD = 5
private const val GUIDE_MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val GUIDE_WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

private val GUIDE_LOCALE: Locale = Locale.forLanguageTag("es-CR")

private inline fun guideSafely(action: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.e(GUIDE_TAG, "Fallo en: $action", e)
    }
}

private fun MainActivity.isUiAlive(): Boolean = !isFinishing && !isDestroyed

private fun MainActivity.resumeLivePlayback() {
    guideSafely("reanudar reproducción en vivo") {
        if (recordingPlayback) toggleRecordingPlayback()
        else if (!player.isPlaying) togglePlayback()
    }
}

private fun View.ensureFocusId(): Int {
    if (id == View.NO_ID) id = View.generateViewId()
    return id
}

private fun SharedPreferences.readIntSafely(key: String, fallback: Int): Int =
    try {
        getInt(key, fallback)
    } catch (e: ClassCastException) {
        Log.w(GUIDE_TAG, "Preferencia con tipo inválido: $key", e)
        fallback
    }

private fun SharedPreferences.readLongSafely(key: String, fallback: Long): Long =
    try {
        getLong(key, fallback)
    } catch (e: ClassCastException) {
        Log.w(GUIDE_TAG, "Preferencia con tipo inválido: $key", e)
        fallback
    }

private fun ScrollView.scrollChildIntoTop(container: ViewGroup, target: View) {
    if (!isAttachedToWindow || !container.isAttachedToWindow || !target.isAttachedToWindow) return
    guideSafely("desplazar elemento al inicio del contenedor") {
        val bounds = Rect(0, 0, target.width, target.height)
        container.offsetDescendantRectToMyCoords(target, bounds)
        scrollTo(0, (container.top + bounds.top - paddingTop).coerceAtLeast(0))
    }
}

internal fun MainActivity.showGuide() {
    if (!isUiAlive()) return
    try {
        buildAndPresentGuideDialog()
    } catch (e: Exception) {
        Log.e(GUIDE_TAG, "Fallo en: mostrar guía", e)
        guideSafely("notificar fallo de la guía") {
            showTvToast("No se pudo abrir la guía", long = true)
        }
    }
}

@SuppressLint("SetTextI18n")
private fun MainActivity.buildAndPresentGuideDialog() {
    val host = this
    val uiBuildStartedAt = SystemClock.elapsedRealtime()
    val colorMuted = getColor(R.color.tv_text_secondary)
    val colorSoft = getColor(R.color.tv_text_primary)
    val colorAccent = getColor(R.color.tv_turquoise)
    val colorError = getColor(R.color.tv_error)
    val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    val costaRicaTimeZone = TimeZone.getTimeZone("GMT-06:00")
    val timeFormat = SimpleDateFormat("h:mm a", GUIDE_LOCALE)
    val dateFormat = SimpleDateFormat("EEEE d 'DE' MMMM", GUIDE_LOCALE)
    val clockFormat = SimpleDateFormat("h:mm a EEE d MMM", GUIDE_LOCALE)
    val weekdayFormat = SimpleDateFormat("EEEE", GUIDE_LOCALE)
    val shortWeekdayFormat = SimpleDateFormat("EEE", GUIDE_LOCALE)
    val dayNumberFormat = SimpleDateFormat("d", GUIDE_LOCALE)
    listOf(timeFormat, dateFormat, clockFormat, weekdayFormat, shortWeekdayFormat, dayNumberFormat)
        .forEach { it.timeZone = costaRicaTimeZone }
    val initialDates = (0 until GUIDE_DAYS).map { offset -> costaRicaCalendar(offset) }
    val screenState = GuideScreenState(
        dates = initialDates,
        datesBaseKey = costaRicaDateKey(initialDates.first()),
    )

    var guideDialogInstance: AlertDialog? = null
    var refreshGuideUi: () -> Unit = {}
    var guideHeaderRef: GuideHeaderBinding? = null

    // ==================== CONTENEDOR RAÍZ ====================
    val rootLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = menuBackdrop(host)
        setPadding(dp(28), dp(18), dp(28), dp(12))
        clipChildren = true
        clipToPadding = false
    }

    // ==================== 1. BARRA SUPERIOR ====================
    fun leaveGuideTo(destination: () -> Unit) {
        if (!isUiAlive()) return
        guideSafely("registrar retorno de pantalla") { pushScreenBackStack { showGuide() } }
        guideSafely("cerrar guía") { guideDialogInstance?.dismiss() }
        guideSafely("abrir destino desde la guía") { destination() }
    }
    val guideTopBar = buildGuideTopBar(
        colorMuted = colorMuted,
        colorSoft = colorSoft,
        colorAccent = colorAccent,
        onLive = {
            guideSafely("limpiar historial de pantallas") { clearScreenBackStack() }
            guideSafely("cerrar guía") { guideDialogInstance?.dismiss() }
            resumeLivePlayback()
        },
        onRecordings = { leaveGuideTo { showRecordingLibraryBrowser() } },
        onMore = { leaveGuideTo { showMoreSettings() } },
        onSearch = { leaveGuideTo { showGuideSearch() } },
    )
    val pillGuide = guideTopBar.guidePill
    val pillLive = guideTopBar.livePill
    val btnSettings = guideTopBar.settingsButton
    val clockTv = guideTopBar.clockText
    rootLayout.addView(
        guideTopBar.view,
        LinearLayout.LayoutParams(GUIDE_MATCH_PARENT, GUIDE_WRAP_CONTENT).apply { bottomMargin = dp(4) },
    )

    // ==================== 2. SUBENCABEZADO Y CONTROLES ====================
    val guideHeader = buildGuideHeader(
        selectedTab = { screenState.selectedTab },
        onTabSelected = { tab ->
            if (screenState.selectedTab != tab) {
                screenState.selectedTab = tab
                guideHeaderRef?.refreshStyles()
                refreshGuideUi()
            }
        },
        guidePill = pillGuide,
        livePill = pillLive,
        colorAccent = colorAccent,
        colorSoft = colorSoft,
    )
    guideHeaderRef = guideHeader
    val tabScheduleBtn = guideHeader.scheduleTab
    val tabSavedBtn = guideHeader.savedTab
    val daysContainer = guideHeader.daysContainer
    val daysScrollView = guideHeader.daysScrollView
    fun updateTabStyles() {
        guideSafely("actualizar estilos de pestañas") { guideHeader.refreshStyles() }
    }
    rootLayout.addView(guideHeader.view)

    // ==================== 3. VISTA DIVIDIDA ====================
    val mainSplitLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = true
        clipToPadding = false
    }

    val programGrid = buildGuideProgramGrid(colorMuted)
    val sectionDateTitle = programGrid.sectionTitle
    val programsContainer = programGrid.programsContainer
    val programsScrollView = programGrid.scrollView
    mainSplitLayout.addView(
        programGrid.view,
        LinearLayout.LayoutParams(0, GUIDE_MATCH_PARENT, 0.62f).apply { marginEnd = dp(16) },
    )

    // Panel de detalle del programa
    val detailCard = buildGuideDetailCard(colorMuted, colorAccent)
    val posterImage = detailCard.posterImage
    val posterBadgeLive = detailCard.posterBadgeLive
    val posterBadgeCategory = detailCard.posterBadgeCategory
    val detailTitleTv = detailCard.title
    val detailMetaTv = detailCard.meta
    val detailStatusContainer = detailCard.statuses
    val detailDescriptionTv = detailCard.description
    val btnReminder = detailCard.reminderButton
    val btnRecord = detailCard.recordButton
    val btnBookmark = detailCard.savedButton
    val detailTooltipTv = detailCard.tooltip
    mainSplitLayout.addView(detailCard.view, LinearLayout.LayoutParams(0, GUIDE_MATCH_PARENT, 0.38f))
    rootLayout.addView(
        mainSplitLayout,
        LinearLayout.LayoutParams(GUIDE_MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(6) },
    )

    // ==================== 4. BARRA INFERIOR ====================
    val footer = buildGuideFooter(
        prefs = prefs,
        colorMuted = colorMuted,
        colorError = colorError,
        timeZone = costaRicaTimeZone,
        guideLocale = GUIDE_LOCALE,
    )
    rootLayout.addView(footer.view, LinearLayout.LayoutParams(GUIDE_MATCH_PARENT, GUIDE_WRAP_CONTENT))

    // ==================== ENLACE DE DATOS DINÁMICOS ====================
    val detailPanel = GuideProgramDetailPanel(
        activity = this,
        posterImage = posterImage,
        posterBadgeLive = posterBadgeLive,
        posterBadgeCategory = posterBadgeCategory,
        title = detailTitleTv,
        meta = detailMetaTv,
        description = detailDescriptionTv,
        statuses = detailStatusContainer,
        reminderButton = btnReminder,
        recordButton = btnRecord,
        savedButton = btnBookmark,
        tooltip = detailTooltipTv,
        userAgent = TV_USER_AGENT,
        timeFormat = timeFormat,
        selectedDayIndex = { screenState.selectedDayIndex },
        reminderLead = {
            prefs.readIntSafely(MainActivity.KEY_REMINDER_LEAD, GUIDE_DEFAULT_REMINDER_LEAD).coerceAtLeast(0)
        },
        updatedAt = { prefs.readLongSafely(MainActivity.KEY_EPG_UPDATED, 0L) },
        stateFor = { program ->
            try {
                val targetKey = programKey(program)
                val scheduled = ProgramSchedule.all(host).firstOrNull {
                    it.program.channelId == program.channelId && it.program.startMs == program.startMs
                }
                GuideProgramPanelState(
                    reminder = scheduled?.reminder == true,
                    recording = scheduled?.recording == true,
                    saved = savedPrograms().any { programKey(it) == targetKey },
                )
            } catch (e: Exception) {
                Log.e(GUIDE_TAG, "Fallo en: resolver estado del programa", e)
                GuideProgramPanelState(reminder = false, recording = false, saved = false)
            }
        },
        isStillSelected = { screenState.selectedProgram?.startMs == it.startMs },
        onSetReminder = { program, enabled, recording, onSaved ->
            guideSafely("programar recordatorio") {
                saveProgramAction(
                    program,
                    enabled,
                    recording,
                    if (enabled) "Recordatorio programado" else "Recordatorio eliminado",
                    onSaved,
                )
            }
        },
        onSetRecording = { program, reminder, enabled, onSaved ->
            guideSafely("programar grabación") {
                saveProgramAction(
                    program,
                    reminder,
                    enabled,
                    if (enabled) "Grabación programada" else "Grabación eliminada",
                    onSaved,
                )
            }
        },
        onSetSaved = { program, saved ->
            guideSafely("guardar programa") {
                setProgramSaved(program, saved)
                showTvToast(if (saved) "Guardado en Mis programas" else "Eliminado de Mis programas", long = true)
                updateTabStyles()
            }
        },
        onChanged = { refreshGuideUi() },
    )

    fun updateDetailPanel(program: TvProgram?) {
        screenState.selectedProgram = program
        guideSafely("actualizar panel de detalle") { detailPanel.update(program) }
    }

    val programGridRenderer = GuideProgramGridRenderer(
        activity = this,
        programsContainer = programsContainer,
        programsScrollView = programsScrollView,
        scheduleTab = tabScheduleBtn,
        savedTab = tabSavedBtn,
        daysContainer = daysContainer,
        reminderButton = btnReminder,
        bookmarkButton = btnBookmark,
        recordingButton = footer.recordingHelper,
        prefs = prefs,
        timeFormat = timeFormat,
        onRefresh = { refreshGuide() },
        onProgramDetail = { updateDetailPanel(it) },
        onProgramFocused = { screenState.lastFocusedProgramCard = it },
        onLiveSelected = {
            guideSafely("cerrar guía") { guideDialogInstance?.dismiss() }
            resumeLivePlayback()
        },
        onRequestReminder = { btnReminder.requestFocus() },
    )

    val daySelectorController = GuideDaySelectorController(
        activity = this,
        container = daysContainer,
        scrollView = daysScrollView,
        scheduleTab = tabScheduleBtn,
        savedTab = tabSavedBtn,
        dates = { screenState.dates },
        selectedTab = { screenState.selectedTab },
        selectedDayIndex = { screenState.selectedDayIndex },
        onDaySelected = { index ->
            if (index in screenState.dates.indices && screenState.selectedDayIndex != index) {
                screenState.selectedDayIndex = index
                refreshGuideUi()
            }
        },
        onRefresh = { refreshGuide() },
        weekdayFormat = weekdayFormat,
        shortWeekdayFormat = shortWeekdayFormat,
        dayNumberFormat = dayNumberFormat,
    )

    fun filterPrograms(activeCalendar: Calendar): List<TvProgram> =
        try {
            if (screenState.selectedTab != 0) {
                GuideProgramSelection.saved(savedPrograms())
            } else {
                val dayStartCalendar = (activeCalendar.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayStart = dayStartCalendar.timeInMillis
                val dayEnd = (dayStartCalendar.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
                GuideProgramSelection.forDay(guidePrograms, dayStart, dayEnd)
            }
        } catch (e: Exception) {
            Log.e(GUIDE_TAG, "Fallo en: filtrar programas", e)
            emptyList()
        }

    fun resolveActiveDayIndex(): Int {
        val lastIndex = screenState.dates.lastIndex
        if (lastIndex < 0) return 0
        return screenState.selectedDayIndex.coerceIn(0, lastIndex)
    }

    // ==================== CONSTRUCTOR PRINCIPAL DE LA GUÍA ====================
    fun renderGuide() {
        val startedAt = SystemClock.elapsedRealtime()

        guideSafely("actualizar estado de la guía") { footer.refreshGuideStatus() }
        val now = System.currentTimeMillis()
        guideSafely("renderizar selector de días") { daySelectorController.render(guideLoading) }

        val dayIndex = resolveActiveDayIndex()
        if (screenState.selectedDayIndex != dayIndex) screenState.selectedDayIndex = dayIndex
        val activeCalendar = screenState.dates.getOrNull(dayIndex)
        if (activeCalendar == null) {
            Log.w(GUIDE_TAG, "Sin fechas disponibles para renderizar la guía")
            return
        }

        guideSafely("actualizar título de sección") {
            sectionDateTitle.text = GuideSectionTitle.format(
                activeCalendar,
                dayIndex,
                screenState.selectedTab,
                dateFormat,
            )
        }

        guideSafely("renderizar lista de programas") {
            val activeList = filterPrograms(activeCalendar)
            val renderResult = programGridRenderer.render(
                activeList = activeList,
                selectedTab = screenState.selectedTab,
                selectedDayIndex = dayIndex,
                nowMs = now,
            )
            screenState.initialGuideFocusView = renderResult.liveCard ?: renderResult.firstCard
        }

        guideSafely("registrar rendimiento de la guía") {
            UiPerformanceProfiler.recordUpdate("guide", rootLayout, startedAt)
        }
    }

    var refreshInProgress = false
    var refreshRequestedWhileBusy = false
    refreshGuideUi = refreshGuideUi@{
        if (!isUiAlive()) return@refreshGuideUi
        if (refreshInProgress) {
            refreshRequestedWhileBusy = true
            return@refreshGuideUi
        }
        refreshInProgress = true
        try {
            var passes = 0
            do {
                refreshRequestedWhileBusy = false
                passes++
                renderGuide()
            } while (refreshRequestedWhileBusy && passes < GUIDE_MAX_REFRESH_PASSES)
        } finally {
            refreshInProgress = false
            refreshRequestedWhileBusy = false
        }
    }

    // Retorno con D-pad izquierdo desde el panel de detalle hacia la lista
    val returnToCardsKey = View.OnKeyListener { _, keyCode, event ->
        if (event?.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            guideSafely("retornar foco a la lista") {
                val target = screenState.lastFocusedProgramCard?.takeIf { it.isAttachedToWindow }
                    ?: screenState.initialGuideFocusView?.takeIf { it.isAttachedToWindow }
                    ?: tabScheduleBtn
                target.requestFocus()
            }
            true
        } else false
    }
    listOf(btnReminder, btnRecord, btnBookmark).forEach { it.setOnKeyListener(returnToCardsKey) }

    val tabScheduleId = tabScheduleBtn.ensureFocusId()
    val tabSavedId = tabSavedBtn.ensureFocusId()
    val settingsId = btnSettings.ensureFocusId()
    val reminderId = btnReminder.ensureFocusId()
    val recordId = btnRecord.ensureFocusId()
    val bookmarkId = btnBookmark.ensureFocusId()
    val recordingHelperId = footer.recordingHelper.ensureFocusId()
    val programsContainerId = programsContainer.ensureFocusId()

    pillGuide.nextFocusDownId = tabScheduleId
    pillLive.nextFocusDownId = tabSavedId
    btnSettings.nextFocusDownId = reminderId
    btnReminder.nextFocusUpId = settingsId
    btnReminder.nextFocusDownId = bookmarkId
    btnReminder.nextFocusLeftId = programsContainerId
    btnReminder.nextFocusRightId = recordId
    btnRecord.nextFocusUpId = reminderId
    btnRecord.nextFocusDownId = bookmarkId
    btnRecord.nextFocusLeftId = reminderId
    btnRecord.nextFocusRightId = bookmarkId
    btnBookmark.nextFocusUpId = recordId
    btnBookmark.nextFocusDownId = recordingHelperId
    btnBookmark.nextFocusLeftId = recordId
    btnBookmark.nextFocusRightId = recordingHelperId
    footer.recordingHelper.nextFocusUpId = bookmarkId
    footer.recordingHelper.nextFocusDownId = recordingHelperId
    footer.recordingHelper.nextFocusLeftId = bookmarkId
    footer.recordingHelper.nextFocusRightId = recordingHelperId

    updateTabStyles()
    refreshGuideUi()

    // ==================== DIÁLOGO ====================
    val dialogLifecycle = GuideDialogLifecycle(
        activity = this,
        rootView = rootLayout,
        clockText = clockTv,
        programsContainer = programsContainer,
        fallbackFocus = tabScheduleBtn,
        preferredFocus = { screenState.initialGuideFocusView },
        onTick = { now ->
            guideSafely("actualizar texto de grabación") { footer.refreshRecordingText() }
            guideSafely("detectar cambio de día") {
                val currentDateKey = costaRicaDateKey(now)
                if (currentDateKey != screenState.datesBaseKey) {
                    val selectedDateKey = screenState.dates
                        .getOrNull(screenState.selectedDayIndex)
                        ?.let { costaRicaDateKey(it) }
                    val refreshedDates = (0 until GUIDE_DAYS).map { offset -> costaRicaCalendar(offset, now) }
                    screenState.dates = refreshedDates
                    screenState.datesBaseKey = currentDateKey
                    screenState.selectedDayIndex = refreshedDates
                        .indexOfFirst { costaRicaDateKey(it) == selectedDateKey }
                        .takeIf { it >= 0 } ?: 0
                    refreshGuideUi()
                }
            }
        },
        onShown = { dialog ->
            guideDialogInstance = dialog
            guideDialog = dialog
            refreshVisibleGuide = { if (dialog.isShowing) refreshGuideUi() }
            guideSafely("enfocar programa inicial") {
                val focusView = screenState.initialGuideFocusView
                if (focusView != null) {
                    programsContainer.post {
                        if (dialog.isShowing && focusView.isAttachedToWindow) {
                            focusView.requestFocus()
                            programsScrollView.scrollChildIntoTop(programsContainer, focusView)
                        }
                    }
                }
            }
        },
        onDismissed = {
            guideSafely("restaurar reproductor") { detailCard.restorePlayer() }
            if (guideDialog === screenState.dialog) {
                guideDialog = null
                refreshVisibleGuide = null
            }
            screenState.dialog = null
            guideDialogInstance = null
        },
        onRedRecord = {
            guideSafely("grabación con botón rojo") { toggleRecording() }
            guideSafely("actualizar texto de grabación") { footer.refreshRecordingText() }
        },
    )
    val dialog = dialogLifecycle.showIfAlive() ?: return
    if (!dialog.isShowing) return
    screenState.dialog = dialog
    guideDialogInstance = dialog
    guideDialog = dialog
    guideSafely("registrar rendimiento de construcción de la guía") {
        UiPerformanceProfiler.recordBuild("guide", rootLayout, uiBuildStartedAt)
    }
}
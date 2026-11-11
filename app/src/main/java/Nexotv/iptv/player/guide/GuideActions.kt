// GuideActions.kt

package Nexotv.iptv.player.guide

import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.guide.GuideSearchProgramUiStateIndex
import Nexotv.iptv.player.guide.GuideStatusResolver
import Nexotv.iptv.player.guide.GuideSearchEmptyStateSelector
import Nexotv.iptv.player.guide.GuideProgramSearch

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import Nexotv.iptv.player.MainActivity.MenuDestination

private const val MAX_GUIDE_QUERY_LENGTH = 120
private const val MAX_DIALOG_RESULT_ROWS = 300
private const val DEFAULT_EPG_INTERVAL_HOURS = 6
private const val GUIDE_ERROR_TAG = "GUIDE_ERROR"

private val guideQueryControlChars = Regex("[\\p{Cntrl}\\u0085\\u2028\\u2029]+")

private fun logGuideFailure(operation: String, error: Throwable) {
    DiagnosticTracker.log(GUIDE_ERROR_TAG, "$operation: ${error.javaClass.simpleName}")
}

private fun sanitizeGuideQuery(raw: String?): String {
    val cleaned = raw.orEmpty().replace(guideQueryControlChars, " ").trim()
    if (cleaned.length <= MAX_GUIDE_QUERY_LENGTH) return cleaned
    var end = MAX_GUIDE_QUERY_LENGTH
    if (Character.isHighSurrogate(cleaned[end - 1])) end--
    return cleaned.substring(0, end).trim()
}

private fun isSameSlot(schedule: ScheduledProgram, program: TvProgram): Boolean =
    (schedule.program.channelId == program.channelId) && (schedule.program.startMs == program.startMs)

internal fun MainActivity.showProgramActions(program: TvProgram, onDismissed: (() -> Unit)? = null) {
    if (!isUiAvailable()) return
    try {
        val returnFocus = currentFocus
        val existing = runCatching {
            ProgramSchedule.all(this).firstOrNull { isSameSlot(it, program) }
        }.getOrNull()
        val initiallySaved = runCatching {
            savedPrograms().any { programKey(it) == programKey(program) }
        }.getOrDefault(defaultValue = false)

        GuideProgramActionsDialog.show(
            activity = this,
            program = program,
            userAgent = TV_USER_AGENT,
            initialReminder = existing?.reminder == true,
            initialRecording = existing?.recording == true,
            initiallySaved = initiallySaved,
            onSaveSchedule = { reminder, recording, message, onSaved ->
                saveProgramAction(program, reminder, recording, message, onSaved)
            },
            onSavedChange = { saved ->
                if (isUiAvailable()) {
                    setProgramSaved(program, saved)
                    showTvToast(
                        getString(if (saved) R.string.tv_guide_program_saved else R.string.tv_guide_program_unsaved),
                        long = true,
                    )
                    runCatching { refreshVisibleGuide?.invoke() }
                }
            },
            onStateChange = { reminder, recording, saved ->
                if (isUiAvailable()) {
                    runCatching {
                        GuideProgramStateViews.updateRow(this, returnFocus, program, reminder, recording, saved)
                    }.onFailure { logGuideFailure("updateRow", it) }
                }
            },
            onDialogReady = { dialog, firstButton ->
                runCatching { setTvDialogBounds(dialog, 1f, 1f) }
                    .onFailure { logGuideFailure("dialogBounds", it) }
                dialog.setOnShowListener {
                    runCatching { animateDialogEntrance(dialog) }
                    firstButton.post { runCatching { firstButton.requestFocus() } }
                }
            },
        ) {
            runCatching { returnFocus?.post { returnFocus.requestFocus() } }
            runCatching { onDismissed?.invoke() }.onFailure { logGuideFailure("onDismissed", it) }
        }
    } catch (e: Exception) {
        logGuideFailure("showProgramActions", e)
        showTvToast("No se pudieron abrir las acciones del programa", long = true)
    }
}

internal fun MainActivity.saveProgramAction(
    program: TvProgram,
    reminder: Boolean,
    recording: Boolean,
    message: String,
    onSaved: () -> Unit,
) {
    if (!isUiAvailable()) return
    try {
        if ((reminder || recording) &&
            !runCatching { PermissionFlows.requestExactAlarmAccess(this) }.getOrDefault(false)
        ) {
            return
        }

        val save: () -> Unit = {
            if (isUiAvailable()) {
                val saved = runCatching {
                    ProgramSchedule.save(this, program, reminder, recording)
                }.getOrDefault(false)
                if (saved) {
                    runCatching { onSaved() }.onFailure { logGuideFailure("onSaved", it) }
                    runCatching { refreshVisibleGuide?.invoke() }
                    showTvToast(message, long = true)
                    if (reminder || recording) {
                        runCatching { requestNotificationsForRecording() }
                            .onFailure { logGuideFailure("requestNotifications", it) }
                    }
                } else {
                    showTvToast(getString(R.string.tv_guide_recording_conflict), long = true)
                }
            }
        }

        val checkActiveRecording: () -> Unit = {
            if (isUiAvailable()) {
                val recordingActive = recording && runCatching { isRecording() }.getOrDefault(false)
                if (recordingActive) {
                    AlertDialog.Builder(this)
                        .setTitle("Hay una grabación en curso")
                        .setMessage(
                            "Si esta grabación sigue activa cuando empiece «${program.title}», " +
                                    "la grabación programada no podrá iniciar. Detén la actual antes de esa hora.",
                        )
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Programar de todas formas") { _, _ -> save() }
                        .showForTv()
                } else {
                    save()
                }
            }
        }

        val alreadyRecordingScheduled = runCatching {
            ProgramSchedule.all(this).any { isSameSlot(it, program) && it.recording }
        }.getOrDefault(false)

        if (recording && !alreadyRecordingScheduled) {
            confirmRecordingStorage(checkActiveRecording)
        } else {
            checkActiveRecording()
        }
    } catch (e: Exception) {
        logGuideFailure("saveProgramAction", e)
        showTvToast("No se pudo guardar la programación", long = true)
    }
}

@UnstableApi
internal fun MainActivity.showGuideSearch() {
    if (!isUiAvailable()) return
    try {
        GuideSearchDialog(
            activity = this,
            recentSearches = recentGuideSearches(),
            onSearch = { submitGuideSearch(it) },
            onVoiceSearch = { startGuideVoiceSearch(it) },
            styleInput = { input -> styleTvInput(input) },
            createSearchButton = { label, action -> searchButton(label, action) },
            onBack = {
                if (!popScreenBackStack()) {
                    showMainMenu(MenuDestination.SEARCH) {}
                }
            },
            onDialogCreated = { dialog, query, results, content ->
                guideSearchResultsContainer = results
                guideSearchInput = query
                guideSearchDialog = dialog
                dialog.setOnCancelListener {
                    if (!popScreenBackStack()) {
                        consumeMenuOrigin { origin -> showMainMenu(origin) {} }
                    }
                }
                dialog.setOnDismissListener {
                    if (guideSearchDialog === dialog) {
                        guideSearchDialog = null
                        guideSearchInput = null
                        guideSearchResultsContainer = null
                    }
                }
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                runCatching { setTvDialogBounds(dialog, 1f, 1f, Gravity.CENTER) }
                    .onFailure { logGuideFailure("searchDialogBounds", it) }
                dialog.setOnShowListener {
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                    runCatching { dialog.window?.let { applyWindowBlur(it, guideDp(22)) } }
                    runCatching { animateMenuSurface(content) }
                    runCatching { focusFirstWhenReady(content, query, query) }
                        .onFailure { logGuideFailure("searchFocus", it) }
                }
            },
        ).show()
    } catch (e: Exception) {
        logGuideFailure("showGuideSearch", e)
        showTvToast("No se pudo abrir la búsqueda", long = true)
    }
}

internal fun MainActivity.searchButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
    text = label
    gravity = Gravity.CENTER
    isFocusable = true
    isClickable = true
    isFocusableInTouchMode = true
    textSize = 18f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(Color.WHITE)
    setPadding(guideDp(24), guideDp(10), guideDp(24), guideDp(10))
    background = menuActionButton(this@searchButton, isRed = false, isCyanAccent = label == "Buscar")
    applyTvFocus(this, scaleTo = 1.035f)
    setOnClickListener {
        try {
            action()
        } catch (e: Exception) {
            logGuideFailure("searchButton", e)
        }
    }
}

@UnstableApi
internal fun MainActivity.submitGuideSearch(term: String) {
    if (!isUiAvailable()) return
    menuOrigin = MenuDestination.SEARCH
    showGuideSearchResults(term)
}

internal fun MainActivity.recentGuideSearches(): List<String> =
    runCatching { guideSearchHistory.recent() }.getOrDefault(emptyList())

private fun MainActivity.createGuideSearchProgramUiStateIndex(
    matches: List<TvProgram>,
): GuideSearchProgramUiStateIndex =
    if (matches.isEmpty()) {
        GuideSearchProgramUiStateIndex(emptyList(), emptyList())
    } else {
        GuideSearchProgramUiStateIndex(
            savedPrograms(),
            runCatching { ProgramSchedule.all(this) }.getOrDefault(emptyList()),
        )
    }

internal fun MainActivity.rememberGuideSearch(query: String) {
    val clean = sanitizeGuideQuery(query)
    if (clean.isEmpty()) return
    runCatching { guideSearchHistory.remember(clean) }
}

@SuppressLint("QueryPermissionsNeeded")
internal fun MainActivity.startGuideVoiceSearch(request: Intent) {
    if (!isUiAvailable()) return
    val available = runCatching { request.resolveActivity(packageManager) != null }.getOrDefault(false)
    if (!available) {
        showTvToast("La búsqueda por voz no está disponible", long = true)
        return
    }
    runCatching { launchGuideVoice(request) }
        .onFailure {
            logGuideFailure("voiceSearch", it)
            showTvToast("No se pudo iniciar la búsqueda por voz", long = true)
        }
}

@UnstableApi
internal fun MainActivity.onGuideVoiceResult(rawText: String?) {
    if (!isUiAvailable()) return
    val text = sanitizeGuideQuery(rawText)
    val input = guideSearchInput
    if (text.isEmpty() || input == null) {
        showTvToast("No se recibió una búsqueda")
        return
    }
    runCatching { input.setText(text) }.onFailure { logGuideFailure("voiceSetText", it) }
    submitGuideSearch(text)
}

@UnstableApi
internal fun MainActivity.showGuideSearchResults(rawQuery: String) {
    if (!isUiAvailable()) return
    val query = sanitizeGuideQuery(rawQuery)
    if (query.isEmpty()) {
        showTvToast("Escribe un título o una palabra")
        return
    }
    try {
        rememberGuideSearch(query)
        val matches = runCatching { GuideProgramSearch.find(guidePrograms, query) }.getOrDefault(emptyList())
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val updatedAtMs = runCatching { prefs.getLong(MainActivity.KEY_EPG_UPDATED, 0L) }.getOrDefault(0L)
        val intervalHours = runCatching {
            prefs.getInt(MainActivity.KEY_EPG_INTERVAL_HOURS, DEFAULT_EPG_INTERVAL_HOURS)
        }.getOrDefault(DEFAULT_EPG_INTERVAL_HOURS)
        val guideStatus = GuideStatusResolver.resolve(
            programs = guidePrograms,
            updatedAtMs = updatedAtMs,
            refreshFailed = guideLoadError != null,
            refreshIntervalHours = intervalHours,
            nowMs = now,
        )
        val results = guideSearchResultsContainer ?: run {
            showSearchResultsDialog(matches, query)
            return
        }
        val programStates = createGuideSearchProgramUiStateIndex(matches)
        GuideSearchResultsView.render(
            activity = this,
            container = results,
            matches = matches,
            emptyState = GuideSearchEmptyStateSelector.select(guideStatus),
            createRow = { searchResultRow(it, programStates) },
            createButton = { label, action -> searchButton(label, action) },
            onRetry = {
                runCatching {
                    results.removeAllViews()
                    val input = guideSearchInput
                    input?.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    input?.let { imm?.showSoftInput(it, 0) }
                }.onFailure { logGuideFailure("searchRetry", it) }
            },
            onRefresh = {
                runCatching { refreshGuide() }.onFailure { logGuideFailure("refreshGuide", it) }
            },
        )
    } catch (e: Exception) {
        logGuideFailure("showGuideSearchResults", e)
        showTvToast("No se pudo completar la búsqueda", long = true)
    }
}

@UnstableApi
internal fun MainActivity.showSearchResultsDialog(matches: List<TvProgram>, query: String) {
    if (!isUiAvailable()) return
    try {
        if (matches.isEmpty()) {
            showGuideSearch()
            guideSearchInput?.setText(query)
            // Evita recursión infinita si el diálogo de búsqueda aún no registró su contenedor
            if (guideSearchResultsContainer != null) showGuideSearchResults(query)
            else showTvToast("Sin resultados para «$query»")
            return
        }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(guideDp(16), guideDp(8), guideDp(16), guideDp(8))
        }
        val programStates = createGuideSearchProgramUiStateIndex(matches)
        val visibleMatches = if (matches.size > MAX_DIALOG_RESULT_ROWS) {
            matches.subList(0, MAX_DIALOG_RESULT_ROWS)
        } else {
            matches
        }
        visibleMatches.forEach { rows.addView(searchResultRow(it, programStates)) }
        val title = if (visibleMatches.size < matches.size) {
            "Resultados · ${visibleMatches.size} de ${matches.size}"
        } else {
            "Resultados · ${matches.size}"
        }
        showCardList(title, rows)
    } catch (e: Exception) {
        logGuideFailure("showSearchResultsDialog", e)
        showTvToast("No se pudieron mostrar los resultados", long = true)
    }
}

internal fun MainActivity.searchResultRow(
    program: TvProgram,
    programStates: GuideSearchProgramUiStateIndex,
): View = try {
    GuideSearchResultRow.create(
        activity = this,
        program = program,
        saved = programStates.isSaved(program),
        scheduled = programStates.schedule(program),
        onSelected = { showProgramActions(program) },
    )
} catch (e: Exception) {
    logGuideFailure("searchResultRow", e)
    TextView(this).apply {
        text = program.title
        setTextColor(Color.WHITE)
        isFocusable = true
        isClickable = true
        setPadding(guideDp(16), guideDp(8), guideDp(16), guideDp(8))
        setOnClickListener { showProgramActions(program) }
    }
}

internal fun MainActivity.savedPrograms(): List<TvProgram> =
    runCatching { guideProgramStore.loadSaved() }.getOrDefault(emptyList())

internal fun programKey(program: TvProgram) = "${program.channelId}:${program.startMs}"

internal fun MainActivity.setProgramSaved(program: TvProgram, saved: Boolean) {
    runCatching { guideProgramStore.setSaved(program, saved) }
        .onFailure {
            logGuideFailure("setProgramSaved", it)
            showTvToast("No se pudo actualizar el programa guardado", long = true)
        }
}

internal fun MainActivity.loadCachedGuide() {
    val cached = runCatching { guideProgramStore.loadCached() }.getOrNull() ?: return
    guidePrograms = cached
    runCatching { HomeScreenChannel.refreshCurrentProgram(this) }
        .onFailure { logGuideFailure("refreshCurrentProgram", it) }
}
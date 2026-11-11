// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenActions.kt
// Acciones compartidas y conexión de clics de los botones del menú principal.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.guide.*
import android.util.Log
import Nexotv.iptv.player.MainActivity.*

internal fun MainActivity.menuScreenEnsureGuideLoaded() {
    if (guidePrograms.isNotEmpty()) return
    menuScreenGuard("cargarGuiaEnCache") { loadCachedGuide() }
    if (guidePrograms.isEmpty() && menuScreenGuardOr("internet", false) { hasValidatedInternet() }) {
        menuScreenGuard("actualizarGuiaSilenciosa") { refreshGuide(silent = true) }
    }
}

internal fun MainActivity.menuScreenToggleLivePlayback() {
    if (recordingPlayback) {
        toggleRecordingPlayback()
        return
    }
    if (!player.isPlaying) togglePlayback() else showPlaybackPanel()
    hideStatusAfterDelay()
}

internal fun MainActivity.menuScreenOpenFrom(
    destination: MenuDestination,
    moreAction: () -> Unit,
    open: () -> Unit
) {
    menuOrigin = destination
    pushScreenBackStack { showMainMenu(destination, moreAction) }
    open()
}

/** Abre las acciones de un programa y, al volver, regresa al menú con el foco en "Guía". */
private fun MainActivity.menuScreenOpenProgramActions(program: TvProgram, moreAction: () -> Unit) {
    pushScreenBackStack { showMainMenu(MenuDestination.GUIDE, moreAction) }
    showProgramActions(program) { popScreenBackStack() }
}

/** Conecta los clics de los botones del menú. [launch] cierra el menú tras abrir el destino; [refresh] repinta el menú. */
internal fun MainActivity.menuScreenWireActions(
    ui: MenuScreenViews,
    state: MenuScreenState,
    moreAction: () -> Unit,
    launch: (() -> Unit) -> Unit,
    refresh: () -> Unit
) {
    ui.btnActions.setOnClickListener {
        launch {
            val program = state.nextProgram
            if (program != null) {
                menuScreenOpenProgramActions(program, moreAction)
            } else {
                pushScreenBackStack { showMainMenu(MenuDestination.GUIDE, moreAction) }
                showGuide()
            }
        }
    }

    ui.btnNextGuide.setOnClickListener {
        launch { menuScreenOpenFrom(MenuDestination.GUIDE, moreAction) { showGuide() } }
    }
    ui.btnNextSchedule.setOnClickListener {
        launch {
            val program = state.nextProgram
            if (program == null) {
                menuScreenOpenFrom(MenuDestination.GUIDE, moreAction) { showGuide() }
            } else {
                menuScreenOpenProgramActions(program, moreAction)
            }
        }
    }

    ui.btnRemind.setOnClickListener {
        try {
            var action = state.actions.reminder.action
            val program = state.nextProgram
            var enableReminder = false
            if (program != null) {
                val existing = ProgramSchedule.all(this).firstOrNull {
                    it.program.channelId == program.channelId && it.program.startMs == program.startMs
                }
                enableReminder = existing?.reminder != true
                action = menuScreenActionsFor(
                    hasNextProgram = true,
                    reminderExists = !enableReminder,
                    exactAlarmAllowed = PermissionFlows.canScheduleExactAlarms(this),
                    recording = state.actions.recording.action == MenuScreenAction.STOP_RECORDING,
                    agendaAvailable = state.actions.agendaAvailable,
                ).reminder.action
            }
            when (action) {
                MenuScreenAction.REFRESH_GUIDE -> {
                    refreshGuide(silent = false)
                    return@setOnClickListener
                }
                MenuScreenAction.REQUEST_EXACT_ALARM -> {
                    if (!PermissionFlows.requestExactAlarmAccess(this) {
                            state.awaitingExactAlarmSettingsReturn = true
                        }) return@setOnClickListener
                }
                MenuScreenAction.ADD_REMINDER,
                MenuScreenAction.REMOVE_REMINDER -> {
                    // The common toggle below preserves the existing reminder semantics.
                }
                MenuScreenAction.START_RECORDING, MenuScreenAction.STOP_RECORDING -> return@setOnClickListener
            }
            if (program == null) return@setOnClickListener
            if (!ProgramSchedule.setReminder(this, program, enableReminder)) {
                showTvToast(getString(R.string.tv_menu_reminder_failed), long = true)
                return@setOnClickListener
            }
            if (enableReminder) requestNotificationsForRecording()
            showStatus(getString(if (enableReminder) R.string.tv_menu_reminder_enabled else R.string.tv_menu_reminder_removed))
            state.scheduleCheckedAt = 0L
        } catch (e: Exception) {
            Log.e(MENU_SCREEN_TAG, "Fallo al gestionar el recordatorio", e)
            showTvToast(getString(R.string.tv_menu_reminder_failed), long = true)
        }
        menuScreenGuard("refrescoTrasRecordatorio") { refresh() }
    }

    ui.btnRecordings.setOnClickListener {
        launch { menuScreenOpenFrom(MenuDestination.RECORDINGS, moreAction) { showRecordingLibrary() } }
    }

    ui.btnRecordNow.setOnClickListener {
        try {
            when (state.actions.recording.action) {
                MenuScreenAction.START_RECORDING,
                MenuScreenAction.STOP_RECORDING -> toggleRecording()
                else -> return@setOnClickListener
            }
        } catch (e: Exception) {
            Log.e(MENU_SCREEN_TAG, "Fallo al alternar la grabación", e)
            showTvToast(getString(R.string.tv_menu_recording_change_failed), long = true)
        }
        menuScreenGuard("refrescoTrasGrabacion") { refresh() }
    }
}





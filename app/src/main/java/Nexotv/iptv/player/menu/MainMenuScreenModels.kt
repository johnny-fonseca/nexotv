// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenModels.kt
// Modelos del menú principal: estado de reproducción y contenedores de vistas, estado y datos.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import Nexotv.iptv.player.MainActivity.*
import Nexotv.iptv.player.R

internal enum class MenuScreenPlayState(val labelRes: Int, val airingBadgeRes: Int, val idleBadgeRes: Int) {
    RECORDING(R.string.tv_menu_state_recording, R.string.tv_menu_badge_recording, R.string.tv_menu_badge_recording),
    RECONNECTING(R.string.tv_menu_state_reconnecting, R.string.tv_menu_badge_reconnecting, R.string.tv_menu_badge_reconnecting),
    CONNECTING(R.string.tv_menu_state_connecting, R.string.tv_menu_badge_connecting, R.string.tv_menu_badge_connecting),
    LIVE(R.string.tv_menu_state_live, R.string.tv_menu_badge_live_airing, R.string.tv_menu_badge_live_idle),
    PAUSED(R.string.tv_menu_state_paused, R.string.tv_menu_badge_paused, R.string.tv_menu_badge_paused),
    ENDED(R.string.tv_menu_state_ended, R.string.tv_menu_badge_no_signal, R.string.tv_menu_badge_no_signal),
    WAITING(R.string.tv_menu_state_waiting, R.string.tv_menu_badge_no_signal, R.string.tv_menu_badge_no_signal)
}

internal class MenuScreenRow(val view: View, val subtitle: TextView?)

internal class MenuScreenTopBar(
    @Suppress("unused")
    val root: LinearLayout,
    val statusPill: LinearLayout,
    val statusIcon: ImageView,
    val statusText: TextView,
    val statusBadge: TextView,
    val clockText: TextView,
    val clockPill: LinearLayout,
    val clockEnabled: Boolean,
)

internal class MenuScreenHero(
    val card: FrameLayout,
    val studioImage: ImageView,
    val liveBadge: TextView,
    val channelInfo: TextView,
    val subtitlesBadge: TextView,
    val playButton: FrameLayout,
    val title: TextView,
    val subtitle: TextView,
    val progressTrack: FrameLayout,
    val progressFill: View
)

internal class MenuScreenInfoCard(val root: LinearLayout, val title: TextView, val subtitle: TextView)

internal class MenuScreenLiveDetails(val root: LinearLayout, val title: TextView, val subtitle: TextView)

internal class MenuScreenViews(
    val root: LinearLayout,
    val topBar: MenuScreenTopBar,
    val liveRow: MenuScreenRow,
    val guideRow: MenuScreenRow,
    val searchRow: MenuScreenRow,
    val recordingsRow: MenuScreenRow,
    val moreRow: MenuScreenRow,
    val hero: MenuScreenHero,
    val nextCard: MenuScreenInfoCard,
    val recCard: MenuScreenInfoCard,
    val btnActions: TextView,
    val btnRemind: TextView,
    val btnRecordings: TextView,
    val btnRecordNow: TextView,
    val btnNextGuide: TextView,
    val btnNextSchedule: TextView,
    val liveTitle: TextView,
    val liveSubtitle: TextView
)

internal class MenuScreenState {
    @Volatile
    var nextProgram: TvProgram? = null
    @Volatile
    var launchingDestination = false
    @Volatile
    var storageText: String? = null
    @Volatile
    var storageCheckedAt = 0L
    @Volatile
    var storageLookupPending = false
    var artworkKey: String? = null
    var guideProgramsSnapshot: List<TvProgram>? = null
    var guideCheckedAt = 0L
    var cachedOnline = false
    var cachedProgramCount = 0
    var cachedSearchableCount = 0
    var cachedAiring: TvProgram? = null
    var cachedNext: TvProgram? = null
    var scheduleCheckedAt = 0L
    var scheduleNextKey: String? = null
    var cachedSchedule = MenuScreenScheduleSummary()
    var lastMenuSnapshot: MenuSnapshot? = null
    var lastGeneralSnapshot: MenuScreenSnapshot? = null
    var lastFullRefreshAt = 0L
    var fullRefreshInFlight = false
    var clockMinuteKey: Long? = null
    var clockTextCache: String? = null
    var heroSubtitleSource: TvProgram? = null
    var heroSubtitleCurrentProgram: Boolean? = null
    var heroSubtitleLive: Boolean? = null
    var heroSubtitleTextCache: String? = null
    var progressWidthPx: Int? = null
    var recordingRowSnapshot: MenuSnapshot? = null
    var recordingRowElapsedSecond: Long? = null
    var recordingRowTextCache: String? = null
    var actions = MenuScreenActions.EMPTY
    var awaitingExactAlarmSettingsReturn = false
}

internal enum class MenuScreenAction {
    REFRESH_GUIDE,
    REQUEST_EXACT_ALARM,
    ADD_REMINDER,
    REMOVE_REMINDER,
    START_RECORDING,
    STOP_RECORDING,
}

internal data class MenuScreenActionButton(
    val labelRes: Int,
    val available: Boolean,
    val action: MenuScreenAction,
)

internal data class MenuScreenActions(
    val reminder: MenuScreenActionButton,
    val recording: MenuScreenActionButton,
    val agendaAvailable: Boolean,
) {
    companion object {
        val EMPTY = MenuScreenActions(
            reminder = MenuScreenActionButton(R.string.tv_menu_action_refresh, true, MenuScreenAction.REFRESH_GUIDE),
            recording = MenuScreenActionButton(R.string.tv_menu_action_record, true, MenuScreenAction.START_RECORDING),
            agendaAvailable = false,
        )
    }
}

/** Keeps the legacy menu's precedence: no next program always means reload; otherwise
 * reminder state selects remove/add, and permission only changes the add action. */
internal fun menuScreenActionsFor(
    hasNextProgram: Boolean,
    reminderExists: Boolean,
    exactAlarmAllowed: Boolean,
    recording: Boolean,
    agendaAvailable: Boolean,
): MenuScreenActions = MenuScreenActions(
    reminder = when {
        !hasNextProgram -> MenuScreenActionButton(
            R.string.tv_menu_action_refresh, true, MenuScreenAction.REFRESH_GUIDE
        )
        reminderExists -> MenuScreenActionButton(
            R.string.tv_menu_action_remove_reminder, true, MenuScreenAction.REMOVE_REMINDER
        )
        !exactAlarmAllowed -> MenuScreenActionButton(
            R.string.tv_menu_action_remind, true, MenuScreenAction.REQUEST_EXACT_ALARM
        )
        else -> MenuScreenActionButton(
            R.string.tv_menu_action_remind, true, MenuScreenAction.ADD_REMINDER
        )
    },
    recording = if (recording) {
        MenuScreenActionButton(R.string.tv_menu_action_stop_recording, true, MenuScreenAction.STOP_RECORDING)
    } else {
        MenuScreenActionButton(R.string.tv_menu_action_record, true, MenuScreenAction.START_RECORDING)
    },
    agendaAvailable = agendaAvailable,
)

internal class MenuScreenSnapshot(
    val now: Long,
    val online: Boolean,
    val programCount: Int,
    val searchableCount: Int,
    val airing: TvProgram?,
    val next: TvProgram?,
    val playState: MenuScreenPlayState,
    val guideLoading: Boolean,
    val loadError: CharSequence?,
    val epgUpdatedAt: Long,
    var nextBoundaryMs: Long? = null,
) {
    val hasLoadError: Boolean get() = !loadError.isNullOrBlank()
}

internal data class MenuScreenScheduleSummary(
    val available: Boolean = false,
    val activeRecordings: Int = 0,
    val upcomingRecordings: Int = 0,
    val activeReminders: Int = 0,
    val upcomingReminders: Int = 0,
    val nextRecordingStartMs: Long? = null,
    val nextReminderStartMs: Long? = null,
    val nextHasRecording: Boolean = false,
    val nextHasReminder: Boolean = false
) {
    val totalRecordings: Int get() = activeRecordings + upcomingRecordings
    val totalReminders: Int get() = activeReminders + upcomingReminders
}

internal class MenuScreenPillState(
    val iconTint: Int,
    val text: String,
    val badge: String,
    val badgeColor: Int
)

@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvUiContracts.kt
// ============================================================================
// NexoTV UI CONTRACTS
// ============================================================================
// Package/build (do not change):
//   namespace Nexotv.iptv.player; applicationIds teletica.iptv.player,
//   teletica.iptv.player.futv, teletica.iptv.player.opa; flavors teletica, futv, opa.
//   BuildConfig: BRAND_NAME, CHANNEL_NAME, BRAND_KEY, DEFAULT_EPG_URL,
//   OFFICIAL_EPG_HOSTS, XMLTV_CHANNEL_MARKERS, DEEP_LINK_SCHEME, PREFERENCES_FILE,
//   RECORDING_PREFIX, DEFAULT_STREAM_URL.
//
// Preferences (file ${BRAND_KEY}_player; keys are MainActivity.Companion constants):
//   stream/EPG: stream_url, stream_headers, stream_user_agent, epg_url, epg_cache,
//   epg_updated, epg_interval_hours, epg_past_days, saved_programs,
//   reminder_lead_minutes, recording_start_lead_minutes, recording_end_lead_minutes.
//   recordings: recording_active, recording_file, recording_tree_uri, recording_name,
//   recording_error; menu-only literal recording_started_at.
//   app/UI: resize_mode, auto_frame_rate, audio_offset_ms, audio_track_signature,
//   audio_passthrough, audio_decoder, video_decoder, surround_by_default, buffer_profile,
//   muted, panel_timeout_sec, panel_transparency, panel_text_size_sp, show_clock,
//   clock_format, skip_steps, remote_ok, remote_up_down, remote_left_right,
//   remote_long_ok, pip_on_home, autostart_on_boot, autostart_on_wake,
//   external_player_always, confirm_exit, diagnostic_hud_enabled,
//   metered_network_rx_bytes, notifications_permission_asked, auto_backup.
//   P1 switch: home_v2_enabled (boolean, default false; see TvHomeEntry).
//   HomeScreenChannel keeps its own keys in the same file; do not rename them.
// Storage summaries: legacy menu and TV home create separate reader requests and keep independent
// caches, locks, and workers; they share no mutable storage-cache state.
//
// IDs and tests (androidTest/MainActivitySmokeTest):
//   android.R.id.content must keep a child. MainActivity.statusHost must keep >= 4
//   children in the debug offline state. The status card starts focused on "Reintentar"
//   and DPAD_RIGHT moves focus to "Ajustes". MainActivity exposes statusHost, status,
//   playerInfo, playerView and the debug fields used by existing screens; the rebuild
//   must not delete them.
//
// Intents/manifest (do not change): scheme ${deepLinkScheme}, hosts live and recording;
//   debug extras debug_player_status and debug_player_status_kind; recording service
//   extra scheduled_program_id; activity, receivers and service stay as declared.
//
// Features kept: live HLS over the single configured stream URL; home menu destinations
//   Live/Guide/Search/Clips/More; guide + XMLTV + day selector + program actions;
//   guide search and voice input; recordings library/browser and scheduled programs;
//   settings, backup and settings import; diagnostics HUD and session report; PiP;
//   autostart on boot/wake; external player; Android TV home channel and Watch Next;
//   recording foreground service; remote keymap; audio offset; buffer profiles; clock;
//   notifications; panel text size/transparency/timeout.
//
// Single stream (P1): every flavor exposes one configured stream, so the channel list has
//   one item. CHANNEL_UP/DOWN and Up/Down on the bare live canvas are silent no-ops while
//   channels.size == 1: no banner, no toast, no retry. The zapping path stays in this
//   contract for N > 1 and is unreachable when [TvUiState.canZap] is false. Left on the
//   bare live canvas opens the app navigation panel, never a channel list.
//
// Quality (P1): [TvUiState.availableQualities] is empty unless the player exposes two or
//   more selectable renditions for the current stream. Empty means the quality section is
//   hidden: no AUTO/HIGH/MEDIUM/LOW control that cannot act. [TvUiActions.selectQuality]
//   stays for the playback owner.
//
// Focus exception (R7): the bare live canvas is the only focusable element without a
//   visible ring. It is not an action, so it carries no ring; every panel, card, button,
//   list row and blocking state must show the 3dp ring plus the 1.05 scale.
//
// API floor: minSdk 23. No call in this layer may require a newer API without a
//   Build.VERSION guard. The animator scale is read from Settings.Global, never from
//   ValueAnimator.areAnimatorsEnabled() (API 26).
//
// UI layer only: playback, ABR, retry and network logic are untouched. The UI consumes
// TvUiState and emits TvUiActions. If a signal or action is missing, the playback owner
// implements it here; the UI never invents upstream behaviour.
// ============================================================================

package Nexotv.iptv.player.ui

/** Stable screen keys for focus memory and Back layers. */
internal object TvScreens {
    const val LIVE = "live"
    const val HOME = "home"
    const val CHANNELS = "channels"
    const val QUALITY = "quality"
    const val STATUS = "status"
    const val DIAGNOSTICS = "diagnostics"
}

/**
 * Closed playback state set. Every visible status message maps to exactly one of these;
 * the UI never derives a cause that was not measured.
 */
internal sealed interface TvPlaybackState {
    data object Idle : TvPlaybackState
    data object Connecting : TvPlaybackState
    data object Playing : TvPlaybackState
    data object Buffering : TvPlaybackState
    data class Reconnecting(val attempt: Int, val secondsToNextRetry: Int) : TvPlaybackState
    data class QualityChanged(val from: TvQualityLevel, val to: TvQualityLevel) : TvPlaybackState
    data class NetworkProblem(val cause: TvNetworkCause) : TvPlaybackState
    data class StreamError(val code: String) : TvPlaybackState
    data object Offline : TvPlaybackState
}

/** Human-level quality choices. AUTO is the default. */
internal enum class TvQualityLevel {
    AUTO,
    HIGH,
    MEDIUM,
    LOW,
}

/** Measured causes only; UNKNOWN is the neutral fallback. */
internal enum class TvNetworkCause {
    NO_CONNECTIVITY,
    LOW_SIGNAL,
    PROVIDER_HTTP_ERROR,
    UNKNOWN,
}

internal data class TvChannelUi(
    val id: String,
    val name: String,
    val number: String? = null,
    val logoRes: Int? = null,
    val isLive: Boolean = true,
)

internal data class TvProgramUi(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val description: String? = null,
) {
    /** Stable identity for DiffUtil and focus restoration. */
    val stableKey: String get() = "$channelId:$startMs"
}

internal data class TvQualityOptionUi(
    val level: TvQualityLevel,
    val height: Int? = null,
    val bitrateKbps: Int? = null,
    val available: Boolean = true,
)

internal data class TvQualityState(
    val selected: TvQualityLevel = TvQualityLevel.AUTO,
)

/** Only fields Android actually reported may be non-null; the UI shows "—" otherwise. */
internal data class TvNetworkUi(
    val online: Boolean = false,
    val band: String? = null,
    val frequencyMhz: Int? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val standard: String? = null,
)

internal data class TvDiagnosticsUi(
    val selectedTrack: String? = null,
    val bitrateKbps: Int? = null,
    val bufferAheadMs: Long? = null,
    val droppedFrames: Long? = null,
    val retryCount: Int = 0,
)

internal data class TvRecordingUi(
    val active: Boolean = false,
    val elapsedMs: Long = 0L,
)

/**
 * Read-only snapshot rendered by the new screens. Visibility of the overlay and panels
 * is UI-owned state and deliberately not part of this model.
 */
internal data class TvUiState(
    val playback: TvPlaybackState = TvPlaybackState.Idle,
    val channel: TvChannelUi? = null,
    val channels: List<TvChannelUi> = emptyList(),
    val program: TvProgramUi? = null,
    val nextProgram: TvProgramUi? = null,
    val upcoming: List<TvProgramUi> = emptyList(),
    val guideLoading: Boolean = false,
    val guideError: String? = null,
    val quality: TvQualityState = TvQualityState(),
    val availableQualities: List<TvQualityOptionUi> = emptyList(),
    val network: TvNetworkUi = TvNetworkUi(),
    val diagnostics: TvDiagnosticsUi = TvDiagnosticsUi(),
    val recording: TvRecordingUi = TvRecordingUi(),
    val clock: String = "",
    val clipsSubtitle: String? = null,
)

/**
 * R1: zapping UI exists only when there is a second stream to switch to. With the single
 * configured stream this is false and Up/Down/CHANNEL_UP/CHANNEL_DOWN are silent no-ops.
 */
internal val TvUiState.canZap: Boolean get() = channels.size > 1

/**
 * R2: a manual quality selector is shown only with at least two selectable renditions.
 * Empty means the section is hidden entirely.
 */
internal val TvUiState.hasQualityChoices: Boolean get() = availableQualities.size >= 2

/**
 * Actions emitted by the UI. The playback/network owner decides how to apply them.
 * Notes for that owner:
 * - selectChannel: only reachable when [TvUiState.canZap] is true. With one channel the
 *   UI never calls it. Do not invent extra streams here.
 * - selectQuality: apply as a maximum video bitrate/height cap. Do not claim 4K. Only
 *   reachable when [TvUiState.hasQualityChoices] is true.
 * - retryNow: cancel the pending countdown and reconnect immediately.
 */
internal interface TvUiActions {
    fun retryNow()
    fun selectChannel(channelId: String)
    fun selectQuality(level: TvQualityLevel)
    fun togglePlayPause()
    fun startRecording()
    fun stopRecording()
    fun openSystemSettings()
}

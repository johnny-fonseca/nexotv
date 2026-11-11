@file:Suppress("PackageName")

package Nexotv.iptv.player

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.network.MeteredNetworkUsageController
import Nexotv.iptv.player.network.WifiPlaybackLock
import Nexotv.iptv.player.guide.*
import Nexotv.iptv.player.common.FrameRateEstimator

import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private val RECORDING_PART_SUFFIX = Regex("-part\\d+(?=\\.[^.]+$)")

@UnstableApi
class MainActivity : ComponentActivity(), Player.Listener, PlaybackRecoveryHost, TvNavigationHost {

    private val settingsFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleActivityResult(REQUEST_SETTINGS_FILE, result.resultCode, result.data)
    }
    private val recordingTreeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleActivityResult(REQUEST_RECORDING_TREE, result.resultCode, result.data)
    }
    private val guideVoiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleActivityResult(REQUEST_GUIDE_VOICE, result.resultCode, result.data)
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        handlePermissionResult(REQUEST_NOTIFICATIONS, intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
    }
    private val backupPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        handlePermissionResult(REQUEST_BACKUP_STORAGE, intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
    }

    internal fun launchGuideVoice(request: Intent) = guideVoiceLauncher.launch(request)
    internal fun launchRecordingTree(request: Intent) = recordingTreeLauncher.launch(request)
    internal fun requestAppPermission(permission: String, requestCode: Int) {
        when (requestCode) {
            REQUEST_NOTIFICATIONS -> notificationPermissionLauncher.launch(permission)
            REQUEST_BACKUP_STORAGE -> backupPermissionLauncher.launch(permission)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Estado de arranque
    // ---------------------------------------------------------------------------------------

    /**
     * Se activa si [initializeMainActivity] lanza una excepción. En ese caso la actividad se
     * cierra de forma ordenada y el resto de callbacks del ciclo de vida se ignoran, para no
     * encadenar fallos sobre propiedades `lateinit` que nunca llegaron a inicializarse.
     */
    @Volatile private var initializationFailed = false

    /** Cuenta de fallos por tarea; evita inundar el log cuando el watchdog falla cada 2 s. */
    private val guardFailureCounts = ConcurrentHashMap<String, Int>()

    /** `true` mientras la actividad puede tocar la interfaz con seguridad. */
    internal val isUiAlive: Boolean get() = !isFinishing && !isDestroyed

    internal val isInPipMode: Boolean
        get() = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) && isInPictureInPictureMode

    // ---------------------------------------------------------------------------------------
    // Modelos
    // ---------------------------------------------------------------------------------------

    internal data class DialogBounds(
        val dialog: AlertDialog,
    )

    internal data class RecordingPart(val uri: Uri, val file: File?, val name: String, val bytes: Long, val modified: Long) {
        val id: String get() = file?.absolutePath ?: uri.toString()
    }

    internal data class RecordingEntry(val parts: List<RecordingPart>) {
        val name: String get() = parts.firstOrNull()?.name?.replace(RECORDING_PART_SUFFIX, "").orEmpty()
        val bytes: Long get() = parts.sumOf(RecordingPart::bytes)
        val modified: Long get() = parts.maxOfOrNull { it.modified } ?: 0L
        val id: String get() = parts.firstOrNull()?.id.orEmpty()
    }

    internal enum class MenuDestination { GUIDE, SEARCH, RECORDINGS, MORE }

    // ---------------------------------------------------------------------------------------
    // Hilos y ejecutores
    // ---------------------------------------------------------------------------------------

    internal val handler = Handler(Looper.getMainLooper())
    internal val guideRefreshExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${BuildConfig.BRAND_NAME}-EPG-Refresh").apply { isDaemon = true }
    }
    @Volatile internal var guideRefreshTask: Future<*>? = null
    internal val automaticBackupExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${BuildConfig.BRAND_NAME}-Auto-Backup").apply { isDaemon = true }
    }
    internal val recordingIoExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${BuildConfig.BRAND_NAME}-Recording-IO").apply { isDaemon = true }
    }
    @Volatile internal var automaticBackupTask: Future<*>? = null

    // ---------------------------------------------------------------------------------------
    // Controladores
    // ---------------------------------------------------------------------------------------

    internal val overlayController by lazy { TvOverlayController(this, handler) }
    internal val recoveryController by lazy { PlaybackRecoveryController(this, handler) }
    internal val navigationController by lazy { TvNavigationController(this) }
    internal val recordingStorageController by lazy { RecordingStorageController(this) }
    internal val recordingPlaybackController by lazy { RecordingPlaybackController(this) }
    internal val meteredNetworkUsageController by lazy { MeteredNetworkUsageController(this) }

    /** Evita que el Wi-Fi entre en ahorro de energía mientras hay reproducción activa. */
    internal val wifiPlaybackLock by lazy { WifiPlaybackLock(this) }
    @Volatile internal var isMeteredNetwork = false
    internal val hotspotStabilizer by lazy {
        HotspotStabilizer(
            baseMaxVideoBitrateBps = { maxVideoBitrateForNetwork(playbackBufferProfile()) },
            onLevelChanged = { previous, current, reason ->
                DiagnosticTracker.log("STABILITY_LEVEL", "$previous → $current; causa=$reason")
            },
        )
    }
    internal val recordingDestinationController by lazy { RecordingDestinationController(this) }
    internal val recordingStatusController by lazy { RecordingStatusController(this) }
    internal val networkController by lazy {
        PlaybackNetworkController(this, ::handlePlayerNetworkLost, ::handlePlayerNetworkAvailable)
    }
    internal val playbackSession by lazy { PlaybackSession(this) }
    internal val playbackDiagnostics by lazy { PlaybackDiagnostics() }
    internal val guideSession by lazy { GuideSession(this) }
    internal val settingsTransferController by lazy {
        SettingsTransferController(object : SettingsTransferController.Host {
            override val context: Context get() = this@MainActivity
            override val contentResolver get() = this@MainActivity.contentResolver
            override val appPreferences get() = this@MainActivity.getSharedPreferences(PREFS, MODE_PRIVATE)
            override val brandName get() = BuildConfig.BRAND_NAME
            override val brandKey get() = BuildConfig.BRAND_KEY
            override fun showTvToast(message: String, long: Boolean) = this@MainActivity.showTvToast(message, long)
            override fun applyImportedSettings(resizeMode: Int) {
                guarded("aplicar ajustes importados") {
                    restoreAudioOffset()
                    // El reproductor puede no existir todavía si la importación llega durante el arranque.
                    if (isPlayerViewInitialized) playerView.resizeMode = resizeMode
                    if (isPlayerInitialized) rebuildPlayer("Ajustes importados")
                    if (isStatusInitialized) styleStatus()
                }
            }
            override fun launchSettingsFilePicker(intent: Intent) {
                settingsFileLauncher.launch(intent)
            }
            override fun safeStartActivity(intent: Intent, unavailableMessage: String) {
                this@MainActivity.safeStartActivity(intent, unavailableMessage)
            }
            override fun logImportFailure(error: Exception) {
                Log.w(LOG_TAG, "No se pudo importar la copia de ajustes", error)
            }
        })
    }
    internal val connectivity get() = networkController.connectivity

    // ---------------------------------------------------------------------------------------
    // PlaybackRecoveryHost
    // ---------------------------------------------------------------------------------------

    override val recoveryStatusInitialized: Boolean get() = isStatusInitialized
    override val recoveryStatusView: View get() = status
    override val recoveryActivityUnavailable: Boolean get() = !isUiAlive
    override val recoveryPlayerInitialized: Boolean get() = isPlayerInitialized
    override val recoveryPlayerPlaying: Boolean get() = isPlayerInitialized && player.isPlaying
    override val recoveryRecordingPlayback: Boolean get() = recordingPlayback
    override val retryStartTimeoutMs: Long get() = resources.getInteger(R.integer.tv_status_retry_start_timeout_ms).toLong()
    override fun setRecoveryStatusText(message: String) = setStatusText(message)
    override fun recoveryString(resId: Int, attemptText: String): String = getString(resId, attemptText)
    override fun clearRecoveryStatus() = renderPlayerStatus(null)
    override fun startRecoveryStream() = startStream()
    override fun logRecoveryFailure(error: Exception) {
        safeDiagnostic("RETRY_FAILED", "Falló el reintento manual: ${error.message ?: error.javaClass.simpleName}")
    }
    override fun logStableRecovery() {
        safeDiagnostic("RETRY_STABLE", "Contador de reintentos restablecido tras reproducción estable")
        SessionReport.onRecoveryStable()
    }

    // ---------------------------------------------------------------------------------------
    // Overlays y estado de la interfaz
    // ---------------------------------------------------------------------------------------

    internal val status: TextView get() = overlayController.status
    internal val playerInfo: PlayerInfoOverlay get() = overlayController.playerInfo
    internal val tvToast: TextView get() = overlayController.tvToast
    internal val statusHost: FrameLayout get() = overlayController.statusHost
    internal val dialogBounds = mutableListOf<DialogBounds>()
    internal val refreshDialogBounds = Runnable {
        if (isUiAlive) guarded("ajustar diálogos") { pruneAndApplyDialogBounds() }
    }
    internal val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            handler.removeCallbacks(refreshDialogBounds)
            handler.postDelayed(refreshDialogBounds, DISPLAY_CHANGE_DEBOUNCE_MS)
        }
    }
    internal lateinit var player: ExoPlayer
    internal lateinit var playerView: PlayerView
    internal var statusCard: View? = null
    internal var recordingIndicator: LinearLayout?
        get() = recordingStatusController.indicator
        set(value) { recordingStatusController.indicator = value }
    internal var recordingIndicatorText: TextView?
        get() = recordingStatusController.indicatorText
        set(value) { recordingStatusController.indicatorText = value }
    internal var recordingIndicatorIcon: ImageView?
        get() = recordingStatusController.indicatorIcon
        set(value) { recordingStatusController.indicatorIcon = value }
    internal var recordingIndicatorCollapsed: Boolean
        get() = recordingStatusController.indicatorCollapsed
        set(value) { recordingStatusController.indicatorCollapsed = value }
    internal var recordingIndicatorBlinking: Boolean
        get() = recordingStatusController.indicatorBlinking
        set(value) { recordingStatusController.indicatorBlinking = value }
    internal var debugRecordingStartedAt: Long?
        get() = recordingStatusController.debugRecordingStartedAt
        set(value) { recordingStatusController.debugRecordingStartedAt = value }
    internal var statusCardIsActionable = false
    internal var selectedPlayerStatus: PlayerStatus? = null
    internal var connectingStatusTask: Runnable? = null
    internal var reconnectStatusTask: Runnable?
        get() = recoveryController.reconnectStatusTask
        set(value) { recoveryController.reconnectStatusTask = value }
    internal var recordingStatusTickerScheduled: Boolean
        get() = recordingStatusController.tickerScheduled
        set(value) { recordingStatusController.tickerScheduled = value }
    internal var exactAlarmWarningShown = false
    internal val recordingStatusTicker: Runnable get() = recordingStatusController.ticker
    internal var retryAttempts: Int
        get() = recoveryController.retryAttempts
        set(value) { recoveryController.retryAttempts = value }
    internal var retryPending: Boolean
        get() = recoveryController.retryPending
        set(value) { recoveryController.retryPending = value }
    internal var diagnosticsVisible: Boolean
        get() = playbackDiagnostics.diagnosticsVisible
        set(value) { playbackDiagnostics.diagnosticsVisible = value }
    internal var diagnosticHudView: TextView?
        get() = playbackDiagnostics.hudView
        set(value) { playbackDiagnostics.hudView = value }
    internal var diagnosticHudTicker: Runnable?
        get() = playbackDiagnostics.hudTicker
        set(value) { playbackDiagnostics.hudTicker = value }
    internal var playbackPanelVisible = false
    internal val isPlayerInitialized: Boolean get() = ::player.isInitialized
    internal val isPlayerViewInitialized: Boolean get() = ::playerView.isInitialized
    internal val isStatusInitialized: Boolean get() = overlayController.isStatusInitialized
    internal val isPlayerInfoInitialized: Boolean get() = overlayController.isPlayerInfoInitialized
    internal val isTvToastInitialized: Boolean get() = overlayController.isTvToastInitialized
    internal val hideTvToast: Runnable get() = overlayController.hideTvToast
    internal var retryAtMs: Long
        get() = recoveryController.retryAtMs
        set(value) { recoveryController.retryAtMs = value }
    internal var lastPositionMs: Long
        get() = playbackDiagnostics.lastPositionMs
        set(value) { playbackDiagnostics.lastPositionMs = value }
    internal var frozenSamples: Int
        get() = playbackDiagnostics.frozenSamples
        set(value) { playbackDiagnostics.frozenSamples = value }
    internal var bufferingSamples: Int
        get() = playbackDiagnostics.bufferingSamples
        set(value) { playbackDiagnostics.bufferingSamples = value }
    internal var videoSize: VideoSize
        get() = playbackDiagnostics.videoSize
        set(value) { playbackDiagnostics.videoSize = value }
    internal var frameRate: FrameRateEstimator
        get() = playbackDiagnostics.frameRate
        set(value) { playbackDiagnostics.frameRate = value }
    internal var fps: Float
        get() = playbackDiagnostics.fps
        set(value) { playbackDiagnostics.fps = value }
    internal var resumeAfterLifecycle = false
    internal var activityResumed = false
    private var longOkHandled = false
    internal var originalDisplayModeId: Int? = null
    internal val audioOffset = AudioOffsetHolder()
    internal var droppedVideoFrames: Long
        get() = playbackDiagnostics.droppedVideoFrames
        set(value) { playbackDiagnostics.droppedVideoFrames = value }
    internal var videoDecoderName: String
        get() = playbackDiagnostics.videoDecoderName
        set(value) { playbackDiagnostics.videoDecoderName = value }
    internal var recoveryReason: String
        get() = recoveryController.recoveryReason
        set(value) { recoveryController.recoveryReason = value }
    internal var surfacedRecordingNotice: String?
        get() = recordingStatusController.surfacedNotice
        set(value) { recordingStatusController.surfacedNotice = value }
    internal var manualAudioSelection = false
    internal var surroundOverrideActive = false
    internal var recordingPlayback = false
    internal var activeRecording: RecordingEntry? = null
    internal var activeRecordingPoster: Uri? = null

    /**
     * Caché de duraciones. Se lee en el hilo principal y se escribe desde [recordingIoExecutor],
     * por eso es concurrente: un `LinkedHashMap` normal puede lanzar ConcurrentModificationException.
     */
    internal val recordingDurationCache: MutableMap<String, Long> = ConcurrentHashMap()
    internal val recordingProgressTicker: Runnable
        get() = recordingPlaybackController.progressTicker
    internal var externalHandoff = false
    internal var backArmedUntilMs = 0L

    // ---------------------------------------------------------------------------------------
    // Guía de programación (EPG)
    // ---------------------------------------------------------------------------------------

    internal val guideProgramTimeIndex: GuideProgramTimeIndex
        get() = guideSession.guideProgramTimeIndex
    internal var guidePrograms: List<TvProgram>
        get() = guideSession.guidePrograms
        set(value) { guideSession.guidePrograms = value }
    internal fun guideProgramAt(timestampMs: Long): TvProgram? = guideSession.guideProgramAt(timestampMs)
    internal val guideProgramStore: GuideProgramStore
        get() = guideSession.guideProgramStore
    internal val guideSearchHistory: GuideSearchHistory
        get() = guideSession.guideSearchHistory
    internal var guideDialog: AlertDialog?
        get() = guideSession.guideDialog
        set(value) { guideSession.guideDialog = value }
    internal var refreshVisibleGuide: (() -> Unit)?
        get() = guideSession.refreshVisibleGuide
        set(value) { guideSession.refreshVisibleGuide = value }
    internal var refreshVisibleMainMenu: (() -> Unit)?
        get() = guideSession.refreshVisibleMainMenu
        set(value) { guideSession.refreshVisibleMainMenu = value }
    internal var guideLoading: Boolean
        get() = guideSession.guideLoading
        set(value) { guideSession.guideLoading = value }
    internal var guideRefreshLastRequestAtMs: Long
        get() = guideSession.guideRefreshLastRequestAtMs
        set(value) { guideSession.guideRefreshLastRequestAtMs = value }
    internal var guideLoadError: String?
        get() = guideSession.guideLoadError
        set(value) { guideSession.guideLoadError = value }
    internal var guideRefreshDeferred: Boolean
        get() = guideSession.guideRefreshDeferred
        set(value) { guideSession.guideRefreshDeferred = value }
    internal var deferredGuideCoverageDateKey: Int?
        get() = guideSession.deferredGuideCoverageDateKey
        set(value) { guideSession.deferredGuideCoverageDateKey = value }
    @Volatile internal var recordingSummaryDirty = true
    @Volatile internal var recordingSummaryLoading = false
    @Volatile internal var recordingSummaryGeneration = 0L
    @Volatile internal var recordingSummaryUpdatedAt = 0L
    @Volatile internal var recordingSummaryCache: Pair<Int, Long> = 0 to 0L
    @Volatile internal var recordingSummaryWasRecording = false
    internal var guideSearchDialog: AlertDialog?
        get() = guideSession.guideSearchDialog
        set(value) { guideSession.guideSearchDialog = value }
    internal var guideSearchInput: EditText?
        get() = guideSession.guideSearchInput
        set(value) { guideSession.guideSearchInput = value }
    internal var guideSearchResultsContainer: LinearLayout?
        get() = guideSession.guideSearchResultsContainer
        set(value) { guideSession.guideSearchResultsContainer = value }

    // ---------------------------------------------------------------------------------------
    // Red y recuperación
    // ---------------------------------------------------------------------------------------

    @Volatile internal var networkWasLost = false
    @Volatile internal var networkLostAtElapsedMs = 0L
    @Volatile internal var networkResumeStartedAtElapsedMs = 0L
    @Volatile internal var liveWindowAdjustedForItem = false
    internal var softRecoveryAttempted = false

    /** Hay una reanudación por vuelta de red esperando el antirrebote. */
    @Volatile internal var networkResumePending = false

    /** Último avance real de posición; sirve para decidir entre reintento liviano y recarga. */
    @Volatile internal var lastProgressAtElapsedMs = 0L

    /** Último sondeo con transporte/INTERNET sin validación (bloque 1.6). */
    @Volatile internal var lastUnvalidatedProbeAtMs = 0L

    /** Último reposicionamiento liviano tras BEHIND_LIVE_WINDOW. */
    @Volatile internal var lastBehindLiveWindowLightAtMs = 0L

    /** Tarea antirrebote de la vuelta de red; se cancela al pausar o destruir. */
    internal val networkResumeTask = Runnable {
        guarded("reanudación tras vuelta de red") { executeNetworkResume() }
    }

    /** Cierra el MTTR de una reanudación de red cuando se mantiene reproduciendo 15 s. */
    internal val networkResumeStableTask = Runnable {
        if (isPlayerInitialized && player.playWhenReady && player.isPlaying) {
            SessionReport.onRecoveryStable()
        }
    }

    internal val playerReleasedForStop: Boolean get() = playbackSession.isReleasedForStop
    internal var reconnectTask: Runnable?
        get() = recoveryController.reconnectTask
        set(value) { recoveryController.reconnectTask = value }

    // ---------------------------------------------------------------------------------------
    // Tareas periódicas
    // ---------------------------------------------------------------------------------------

    /**
     * Verifica la salud de la reproducción cada [SAMPLE_INTERVAL_MS]. El `finally` garantiza que
     * el siguiente ciclo siempre se programe, y solo mientras la actividad siga viva.
     */
    @UnstableApi
    internal val watchdog = object : Runnable {
        override fun run() {
            try {
                guarded("verificación de reproducción") { checkPlaybackHealth() }
                guarded("aviso de grabación") { showRecordingNoticeIfNeeded() }
            } finally {
                if (isUiAlive) handler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }
    }

    internal val hideStatus = Runnable { guarded("ocultar estado") { runStatusTimeout() } }
    internal val retryCountdown: Runnable get() = recoveryController.retryCountdown
    internal val clockTicker = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (!isUiAlive) return
            try {
                // Si el overlay aún no está listo, se reintenta en el siguiente ciclo en vez de
                // dejar el reloj detenido para siempre.
                if (isPlayerInfoInitialized) {
                    guarded("reloj") { refreshClock() }
                    guarded("vigencia de la guía") {
                        refreshGuideIfUpcomingCostaRicaWeekMissing(costaRicaDateKey())
                    }
                }
            } finally {
                if (isUiAlive) handler.postDelayed(this, CLOCK_REFRESH_MS)
            }
        }
    }

    internal var menuOrigin: MenuDestination?
        get() = navigationController.menuOrigin
        set(value) { navigationController.menuOrigin = value }
    internal var menuReturnFocus: View?
        get() = navigationController.menuReturnFocus
        set(value) { navigationController.menuReturnFocus = value }

    internal val mainPrefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    // ---------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initializeMainActivity(savedInstanceState)
        } catch (e: Exception) {
            // Un arranque a medias deja propiedades lateinit sin inicializar; cerrar limpio
            // es más seguro que seguir y fallar más tarde en cualquier callback.
            initializationFailed = true
            Log.e(LOG_TAG, "Falló la inicialización de MainActivity", e)
            safeDiagnostic("INIT_FAILED", "Falló el arranque: ${e.message ?: e.javaClass.simpleName}")
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleStep("procesar nuevo intent") { handleMainActivityIntent(intent) }
    }


    /** Mantiene alineados los diálogos abiertos si Android cambia el tamaño útil o el modo de la pantalla. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lifecycleStep("cambio de configuración") { handleDisplayConfigurationChanged(newConfig) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        lifecycleStep("salida del usuario") { handleUserLeaveForDisplay() }
    }

    override fun onPause() {
        lifecycleStep("preparar pausa") { prepareForPauseLifecycle() }
        super.onPause()
        lifecycleStep("finalizar pausa") { finishPauseLifecycle() }
    }


    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        lifecycleStep("cambio de PiP") { handlePictureInPictureChanged(isInPictureInPictureMode) }
    }

    override fun onResume() {
        super.onResume()
        lifecycleStep("reanudar") { resumeMainActivityLifecycle() }
    }

    override fun onStop() {
        lifecycleStep("detener") { stopMainActivityLifecycle() }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        lifecycleStep("iniciar") { startMainActivityLifecycle() }
    }

    /**
     * Ante poca memoria se descartan cachés reconstruibles. Nunca se toca el reproductor ni la
     * grabación en curso.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            guarded("liberar caché por memoria") { recordingDurationCache.clear() }
            Log.w(LOG_TAG, "Memoria baja (nivel $level): caché de duraciones liberada")
        }
    }

    override fun onDestroy() {
        lifecycleStep("destruir actividad") { destroyMainActivityLifecycle() }
        releaseBackgroundWork()
        super.onDestroy()
    }

    /**
     * Red de seguridad al cerrar: cancela tareas pendientes, quita los runnables periódicos,
     * descarta diálogos abiertos y apaga los ejecutores. Todo es idempotente, así que no choca
     * con la limpieza que ya haga [destroyMainActivityLifecycle].
     */
    private fun releaseBackgroundWork() {
        guarded("cancelar actualización de guía") { guideRefreshTask?.cancel(false) }
        guarded("cancelar respaldo automático") { automaticBackupTask?.cancel(false) }
        guarded("quitar tareas periódicas") {
            handler.removeCallbacks(watchdog)
            handler.removeCallbacks(clockTicker)
            handler.removeCallbacks(hideStatus)
            handler.removeCallbacks(refreshDialogBounds)
        }
        guarded("quitar listener de pantalla") {
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        }
        guarded("cerrar diálogos") {
            dialogBounds.toList().forEach { bounds ->
                guarded("cerrar diálogo") { if (bounds.dialog.isShowing) bounds.dialog.dismiss() }
            }
            dialogBounds.clear()
            guideDialog?.let { dialog -> if (dialog.isShowing) dialog.dismiss() }
            guideSearchDialog?.let { dialog -> if (dialog.isShowing) dialog.dismiss() }
        }
        // shutdown() y no shutdownNow(): una tarea de E/S de grabación en curso no debe interrumpirse.
        listOf(guideRefreshExecutor, automaticBackupExecutor, recordingIoExecutor).forEach { executor ->
            guarded("apagar ejecutor") { executor.shutdown() }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Callbacks de ExoPlayer (una excepción aquí tumbaría toda la app, por eso van protegidos)
    // ---------------------------------------------------------------------------------------

    override fun onPlaybackStateChanged(playbackState: Int) {
        lifecycleStep("estado de reproducción") { handlePlaybackStateChanged(playbackState) }
    }

    override fun onVideoSizeChanged(size: VideoSize) {
        lifecycleStep("tamaño de video") { handleVideoSizeChanged(size) }
    }

    override fun onTracksChanged(tracks: Tracks) {
        lifecycleStep("cambio de pistas") { handleTracksChanged(tracks) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        lifecycleStep("cambio de reproducción") { handleIsPlayingChanged(isPlaying) }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (initializationFailed) return
        try {
            handlePlayerError(error)
        } catch (e: Exception) {
            // Si el manejador de errores falla, al menos queda registrado el error original.
            reportGuardedFailure("manejo de error del reproductor", e)
            Log.e(LOG_TAG, "Error original del reproductor: ${error.errorCodeName}", error)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Navegación
    // ---------------------------------------------------------------------------------------

    internal fun pushScreenBackStack(returnAction: () -> Unit) =
        navigationController.pushScreenBackStack(returnAction)

    internal fun popScreenBackStack(): Boolean = navigationController.popScreenBackStack()

    internal fun clearScreenBackStack() = navigationController.clearScreenBackStack()

    override fun runNavigationAction(action: () -> Unit) {
        guarded("retorno de pantalla", action)
    }

    // ---------------------------------------------------------------------------------------
    // Teclas del control remoto
    // ---------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: Exception) {
            reportGuardedFailure("tecla ${event.keyCode}", e)
            true
        }
    }

    @UnstableApi
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (initializationFailed) return super.onKeyDown(keyCode, event)
        return try {
            handleKeyDown(keyCode, event)
        } catch (e: Exception) {
            reportGuardedFailure("onKeyDown $keyCode", e)
            // Atrás se deja pasar al sistema para que el usuario nunca quede atrapado.
            keyCode != KeyEvent.KEYCODE_BACK
        }
    }

    @UnstableApi
    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Los diálogos modales controlan la navegación y el OK; el mapa de teclas del
        // reproductor no debe consumir esas teclas mientras haya uno en pantalla.
        if (hasVisibleTvDialog() && (isDpadDirection(keyCode) || isSelectKey(keyCode))) {
            return super.onKeyDown(keyCode, event)
        }
        if (isDpadDirection(keyCode) && hasFocusableStatusControls()) {
            return super.onKeyDown(keyCode, event)
        }
        return when {
            keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS -> { showOptions(); true }
            isSelectKey(keyCode) -> {
                if (event.repeatCount == 0) { longOkHandled = false; event.startTracking() }
                true
            }
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (event.repeatCount == 0) togglePlayback(); true }
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> { if (event.repeatCount == 0) playFromRemote(); true }
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (event.repeatCount == 0) pauseFromRemote(); true }
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1
                when (keymap(KEY_UP_DOWN, "info")) {
                    "volume" -> adjustVolume(delta)
                    "info" -> showDiagnostics()
                    else -> Unit // Valor desconocido en preferencias: se ignora sin fallar.
                }
                true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                when (keymap(KEY_LEFT_RIGHT, "none")) {
                    "volume" -> adjustVolume(if (forward) 1 else -1)
                    "sync" -> adjustSync(if (forward) SYNC_STEP_MS else -SYNC_STEP_MS)
                    "seek" -> seekInLive(forward)
                    else -> Unit
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (initializationFailed || !isSelectKey(keyCode)) return super.onKeyLongPress(keyCode, event)
        longOkHandled = true
        guarded("pulsación larga de OK") {
            when (keymap(KEY_LONG_OK, "menu")) {
                "menu" -> showOptions()
                "info" -> showDiagnostics()
                else -> Unit
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (initializationFailed || !isSelectKey(keyCode) || hasVisibleTvDialog()) {
            return super.onKeyUp(keyCode, event)
        }
        // Una pulsación cancelada (por ejemplo, al perder el foco) no debe ejecutar la acción.
        if (event.isCanceled) {
            longOkHandled = false
            return true
        }
        try {
            if (!longOkHandled) {
                when (keymap(KEY_OK, "pause")) {
                    "menu" -> showOptions()
                    "info" -> showDiagnostics()
                    else -> togglePlayback()
                }
            }
        } catch (e: Exception) {
            reportGuardedFailure("soltar OK", e)
        } finally {
            longOkHandled = false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Utilidades de ejecución segura
    // ---------------------------------------------------------------------------------------

    /** Ejecuta [block] y registra cualquier excepción sin propagarla. */
    internal fun guarded(task: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            reportGuardedFailure(task, e)
        }
    }

    /** Como [guarded], pero devuelve [fallback] si [block] falla. */
    internal fun <T> guardedOr(task: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            reportGuardedFailure(task, e)
            fallback
        }
    }

    /** Programa [block] en el hilo principal solo si la actividad sigue viva al ejecutarse. */
    internal fun postGuarded(task: String, delayMs: Long = 0L, block: () -> Unit): Runnable {
        val runnable = Runnable { if (isUiAlive) guarded(task, block) }
        handler.postDelayed(runnable, delayMs)
        return runnable
    }

    /** Paso del ciclo de vida: se omite si el arranque falló, y nunca propaga excepciones. */
    private fun lifecycleStep(task: String, block: () -> Unit) {
        if (initializationFailed) return
        guarded(task, block)
    }

    private fun reportGuardedFailure(task: String, error: Exception) {
        val count = guardFailureCounts.merge(task, 1) { previous, one -> previous + one } ?: 1
        // Los primeros fallos se registran siempre; los repetidos, de forma espaciada.
        if (count > GUARD_LOG_BURST && count % GUARD_LOG_EVERY != 0) return
        Log.e(LOG_TAG, "Fallo en $task (#$count)", error)
        safeDiagnostic("GUARDED_FAILURE", "$task (#$count): ${error.message ?: error.javaClass.simpleName}")
    }

    /** El registro de diagnóstico nunca debe ser causa de un nuevo fallo. */
    private fun safeDiagnostic(tag: String, message: String) {
        try {
            DiagnosticTracker.log(tag, message)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No se pudo registrar el diagnóstico $tag", e)
        }
    }

    companion object {
        internal const val LOG_TAG = "MainActivity"
        internal const val GUARD_LOG_BURST = 3
        internal const val GUARD_LOG_EVERY = 30
        internal val RECORDING_EXTENSIONS = setOf("ts", "mp4")
        internal const val RECORDING_SUMMARY_REFRESH_MS = 10_000L
        internal const val PREFS = "${BuildConfig.BRAND_KEY}_player"
        internal const val KEY_USER_AGENT = "stream_user_agent"
        internal const val KEY_AFR = "auto_frame_rate"
        internal const val KEY_PIP_ON_HOME = "pip_on_home"
        internal const val KEY_AUTOSTART_BOOT = "autostart_on_boot"
        internal const val KEY_AUTOSTART_WAKE = "autostart_on_wake"
        internal const val KEY_EXTERNAL_ALWAYS = "external_player_always"
        internal const val KEY_CONFIRM_EXIT = "confirm_exit"
        internal const val KEY_EPG_URL = "epg_url"
        internal const val KEY_STREAM_URL = "stream_url"
        internal const val KEY_STREAM_HEADERS = "stream_headers"
        internal const val KEY_EPG_CACHE = "epg_cache"
        internal const val KEY_MY_LIST = "saved_programs"
        internal const val KEY_EPG_UPDATED = "epg_updated"
        internal const val KEY_EPG_INTERVAL_HOURS = "epg_interval_hours"
        internal const val KEY_EPG_PAST_DAYS = "epg_past_days"
        internal const val KEY_REMINDER_LEAD = "reminder_lead_minutes"
        internal const val KEY_RECORDING_START_LEAD = "recording_start_lead_minutes"
        internal const val KEY_RECORDING_END_LEAD = "recording_end_lead_minutes"
        internal const val KEY_AUTO_BACKUP = "auto_backup"
        internal const val DAY_MS = 24 * 60 * 60 * 1_000L
        internal const val EXIT_CONFIRM_WINDOW_MS = 2_000L
        internal const val AFR_OFF = "Off"
        internal const val AFR_ON = "On"
        internal const val AFR_RESTORE = "On (also switch refresh rate on stop)"
        internal const val REQUEST_SETTINGS_FILE = 7412
        internal const val REQUEST_BACKUP_STORAGE = 7413
        internal const val REQUEST_NOTIFICATIONS = 7416
        internal const val KEY_NOTIFICATIONS_ASKED = "notifications_permission_asked"
        internal const val REQUEST_RECORDING_TREE = 7414
        internal const val REQUEST_GUIDE_VOICE = 7415
        internal const val KEY_RESIZE = "resize_mode"
        internal const val KEY_AUDIO_OFFSET = "audio_offset_ms"
        internal const val KEY_AUDIO_TRACK = "audio_track_signature"
        internal const val KEY_PASSTHROUGH = "audio_passthrough"
        internal const val KEY_BUFFER = "buffer_profile"
        internal const val KEY_METERED_RX_BYTES = "metered_network_rx_bytes"
        internal const val KEY_DIAGNOSTIC_HUD = "diagnostic_hud_enabled"
        internal const val KEY_AUDIO_DECODER = "audio_decoder"
        internal const val KEY_VIDEO_DECODER = "video_decoder"
        internal const val KEY_SURROUND = "surround_by_default"
        internal const val KEY_MUTED = "muted"
        internal const val KEY_PANEL_TIMEOUT = "panel_timeout_sec"
        internal const val KEY_PANEL_TRANSPARENCY = "panel_transparency"
        internal const val KEY_PANEL_TEXT_SIZE = "panel_text_size_sp"
        internal const val KEY_SHOW_CLOCK = "show_clock"
        internal const val KEY_CLOCK_FORMAT = "clock_format"
        internal const val KEY_RECORDING = "recording_active"
        internal const val KEY_RECORDING_FILE = "recording_file"
        internal const val KEY_RECORDING_TREE = "recording_tree_uri"
        internal const val KEY_RECORDING_NAME = "recording_name"
        internal const val KEY_RECORDING_ERROR = "recording_error"
        internal const val KEY_SKIP_STEPS = "skip_steps"
        internal const val KEY_OK = "remote_ok"
        internal const val KEY_UP_DOWN = "remote_up_down"
        internal const val KEY_LEFT_RIGHT = "remote_left_right"
        internal const val KEY_LONG_OK = "remote_long_ok"
        internal val KEYMAP_KEYS = arrayOf(KEY_OK, KEY_UP_DOWN, KEY_LEFT_RIGHT, KEY_LONG_OK)
        internal const val SAMPLE_INTERVAL_MS = 2_000L
        internal const val RECORDING_PROGRESS_INTERVAL_MS = 15_000L
        internal const val FROZEN_LIMIT = 5
        internal const val BUFFERING_LIMIT = 15
        internal const val MAX_RETRIES = 6
        internal const val RETRY_STABLE_PLAYBACK_MS = 15_000L

        internal const val CLOCK_FORMAT_12H = "12-hour"
        internal const val CLOCK_FORMAT_24H = "24-hour"
        internal const val CLOCK_REFRESH_MS = 15_000L
        internal const val EXTERNAL_PLAYER_DELAY_MS = 500L
        internal const val DISPLAY_CHANGE_DEBOUNCE_MS = 250L
        internal const val SYNC_STEP_MS = 50L
        internal const val HOUR_MS = 3_600_000L
        internal const val DEFAULT_EPG_INTERVAL_HOURS = 6
        internal const val SUBTITLE_TEXT_FRACTION = 0.06f
        internal const val DIALOG_DIM_AMOUNT = 0.34f
        internal const val DIALOG_BLUR_RADIUS_DP = 20
        internal const val DEEP_LINK_SCHEME = BuildConfig.DEEP_LINK_SCHEME
        internal const val DEEP_LINK_HOST_LIVE = "live"
        internal const val EXTRA_DEBUG_PLAYER_STATUS = "debug_player_status"
        internal const val EXTRA_DEBUG_PLAYER_STATUS_KIND = "debug_player_status_kind"
    }
}

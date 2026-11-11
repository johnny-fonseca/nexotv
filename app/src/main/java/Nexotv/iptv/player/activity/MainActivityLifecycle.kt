// Archivo: Nexotv/iptv/player/activity/MainActivityLifecycle.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.activity

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.guide.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.MainActivity.Companion.EXTERNAL_PLAYER_DELAY_MS
import Nexotv.iptv.player.MainActivity.Companion.KEY_EXTERNAL_ALWAYS
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

private const val LIFECYCLE_LOG_TAG = "MainActivityLifecycle"

@OptIn(UnstableApi::class)
internal fun MainActivity.initializeMainActivity(savedInstanceState: Bundle?) {
    onBackPressedDispatcher.addCallback(this, createBackPressedCallback())
    guarded("configuración de ventana") { configureWindow() }
    setContentView(buildContentView())

    // Precarga en segundo plano: si el executor está cerrado o la precarga falla, se registra y se continúa
    try {
        recordingIoExecutor.execute {
            try {
                guideProgramStore.preloadSavedPrograms()
                ProgramSchedule.preloadUiState(applicationContext)
            } catch (e: Exception) {
                Log.w(LIFECYCLE_LOG_TAG, "Falló la precarga de guía/programación", e)
            }
        }
    } catch (e: Exception) {
        Log.w(LIFECYCLE_LOG_TAG, "Executor de grabaciones no disponible", e)
    }

    guarded("panel de diagnóstico") { setupDiagnosticHud() }
    registerDisplayListenerSafely()
    guarded("reloj") { handler.post(clockTicker) }
    guarded("callback de red") { registerNetworkCallback() }
    guarded("monitor de red medida") { meteredNetworkUsageController.start() }
    guarded("compensación de audio") { restoreAudioOffset() }
    guarded("guía en caché") { loadCachedGuide() }
    guarded("canal de la pantalla de inicio") { HomeScreenChannel.refreshCurrentProgram(this) }
    guarded("respaldo automático") { scheduleAutomaticBackup() }

    val sesionLista = try {
        playbackSession.createAndAttach()
    } catch (e: Exception) {
        Log.e(LIFECYCLE_LOG_TAG, "createAndAttach() lanzó una excepción", e)
        false
    }
    if (!sesionLista) {
        Log.w(LIFECYCLE_LOG_TAG, "createAndAttach() no tuvo éxito; se aborta la inicialización")
        return
    }

    val debugStatusRequested = try {
        renderDebugStatusIfRequested()
    } catch (e: Exception) {
        Log.w(LIFECYCLE_LOG_TAG, "Falló el estado de depuración", e)
        false
    }
    // Si el arranque falla, el watchdog se encarga de recuperar la reproducción
    if (!debugStatusRequested) guarded("inicio de transmisión") { startStream() }

    guarded("intent de grabación") { intent?.let { handleHomeRecordingIntent(it) } }

    if (mainPrefs.safeBoolean(KEY_EXTERNAL_ALWAYS, default = false)) {
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) guarded("reproductor externo") { openExternalPlayer() }
        }, EXTERNAL_PLAYER_DELAY_MS)
    }

    // Un fallo al refrescar la guía no debe impedir que arranque el watchdog
    if (shouldRefreshGuideSafely()) {
        guarded("actualización de guía") { refreshGuide(silent = true, deferWhilePlaying = true) }
    }
    handler.post(watchdog)
}

internal fun MainActivity.createBackPressedCallback(): OnBackPressedCallback =
    object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isFinishing || isDestroyed) return
            guarded("tecla atrás") { handleBackKey() }
        }
    }

internal fun MainActivity.prepareForPauseLifecycle() =
    guarded("preparar pausa") { preparePlayerForPause() }

internal fun MainActivity.finishPauseLifecycle() =
    guarded("finalizar pausa") { finishPlayerPause() }

internal fun MainActivity.resumeMainActivityLifecycle() {
    // Los pasos son independientes: si uno falla, los demás deben ejecutarse igual
    guarded("alarmas exactas") { syncExactAlarmState() }
    guarded("reanudación del reproductor") { handlePlayerResume() }
    guarded("parámetros de PiP") { updatePipParams() }
}

internal fun MainActivity.startMainActivityLifecycle() {
    if (playerReleasedForStop && !isFinishing && !isDestroyed) {
        guarded("restauración tras detención") { playbackSession.restoreAfterStop() }
    }
    if (!playerReleasedForStop && isPlayerInitialized) {
        guarded("reinicio de temporizadores") {
            handler.removeCallbacks(watchdog)
            handler.post(watchdog)
            handler.removeCallbacks(clockTicker)
            handler.post(clockTicker)
        }
        guarded("panel de diagnóstico") { setupDiagnosticHud() }
        val grabando = try {
            recordingIndicator != null && (isRecording() || debugRecordingStartedAt != null)
        } catch (_: Exception) {
            false
        }
        if (grabando) guarded("indicador de grabación") { scheduleRecordingStatusTicker() }
    }
}

internal fun MainActivity.stopMainActivityLifecycle() {
    if (!isInPipMode) guarded("liberación al detener") { playbackSession.releaseForStop() }
}

/**
 * Cada paso de limpieza va aislado: si uno falla, los siguientes se ejecutan igual.
 * Se conserva el orden original.
 */
internal fun MainActivity.destroyMainActivityLifecycle() {
    guarded("cancelar actualización de guía") { cancelGuideRefresh() }
    guarded("monitor de red medida") { meteredNetworkUsageController.stop() }
    guarded("tarea de respaldo automático") {
        automaticBackupTask?.cancel(true)
        automaticBackupTask = null
    }
    guarded("executor de respaldo") { automaticBackupExecutor.shutdownNow() }
    guarded("executor de grabaciones") { recordingIoExecutor.shutdownNow() }
    guarded("sesión de reproducción") { playbackSession.destroy() }

    guarded("HUD de diagnóstico") { playbackDiagnostics.clearHud(handler) }
    guarded("tareas de recuperación") { recoveryController.clearScheduledTasks() }
    guarded("overlays") { overlayController.clear() }
    guarded("navegación") { navigationController.release() }
    unregisterDisplayListenerSafely()
    guarded("callbacks del handler") { handler.removeCallbacksAndMessages(null) }
    guarded("límites de diálogos") { dialogBounds.clear() }
    guarded("pila de pantallas") { clearScreenBackStack() }
}

private fun MainActivity.shouldRefreshGuideSafely(): Boolean =
    try {
        shouldRefreshGuide()
    } catch (e: Exception) {
        Log.w(LIFECYCLE_LOG_TAG, "No se pudo evaluar si la guía requiere actualización", e)
        false
    }

private fun MainActivity.registerDisplayListenerSafely() {
    guarded("registro del listener de pantalla") {
        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, handler)
    }
}

private fun MainActivity.unregisterDisplayListenerSafely() {
    guarded("desregistro del listener de pantalla") {
        getSystemService(DisplayManager::class.java)
            ?.unregisterDisplayListener(displayListener)
    }
}
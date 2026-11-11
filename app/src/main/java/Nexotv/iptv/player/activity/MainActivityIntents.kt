// Archivo: Nexotv/iptv/player/activity/MainActivityIntentHandler.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*


import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.media3.common.util.UnstableApi
import Nexotv.iptv.player.MainActivity.Companion.DEEP_LINK_HOST_LIVE
import Nexotv.iptv.player.MainActivity.Companion.DEEP_LINK_SCHEME
import Nexotv.iptv.player.MainActivity.Companion.EXTRA_DEBUG_PLAYER_STATUS
import Nexotv.iptv.player.MainActivity.Companion.EXTRA_DEBUG_PLAYER_STATUS_KIND
import Nexotv.iptv.player.MainActivity.Companion.REQUEST_GUIDE_VOICE
import Nexotv.iptv.player.MainActivity.Companion.REQUEST_RECORDING_TREE
import Nexotv.iptv.player.MainActivity.Companion.REQUEST_SETTINGS_FILE
import Nexotv.iptv.player.guide.onGuideVoiceResult
import Nexotv.iptv.player.player.startStream
import Nexotv.iptv.player.status.PlayerStatus
import Nexotv.iptv.player.status.openConnectionSettings
import Nexotv.iptv.player.status.renderPlayerStatus
import Nexotv.iptv.player.status.retryFromStatus
import Nexotv.iptv.player.status.showStatus
import Nexotv.iptv.player.status.updateRecordingIndicator

internal fun MainActivity.renderDebugStatusIfRequested(): Boolean {
    if (!BuildConfig.DEBUG) return false
    val solicitado = try {
        intent?.getBooleanExtra(EXTRA_DEBUG_PLAYER_STATUS, false) == true
    } catch (_: Exception) {
        false
    }
    if (!solicitado) return false
    if (isFinishing || isDestroyed) return true

    val tipo = try {
        intent?.getStringExtra(EXTRA_DEBUG_PLAYER_STATUS_KIND)
    } catch (_: Exception) {
        null
    }

    ejecutarProtegido {
        when (tipo) {
            "offline" -> renderPlayerStatus(PlayerStatus.Offline({ retryFromStatus() }, { openConnectionSettings() }))
            "signal" -> renderPlayerStatus(PlayerStatus.NoSignal({ retryFromStatus() }, { openConnectionSettings() }))
            "space" -> renderPlayerStatus(PlayerStatus.NoSpace(::showRecordingLibrary))
            "connecting" -> renderPlayerStatus(PlayerStatus.Connecting)
            "reconnecting" -> renderPlayerStatus(PlayerStatus.Reconnecting)
            "recording" -> {
                debugRecordingStartedAt = System.currentTimeMillis()
                updateRecordingIndicator()
            }
            else -> Unit
        }
    }
    return true
}

internal fun MainActivity.isLiveDeepLink(intent: Intent): Boolean {
    return try {
        val data = intent.data ?: return false
        data.scheme == DEEP_LINK_SCHEME && data.host == DEEP_LINK_HOST_LIVE
    } catch (_: Exception) {
        false
    }
}

@UnstableApi
internal fun MainActivity.handleMainActivityIntent(intent: Intent) {
    if (isFinishing || isDestroyed) return

    val manejado = try {
        handleHomeRecordingIntent(intent)
    } catch (_: Exception) {
        false
    }
    if (manejado) return
    if (!isLiveDeepLink(intent)) return

    ejecutarProtegido {
        if (playerReleasedForStop) {
            playbackSession.discardStoppedPlayback()
            recordingPlayback = false
            resumeAfterLifecycle = true
            showStatus("Volviendo a ${BuildConfig.BRAND_NAME} en vivo…")
            return@ejecutarProtegido
        }
        if (isPlayerInitialized && (recordingPlayback || !player.isPlaying)) {
            startStream()
            showStatus("Volviendo a ${BuildConfig.BRAND_NAME} en vivo…")
        }
    }
}

@Suppress("DEPRECATION")
internal fun MainActivity.handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (resultCode != RESULT_OK) return
    if (isFinishing || isDestroyed) return

    ejecutarProtegido {
        when (requestCode) {
            REQUEST_RECORDING_TREE -> {
                if (data?.data != null) acceptUsbFolder(data)
            }
            REQUEST_GUIDE_VOICE -> {
                val texto = data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull { !it.isNullOrBlank() }
                if (texto != null) onGuideVoiceResult(texto)
            }
            REQUEST_SETTINGS_FILE -> data?.data?.let(settingsTransferController::importFrom)
            else -> Unit
        }
    }
}

private inline fun ejecutarProtegido(bloque: () -> Unit) {
    try {
        bloque()
    } catch (_: Exception) {
        // Un intent o resultado inválido nunca debe cerrar la app
    }
}
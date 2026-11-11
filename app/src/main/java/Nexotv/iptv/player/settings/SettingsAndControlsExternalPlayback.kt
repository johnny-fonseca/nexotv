@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings

import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Rect
import android.util.Rational
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi

// ---------- Picture in Picture ----------

internal fun MainActivity.togglePipOnHome() {
    val enabled = flipBoolean(MainActivity.KEY_PIP_ON_HOME, false)
    updatePipParams()
    flashStatus("Ventana pequeña ${if (enabled) "activada" else "desactivada"}")
}

private fun MainActivity.pipAspectRatio(): Rational {
    val size = player.videoSize
    val width = (size.width * size.pixelWidthHeightRatio).toInt()
    val height = size.height
    if (width <= 0 || height <= 0) return Rational(16, 9)
    val ratio = width.toFloat() / height
    return when {
        ratio > 2.39f -> Rational(239, 100)
        ratio < 1f / 2.39f -> Rational(100, 239)
        else -> Rational(width, height)
    }
}

internal fun MainActivity.updatePipParams() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (isFinishing || isDestroyed || !isPlayerInitialized) return

    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(pipAspectRatio())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val sourceRect = Rect()
        if (playerView.getGlobalVisibleRect(sourceRect) && !sourceRect.isEmpty) {
            builder.setSourceRectHint(sourceRect)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val pipOnHome = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .safeBoolean(MainActivity.KEY_PIP_ON_HOME, false)
        builder.setAutoEnterEnabled(pipOnHome)
    }
    runCatching { setPictureInPictureParams(builder.build()) }
}

internal fun MainActivity.enterPip() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        showTvToast("Picture in Picture requiere Android 8 o posterior")
        return
    }
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        showTvToast("Picture in Picture no está disponible")
        return
    }
    if (isFinishing || isDestroyed) return
    runCatching {
        updatePipParams()
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio()).build())
    }.onFailure { showTvToast("Picture in Picture no está disponible") }
}

// ---------- Reproductor externo ----------

internal fun MainActivity.openExternalPlayer() {
    val streamUrl = currentStreamUrl()
    if (streamUrl.isBlank()) {
        showTvToast("No hay una señal configurada", long = true)
        return
    }
    if (!isValidHttpUrl(streamUrl)) {
        showTvToast("La señal configurada no es una URL HTTP o HTTPS válida", long = true)
        return
    }
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(streamUrl.toUri(), "application/vnd.apple.mpegurl")
    val wasPlaying = player.playWhenReady
    try {
        externalHandoff = true
        resumeAfterLifecycle = false
        player.pause()
        startActivity(Intent.createChooser(view, "Reproducir ${BuildConfig.BRAND_NAME} con"))
    } catch (_: Exception) {
        externalHandoff = false
        if (wasPlaying) player.play()
        showTvToast("No hay otro reproductor disponible", long = true)
    }
}

internal fun MainActivity.externalAlways(): Boolean =
    appPreferences().safeBoolean(MainActivity.KEY_EXTERNAL_ALWAYS, false)

@UnstableApi
internal fun MainActivity.showExternalPlayerSetting() {
    val choices = listOf(
        "Off · reproducir dentro de ${BuildConfig.BRAND_NAME}" to "",
        "Always · abrir fuera al iniciar" to "",
    )
    showStyledChoiceDialog(
        "Reproductor externo", "REPRODUCCIÓN",
        choices, if (externalAlways()) 1 else 0,
    ) { which ->
        val enabled = which == 1
        appPreferences().edit { putBoolean(MainActivity.KEY_EXTERNAL_ALWAYS, enabled) }
        if (enabled) {
            openExternalPlayer()
        } else {
            externalHandoff = false
            startStream()
            showStatus("Reproducción interna activada")
        }
    }
}



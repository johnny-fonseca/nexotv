@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings




import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.guide.*
import android.app.AlertDialog
import android.content.SharedPreferences
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

// ---------- Menú «Más ajustes» ----------

private fun epgSourceLabel(epgUrl: String?): String {
    val defaultUrl = XmlTvGuide.DEFAULT_URL
    if (defaultUrl.isBlank() && epgUrl.isNullOrBlank()) return "Sin fuente configurada"
    val defaultHost = runCatching { defaultUrl.toUri().host }.getOrNull()
    val configuredHost = epgUrl?.let { runCatching { it.toUri().host }.getOrNull() }
    if (defaultUrl.isNotBlank() && (epgUrl == defaultUrl || configuredHost == defaultHost)) {
        return "Oficial · $defaultHost"
    }
    val host = epgUrl?.let { runCatching { it.toUri().host }.getOrNull() } ?: "personalizado"
    return "XMLTV · $host"
}

private fun MainActivity.streamSourceSummary(prefs: SharedPreferences): String = buildString {
    append(
        prefs.safeString(MainActivity.KEY_STREAM_URL, DEFAULT_STREAM_URL)
            ?.let { runCatching { it.toUri().host }.getOrNull() } ?: "Predeterminada",
    )
    val headerCount = currentStreamHeaders().size
    if (headerCount > 0) append(" · $headerCount encabezados")
}

internal fun MainActivity.showMoreSettings() {
    val prefs = appPreferences()
    var moreSettingsDialog: AlertDialog? = null
    val groupNames = arrayOf("Guía y recordatorios", "Imagen y sonido", "Mi aplicación", "Ayuda")

    val state = readMoreSettingsState(prefs)
    val recommended = buildRecommendedSettings(state)

    val advancedItems = buildAdvancedSettings(state)

    MoreSettingsDialog(
        context = this,
        groupNames = groupNames.toList(),
        recommended = recommended,
        advancedItems = advancedItems,
        createFocusCard = { title, description, action -> focusCard(title, description, action) },
        iconFor = { cardIcon(it) },
        onDialogReady = { dialog, page, firstGroup ->
            moreSettingsDialog = dialog
            setTvDialogBounds(dialog, 1f, 1f)
            dialog.setOnCancelListener {
                if (!popScreenBackStack()) {
                    showMainMenu(MainActivity.MenuDestination.MORE) {}
                }
            }
            dialog.setOnShowListener {
                dialog.window?.let { applyWindowBlur(it, dp(22)) }
                animateDialogEntrance(dialog)
                page.post {
                    firstGroup.requestFocus()
                    focusFirstWhenReady(page, preferred = firstGroup)
                }
            }
        },
        prepareEntryAction = { label, action ->
            if (label == "Guía de TV" || label == "Programaciones") {
                {
                    moreSettingsDialog?.dismiss()
                    pushScreenBackStack { showMoreSettings() }
                    action()
                }
            } else action
        },
    ).show()
}


private typealias MoreSettingItem = Pair<Pair<String, String>, () -> Unit>

private data class MoreSettingsState(
    val prefs: SharedPreferences,
    val epgUrl: String?,
    val reminderLead: Int,
    val recordingStartLead: Int,
    val recordingEndLead: Int,
    val videoDecoder: String,
    val audioDecoder: String,
    val skipSteps: String,
    val diagnosticHud: Boolean,
    val pipOnHome: Boolean,
    val subtitlesLabel: String,
)

private fun MainActivity.readMoreSettingsState(prefs: SharedPreferences): MoreSettingsState {
    val player = player
    return MoreSettingsState(
        prefs = prefs,
        epgUrl = prefs.safeString(MainActivity.KEY_EPG_URL, XmlTvGuide.DEFAULT_URL),
        reminderLead = prefs.safeInt(MainActivity.KEY_REMINDER_LEAD, 5),
        recordingStartLead = prefs.safeInt(MainActivity.KEY_RECORDING_START_LEAD, 0),
        recordingEndLead = prefs.safeInt(MainActivity.KEY_RECORDING_END_LEAD, 0),
        videoDecoder = prefs.safeString(MainActivity.KEY_VIDEO_DECODER, "Hardware") ?: "Hardware",
        audioDecoder = prefs.safeString(MainActivity.KEY_AUDIO_DECODER, "Hardware") ?: "Hardware",
        skipSteps = prefs.safeString(MainActivity.KEY_SKIP_STEPS, DEFAULT_SKIP_PRESET) ?: DEFAULT_SKIP_PRESET,
        diagnosticHud = prefs.safeBoolean(MainActivity.KEY_DIAGNOSTIC_HUD, false),
        pipOnHome = prefs.safeBoolean(MainActivity.KEY_PIP_ON_HOME, false),
        subtitlesLabel = if (C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) "Desactivados" else "Selección automática",
    )
}

private fun MainActivity.buildRecommendedSettings(state: MoreSettingsState): Map<String, List<MoreSettingItem>> = linkedMapOf(
        "Guía y recordatorios" to recommendedGuide(state),
        "Imagen y sonido" to recommendedVideoAudio(state),
        "Mi aplicación" to recommendedApp(state),
        "Ayuda" to recommendedHelp(state)
)

private fun MainActivity.recommendedGuide(state: MoreSettingsState): List<MoreSettingItem> = listOf(
            "Actualizar guía" to "Actualiza los horarios y programas disponibles." to { refreshGuide() },
            "Fuente de guía" to epgSourceLabel(state.epgUrl) to { showEpgSourceEditor() },
            "Guía de TV" to (if (state.prefs.safeString(MainActivity.KEY_EPG_CACHE, null).isNullOrBlank()) {
                "Sin datos descargados"
            } else {
                "Programación disponible"
            }) to { showGuide() },
            "Avisos y grabaciones" to "Aviso ${state.reminderLead} min antes · grabación ${state.recordingStartLead} min antes / ${state.recordingEndLead} después" to { showGuideSettings() },
            "Programaciones" to "Revisa avisos y grabaciones futuras." to { showProgramSchedules() },
        )

private fun MainActivity.recommendedVideoAudio(state: MoreSettingsState): List<MoreSettingItem> = listOf(
            "Imagen estable" to "Buffer ${bufferProfileLabel(state.prefs)}" to { showBufferPicker() },
            "Video compatible" to "Video ${state.videoDecoder}" to { showDecoderPicker() },
            "Fluidez de video" to frameRateDisplayLabel() to { showFrameRatePicker() },
            "Tamaño de imagen" to aspectModeLabel() to { showAspectPicker() },
            "Audio y subtítulos" to "Elige el idioma de audio o subtítulos." to { showTrackPicker(C.TRACK_TYPE_AUDIO) },
            "Subtítulos" to state.subtitlesLabel to { showTrackPicker(C.TRACK_TYPE_TEXT) },
            "Sincronizar audio" to "${state.prefs.safeLong(MainActivity.KEY_AUDIO_OFFSET, 0L)} ms" to { showAudioSyncPicker() },
            "Audio envolvente" to (if (state.prefs.safeBoolean(MainActivity.KEY_SURROUND, false)) "Activado" else "Automático") to { toggleSurroundPreference() },
            (if (player.volume == 0f) "Activar sonido" else "Silenciar") to "Cambia el sonido de la reproducción." to { toggleMute() },
            "Enviar a reproductor" to "Envía la señal a un reproductor compatible." to { openExternalPlayer() },
            "Saltos en vivo" to state.skipSteps to { showSkipPicker() },
        )

private fun MainActivity.recommendedApp(state: MoreSettingsState): List<MoreSettingItem> = listOf(
            "Destino de grabación" to (if (state.prefs.safeString(MainActivity.KEY_RECORDING_TREE, null) == null) {
                "Almacenamiento del dispositivo"
            } else {
                "Carpeta USB seleccionada"
            }) to { showRecordingDestination() },
            "Ventana pequeña" to (if (state.pipOnHome) "Activado" else "Desactivado") to { togglePipOnHome() },
            "Abrir ventana PiP" to "Inicia Picture in Picture." to { enterPip() },
            "Abrir con otra aplicación" to (if (externalAlways()) "Siempre" else "Cuando lo elijas") to { showExternalPlayerSetting() },
            "Copia de ajustes" to "Exporta o recupera tus preferencias." to { showSettingsBackup() },
            "Inicio automático" to "Configura cuándo abrir ${BuildConfig.BRAND_NAME}." to { showAutoStartSettings() },
            "Fila de Android TV" to "Añade ${BuildConfig.BRAND_NAME} a la pantalla de inicio." to { HomeScreenChannel.request(this) },
            "Eliminar fila de Android TV" to "Quita ${BuildConfig.BRAND_NAME} de la pantalla de inicio." to { HomeScreenChannel.remove(this) },
            "Apariencia" to "Tamaño del texto y aspecto del panel." to { showAppearanceSettings() },
        )

private fun MainActivity.recommendedHelp(state: MoreSettingsState): List<MoreSettingItem> = listOf(
            "Estado de reproducción" to "Estado actual del video y el audio." to { showStatus(channelDetails()); hideStatusAfterDelay() },
            "Diagnóstico en pantalla" to (if (state.diagnosticHud) "• Activado · muestra buffer, red y errores en vivo" else "Desactivado · no ocupa recursos") to { toggleDiagnosticHud() },
            "Registro de red" to "Ver y copiar cortes, rebuffers y errores de los últimos minutos." to { showDiagnosticLogDialog() },
            "Exportar informe" to "Comparte información para recibir ayuda." to { exportDiagnostics() },
        )

private fun MainActivity.buildAdvancedSettings(state: MoreSettingsState): Map<String, List<MoreSettingItem>> = linkedMapOf(
        "Guía y recordatorios" to advancedGuide(state),
        "Imagen y sonido" to advancedVideoAudio(state),
        "Mi aplicación" to advancedApp(state),
        "Ayuda" to advancedHelp(state)
)

private fun MainActivity.advancedGuide(state: MoreSettingsState): List<MoreSettingItem> = listOf(
            "Fuente de la guía" to "URL XMLTV completa" to { showEpgSourceEditor() },
            "Ritmo de guía" to "Cada ${state.prefs.safeInt(MainActivity.KEY_EPG_INTERVAL_HOURS, 6)} horas" to { showGuideSettings() },
            "Días anteriores" to "${state.prefs.safeInt(MainActivity.KEY_EPG_PAST_DAYS, 7)} días" to { showGuideSettings() },
            "Aviso previo" to "${state.reminderLead} min" to { showGuideSettings() },
            "Margen al grabar" to "${state.recordingStartLead} min antes · ${state.recordingEndLead} min después" to { showGuideSettings() },
        )

private fun MainActivity.advancedVideoAudio(state: MoreSettingsState): List<MoreSettingItem> = listOf(
            "Fuente de TV" to streamSourceSummary(state.prefs) to { showStreamSourceEditor() },
            "Calidad y pistas" to "Selecciona pista de video o audio." to { showTrackPicker(C.TRACK_TYPE_VIDEO) },
            "Buffer de reproducción" to bufferProfileShortLabel(state.prefs) to { showBufferPicker() },
            "Decodificadores" to "Audio ${state.audioDecoder}; video ${state.videoDecoder}" to { showDecoderPicker() },
            "Restablecer imagen" to "Restablece el tamaño de imagen." to { resetAspectRatio() },
            "Audio envolvente avanzado" to (if (state.prefs.safeBoolean(MainActivity.KEY_PASSTHROUGH, false)) "Passthrough activado" else "Passthrough desactivado") to { togglePassthrough() },
            "Silenciar audio" to (if (player.volume == 0f) "Silenciado" else "Con sonido") to { toggleMute() },
            "Saltos de reproducción" to state.skipSteps to { showSkipPicker() },
            "Reintentar señal" to "Vuelve a intentar la reproducción." to { retryNow() },
            "Retroceder" to "Usa el salto configurado." to { seekByConfiguredStep(false) },
            "Avanzar" to "Usa el salto configurado." to { seekByConfiguredStep(true) },
            "User-Agent del stream" to (if (usesBuiltInStream() || state.prefs.safeString(MainActivity.KEY_USER_AGENT, "").isNullOrBlank()) "Predeterminado" else "Personalizado") to { showUserAgentEditor() },
        )

private fun MainActivity.advancedApp(state: MoreSettingsState): List<MoreSettingItem> = listOf(
            "Reproductor externo" to (if (externalAlways()) "Preferido siempre" else "Preguntar") to { showExternalPlayerSetting() },
            "Frecuencia automática" to frameRateDisplayLabel() to { showFrameRatePicker() },
            "Respaldo automático" to (if (state.prefs.safeBoolean(MainActivity.KEY_AUTO_BACKUP, true)) "Activado" else "Desactivado") to { toggleAutoBackup() },
            "Confirmar al salir" to (if (state.prefs.safeBoolean(MainActivity.KEY_CONFIRM_EXIT, true)) "Activado" else "Desactivado") to { toggleExitConfirmation() },
            "Ventana pequeña al salir" to (if (state.pipOnHome) "Activada" else "Desactivada") to { togglePipOnHome() },
        )

private fun MainActivity.advancedHelp(state: MoreSettingsState): List<MoreSettingItem> = listOf(
            "Configurar teclas" to "Personaliza flechas y OK." to { showKeymapSettings() },
            "Diagnóstico" to "Muestra el estado técnico de la reproducción." to { showDiagnostics() },
            "Diagnóstico en pantalla" to (if (state.diagnosticHud) "• Activado" else "Desactivado") to { toggleDiagnosticHud() },
            "Registro de eventos" to "Ver o copiar log de rebuffers y errores." to { showDiagnosticLogDialog() },
            "Exportar informe" to "Comparte detalles técnicos para soporte." to { exportDiagnostics() },
        )

private fun bufferProfileLabel(prefs: SharedPreferences): String =
    when (prefs.bufferProfile()) {
        BufferSizes.PROFILE_HOTSPOT -> "Hotspot LTE (Anti-cortes)"
        BufferSizes.PROFILE_LARGE -> "Grande (20s)"
        BufferSizes.PROFILE_MEDIUM -> "Medio (14s)"
        BufferSizes.PROFILE_SMALL -> "Pequeño"
        else -> "Hotspot LTE (Anti-cortes)"
    }

private fun bufferProfileShortLabel(prefs: SharedPreferences): String =
    when (prefs.bufferProfile()) {
        BufferSizes.PROFILE_HOTSPOT -> "Modo Hotspot LTE"
        BufferSizes.PROFILE_LARGE -> "Grande"
        BufferSizes.PROFILE_MEDIUM -> "Medio"
        BufferSizes.PROFILE_SMALL -> "Pequeño"
        else -> "Hotspot LTE"
    }




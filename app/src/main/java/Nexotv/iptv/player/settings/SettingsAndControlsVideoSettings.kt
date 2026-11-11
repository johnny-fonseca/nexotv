@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings



import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

// ---------- Selector de buffer ----------

@UnstableApi
internal fun MainActivity.showBufferPicker() {
    val options = arrayOf(
        Triple(BufferSizes.PROFILE_HOTSPOT, "Hotspot LTE / móvil", "Anti-cortes para redes móviles · hasta 10 s de buffer"),
        Triple(BufferSizes.PROFILE_LARGE, "Grande", "Máxima reserva para redes inestables · 20 s de margen"),
        Triple(BufferSizes.PROFILE_MEDIUM, "Medio", "Equilibrio entre estabilidad y retraso · 14 s de margen"),
        Triple(BufferSizes.PROFILE_SMALL, "Pequeño", "Inicio rápido en redes fijas estables · 8 s de margen"),
    )
    val prefs = appPreferences()
    val current = prefs.bufferProfile()
    val selected = options.indexOfFirst { it.first == current }.coerceAtLeast(0)

    val (root, panel) = createPanelScaffold()
    panel.addView(
        createPanelHeader("REPRODUCCIÓN", "Buffer y estabilidad de señal", R.drawable.tv_icon_stability),
        LinearLayout.LayoutParams(-1, -2),
    )

    val optionsContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    addSectionLabel(optionsContent, "PERFIL ACTUAL")
    val currentCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = menuCardSurface(this@showBufferPicker, 18f)
    }
    val currentLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    currentLabels.addView(TextView(this).apply {
        text = options[selected].second
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
    })
    currentLabels.addView(
        TextView(this).apply {
            text = options[selected].third
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) },
    )
    currentCard.addView(currentLabels, LinearLayout.LayoutParams(0, -2, 1f))
    currentCard.addView(createBadge("EN USO", horizontalDp = 10, verticalDp = 6))
    optionsContent.addView(currentCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    addSectionLabel(optionsContent, "ELIGE UN PERFIL")

    val rows = options.mapIndexed { index, option ->
        createOptionRow(
            label = option.second,
            description = option.third,
            badge = if (index == selected) createBadge("ACTUAL") else null,
            stateDescription = if (index == selected) "Perfil actual" else null,
        )
    }
    val closeButton = createCloseButton("Cerrar")
    rows.forEach {
        optionsContent.addView(it, LinearLayout.LayoutParams(-1, dp(PANEL_ROW_HEIGHT_DP)).apply { bottomMargin = dp(6) })
    }
    linkFocusChain(rows, closeButton)
    optionsContent.addView(
        TextView(this).apply {
            text = "El cambio se aplica al reconstruir el reproductor."
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        },
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
    )
    val optionsScroll = ScrollView(this).apply {
        isFillViewport = true
        isSmoothScrollingEnabled = true
        addView(optionsContent)
        scrollFocusedDescendantIntoView()
    }
    panel.addView(optionsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    panel.addView(
        closeButton,
        LinearLayout.LayoutParams(dp(PANEL_CLOSE_WIDTH_DP), dp(PANEL_CLOSE_HEIGHT_DP)).apply { gravity = Gravity.END },
    )

    val dialog = createTvPanelDialog(root)
    closeButton.setOnClickListener { dialog.dismiss() }
    rows.forEachIndexed { index, row ->
        row.setOnClickListener {
            val profile = options.getOrNull(index)?.first ?: return@setOnClickListener
            prefs.edit { putString(MainActivity.KEY_BUFFER, profile) }
            dialog.dismiss()
            rebuildPlayer("Estabilidad $profile")
        }
    }
    showPanelDialog(dialog, rows.getOrNull(selected))
}

// ---------- Decodificadores ----------

@UnstableApi
internal fun MainActivity.showDecoderPicker() {
    showStyledChoiceDialog(
        "Decodificador", "REPRODUCCIÓN",
        listOf(
            "Compatibilidad de video" to "Selecciona el decodificador de imagen.",
            "Compatibilidad de audio" to "Selecciona el decodificador de sonido.",
        ),
        -1,
    ) { type ->
        val key = if (type == 0) MainActivity.KEY_VIDEO_DECODER else MainActivity.KEY_AUDIO_DECODER
        val media = if (type == 0) "video" else "audio"
        val prefs = appPreferences()
        showStyledChoiceDialog(
            "Compatibilidad de $media", "REPRODUCCIÓN",
            listOf(
                "Hardware (recomendado)" to "Usa la aceleración del dispositivo.",
                "Software (compatibilidad)" to "Prueba el decodificador por software si hay problemas.",
            ),
            if (prefs.safeString(key, "Hardware") == "Software") 1 else 0,
        ) { mode ->
            prefs.edit { putString(key, if (mode == 1) "Software" else "Hardware") }
            rebuildPlayer("Decodificador ${if (mode == 1) "software" else "hardware"}")
        }
    }
}

internal fun MainActivity.decoderPreferences(): DecoderPreferences {
    val prefs = appPreferences()
    val audio = if (prefs.safeString(MainActivity.KEY_AUDIO_DECODER, "Hardware") == "Software") {
        DecoderMode.SOFTWARE
    } else {
        DecoderMode.HARDWARE
    }
    val video = if (prefs.safeString(MainActivity.KEY_VIDEO_DECODER, "Hardware") == "Software") {
        DecoderMode.SOFTWARE
    } else {
        DecoderMode.HARDWARE
    }
    return DecoderPreferences(audio, video)
}

// ---------- Imagen ----------

@UnstableApi
internal fun MainActivity.frameRateDisplayLabel(): String =
    when (frameRatePreference()) {
        MainActivity.AFR_OFF -> "Ahorro de datos"
        MainActivity.AFR_ON -> "Alta"
        else -> "Automática (recomendada)"
    }

internal fun MainActivity.aspectModeLabel(): String =
    when (appPreferences().safeInt(MainActivity.KEY_RESIZE, AspectRatioFrameLayout.RESIZE_MODE_FIT)) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Ajustar a la pantalla"
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Estirar"
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Ancho fijo"
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Alto fijo"
        else -> "Zoom o recorte"
    }

internal fun MainActivity.showAspectPicker() {
    val labels = arrayOf("Fit (sin recorte)", "Zoom (recortar)", "Stretch", "Ancho fijo", "Alto fijo")
    val modes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
    )
    val selected = modes.indexOf(playerView.resizeMode).coerceAtLeast(0)
    showStyledChoiceDialog("Tamaño de la imagen", "IMAGEN", labels.map { it to "" }, selected) { index ->
        val mode = modes.getOrNull(index) ?: return@showStyledChoiceDialog
        playerView.resizeMode = mode
        appPreferences().edit { putInt(MainActivity.KEY_RESIZE, mode) }
    }
}

internal fun MainActivity.resetAspectRatio() {
    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    appPreferences().edit { putInt(MainActivity.KEY_RESIZE, AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    flashStatus("Imagen ajustada a Fit")
}

// ---------- Fluidez de imagen ----------

internal fun MainActivity.frameRatePreference(): String =
    appPreferences().safeString(MainActivity.KEY_AFR, MainActivity.AFR_RESTORE) ?: MainActivity.AFR_RESTORE

internal fun MainActivity.showFrameRatePicker() {
    val choices = arrayOf("Ahorro de datos", "Alta", "Automática (recomendada)")
    val values = arrayOf(MainActivity.AFR_OFF, MainActivity.AFR_ON, MainActivity.AFR_RESTORE)
    val selected = values.indexOf(frameRatePreference()).coerceAtLeast(0)
    showStyledChoiceDialog("Fluidez de la imagen", "IMAGEN", choices.map { it to "" }, selected) { index ->
        val value = values.getOrNull(index) ?: return@showStyledChoiceDialog
        appPreferences().edit { putString(MainActivity.KEY_AFR, value) }
        if (value == MainActivity.AFR_OFF || value == MainActivity.AFR_RESTORE) {
            restoreDisplayMode()
        } else if (fps > 0f) {
            applyFrameRate(fps)
        }
        flashStatus("AFR: ${choices[index]}")
    }
}

@UnstableApi
@Suppress("DEPRECATION")
internal fun MainActivity.restoreDisplayMode() {
    if (frameRatePreference() == MainActivity.AFR_ON) return
    val original = originalDisplayModeId ?: return
    originalDisplayModeId = null
    runCatching {
        window.attributes = window.attributes.apply { preferredDisplayModeId = original }
    }
}


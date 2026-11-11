@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

internal const val DEFAULT_SKIP_PRESET = "10s / 30s / 1m / 5m"

// ---------- Saltos de reproducción ----------

internal fun MainActivity.showSkipPicker() {
    val presets = arrayOf(DEFAULT_SKIP_PRESET, "5s / 15s / 30s / 1m", "30s / 1m / 5m / 10m", "1m / 5m / 15m / 30m")
    val currentPreset = appPreferences()
        .safeString(MainActivity.KEY_SKIP_STEPS, presets[0]) ?: presets[0]
    val selected = presets.indexOf(currentPreset).coerceAtLeast(0)
    showStyledChoiceDialog(
        "Saltos de reproducción", "REPRODUCCIÓN",
        presets.map { it to "Atrás / adelante" }, selected,
    ) { index ->
        val preset = presets.getOrNull(index) ?: return@showStyledChoiceDialog
        appPreferences().edit { putString(MainActivity.KEY_SKIP_STEPS, preset) }
        applySkipSteps()
    }
}

private const val MAX_SKIP_AMOUNT = 360L
private const val MAX_SKIP_STEPS = 8

internal fun MainActivity.skipValues(): List<Long> {
    val raw = appPreferences()
        .safeString(MainActivity.KEY_SKIP_STEPS, DEFAULT_SKIP_PRESET) ?: DEFAULT_SKIP_PRESET
    val parsed = Regex("(\\d+)([sm])").findAll(raw).mapNotNull { match ->
        val amount = match.groupValues[1].toLongOrNull()?.takeIf { it in 1L..MAX_SKIP_AMOUNT } ?: return@mapNotNull null
        val multiplier = if (match.groupValues[2] == "m") 60_000L else 1_000L
        amount * multiplier
    }.take(MAX_SKIP_STEPS).toList()
    return parsed.ifEmpty { listOf(10_000L, 30_000L) }
}

internal fun MainActivity.applySkipSteps() {
    flashStatus("Saltos configurados: ${formatSkip(0)} atrás / ${formatSkip(1)} adelante")
}

internal fun MainActivity.seekByConfiguredStep(forward: Boolean) {
    if (!player.isCurrentMediaItemSeekable) return
    val steps = skipValues()
    if (steps.isEmpty()) return
    val delta = steps.getOrElse(if (forward) 1 else 0) { steps.first() }
    val target = (player.currentPosition + if (forward) delta else -delta).coerceAtLeast(0L)
    val duration = player.duration
    player.seekTo(
        if (duration != C.TIME_UNSET && duration > 0L) target.coerceAtMost(duration) else target,
    )
}

internal fun MainActivity.formatSkip(index: Int): String {
    val steps = skipValues()
    if (steps.isEmpty()) return "10s"
    val millis = steps.getOrElse(index) { steps.last() }
    return if (millis % 60_000L == 0L) "${millis / 60_000L}m" else "${millis / 1_000L}s"
}

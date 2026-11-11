@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import android.view.KeyEvent
import android.view.View
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi

private val KEYMAP_CONTROL_LABELS = arrayOf("OK", "Arriba / abajo", "Izquierda / derecha", "OK mantenido")

private val DPAD_DIRECTION_KEYS: Set<Int> = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

private val SELECT_KEYS: Set<Int> = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
)

// ---------- Mando a distancia ----------

internal fun MainActivity.showKeymapSettings() {
    showStyledChoiceDialog(
        "Configurar mando", "CONTROLES",
        KEYMAP_CONTROL_LABELS.map { it to "Configura la acción asignada a esta tecla." }, -1,
    ) { index -> showKeymapChoice(index) }
}

internal fun MainActivity.showKeymapChoice(control: Int) {
    val key = MainActivity.KEYMAP_KEYS.getOrNull(control) ?: return
    val choices = when (control) {
        0 -> arrayOf("Pausar / reanudar", "Abrir barra rápida", "Mostrar diagnóstico")
        1 -> arrayOf("Volumen", "Mostrar diagnóstico", "Sin acción")
        2 -> arrayOf("Volumen", "Sincronización de audio", "Salto en vivo", "Sin acción")
        else -> arrayOf("Abrir barra rápida", "Mostrar diagnóstico", "Sin acción")
    }
    val values = when (control) {
        0 -> arrayOf("pause", "menu", "info")
        1 -> arrayOf("volume", "info", "none")
        2 -> arrayOf("volume", "sync", "seek", "none")
        else -> arrayOf("menu", "info", "none")
    }
    val current = keymap(key, values.first())
    val selected = values.indexOf(current).coerceAtLeast(0)
    showStyledChoiceDialog(
        controlsLabel(control), "CONTROLES",
        choices.map { it to "" }, selected,
    ) { index ->
        val value = values.getOrNull(index) ?: return@showStyledChoiceDialog
        appPreferences().edit { putString(key, value) }
        flashStatus("Control del mando actualizado")
    }
}

internal fun controlsLabel(index: Int): String =
    KEYMAP_CONTROL_LABELS.getOrElse(index) { "OK" }

internal fun MainActivity.keymap(key: String, default: String): String =
    appPreferences().safeString(key, default) ?: default

// ---------- Teclas ----------

internal fun isDpadDirection(keyCode: Int): Boolean = keyCode in DPAD_DIRECTION_KEYS

internal fun isSelectKey(keyCode: Int): Boolean = keyCode in SELECT_KEYS

internal fun MainActivity.hasFocusableStatusControls(): Boolean =
    statusCardIsActionable && statusCard?.visibility == View.VISIBLE

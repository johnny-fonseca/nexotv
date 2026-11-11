@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi

// ---------- Apariencia ----------

internal fun MainActivity.showAppearanceSettings() {
    val prefs = appPreferences()
    val panelTimeout = prefs.safeInt(MainActivity.KEY_PANEL_TIMEOUT, 5)
    val transparency = prefs.safeInt(MainActivity.KEY_PANEL_TRANSPARENCY, 0)
    val textSize = prefs.safeInt(
        MainActivity.KEY_PANEL_TEXT_SIZE,
        resources.getInteger(R.integer.tv_panel_text_max_sp),
    ).coerceIn(20, 24)
    val clockVisible = prefs.safeBoolean(MainActivity.KEY_SHOW_CLOCK, true)
    val clock24Hour = prefs.safeString(MainActivity.KEY_CLOCK_FORMAT, "12-hour") == "24-hour"
    showStyledChoiceDialog(
        "Apariencia del panel", "APARIENCIA",
        listOf(
            "Tiempo visible" to "$panelTimeout s",
            "Transparencia" to "$transparency%",
            "Tamaño de texto" to "$textSize sp",
            "Reloj" to if (clockVisible) "Visible · selecciona para ocultar" else "Oculto · selecciona para mostrar",
            "Formato del reloj" to if (clock24Hour) "24 horas" else "12 horas",
        ),
        -1,
    ) { which ->
        when (which) {
            0 -> choosePreference("Tiempo visible", arrayOf("2 s", "5 s", "8 s", "10 s"), intArrayOf(2, 5, 8, 10), MainActivity.KEY_PANEL_TIMEOUT)
            1 -> choosePreference("Transparencia", arrayOf("0%", "10%", "25%", "50%"), intArrayOf(0, 10, 25, 50), MainActivity.KEY_PANEL_TRANSPARENCY)
            2 -> choosePreference("Tamaño de texto", arrayOf("Pequeño", "Mediano", "Grande", "Muy grande"), intArrayOf(20, 21, 22, 24), MainActivity.KEY_PANEL_TEXT_SIZE)
            3 -> {
                prefs.edit { putBoolean(MainActivity.KEY_SHOW_CLOCK, !clockVisible) }
                clockTicker.run()
                showAppearanceSettings()
            }
            4 -> showStyledChoiceDialog(
                "Formato del reloj", "APARIENCIA",
                listOf("12 horas" to "", "24 horas" to ""),
                if (clock24Hour) 1 else 0,
            ) { selected ->
                prefs.edit {
                    putString(
                        MainActivity.KEY_CLOCK_FORMAT,
                        if (selected == 0) "12-hour" else "24-hour",
                    )
                }
                clockTicker.run()
            }
        }
    }
}

@UnstableApi
internal fun MainActivity.choosePreference(
    title: String,
    labels: Array<String>,
    values: IntArray,
    key: String,
) {
    if (values.isEmpty() || labels.size != values.size) return
    val current = appPreferences().safeInt(key, values[0])
    val selected = values.indexOf(current).coerceAtLeast(0)
    showStyledChoiceDialog(title, "APARIENCIA", labels.map { it to "" }, selected) { index ->
        val value = values.getOrNull(index) ?: return@showStyledChoiceDialog
        appPreferences().edit { putInt(key, value) }
        styleStatus()
        flashStatus("$title actualizado")
    }
}

@UnstableApi
internal fun MainActivity.styleStatus() {
    if (!isStatusInitialized) return
    val prefs = appPreferences()
    status.textSize = prefs.safeInt(MainActivity.KEY_PANEL_TEXT_SIZE, 18).toFloat().coerceIn(15f, 24f)
    val transparency = prefs.safeInt(MainActivity.KEY_PANEL_TRANSPARENCY, 0).coerceIn(0, 50)
    status.background = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(Color.argb((100 - transparency) * 255 / 100, 16, 21, 31))
        setStroke(dp(1), 0x667DACEA.toInt())
    }
}

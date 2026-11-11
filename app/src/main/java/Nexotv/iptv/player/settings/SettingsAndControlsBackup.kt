@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings



import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.app.AlertDialog
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi

// ---------- Copia y restauración de ajustes ----------

internal fun MainActivity.showSettingsBackup() {
    showStyledChoiceDialog(
        "Copia de ajustes", "APLICACIÓN",
        listOf(
            "Exportar ajustes" to "Comparte tus preferencias como archivo de texto.",
            "Importar desde texto" to "Pega el contenido de una copia exportada.",
            "Importar desde archivo" to "Selecciona una copia guardada en el dispositivo.",
            "Restaurar respaldo automático" to "Recupera la copia local más reciente.",
        ),
        -1,
    ) { which ->
        when (which) {
            0 -> exportSettings()
            1 -> importSettingsPrompt()
            2 -> openSettingsFile()
            else -> restoreAutomaticBackup()
        }
    }
}

internal fun MainActivity.restoreAutomaticBackup() {
    settingsTransferController.restoreAutomaticBackup()
}

internal fun MainActivity.openSettingsFile() {
    settingsTransferController.openSettingsFile()
}

internal fun MainActivity.exportSettings() {
    settingsTransferController.exportSettings()
}

internal fun MainActivity.importSettingsPrompt() {
    val input = EditText(this).apply { hint = "Pega aquí el JSON exportado"; minLines = 4 }
    styleTvInput(input, 140)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(TextView(this@importSettingsPrompt).apply {
            text = "Pega el texto JSON de una copia de ${BuildConfig.BRAND_NAME}."
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(12))
        })
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    AlertDialog.Builder(this)
        .setView(createStyledFormPanel("Importar ajustes", "APLICACIÓN", content))
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Importar") { _, _ ->
            importSettings(input.text.toString())
        }.showForTv()
}

internal fun MainActivity.importSettings(jsonText: String) {
    settingsTransferController.importSettings(jsonText)
}

// ---------- Respaldo automático ----------

internal fun MainActivity.toggleAutoBackup() {
    val prefs = appPreferences()
    val enabled = !prefs.safeBoolean(MainActivity.KEY_AUTO_BACKUP, true)
    if (enabled && !AutoBackupStore.canWrite(this)) {
        runCatching {
            requestAppPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, MainActivity.REQUEST_BACKUP_STORAGE)
        }.onFailure { showTvToast("No se pudo solicitar el permiso de almacenamiento", long = true) }
        showTvToast("El respaldo automático se activará al conceder el permiso de almacenamiento")
        return
    }
    prefs.edit { putBoolean(MainActivity.KEY_AUTO_BACKUP, enabled) }
    if (enabled) saveAutomaticBackup()
    flashStatus("Respaldo automático ${if (enabled) "activado" else "desactivado"}")
}

@UnstableApi
internal fun MainActivity.saveAutomaticBackup() {
    settingsTransferController.saveAutomaticBackup()
}

/** Ejecuta la copia de arranque sin bloquear el primer render ni la reproducción. */
internal fun MainActivity.scheduleAutomaticBackup() {
    automaticBackupTask?.cancel(false)
    automaticBackupTask = automaticBackupExecutor.submit {
        runCatching { saveAutomaticBackup() }
    }
}


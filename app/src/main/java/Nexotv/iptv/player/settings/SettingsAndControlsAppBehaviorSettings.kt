@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings


import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.guide.*
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi

// ---------- Inicio automático ----------

internal fun MainActivity.showAutoStartSettings() {
    val prefs = appPreferences()
    val options = arrayOf(
        "Abrir ${BuildConfig.BRAND_NAME} al encender el TV" to "Inicia la aplicación cuando el televisor se enciende.",
        "Abrir ${BuildConfig.BRAND_NAME} al despertar" to "Inicia la aplicación cuando el televisor sale del reposo.",
    )
    val keys = arrayOf(MainActivity.KEY_AUTOSTART_BOOT, MainActivity.KEY_AUTOSTART_WAKE)
    val (root, panel) = createPanelScaffold()
    panel.addView(
        createPanelHeader("APLICACIÓN", "Inicio automático", cardIcon("Inicio automático")),
        LinearLayout.LayoutParams(-1, -2),
    )
    addSectionLabel(panel, "ELIGE CUÁNDO ABRIR")

    val rows = options.mapIndexed { index, (label, description) ->
        val enabled = prefs.safeBoolean(keys[index], false)
        val badge = createBadge(if (enabled) "ACTIVO" else "INACTIVO", active = enabled)
        val row = createOptionRow(
            label, description, badge,
            if (enabled) "Activado" else "Desactivado",
        )
        row.setOnClickListener {
            val next = !prefs.safeBoolean(keys[index], false)
            prefs.edit { putBoolean(keys[index], next) }
            applyBadgeState(badge, if (next) "ACTIVO" else "INACTIVO", next)
            row.contentDescription = "$label. $description. ${if (next) "Activado" else "Desactivado"}"
        }
        row
    }
    val close = createCloseButton("Listo")
    rows.forEach {
        panel.addView(it, LinearLayout.LayoutParams(-1, dp(PANEL_ROW_HEIGHT_DP)).apply { bottomMargin = dp(6) })
    }
    linkFocusChain(rows, close)
    panel.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
    panel.addView(
        close,
        LinearLayout.LayoutParams(dp(PANEL_CLOSE_WIDTH_DP), dp(PANEL_CLOSE_HEIGHT_DP)).apply {
            topMargin = dp(8)
            gravity = Gravity.END
        },
    )

    val dialog = createTvPanelDialog(root)
    close.setOnClickListener { dialog.dismiss() }
    showPanelDialog(dialog, rows.firstOrNull())
}

// ---------- Confirmación al salir ----------

internal fun MainActivity.toggleExitConfirmation() {
    val enabled = flipBoolean(MainActivity.KEY_CONFIRM_EXIT, true)
    flashStatus("Confirmación al salir ${if (enabled) "activada" else "desactivada"}")
}

// ---------- Diagnóstico ----------

internal fun MainActivity.showDiagnostics() {
    showStatus(channelDetails())
    diagnosticsVisible = true
    handler.removeCallbacks(hideStatus)
    handler.postDelayed(hideStatus, 1_000L)
}

internal fun MainActivity.exportDiagnostics() {
    val sourceHost = runCatching { currentStreamUrl().toUri().host }.getOrNull() ?: "personalizada"
    val guideSource = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        .getString(MainActivity.KEY_EPG_URL, XmlTvGuide.DEFAULT_URL)
    val guideHost = runCatching { guideSource?.toUri()?.host }.getOrNull() ?: "predeterminada"
    val report = buildString {
        appendLine("${BuildConfig.BRAND_NAME} — diagnóstico")
        appendLine(channelDetails())
        appendLine("Fuente de video: $sourceHost")
        appendLine("Fuente de guía: $guideHost")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine("=== EVENTOS RECIENTES ===")
        append(DiagnosticTracker.exportLog())
        appendLine()
        appendLine("=== INFORME DE SESIONES ===")
        append(SessionReport.summaryText(this@exportDiagnostics))
    }

    // Android TV suele carecer de destinos ACTION_SEND; mostrar el informe y copiarlo
    // funciona también en televisores donde el selector del sistema responde «acción no admitida».
    AlertDialog.Builder(this)
        .setTitle("Diagnóstico de ${BuildConfig.BRAND_NAME}")
        .setMessage(report.ifBlank { "No hay datos de diagnóstico disponibles." })
        .setPositiveButton("Copiar diagnóstico") { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                showTvToast("No se pudo acceder al portapapeles", long = true)
            } else {
                runCatching {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico ${BuildConfig.BRAND_NAME}", report))
                }.onSuccess {
                    showTvToast("Diagnóstico copiado al portapapeles")
                }.onFailure {
                    showTvToast("No se pudo copiar el diagnóstico", long = true)
                }
            }
        }
        .setNegativeButton("Cerrar", null)
        .showForTv()
}



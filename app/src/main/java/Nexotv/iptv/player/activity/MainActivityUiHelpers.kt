@file:Suppress("PackageName")
@file:UnstableApi

// ============================================================================
// Archivo: Nexotv/iptv/player/ActivityUiHelpers.kt
// ============================================================================

package Nexotv.iptv.player.activity
import Nexotv.iptv.player.*

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.guide.*
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.content.Context.*
import Nexotv.iptv.player.MainActivity.*
import Nexotv.iptv.player.MainActivity.Companion.KEY_PANEL_TEXT_SIZE
import Nexotv.iptv.player.MainActivity.Companion.KEY_RECORDING_TREE
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.WindowManager
import android.view.Gravity
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.view.animation.DecelerateInterpolator
import android.transition.TransitionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContextWrapper
import android.annotation.SuppressLint
import androidx.core.view.isNotEmpty
import androidx.media3.common.util.UnstableApi

// ----------------------------------------------------------------------------
// Constantes internas del archivo
// ----------------------------------------------------------------------------

private const val UI_HELPERS_LOG_TAG = "ActivityUiHelpers"

/** Duración (ms) de la animación de entrada de diálogos y superficies de menú. */
private const val DURACION_ANIMACION_ENTRADA_MS = 240L

/** Duración (ms) de la transición al reconstruir el contenido de un menú. */
private const val DURACION_TRANSICION_MENU_MS = 190L
private const val DURACION_ENTRADA_ELEMENTO_MS = 220L
private const val RETARDO_ENTRADA_ELEMENTO_MS = 36L
private const val MAX_ELEMENTOS_ENTRADA_ESCALONADA = 8

/** Número máximo de reintentos al solicitar el foco inicial en un contenedor. */
private const val MAX_INTENTOS_FOCO = 8

/** Retardo (ms) entre reintentos de solicitud de foco. */
private const val RETARDO_REINTENTO_FOCO_MS = 40L

/** Tamaño mínimo (sp) del texto de los paneles. */
private const val MIN_TAMANO_TEXTO_PANEL_SP = 20

/** Expresión regular para extraer la marca de tiempo del nombre de una grabación. */
private val REGEX_MARCA_GRABACION = Regex("${Regex.escape(BuildConfig.BRAND_NAME)}-(\\d+)")

/** Palabras clave que identifican una tarjeta de advertencia. */
private val PALABRAS_ADVERTENCIA = listOf("error", "fallo", "insuficiente", "conflicto", "eliminar")

// ----------------------------------------------------------------------------
// Utilidades de robustez internas
// ----------------------------------------------------------------------------

/** Ejecuta [bloque] registrando cualquier excepción en lugar de propagarla a la interfaz. */
private inline fun ejecutarSeguro(operacion: String, bloque: () -> Unit) {
    try {
        bloque()
    } catch (e: Exception) {
        Log.e(UI_HELPERS_LOG_TAG, "Fallo en $operacion", e)
    }
}

/** Indica si la actividad puede alojar diálogos sin riesgo de `BadTokenException`. */
private fun MainActivity.puedeMostrarDialogos(): Boolean = !isFinishing && !isDestroyed

/** Muestra un diálogo capturando fallos del gestor de ventanas. Devuelve `true` si se mostró. */
private fun mostrarSeguro(dialog: AlertDialog): Boolean = try {
    dialog.show()
    true
} catch (e: RuntimeException) {
    Log.e(UI_HELPERS_LOG_TAG, "No se pudo mostrar el diálogo", e)
    false
}

/**
 * Ejecuta la navegación de retorno estándar: primero intenta retroceder en la pila
 * de pantallas y, si no hay historial, vuelve al menú principal desde su origen.
 */
@UnstableApi
private fun MainActivity.volverAPantallaAnterior() {
    if (isFinishing || isDestroyed) return
    ejecutarSeguro("volverAPantallaAnterior") {
        if (!popScreenBackStack()) {
            consumeMenuOrigin { origin -> showMainMenu(origin) {} }
        }
    }
}

/** Descripción del destino de grabación vigente, tolerante a preferencias corruptas. */
private fun MainActivity.destinoGrabacionActual(): String {
    val almacenado = runCatching {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_RECORDING_TREE, null)
    }.getOrNull()
    return if (almacenado.isNullOrBlank()) "almacenamiento del TV" else "carpeta USB seleccionada"
}

// ----------------------------------------------------------------------------
// Listas de tarjetas y programaciones
// ----------------------------------------------------------------------------

/**
 * Muestra un diálogo a pantalla completa con una lista vertical de tarjetas.
 * Configura la navegación con el mando (cada fila apunta a sus vecinas) y el
 * desplazamiento automático hacia la fila enfocada.
 *
 * @param title título del diálogo.
 * @param rows contenedor vertical con las tarjetas ya construidas.
 * @param returnToPreviousScreen si es `true`, al cerrar se regresa a la pantalla anterior.
 */
internal fun MainActivity.showCardList(title: String, rows: LinearLayout, returnToPreviousScreen: Boolean = true) {
    if (!puedeMostrarDialogos()) return
    val ultimo = rows.childCount - 1
    for (index in 0..ultimo) {
        val row = rows.getChildAt(index) ?: continue
        if (row.id == View.NO_ID) row.id = View.generateViewId()
        row.nextFocusLeftId = row.id
        row.nextFocusRightId = row.id
        row.nextFocusUpId = rows.getChildAt(index - 1)?.id ?: row.id
        row.nextFocusDownId = rows.getChildAt(index + 1)?.id ?: row.id
        val existente = row.layoutParams
        val params = when (existente) {
            is LinearLayout.LayoutParams -> existente
            is ViewGroup.MarginLayoutParams -> LinearLayout.LayoutParams(existente)
            is ViewGroup.LayoutParams -> LinearLayout.LayoutParams(existente)
            else -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (index < ultimo) params.bottomMargin = dp(12)
        row.layoutParams = params
    }
    (rows.parent as? ViewGroup)?.removeView(rows)
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        isSmoothScrollingEnabled = true
        addView(rows)
        scrollFocusedDescendantIntoView()
    }
    val section = when (title) {
        "Programaciones" -> "GUÍA"
        "Grabaciones", "Grabaciones programadas" -> "GRABACIONES"
        "Diagnóstico", "Registro de eventos" -> "DIAGNÓSTICO"
        else -> BuildConfig.BRAND_NAME.uppercase()
    }
    val builder = AlertDialog.Builder(this)
    if (title == "Programaciones") builder.setView(createStyledFormPanel(title, section, scroll))
    else builder.setTitle(title).setView(scroll)
    val dialog = builder.setNegativeButton("Cerrar", null).create()
    if (returnToPreviousScreen) dialog.setOnCancelListener { volverAPantallaAnterior() }
    ejecutarSeguro("setTvDialogBounds") { setTvDialogBounds(dialog, 1f, 1f) }
    dialog.setOnShowListener {
        ejecutarSeguro("animateDialogEntrance") { animateDialogEntrance(dialog) }
        ejecutarSeguro("focusFirstWhenReady") { focusFirstWhenReady(rows) }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            ejecutarSeguro("cerrarListaDeTarjetas") {
                dialog.dismiss()
                if (returnToPreviousScreen) volverAPantallaAnterior()
            }
        }
    }
    if (!mostrarSeguro(dialog)) return
    ejecutarSeguro("enlazarFocoBotonCerrar") {
        if (rows.isNotEmpty()) {
            val closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val lastRow = rows.getChildAt(rows.childCount - 1)
            if (closeButton != null && lastRow != null) {
                lastRow.nextFocusDownId = closeButton.id
                closeButton.nextFocusUpId = lastRow.id
            }
        }
    }
}

/**
 * Panel mostrado cuando no existe ninguna programación futura, con accesos a la guía.
 */
@SuppressLint("SetTextI18n")
@UnstableApi
private fun MainActivity.mostrarProgramacionesVacias() {
    val act = this
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(18), dp(28), dp(12))
        background = menuBackdrop(act)
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(24), dp(28), dp(20))
        background = menuMainPanel(act)
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val headerIcon = FrameLayout(this).apply {
        background = menuPill(act, 0xFF12263A.toInt(), 0xFF27415B.toInt())
        addView(
            ImageView(act).apply {
                setImageResource(cardIcon("Programaciones"))
                imageTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
            },
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER),
        )
    }
    header.addView(headerIcon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
    header.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(act).apply {
            text = "GUÍA"
            setTextColor(getColor(R.color.tv_turquoise))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = .12f
        })
        addView(TextView(act).apply {
            text = "Programaciones"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }, LinearLayout.LayoutParams(0, -2, 1f))
    panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    panel.addView(TextView(this).apply {
        text = "No hay recordatorios ni grabaciones programadas.\n\nExplora la guía para elegir un programa."
        setTextColor(getColor(R.color.tv_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(24), dp(20), dp(24))
        background = menuCardSurface(act, 18f)
    }, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(20) })
    val openGuide = TvStyle.primaryButton(this, "Abrir guía") { }.apply {
        id = View.generateViewId()
    }
    val close = TvStyle.secondaryButton(this, "Cerrar") { }.apply {
        id = View.generateViewId()
    }
    panel.addView(openGuide, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
    panel.addView(close, LinearLayout.LayoutParams(-1, dp(52)))
    openGuide.nextFocusUpId = openGuide.id
    openGuide.nextFocusDownId = close.id
    close.nextFocusUpId = openGuide.id
    close.nextFocusDownId = close.id
    root.addView(panel, LinearLayout.LayoutParams(-1, -1))
    val dialog = AlertDialog.Builder(this).setView(root).create()
    openGuide.setOnClickListener {
        ejecutarSeguro("cerrarPanelProgramacionesVacio") { dialog.dismiss() }
        try {
            showGuide()
        } catch (e: Exception) {
            Log.e(UI_HELPERS_LOG_TAG, "No se pudo abrir la guía", e)
            volverAPantallaAnterior()
        }
    }
    close.setOnClickListener {
        ejecutarSeguro("cerrarProgramaciones") {
            dialog.dismiss()
            volverAPantallaAnterior()
        }
    }
    dialog.setOnCancelListener { volverAPantallaAnterior() }
    dialog.setOnShowListener {
        ejecutarSeguro("animateDialogEntrance") { animateDialogEntrance(dialog) }
        openGuide.post { openGuide.requestFocus() }
    }
    ejecutarSeguro("setTvDialogBounds") { setTvDialogBounds(dialog, 1f, 1f) }
    mostrarSeguro(dialog)
}

/**
 * Muestra las programaciones futuras (recordatorios y grabaciones). Si no existe
 * ninguna, presenta un panel vacío con accesos a la guía; de lo contrario lista
 * cada programación con la opción de cancelarla.
 */
@SuppressLint("SetTextI18n")
@UnstableApi
internal fun MainActivity.showProgramSchedules() {
    if (!puedeMostrarDialogos()) return
    val ahora = System.currentTimeMillis()
    val scheduled = runCatching {
        ProgramSchedule.all(this)
            .asSequence()
            .filter { (program, _, _) -> program.endMs > ahora }
            .sortedBy { (program, _, _) -> program.startMs }
            .toList()
    }.getOrElse { error ->
        Log.e(UI_HELPERS_LOG_TAG, "No se pudieron leer las programaciones", error)
        emptyList()
    }
    if (scheduled.isEmpty()) {
        mostrarProgramacionesVacias()
        return
    }
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
    val formatter = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
    scheduled.forEach { (program, reminder, recording) ->
        val acciones = listOfNotNull("Recordatorio".takeIf { reminder }, "Grabación".takeIf { recording })
            .joinToString(" · ")
            .ifEmpty { "Programación" }
        val fecha = runCatching { formatter.format(Date(program.startMs)) }.getOrDefault("")
        val detalle = if (fecha.isEmpty()) acciones else "$fecha · $acciones"
        val titulo = program.title.ifBlank { "Programa sin título" }
        rows.addView(focusCard(titulo, detalle, R.drawable.tv_icon_guide) {
            if (!puedeMostrarDialogos()) return@focusCard
            val prompt = TextView(this).apply {
                text = "$detalle\n\n¿Cancelar esta programación?"
                setTextColor(getColor(R.color.tv_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            ejecutarSeguro("confirmarCancelacionPrograma") {
                AlertDialog.Builder(this)
                    .setView(createStyledFormPanel(titulo, "GUÍA", prompt))
                    .setNegativeButton("Conservar", null)
                    .setPositiveButton("Cancelar programación") { _, _ ->
                        ejecutarSeguro("cancelarProgramacion") {
                            ProgramSchedule.remove(this, program)
                            refreshVisibleGuide?.invoke()
                            showProgramSchedules()
                        }
                    }.showForTv()
            }
        })
    }
    showCardList("Programaciones", rows)
}

/**
 * Muestra un menú de opciones a pantalla completa. Cada elemento recibe una pista
 * descriptiva según el título del menú y la etiqueta de la opción.
 *
 * @param title título del menú.
 * @param items pares (etiqueta, acción) a mostrar.
 * @param mainMenu indica si se trata del menú principal (no navega hacia atrás al cerrar).
 */
@Suppress("unused")
internal fun MainActivity.showOptionList(title: String, items: List<Pair<String, () -> Unit>>, mainMenu: Boolean = false) {
    if (!puedeMostrarDialogos()) return
    val hintFor: (String) -> String = { label ->
        // Mantiene la primera fila accionable cerca del foco del mando; un párrafo introductorio solo añade un paso.
        when (title) {
            "¿Qué quieres hacer?" -> when (label) {
                "Ver ${BuildConfig.BRAND_NAME}", "Ver ${BuildConfig.BRAND_NAME} en vivo", "En vivo" -> "Vuelve a la señal en directo"
                "Guía", "Guía de TV" -> "Programación, Mis programas y recordatorios"
                "Buscar", "Buscar un programa" -> "Encuentra un programa por su nombre"
                "Grabar ahora" -> "Guarda la señal para verla más tarde"
                "Detener grabación" -> "Finaliza la grabación en curso"
                "Mis grabaciones" -> "Grabaciones guardadas, programaciones y espacio"
                "Mis programas" -> "Tus programas favoritos y guardados"
                "Más", "Más opciones" -> "Ajustes, sonido, ayuda y diagnóstico"
                else -> ""
            }
            "Más opciones" -> when (label) {
                "Guía y recordatorios" -> "Actualiza la guía y administra avisos"
                "Programaciones" -> "Revisa tus recordatorios y grabaciones futuras"
                "Reproducción y sonido" -> "Opciones de imagen, audio y reproducción"
                "Ajustes de ${BuildConfig.BRAND_NAME}" -> "Apariencia y preferencias de la aplicación"
                "Ayuda y diagnóstico" -> "Revisa la señal o comparte información de soporte"
                else -> ""
            }
            "Grabaciones" -> when (label) {
                "Reproducir última grabación" -> "Continúa desde la grabación más reciente"
                "Biblioteca de grabaciones" -> "Archivos, avance de reproducción y espacio ocupado"
                "Destino de grabación" -> "Actual: ${destinoGrabacionActual()}"
                else -> ""
            }
            "Guía y programas" -> when (label) {
                "Abrir guía" -> "Consulta los programas de hoy"
                "Buscar en la guía" -> "Encuentra emisiones por título"
                "Mi lista de programas" -> "Programas que guardaste"
                "Programaciones" -> "Recordatorios y grabaciones previstas"
                else -> ""
            }
            BuildConfig.BRAND_NAME -> when (label) {
                "Guía y programas" -> "Agenda, búsquedas y recordatorios"
                "Buscar en la guía" -> "Busca programas por título o descripción"
                "Mi lista de programas" -> "Tus emisiones guardadas"
                "Grabaciones" -> "Reproduce o administra tus capturas"
                "Programaciones" -> "Recordatorios y grabaciones futuras"
                "Reproducción y sonido" -> "Imagen, audio y controles del stream"
                "Ajustes de ${BuildConfig.BRAND_NAME}" -> "Preferencias y accesibilidad del panel"
                "Diagnóstico y ayuda" -> "Estado del stream y diagnóstico"
                else -> ""
            }
            else -> ""
        }
    }

    val itemsSeguros: List<Pair<String, () -> Unit>> = items.map { (label, accion) ->
        label to { ejecutarSeguro("opción '$label'") { accion() } }
    }

    try {
        OptionListDialog(
            activity = this,
            title = title,
            items = itemsSeguros,
            mainMenu = mainMenu,
            hintFor = hintFor,
            createFocusCard = { label, hint, action -> focusCard(label, hint, action) },
        ) { dialog, rows ->
            dialog.setOnCancelListener { volverAPantallaAnterior() }
            ejecutarSeguro("setTvDialogBounds") { setTvDialogBounds(dialog, 1f, 1f, Gravity.CENTER) }
            dialog.setOnShowListener {
                ejecutarSeguro("animateDialogEntrance") { animateDialogEntrance(dialog) }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                    ejecutarSeguro("cerrarListaDeOpciones") {
                        dialog.dismiss()
                        if (!mainMenu) {
                            if (!popScreenBackStack()) showOptions()
                        }
                    }
                }
                ejecutarSeguro("focusFirstWhenReady") { focusFirstWhenReady(rows) }
            }
        }.show()
    } catch (e: RuntimeException) {
        Log.e(UI_HELPERS_LOG_TAG, "No se pudo mostrar la lista de opciones '$title'", e)
    }
}

// ----------------------------------------------------------------------------
// Grabaciones y preferencias de texto
// ----------------------------------------------------------------------------

/**
 * Obtiene un título legible para una grabación. Si el nombre contiene una marca de
 * tiempo y existe un programa de la guía que la cubra, devuelve el título del
 * programa; en caso contrario, devuelve la fecha formateada.
 */
internal fun MainActivity.recordingTitle(entry: RecordingEntry): String {
    val generico = "Grabación de ${BuildConfig.BRAND_NAME}"
    val stamp = runCatching {
        REGEX_MARCA_GRABACION.find(entry.name)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }.getOrNull() ?: return generico
    val programa = runCatching { guideProgramAt(stamp)?.title }.getOrNull()
    if (!programa.isNullOrBlank()) return programa
    val fecha = runCatching {
        SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(stamp))
    }.getOrNull()
    return if (fecha != null) "Grabación · $fecha" else generico
}

/**
 * Tamaño de texto (sp) de los paneles, limitado entre 20 y el máximo definido en
 * los recursos. Se protege el rango para que un máximo inferior a 20 no provoque
 * una excepción en [coerceIn] y para tolerar preferencias de tipo inesperado.
 */
internal fun MainActivity.panelTextSize(): Int {
    val maximo = runCatching { resources.getInteger(R.integer.tv_panel_text_max_sp) }
        .getOrDefault(MIN_TAMANO_TEXTO_PANEL_SP)
        .coerceAtLeast(MIN_TAMANO_TEXTO_PANEL_SP)
    val guardado = runCatching {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getInt(MainActivity.KEY_PANEL_TEXT_SIZE, maximo)
    }.getOrDefault(maximo)
    return guardado.coerceIn(MIN_TAMANO_TEXTO_PANEL_SP, maximo)
}

/**
 * Aplica el estilo de texto de las pantallas de grabaciones a un [TextView].
 *
 * @param view vista de texto a modificar.
 * @param secondary usa el color de texto secundario si es `true`.
 * @param bold aplica negrita si es `true`.
 */
@Suppress("unused")
internal fun MainActivity.applyRecordingText(view: TextView, secondary: Boolean = false, bold: Boolean = false) {
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, panelTextSize().toFloat())
    view.setTextColor(getColor(if (secondary) R.color.tv_text_secondary else R.color.tv_text_primary))
    if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
}

// ----------------------------------------------------------------------------
// Ventanas y navegación de menús
// ----------------------------------------------------------------------------

/**
 * Aplica desenfoque de fondo a una ventana (Android 12 o superior). Si el
 * dispositivo no soporta el efecto, se limpia la bandera y el diálogo se muestra
 * igualmente sin desenfoque.
 *
 * @param window ventana sobre la que aplicar el efecto.
 * @param radiusPx radio de desenfoque en píxeles.
 */
internal fun MainActivity.applyWindowBlur(window: android.view.Window, radiusPx: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = radiusPx.coerceAtLeast(0) }
    }.onFailure {
        // Se sigue mostrando el diálogo aunque el gestor de ventanas no soporte desenfoque.
        runCatching { window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND) }
    }
}

/** Abre el menú principal y, al retroceder, muestra los ajustes adicionales. */
internal fun MainActivity.showOptions() {
    if (!puedeMostrarDialogos()) return
    ejecutarSeguro("showOptions") {
        showMainMenu { showMoreSettings() }
    }
}

/**
 * Consume el origen del menú almacenado (lo deja en `null`) y ejecuta [onBack]
 * con él. No hace nada si no hay un origen registrado.
 */
internal fun MainActivity.consumeMenuOrigin(onBack: (MenuDestination) -> Unit) {
    ejecutarSeguro("consumeMenuOrigin") {
        navigationController.consumeMenuOrigin(onBack)
    }
}
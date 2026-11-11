// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenFocus.kt
// Mapa de foco del control remoto para el menú principal.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.recordings.*
import android.view.View
import android.content.pm.ApplicationInfo
import android.util.Log

private fun View.menuScreenLinkFocus(
    up: View = this,
    down: View = this,
    left: View = this,
    right: View = this
) {
    if (up.id != View.NO_ID) nextFocusUpId = up.id
    if (down.id != View.NO_ID) nextFocusDownId = down.id
    if (left.id != View.NO_ID) nextFocusLeftId = left.id
    if (right.id != View.NO_ID) nextFocusRightId = right.id
}

private fun View.menuScreenEnsureId() {
    if (id == View.NO_ID) id = View.generateViewId()
}

/**
 * Mapa de foco del rail lateral y las acciones, sin callejones:
 *
 *   rail   :  [En vivo] ↓ [Guía] ↓ [Buscar] ↓ [Mis grabaciones] ↓ [Más] ↓ [estado]
 *   cuerpo :  [▶ emisión] → [A continuación: Ver guía][Programar]
 *             [Ver acciones][Recordarme] → [Grabación: Mis grabaciones][Grabar]
 *
 *  - → desde el rail entra al contenido; "Más" abre la tarjeta de programación.
 *  - ↑ desde la primera fila de contenido vuelve al destino del rail usado más recientemente.
 *  - Cada ↓ tiene su ↑ inverso; en los bordes el foco se queda quieto (nunca sale del menú).
 */
internal fun menuScreenWireFocus(ui: MenuScreenViews) {
    val pill = ui.topBar.statusPill
    val live = ui.liveRow.view
    val guide = ui.guideRow.view
    val search = ui.searchRow.view
    val recordings = ui.recordingsRow.view
    val more = ui.moreRow.view
    val play = ui.hero.playButton
    val actions = ui.btnActions
    val remind = ui.btnRemind
    val nextGuide = ui.btnNextGuide
    val nextSchedule = ui.btnNextSchedule
    val library = ui.btnRecordings
    val recordNow = ui.btnRecordNow

    val navigationItems = listOf(live, guide, search, recordings, more)
    val allRailItems = navigationItems + pill
    val contentViews = listOf(play, actions, remind, nextGuide, nextSchedule, library, recordNow)
    (allRailItems + contentViews).forEach { it.menuScreenEnsureId() }

    pill.menuScreenLinkFocus(up = more, down = pill, right = actions)
    live.menuScreenLinkFocus(up = live, down = guide, right = play)
    guide.menuScreenLinkFocus(up = live, down = search, right = play)
    search.menuScreenLinkFocus(up = guide, down = recordings, right = play)
    recordings.menuScreenLinkFocus(up = search, down = more, right = play)
    more.menuScreenLinkFocus(up = recordings, down = pill, right = nextGuide)

    // Columna izquierda: hero y tarjeta "en vivo"
    play.menuScreenLinkFocus(left = live, down = actions, right = nextGuide)
    actions.menuScreenLinkFocus(up = play, left = pill, right = remind)
    remind.menuScreenLinkFocus(up = play, left = actions, right = library)

    // Columna derecha: "A continuación" y "Grabación"
    nextGuide.menuScreenLinkFocus(left = play, right = nextSchedule, down = library)
    nextSchedule.menuScreenLinkFocus(left = nextGuide, down = recordNow)
    library.menuScreenLinkFocus(up = nextGuide, left = remind, right = recordNow)
    recordNow.menuScreenLinkFocus(up = nextSchedule, left = library)

    // ↑ desde la primera fila de contenido regresa al destino lateral usado por última vez
    val upEntryPoints = listOf(play, nextGuide, nextSchedule)
    upEntryPoints.forEach { it.nextFocusUpId = live.id }
    navigationItems.forEach { tab ->
        val previous = tab.onFocusChangeListener
        tab.setOnFocusChangeListener { view, focused ->
            previous?.onFocusChange(view, focused)
            if (focused && view.id != View.NO_ID) {
                upEntryPoints.forEach { it.nextFocusUpId = view.id }
            }
        }
    }
}

internal fun menuScreenValidateFocusTargetsIfDebug(activity: android.app.Activity, ui: MenuScreenViews) {
    try {
        if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val viewsById = mutableMapOf<Int, View>()
        fun collect(view: View) {
            if (view.id != View.NO_ID) viewsById[view.id] = view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(ui.root)
        val directions = listOf(
            "nextFocusUpId" to { view: View -> view.nextFocusUpId },
            "nextFocusDownId" to { view: View -> view.nextFocusDownId },
            "nextFocusLeftId" to { view: View -> view.nextFocusLeftId },
            "nextFocusRightId" to { view: View -> view.nextFocusRightId },
        )
        for (source in viewsById.values) {
            for ((direction, targetId) in directions) {
                val id = targetId(source)
                if (id == View.NO_ID) continue
                val target = viewsById[id]
                if (target == null || !target.isAttachedToWindow || !target.isShown || !target.isFocusable) {
                    Log.w(
                        MENU_SCREEN_TAG,
                        "Destino de foco inválido: $direction desde id=${source.id} hacia id=$id",
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.w(MENU_SCREEN_TAG, "No se pudo validar el mapa de foco", e)
    }
}

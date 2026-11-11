// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenPlayerHost.kt
// Traslada la vista real del reproductor al hero del menú y la restaura al cerrarse.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu

import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.ui.AspectRatioFrameLayout

/** Mueve el PlayerView al hero mientras el menú está abierto y lo restaura al cerrarse. */
internal class MenuScreenPlayerHost(
    private val activity: MainActivity,
    private val heroCard: FrameLayout,
    private val fallbackArt: ImageView,
) {
    private var originalParent: ViewGroup? = null
    private var originalIndex = -1
    private var originalLayoutParams: ViewGroup.LayoutParams? = null
    private var originalResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var attachCalled = false
    private var restored = false

    fun attach() {
        if (attachCalled || restored) return
        attachCalled = true

        val playerView = activity.playerView
        originalParent = playerView.parent as? ViewGroup
        originalIndex = originalParent?.indexOfChild(playerView) ?: -1
        originalLayoutParams = playerView.layoutParams
        originalResizeMode = playerView.resizeMode

        val parent = originalParent
        if (parent == null) {
            fallbackArt.visibility = android.view.View.VISIBLE
            Log.w(TAG, "PlayerView sin padre original al abrir el menú; se muestra el arte del hero")
            return
        }

        try {
            parent.removeView(playerView)
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            heroCard.addView(playerView, 1, FrameLayout.LayoutParams(MENU_MATCH, MENU_MATCH))
        } catch (error: Exception) {
            fallbackArt.visibility = android.view.View.VISIBLE
            Log.w(TAG, "No se pudo adjuntar PlayerView al hero; se conserva el arte", error)
        }
    }

    fun restore() {
        if (restored) return
        restored = true

        val playerView = activity.playerView
        try {
            val attachedOriginalParent = originalParent?.takeIf { it.isAttachedToWindow }
            val targetParent = attachedOriginalParent ?: activity.statusHost.takeIf { it.isAttachedToWindow }
            if (targetParent == null) {
                Log.w(TAG, "No hay padre adjunto para restaurar PlayerView; se deja sin mover")
                fallbackArt.visibility = android.view.View.VISIBLE
                return
            }

            (playerView.parent as? ViewGroup)?.removeView(playerView)

            playerView.resizeMode = if (attachedOriginalParent != null) {
                originalResizeMode
            } else {
                val preferred = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .safeInt(MainActivity.KEY_RESIZE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
                preferred.takeIf {
                    it in AspectRatioFrameLayout.RESIZE_MODE_FIT..AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } ?: AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

            val params = if (attachedOriginalParent != null) {
                originalLayoutParams ?: FrameLayout.LayoutParams(MENU_MATCH, MENU_MATCH)
            } else {
                FrameLayout.LayoutParams(MENU_MATCH, MENU_MATCH)
            }
            val index = if (attachedOriginalParent != null) {
                originalIndex.coerceIn(0, targetParent.childCount)
            } else {
                0
            }
            targetParent.addView(playerView, index, params)
            fallbackArt.visibility = android.view.View.VISIBLE
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo restaurar PlayerView", error)
            fallbackArt.visibility = android.view.View.VISIBLE
        }
    }

    private companion object {
        const val TAG = "MenuScreenPlayerHost"
    }
}

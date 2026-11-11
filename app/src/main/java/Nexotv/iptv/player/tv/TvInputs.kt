@file:Suppress("PackageName")
@file:UnstableApi

package Nexotv.iptv.player.tv
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import android.graphics.Color
import android.widget.EditText
import androidx.media3.common.util.UnstableApi

// ----------------------------------------------------------------------------
// Campos de entrada y manejo de foco
// ----------------------------------------------------------------------------

/**
 * Aplica el estilo visual de TV a un [EditText], incluyendo el fondo que cambia
 * según el foco y la animación de elevación.
 *
 * @param input campo de texto a estilizar.
 * @param minHeightDp altura mínima en dp (por defecto 58).
 */
internal fun MainActivity.styleTvInput(input: EditText, minHeightDp: Int = 58) {
    input.textSize = 17f
    input.setTextColor(Color.WHITE)
    input.setHintTextColor(getColor(R.color.tv_text_secondary))
    input.minimumHeight = dp(minHeightDp.coerceAtLeast(0))
    input.setPadding(dp(18), dp(10), dp(18), dp(10))
    input.background = menuRowBackground(this)
    input.setOnFocusChangeListener { view, focused ->
        animarFoco(view, focused, 4)
    }
}

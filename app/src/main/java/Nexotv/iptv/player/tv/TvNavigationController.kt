package Nexotv.iptv.player.tv
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.settings.*
import android.view.View
import androidx.media3.common.util.UnstableApi
import java.util.ArrayDeque

internal interface TvNavigationHost {
    fun runNavigationAction(action: () -> Unit)
}

/**
 * Owns TV screen navigation state while existing screen builders continue to
 * use the MainActivity compatibility APIs during the incremental migration.
 */
@UnstableApi
internal class TvNavigationController(
    private val host: TvNavigationHost,
) {
    var menuOrigin: MainActivity.MenuDestination? = null
    var menuReturnFocus: View? = null

    private val screenBackStack = ArrayDeque<() -> Unit>()

    fun pushScreenBackStack(returnAction: () -> Unit) {
        if (screenBackStack.size >= MAX_SCREEN_BACK_STACK) screenBackStack.removeFirst()
        screenBackStack.addLast(returnAction)
    }

    fun popScreenBackStack(): Boolean {
        val action = screenBackStack.pollLast() ?: return false
        host.runNavigationAction(action)
        return true
    }

    fun clearScreenBackStack() {
        screenBackStack.clear()
    }

    fun consumeMenuOrigin(onBack: (MainActivity.MenuDestination) -> Unit): Boolean {
        val origin = menuOrigin ?: return false
        menuOrigin = null
        onBack(origin)
        return true
    }

    fun release() {
        screenBackStack.clear()
        menuOrigin = null
        menuReturnFocus = null
    }

    companion object {
        private const val MAX_SCREEN_BACK_STACK = 16
    }
}

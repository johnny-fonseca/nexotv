// app/src/main/java/Nexotv/iptv/player/menu/MainMenuScreenConstants.kt
// Constantes de tiempos, dimensiones y pesos del menú principal, carrusel y paleta de colores.
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import android.view.ViewGroup

internal const val MENU_SCREEN_TAG = "MainMenuScreen"
internal const val MENU_MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val MENU_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// Hero
internal const val MENU_HERO_PROGRESS_TRACK_WIDTH_DP = 80

internal val MENU_CAROUSEL_IMAGES = intArrayOf(
    R.drawable.carrusel_01,
    R.drawable.carrusel_02,
    R.drawable.carrusel_03,
    R.drawable.carrusel_04,
    R.drawable.carrusel_05,
    R.drawable.carrusel_06,
)

@Suppress("ConstVal", "PropertyCouldBeConst")
internal object MenuScreenPalette {
    private val isFutv = BuildConfig.BRAND_KEY == "futv"
    val textSoft = if (isFutv) 0xFFF2F0E8.toInt() else 0xFFEAF1FF.toInt()
    val separator = if (isFutv) 0xFF594A25.toInt() else 0xFF35538B.toInt()
    val textMuted = if (isFutv) 0xFFC6B780.toInt() else 0xFFB4C7E8.toInt()
    val textHint = if (isFutv) 0xFF99864D.toInt() else 0xFF8299C4.toInt()
    val cardSubtitle = if (isFutv) 0xFFD0CCBE.toInt() else 0xFFCDD9ED.toInt()
    val liveSubtitle = if (isFutv) 0xFFD0CCBE.toInt() else 0xFFCDD9ED.toInt()
    val heroSubtitle = if (isFutv) 0xFFE0D9C4.toInt() else 0xFFDDE8FA.toInt()
    val error = 0xFFF87171.toInt()
    val reminderAccent = if (isFutv) 0xFFFFB300.toInt() else 0xFFF04455.toInt()
    val heroScrim = if (isFutv) 0xE6000000.toInt() else 0xE6081230.toInt()
    val heroTopScrim = if (isFutv) 0xA6000000.toInt() else 0xA6081230.toInt()
    val overlayPillFill = if (isFutv) 0xD01C1A14.toInt() else 0xD0102650.toInt()
    val overlayPillStroke = if (isFutv) 0xFF5A4A1A.toInt() else 0xFF3B5D9C.toInt()
    val playIdleFill = if (isFutv) 0xFF352900.toInt() else 0xFF8F1D2D.toInt()
    val playIdleTint = if (isFutv) 0xFFFFF8E5.toInt() else 0xFFFFFFFF.toInt()
    val playFocusedTint = if (isFutv) 0xFF1B1600.toInt() else 0xFF351018.toInt()
    val progressTrack = if (isFutv) 0xFF55451B.toInt() else 0xFF395284.toInt()
    val glassTop = if (isFutv) 0xFF24211A.toInt() else 0xFF14254A.toInt()
    val glassBottom = if (isFutv) 0xFF1A1916.toInt() else 0xFF101D3A.toInt()
    val glassStroke = if (isFutv) 0xFF594B28.toInt() else 0xFF35538B.toInt()
    val glow = reminderAccent
}

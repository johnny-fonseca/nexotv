// MainMenuBuilders.kt
@file:androidx.media3.common.util.UnstableApi
@file:Suppress("SetTextI18n", "NewApi", "unused", "DEPRECATION", "InlinedApi", "PropertyCouldBeConst", "UsableSpace", "HardCodedUrl", "DestructuringDeclaration", "BooleanLiteralArgument", "FoldableIfThen")

package Nexotv.iptv.player.menu
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.tv.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

internal const val MENU_SIDEBAR_WIDTH_DP = 208
internal const val MENU_SIDEBAR_PAD_H_DP = 18
internal const val MENU_SIDEBAR_PAD_TOP_DP = 24
internal const val MENU_SIDEBAR_PAD_BOTTOM_DP = 18
internal const val MENU_STATUS_ICON_DP = 16
internal const val MENU_STATUS_ICON_MARGIN_DP = 8
internal const val MENU_STATUS_SEPARATOR_MARGIN_DP = 6
internal const val MENU_CLOCK_ICON_DP = 15
internal const val MENU_CLOCK_ICON_MARGIN_DP = 8
internal const val MENU_PILL_PAD_H_DP = 14
internal const val MENU_PILL_PAD_V_DP = 6

// Hero
internal const val MENU_HERO_SCRIM_HEIGHT_DP = 190
internal const val MENU_HERO_TOP_SCRIM_HEIGHT_DP = 84
internal const val MENU_HERO_BRAND_ICON_DP = 30
internal const val MENU_BRAND_ICON_MARGIN_DP = 10
internal const val MENU_HERO_TOP_PAD_DP = 12
internal const val MENU_HERO_LIVE_CORNER_DP = 13
internal const val MENU_HERO_LIVE_PAD_H_DP = 10
internal const val MENU_HERO_LIVE_PAD_V_DP = 4
internal const val MENU_HERO_LIVE_MARGIN_DP = 8
internal const val MENU_HERO_SUB_ICON_DP = 14
internal const val MENU_HERO_SUB_ICON_MARGIN_DP = 6
internal const val MENU_HERO_PLAY_SIZE_DP = 56
internal const val MENU_HERO_PLAY_ICON_DP = 24
internal const val MENU_HERO_PLAY_MARGIN_DP = 12
internal const val MENU_HERO_PLAY_STROKE_DP = 3
internal const val MENU_HERO_PLAY_LIFT_Z = 8f
internal const val MENU_HERO_TITLE_MIN_SP = 26
internal const val MENU_HERO_TITLE_MAX_SP = 38
internal const val MENU_HERO_TITLE_STEP_SP = 1
internal const val MENU_HERO_SUBTITLE_MARGIN_TOP_DP = 2
internal const val MENU_HERO_SUBTITLE_MARGIN_BOTTOM_DP = 6
internal const val MENU_HERO_PROGRESS_HEIGHT_DP = 4
internal const val MENU_HERO_BOTTOM_PAD_V_DP = 10
internal const val MENU_HERO_BOTTOM_PAD_BOTTOM_DP = 12
internal const val MENU_HERO_INFO_MARGIN_DP = 14

// Tarjetas de vidrio
internal const val MENU_CARD_PAD_H_DP = 16
internal const val MENU_CARD_PAD_V_DP = 14
internal const val MENU_CARD_ICON_DP = 14
internal const val MENU_CARD_ICON_MARGIN_DP = 6
internal const val MENU_CARD_HEADER_MARGIN_DP = 6
internal const val MENU_CARD_TITLE_MARGIN_DP = 2
internal const val MENU_CARD_BTN_MARGIN_DP = 8

// Tarjeta "en vivo" bajo el hero
internal const val MENU_LIVE_DETAILS_PAD_H_DP = 20
internal const val MENU_LIVE_DETAILS_PAD_V_DP = 14
internal const val MENU_LIVE_DETAILS_TITLE_MARGIN_DP = 3
internal const val MENU_LIVE_DETAILS_SUBTITLE_MARGIN_DP = 2
internal const val MENU_LIVE_DETAILS_ACTIONS_MARGIN_DP = 10
internal const val MENU_LIVE_DETAILS_BTN_MARGIN_DP = 8

// Distribución general
internal const val MENU_CONTENT_PAD_H_DP = 48
internal const val MENU_CONTENT_PAD_TOP_DP = 27
internal const val MENU_CONTENT_PAD_BOTTOM_DP = 27
internal const val MENU_LEFT_COLUMN_WEIGHT = 0.68f
internal const val MENU_RIGHT_COLUMN_WEIGHT = 0.32f
internal const val MENU_LEFT_HERO_WEIGHT = 0.68f
internal const val MENU_LEFT_DETAILS_WEIGHT = 0.32f
internal const val MENU_LEFT_DETAILS_MARGIN_TOP_DP = 10
internal const val MENU_RIGHT_CARDS_MARGIN_DP = 10
internal const val MENU_LOWER_CARDS_MARGIN_DP = 12

internal fun MainActivity.menuScreenBuildRoot(
    topBar: LinearLayout,
    content: LinearLayout
): LinearLayout {
    val activity = this
    return LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF101511.toInt(), 0xFF0B0F0C.toInt())
        )
        clipChildren = false
        clipToPadding = false
        addView(topBar, LinearLayout.LayoutParams(dp(MENU_SIDEBAR_WIDTH_DP), MENU_MATCH))
        addView(content, LinearLayout.LayoutParams(0, MENU_MATCH, 1f))
    }
}

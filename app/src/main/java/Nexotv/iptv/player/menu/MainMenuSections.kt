// MainMenuSections.kt
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

/** Tarjeta de vidrio bajo el hero: etiqueta "EN VIVO", título del canal y accesos rápidos. */
internal fun MainActivity.menuScreenBuildLiveDetails(
    actionsButton: View,
    remindButton: View
): MenuScreenLiveDetails {
    val activity = this
    val title = menuScreenLabel(18f, getString(R.string.tv_menu_live_card_title), Color.WHITE, bold = true)
    val secondaryTextMinSp = resources.getDimension(R.dimen.tv_classic_menu_secondary_text_min) /
        resources.displayMetrics.scaledDensity
    val subtitle = menuScreenLabel(12f.coerceAtLeast(secondaryTextMinSp), getString(R.string.tv_menu_live_card_subtitle), MenuScreenPalette.cardSubtitle)
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = menuScreenGlassSurface()
        applyTvCardDepth(this)
        setPadding(
            dp(MENU_LIVE_DETAILS_PAD_H_DP), dp(MENU_LIVE_DETAILS_PAD_V_DP),
            dp(MENU_LIVE_DETAILS_PAD_H_DP), dp(MENU_LIVE_DETAILS_PAD_V_DP)
        )
        clipChildren = false
        clipToPadding = false
        addView(menuScreenLabel(10f, getString(R.string.tv_menu_live_marker), getColor(R.color.tv_turquoise), bold = true))
        addView(title, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP).apply {
            topMargin = dp(MENU_LIVE_DETAILS_TITLE_MARGIN_DP)
        })
        addView(subtitle, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP).apply {
            topMargin = dp(MENU_LIVE_DETAILS_SUBTITLE_MARGIN_DP)
        })
        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(actionsButton, LinearLayout.LayoutParams(0, MENU_WRAP, 1f).apply {
                marginEnd = dp(MENU_LIVE_DETAILS_BTN_MARGIN_DP)
            })
            addView(remindButton, LinearLayout.LayoutParams(0, MENU_WRAP, 1f))
        }
        addView(actionsRow, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP).apply {
            topMargin = dp(MENU_LIVE_DETAILS_ACTIONS_MARGIN_DP)
        })
    }
    return MenuScreenLiveDetails(root, title, subtitle)
}

/** Cuerpo principal: emisión destacada y acciones de programación/grabación en una columna auxiliar. */
internal fun MainActivity.menuScreenBuildContent(
    hero: MenuScreenHero,
    liveDetails: MenuScreenLiveDetails,
    nextCard: MenuScreenInfoCard,
    recCard: MenuScreenInfoCard
): LinearLayout {
    val activity = this
    val leftContent = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(hero.card, LinearLayout.LayoutParams(MENU_MATCH, 0, MENU_LEFT_HERO_WEIGHT))
        addView(liveDetails.root, LinearLayout.LayoutParams(MENU_MATCH, 0, MENU_LEFT_DETAILS_WEIGHT).apply {
            topMargin = dp(MENU_LEFT_DETAILS_MARGIN_TOP_DP)
        })
    }
    val lowerCards = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(nextCard.root, LinearLayout.LayoutParams(MENU_MATCH, 0, 1f).apply {
            bottomMargin = dp(MENU_LOWER_CARDS_MARGIN_DP)
        })
        addView(recCard.root, LinearLayout.LayoutParams(MENU_MATCH, 0, 1f))
    }
    return LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(
            dp(MENU_CONTENT_PAD_H_DP), dp(MENU_CONTENT_PAD_TOP_DP),
            dp(MENU_CONTENT_PAD_H_DP), dp(MENU_CONTENT_PAD_BOTTOM_DP)
        )
        addView(leftContent, LinearLayout.LayoutParams(0, MENU_MATCH, MENU_LEFT_COLUMN_WEIGHT).apply {
            marginEnd = dp(MENU_RIGHT_CARDS_MARGIN_DP)
        })
        addView(lowerCards, LinearLayout.LayoutParams(0, MENU_MATCH, MENU_RIGHT_COLUMN_WEIGHT))
    }
}

// MainMenuRows.kt
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

internal fun MainActivity.menuScreenBuildTopBar(
    navigation: LinearLayout,
    onStatusClick: () -> Unit
): MenuScreenTopBar {
    val activity = this

    val statusIcon = ImageView(activity).apply { setImageResource(R.drawable.tv_icon_wifi) }
    val statusText = menuScreenLabel(10f, color = MenuScreenPalette.textSoft).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val statusBadge = menuScreenLabel(11f, bold = true)
    val statusPill = LinearLayout(activity).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val idleBackground = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(MenuScreenPalette.overlayPillFill)
            setStroke(dp(1), MenuScreenPalette.overlayPillStroke)
        }
        val focusedBackground = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(getColor(R.color.tv_menu_row_focused))
            setStroke(dp(3), getColor(R.color.tv_turquoise))
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBackground)
            addState(intArrayOf(android.R.attr.state_pressed), focusedBackground)
            addState(intArrayOf(), idleBackground)
        }
        setPadding(dp(10), dp(10), dp(10), dp(10))
        minimumHeight = dp(48)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        applyTvFocus(this, focusCornerRadiusDp = 10f)
        setOnClickListener { menuScreenGuard("clicEstado") { onStatusClick() } }
        addView(statusIcon, LinearLayout.LayoutParams(dp(MENU_STATUS_ICON_DP), dp(MENU_STATUS_ICON_DP)).apply {
            marginEnd = dp(MENU_STATUS_ICON_MARGIN_DP)
        })
        addView(statusText)
        addView(
            menuScreenLabel(12f, "│", MenuScreenPalette.separator),
            LinearLayout.LayoutParams(MENU_WRAP, MENU_WRAP).apply { marginEnd = dp(MENU_STATUS_SEPARATOR_MARGIN_DP) }
        )
        addView(statusBadge)
    }

    val secondaryTextMinSp = resources.getDimension(R.dimen.tv_classic_menu_secondary_text_min) /
        resources.displayMetrics.scaledDensity
    val clockText = menuScreenLabel(13f.coerceAtLeast(secondaryTextMinSp), color = MenuScreenPalette.textSoft).apply { maxLines = 1 }
    val clockPill = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.tv_icon_clock)
            imageTintList = ColorStateList.valueOf(MenuScreenPalette.textSoft)
        }, LinearLayout.LayoutParams(dp(MENU_CLOCK_ICON_DP), dp(MENU_CLOCK_ICON_DP)).apply {
            marginEnd = dp(MENU_CLOCK_ICON_MARGIN_DP)
        })
        addView(clockText)
    }
    val showClock = menuScreenGuardOr("preferenciaReloj", true) {
        menuScreenPrefs().getBoolean(MainActivity.KEY_SHOW_CLOCK, true)
    }
    val brand = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(menuBrandBadge(activity), LinearLayout.LayoutParams(
            dp(MENU_BRAND_BADGE_SIZE_DP), dp(MENU_BRAND_BADGE_SIZE_DP)
        ).apply {
            marginEnd = dp(10)
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menuScreenLabel(16f, BuildConfig.BRAND_NAME, Color.WHITE, bold = true))
            addView(menuScreenLabel(9f, BuildConfig.CHANNEL_NAME.uppercase(), MenuScreenPalette.textMuted))
        })
    }
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(getColor(R.color.tv_menu_panel), getColor(R.color.tv_menu_bg))
        )
        setPadding(
            dp(MENU_SIDEBAR_PAD_H_DP), dp(MENU_SIDEBAR_PAD_TOP_DP),
            dp(MENU_SIDEBAR_PAD_H_DP), dp(MENU_SIDEBAR_PAD_BOTTOM_DP)
        )
        clipChildren = false
        clipToPadding = false
        addView(brand, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP))
        addView(View(activity).apply {
            setBackgroundColor(MenuScreenPalette.separator)
        }, LinearLayout.LayoutParams(MENU_MATCH, dp(1)).apply {
            topMargin = dp(22)
            bottomMargin = dp(20)
        })
        addView(menuScreenLabel(10f, getString(R.string.tv_menu_explore), MenuScreenPalette.textMuted, bold = true),
            LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP).apply { bottomMargin = dp(10) })
        addView(navigation, LinearLayout.LayoutParams(MENU_MATCH, 0, 1f))
        addView(clockPill, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP).apply {
            bottomMargin = dp(10)
        })
        addView(statusPill, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP))
    }

    return MenuScreenTopBar(root, statusPill, statusIcon, statusText, statusBadge, clockText, clockPill, showClock)
}

package Nexotv.iptv.player.player
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.guide.GuideProgramTimeIndex

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Capa informativa del reproductor, no interactiva; todas las teclas del control remoto continúan hacia la Activity. */
internal class PlayerInfoOverlay(context: Context) : FrameLayout(context) {

    private companion object {
        const val CARD_ELEVATION_DP = 16f
        const val CARD_AMBIENT_SHADOW = 0xCC000000.toInt()
        const val CARD_SPOT_SHADOW = 0xE6000000.toInt()
        const val LOGO_SIZE_DP = 32f
        const val LOGO_MARGIN_END_DP = 2f
        const val PROGRESS_SHADOW_HEIGHT_DP = 3f
        const val PROGRESS_SHADOW_MID_COLOR = 0x10000000
        const val BADGE_RADIUS_DP = 10f
        const val CLOCK_ELEVATION_DP = 6f
        const val CLOCK_PADDING_VERTICAL_DP = 5f
        const val CLOCK_SHADOW_RADIUS_DP = 3f
        const val CLOCK_SHADOW_COLOR = 0x99000000.toInt()
        const val CLOCK_FADE_MS = 180L
        const val TITLE_MIN_SP = 18
        const val TITLE_MAX_SP = 26
        const val SCHEDULE_PATTERN = "h:mma"
        const val DESCRIPTION_SEPARATOR = ". "
    }

    private val density: Float
        get() = resources.displayMetrics.density
    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels
    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    private val card = LinearLayout(context)
    private val badge = TvStyle.statusChip(context, context.getString(R.string.player_live))
    private val brandLabel = TextView(context)
    private val title = TextView(context)
    private val schedule = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val next = TextView(context)
    private val state = TextView(context)
    private val clock = TextView(context)
    private val channelLogo = ImageView(context).apply {
        setImageResource(R.drawable.channel_icon)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MenuScreenPalette.glassBottom)
        }
        clipToOutline = true
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = context.getString(R.string.app_name)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        visibility = GONE
    }
    private val scheduleFormat by lazy(LazyThreadSafetyMode.NONE) { SimpleDateFormat(SCHEDULE_PATTERN, Locale.US) }

    private val safeHorizontalMargin: Int
        get() = (screenWidth * fraction(R.fraction.tv_safe_margin_horizontal)).toInt()
    private val safeVerticalMargin: Int
        get() = (screenHeight * fraction(R.fraction.tv_safe_margin_vertical)).toInt()


    init {
        isFocusable = false
        isClickable = false
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
        buildCard()
        buildClock()
        card.visibility = GONE
        clock.visibility = GONE
        visibility = VISIBLE
    }

    private fun buildCard() {
        val horizontalPadding = px(R.dimen.tv_player_card_padding_horizontal)
        val verticalPadding = px(R.dimen.tv_player_card_padding_vertical)
        val gap = px(R.dimen.tv_player_card_gap)

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(0, 0, 0, 0)
        card.background = TvStyle.playerCardSurface(context)
        card.elevation = density * CARD_ELEVATION_DP
        card.clipChildren = false
        card.clipToPadding = false
        card.clipToOutline = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            card.outlineAmbientShadowColor = CARD_AMBIENT_SHADOW
            card.outlineSpotShadowColor = CARD_SPOT_SHADOW
        }
        card.isFocusable = false
        card.isClickable = false

        card.addView(View(context).apply {
            setBackgroundColor(MenuScreenPalette.reminderAccent)
        }, LinearLayout.LayoutParams(-1, dp(3f)))

        val logoSize = dp(LOGO_SIZE_DP)
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            isFocusable = false
            addView(
                channelLogo,
                LinearLayout.LayoutParams(logoSize, logoSize).apply { marginEnd = dp(LOGO_MARGIN_END_DP) },
            )
            addView(brandLabel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
            addView(badge, LinearLayout.LayoutParams(-2, -2))
        }

        brandLabel.text = BuildConfig.BRAND_NAME.uppercase(Locale.ROOT)
        brandLabel.setTextColor(MenuScreenPalette.textMuted)
        brandLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        brandLabel.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        brandLabel.letterSpacing = 0.12f
        brandLabel.maxLines = 1

        style(title, title = true)
        title.maxLines = 1
        title.isSingleLine = true
        title.ellipsize = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            title.setAutoSizeTextTypeUniformWithConfiguration(
                TITLE_MIN_SP,
                TITLE_MAX_SP,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }
        style(schedule)
        style(next)
        style(state)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(top)
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(schedule, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(buildProgressHost(gap))
            addView(next, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            addView(state, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }
        card.addView(content, LinearLayout.LayoutParams(-1, -2))

        addView(
            card,
            LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = safeHorizontalMargin
                bottomMargin = safeVerticalMargin
                width = (screenWidth * fraction(R.fraction.tv_player_card_max_width)).toInt()
            },
        )
    }

    private fun buildProgressHost(gap: Int): FrameLayout {
        progress.max = resources.getInteger(R.integer.tv_player_progress_max).coerceAtLeast(1)
        progress.progressTintList = ColorStateList.valueOf(context.getColor(R.color.tv_live))
        progress.progressBackgroundTintList = ColorStateList.valueOf(MenuScreenPalette.separator and 0x66FFFFFF)

        val progressHeight = px(R.dimen.tv_player_progress_height)
        val shadowHeight = dp(PROGRESS_SHADOW_HEIGHT_DP)
        val host = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(-1, progressHeight + shadowHeight).apply { topMargin = gap }
        }
        val shadow = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, PROGRESS_SHADOW_MID_COLOR, Color.TRANSPARENT),
            )
        }
        host.addView(
            shadow,
            LayoutParams(-1, shadowHeight, Gravity.TOP).apply { topMargin = progressHeight },
        )
        host.addView(progress, LayoutParams(-1, progressHeight, Gravity.TOP))
        return host
    }

    private fun buildClock() {
        val fontScale = resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val verticalPadding = dp(CLOCK_PADDING_VERTICAL_DP)
        clock.textSize = resources.getDimension(R.dimen.tv_text_secondary) / (density * fontScale)
        clock.setTextColor(context.getColor(R.color.tv_text_primary))
        clock.alpha = fraction(R.fraction.tv_player_clock_opacity)
        clock.setPadding(px(R.dimen.tv_space_2), verticalPadding, px(R.dimen.tv_space_2), verticalPadding)
        clock.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        clock.letterSpacing = 0.04f
        clock.background = menuPill(
            context,
            MenuScreenPalette.overlayPillFill,
            MenuScreenPalette.overlayPillStroke,
        )
        val clockAccent = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MenuScreenPalette.reminderAccent)
            setSize(dp(7f), dp(7f))
        }
        clock.setCompoundDrawablesRelativeWithIntrinsicBounds(clockAccent, null, null, null)
        clock.compoundDrawablePadding = dp(8f)
        clock.elevation = density * CLOCK_ELEVATION_DP
        clock.setShadowLayer(density * CLOCK_SHADOW_RADIUS_DP, 0f, density, CLOCK_SHADOW_COLOR)
        clock.isFocusable = false
        addView(
            clock,
            LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = safeVerticalMargin
                marginEnd = safeHorizontalMargin
            },
        )
    }

    override fun onDetachedFromWindow() {
        clock.animate().cancel()
        super.onDetachedFromWindow()
    }

    fun hideInfo() {
        card.visibility = GONE
        setClockVisible(false)
    }

    fun showInfo(
        programIndex: GuideProgramTimeIndex,
        isPlaying: Boolean,
        isRecording: Boolean,
        textSizeSp: Int,
        isConnecting: Boolean = false,
    ) {
        visibility = VISIBLE
        card.visibility = VISIBLE

        val now = System.currentTimeMillis()
        val airing = programIndex.at(now)
        val upcoming = programIndex.nextAfter(now)
        val isLive = isRecording || isPlaying || airing != null
        val scheduledProgram = airing.takeIf { !isRecording }
        val upcomingProgram = upcoming.takeIf { !isRecording }

        channelLogo.visibility = VISIBLE
        applyBadgeState(
            labelRes = when {
                isRecording -> R.string.player_recording
                isPlaying || airing != null -> R.string.player_live
                else -> R.string.player_channel
            },
            isLive = isLive,
        )

        title.text = when {
            airing != null -> airing.title
            !isRecording && isPlaying -> context.getString(R.string.player_live)
            else -> ""
        }
        title.visibility = visibleIf(scheduledProgram != null)
        schedule.visibility = visibleIf(scheduledProgram != null)
        progress.visibility = visibleIf(scheduledProgram != null)
        next.visibility = visibleIf(upcomingProgram != null)
        badge.visibility = VISIBLE

        if (scheduledProgram != null) {
            schedule.text = context.getString(
                R.string.player_schedule_time_range,
                formatTime(scheduledProgram.startMs),
                formatTime(scheduledProgram.endMs),
            )
            progress.progress = progressOf(scheduledProgram.startMs, scheduledProgram.endMs, now)
        }
        if (upcomingProgram != null) {
            next.text = context.getString(R.string.player_up_next, upcomingProgram.title)
        }

        state.text = when {
            isPlaying && airing == null && !isRecording -> context.getString(R.string.player_live)
            isPlaying -> ""
            isConnecting -> context.getString(R.string.player_connecting)
            else -> context.getString(R.string.player_paused)
        }
        state.visibility = visibleIf(!isPlaying || (airing == null && !isRecording))

        TvStyle.applyPlayerText(badge, textSizeSp, fixedSize = R.dimen.tv_player_badge_size)
        applyBadgePadding()
        TvStyle.applyPlayerText(title, textSizeSp, title = true)
            TvStyle.applyPlayerText(schedule, textSizeSp, secondary = true, baseSize = R.dimen.tv_player_schedule_size)
        TvStyle.applyPlayerText(next, textSizeSp, secondary = true, baseSize = R.dimen.tv_player_secondary_size)
        TvStyle.applyPlayerText(state, textSizeSp, secondary = true, baseSize = R.dimen.tv_player_schedule_size)

        title.setTextColor(context.getColor(R.color.tv_text_primary))
        badge.setTextColor(context.getColor(R.color.tv_text_primary))
        badge.alpha = 1f

        card.contentDescription = listOfNotNull(
            brandLabel.text?.toString()?.takeIf(String::isNotBlank),
            badge.text?.toString()?.takeIf(String::isNotBlank),
            title.text?.toString()?.takeIf(String::isNotBlank),
            schedule.text?.toString()?.takeIf { schedule.isVisible },
            next.text?.toString()?.takeIf { next.isVisible },
            state.text?.toString()?.takeIf { state.isVisible },
        ).joinToString(DESCRIPTION_SEPARATOR)
    }

    @Suppress("unused")
    fun showStateOnly(message: String, textSizeSp: Int) {
        visibility = VISIBLE
        card.visibility = VISIBLE
        title.visibility = GONE
        schedule.visibility = GONE
        progress.visibility = GONE
        next.visibility = GONE
        state.text = message
        state.visibility = VISIBLE

        badge.visibility = VISIBLE
        badge.text = context.getString(R.string.player_channel)
        badge.background = idleBadgeBackground()
        TvStyle.applyPlayerText(badge, textSizeSp, fixedSize = R.dimen.tv_player_badge_size)
        badge.setTextColor(context.getColor(R.color.tv_text_primary))
        applyBadgePadding()
        TvStyle.applyPlayerText(state, textSizeSp, secondary = true, baseSize = R.dimen.tv_player_schedule_size)
    }

    fun setClock(value: String, visible: Boolean) {
        clock.text = value
        setClockVisible(visible)
    }

    private fun setClockVisible(visible: Boolean) {
        val targetVisibility = visibleIf(visible)
        if (clock.visibility == targetVisibility) {
            if (visible && clock.alpha != 1f) {
                clock.animate().cancel()
                clock.alpha = 1f
            }
            return
        }
        clock.animate().cancel()
        if (visible) {
            clock.alpha = 1f
            clock.visibility = VISIBLE
        } else {
            clock.animate().alpha(0f).setDuration(CLOCK_FADE_MS).withEndAction {
                clock.visibility = GONE
                clock.alpha = 1f
            }.start()
        }
    }

    private fun applyBadgeState(labelRes: Int, isLive: Boolean) {
        badge.text = context.getString(labelRes)
        badge.contentDescription = badge.text
        badge.background = if (isLive) liveBadgeBackground() else idleBadgeBackground()
        badge.setTextColor(context.getColor(R.color.tv_text_primary))
    }

    private fun liveBadgeBackground() = GradientDrawable().apply {
        cornerRadius = density * BADGE_RADIUS_DP
        setColor(context.getColor(R.color.tv_menu_red_btn))
    }

    private fun idleBadgeBackground() = menuPill(
        context,
        MenuScreenPalette.overlayPillFill,
        MenuScreenPalette.overlayPillStroke,
    )

    private fun applyBadgePadding() {
        val horizontal = px(R.dimen.tv_player_badge_padding_horizontal)
        val vertical = px(R.dimen.tv_player_badge_padding_vertical)
        badge.setPadding(horizontal, vertical, horizontal, vertical)
    }

    private fun formatTime(millis: Long): String =
        scheduleFormat.format(Date(millis)).lowercase(Locale.ROOT)

    private fun progressOf(startMs: Long, endMs: Long, nowMs: Long): Int {
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val ratio = (nowMs - startMs).toDouble() / duration
        return (ratio * progress.max).toInt().coerceIn(0, progress.max)
    }

    private fun style(view: TextView, title: Boolean = false) {
        TvStyle.applyPlayerText(
            view,
            resources.getInteger(R.integer.tv_panel_text_max_sp),
            title,
            baseSize = if (title) R.dimen.tv_player_title_size else R.dimen.tv_player_schedule_size,
        )
    }

    private fun visibleIf(condition: Boolean) = if (condition) VISIBLE else GONE

    private fun fraction(id: Int) = resources.getFraction(id, 1, 1)

    private fun dp(value: Float) = (density * value).toInt()

    private fun px(id: Int) = resources.getDimensionPixelSize(id)
}

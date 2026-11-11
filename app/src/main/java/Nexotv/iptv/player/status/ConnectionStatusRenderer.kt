@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("PackageName")

package Nexotv.iptv.player.status








import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.*
import android.animation.ValueAnimator
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.withRotation
import java.util.Locale

internal fun MainActivity.showStatus(message: String) {
    if (isDestroyed) return
    diagnosticsVisible = false
    playbackPanelVisible = false
    if (isPlayerInfoInitialized) playerInfo.hideInfo()
    handler.removeCallbacks(hideStatus)
    setStatusText(message)
    revealStatusPanel()
}

internal fun MainActivity.renderPlayerStatus(model: PlayerStatus?) {
    if (model == null) {
        selectedPlayerStatus = null
    } else {
        val currentPriority = selectedPlayerStatus?.let { playerStatusPriority(it) } ?: Int.MIN_VALUE
        if (playerStatusPriority(model) < currentPriority) return
        selectedPlayerStatus = model
    }
    drawPlayerStatus(selectedPlayerStatus)
}

private fun MainActivity.connectionStatusSpec(model: PlayerStatus): ConnectionStatusCardSpec = when (model) {
    PlayerStatus.Connecting -> ConnectionStatusCardSpec(
        getString(R.string.player_status_connecting_brand, BuildConfig.BRAND_NAME),
        getString(R.string.player_status_connecting_help),
        R.drawable.tv_status_connecting, MenuScreenPalette.reminderAccent,
        getString(R.string.player_status_connection_eyebrow, BuildConfig.CHANNEL_NAME),
    )
    PlayerStatus.Reconnecting -> ConnectionStatusCardSpec(
        getString(R.string.player_status_reconnecting_brand, BuildConfig.BRAND_NAME),
        getString(R.string.player_status_reconnecting_help),
        R.drawable.tv_status_reconnecting, MenuScreenPalette.reminderAccent,
        getString(R.string.player_status_connection_eyebrow, BuildConfig.CHANNEL_NAME),
    )
    is PlayerStatus.Offline -> connectionFailureStatusSpec(model.issue, null, R.drawable.tv_status_offline)
    is PlayerStatus.NoSignal -> connectionFailureStatusSpec(model.issue, model.helpResId, R.drawable.tv_status_signal)
    is PlayerStatus.NoSpace -> ConnectionStatusCardSpec(
        getString(R.string.player_status_space_title), getString(R.string.player_status_space_help),
        R.drawable.tv_status_space, getColor(R.color.tv_error),
    )
}

private fun MainActivity.connectionFailureStatusSpec(
    issue: ConnectionIssue,
    helpResId: Int?,
    icon: Int,
): ConnectionStatusCardSpec {
    val copy = connectionStatusCopy(issue, helpResId)
    return ConnectionStatusCardSpec(
        getString(copy.titleResId),
        getString(copy.helpResId),
        icon,
        getColor(R.color.tv_error),
    )
}

private fun MainActivity.detachStatusCard(card: View) {
    statusHost.removeView(card)
}

private fun MainActivity.buildStatusIconView(model: PlayerStatus, spec: ConnectionStatusCardSpec): View {
    if (model is PlayerStatus.Connecting || model is PlayerStatus.Reconnecting) {
        val iconSize = TvStyle.px(this, R.dimen.tv_status_icon_size)
        return FrameLayout(this).apply {
            addView(ConnectionPulseView(context, spec.accent), FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.channel_icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                clipToOutline = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MenuScreenPalette.glassBottom)
                    setStroke(dp(1), spec.accent)
                }
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            layoutParams = ViewGroup.LayoutParams(iconSize, iconSize)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    val tint = ColorStateList.valueOf(spec.accent)
    return ImageView(this).apply {
        setImageResource(spec.icon)
        imageTintList = tint
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

private fun MainActivity.buildStatusTextColumn(spec: ConnectionStatusCardSpec): LinearLayout {
    val textSizeSp = panelTextSizeSp()
    val shadowRadius = dp(3).toFloat()
    val shadowOffsetY = dp(1).toFloat()
    val host = this
    return LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        if (spec.eyebrow.isNotBlank()) addView(TextView(host).apply {
            text = spec.eyebrow.uppercase(Locale.ROOT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(spec.accent)
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(3))
        })
        addView(TextView(host).apply {
            text = spec.title
            TvStyle.applyPlayerText(this, textSizeSp, title = true, baseSize = R.dimen.tv_player_title_size)
            setShadowLayer(shadowRadius, 0f, shadowOffsetY, STATUS_SHADOW_COLOR)
        })
        if (spec.help.isNotBlank()) addView(TextView(host).apply {
            text = spec.help
            TvStyle.applyPlayerText(this, textSizeSp, secondary = true, baseSize = R.dimen.tv_player_schedule_size)
            setShadowLayer(shadowRadius, 0f, shadowOffsetY, STATUS_SHADOW_COLOR)
        })
    }
}

private fun MainActivity.buildStatusCard(
    model: PlayerStatus,
    spec: ConnectionStatusCardSpec,
    transientConnectionStatus: Boolean,
): LinearLayout {
    val host = this
    val iconSize = TvStyle.px(host, R.dimen.tv_status_icon_size)
    val iconGap = TvStyle.px(host, R.dimen.tv_status_icon_gap)
    val cardElevation = dp(20).toFloat()
    return LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        // Mantiene el estado de conexión sobre PlayerInfoOverlay, que ocupa toda la pantalla.
        elevation = cardElevation
        if (transientConnectionStatus) {
            background = TvStyle.playerOverlaySurface(host, TvStyle.px(host, R.dimen.tv_radius_card).toFloat())
            clipToOutline = true
            setPadding(0, 0, 0, 0)
            addView(View(host).apply {
                setBackgroundColor(spec.accent)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        } else {
            val horizontal = TvStyle.px(host, R.dimen.tv_status_card_padding_horizontal)
            val vertical = TvStyle.px(host, R.dimen.tv_status_card_padding_vertical)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = TvStyle.playerOverlaySurface(host, TvStyle.px(host, R.dimen.tv_radius_panel).toFloat())
        }
        contentDescription = listOf(spec.eyebrow, spec.title, spec.help)
            .filter(String::isNotBlank)
            .joinToString(". ")
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        isFocusable = false
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (transientConnectionStatus) {
                val horizontal = TvStyle.px(host, R.dimen.tv_status_card_padding_horizontal)
                val vertical = TvStyle.px(host, R.dimen.tv_status_card_padding_vertical)
                setPadding(horizontal, vertical, horizontal, vertical)
            }
        }
        row.addView(
            buildStatusIconView(model, spec),
            LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = iconGap },
        )
        row.addView(
            buildStatusTextColumn(spec),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(row, LinearLayout.LayoutParams(-1, -2))
    }
}

private fun MainActivity.addStatusCardActions(card: LinearLayout, model: PlayerStatus): Button? = when (model) {
    is PlayerStatus.Offline -> addStatusActions(card, model.onRetry, model.onSettings)
    is PlayerStatus.NoSignal -> addStatusActions(card, model.onRetry, model.onSettings)
    is PlayerStatus.NoSpace -> {
        val button = TvStyle.primaryButton(
            this,
            getString(R.string.player_status_recordings),
            getString(R.string.player_status_open_recordings),
            model.onRecordings,
        )
        card.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = TvStyle.px(this@addStatusCardActions, R.dimen.tv_status_action_top_gap) },
        )
        button
    }
    PlayerStatus.Connecting, PlayerStatus.Reconnecting -> null
}

private fun MainActivity.overlayBottomEdge(view: View?, defaultTopMargin: Int, defaultHeight: Int): Int? {
    if (view == null || view.parent !== statusHost || view.visibility != View.VISIBLE) return null
    val top = (view.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: defaultTopMargin
    val height = view.height.takeIf { it > 0 } ?: defaultHeight
    return top + height + dp(8)
}

private fun MainActivity.transientStatusTopMargin(): Int {
    val safeTop = safeMarginVertical()
    val recordingBottom = overlayBottomEdge(recordingIndicator, safeTop, dp(48)) ?: safeTop
    val hudBottom = overlayBottomEdge(diagnosticHudView, dp(16), dp(100)) ?: safeTop
    return maxOf(safeTop, recordingBottom, hudBottom)
}

private fun MainActivity.statusCardLayoutParams(actionable: Boolean, transientConnectionStatus: Boolean): FrameLayout.LayoutParams {
    val width = if (actionable) {
        TvStyle.fractionWidth(this, R.fraction.tv_status_action_card_max_width)
    } else {
        ViewGroup.LayoutParams.WRAP_CONTENT
    }
    val gravity = when {
        actionable -> Gravity.CENTER
        transientConnectionStatus -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        else -> Gravity.BOTTOM or Gravity.START
    }
    return FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, gravity).also { params ->
        if (actionable) return@also
        if (transientConnectionStatus) {
            params.topMargin = transientStatusTopMargin()
        } else {
            params.marginStart = safeMarginHorizontal()
            params.bottomMargin = safeMarginVertical()
        }
    }
}

internal fun MainActivity.drawPlayerStatus(model: PlayerStatus?) {
    if (isDestroyed) return
    val old = statusCard
    if (model == null) {
        if (old != null) {
            detachStatusCard(old)
            statusCard = null
            if (statusCardIsActionable) statusHost.post { statusHost.requestFocus() }
        }
        statusCardIsActionable = false
        return
    }
    old?.let { detachStatusCard(it) }
    val actionable = model is PlayerStatus.Offline || model is PlayerStatus.NoSignal || model is PlayerStatus.NoSpace
    val transientConnectionStatus = model is PlayerStatus.Connecting || model is PlayerStatus.Reconnecting
    val card = buildStatusCard(model, connectionStatusSpec(model), transientConnectionStatus)
    val primaryButton = addStatusCardActions(card, model)
    statusCard = card
    statusCardIsActionable = actionable
    statusHost.addView(card, statusCardLayoutParams(actionable, transientConnectionStatus))
    card.bringToFront()
    if (transientConnectionStatus) {
        card.alpha = 0f
        card.translationY = -dp(8).toFloat()
        card.animate().alpha(1f).translationY(0f)
            .setDuration(STATUS_REVEAL_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    if (actionable) card.post { primaryButton?.requestFocus() }
}
private fun MainActivity.isRecordingIndicatorActive(): Boolean = isRecording() || debugRecordingStartedAt != null

private fun MainActivity.removeRecordingIndicator() {
    recordingIndicator?.let(statusHost::removeView)
    recordingIndicatorIcon?.animate()?.cancel()
    recordingIndicator = null
    recordingIndicatorText = null
    recordingIndicatorIcon = null
    recordingIndicatorCollapsed = false
    recordingIndicatorBlinking = false
    handler.removeCallbacks(recordingStatusTicker)
}

private fun MainActivity.createRecordingIndicator(): LinearLayout {
    val host = this
    val iconSize = TvStyle.px(host, R.dimen.tv_status_icon_size)
    val iconGap = TvStyle.px(host, R.dimen.tv_status_icon_gap)
    val shadowRadius = dp(3).toFloat()
    val shadowOffsetY = dp(1).toFloat()
    val icon = ImageView(host).apply {
        setImageResource(R.drawable.tv_status_recording)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(host, R.color.tv_live))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val label = TextView(host).apply {
        TvStyle.applyText(this, R.dimen.tv_text_secondary, R.color.tv_text_primary, bold = true)
        setShadowLayer(shadowRadius, 0f, shadowOffsetY, STATUS_SHADOW_COLOR)
    }
    recordingIndicatorIcon = icon
    recordingIndicatorText = label
    return LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
        contentDescription = getString(R.string.player_status_recording_accessibility, "")
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = iconGap })
        addView(label)
    }
}

internal fun MainActivity.updateRecordingIndicator() {
    if (isDestroyed) return
    val startedAt = debugRecordingStartedAt ?: statusPrefs().safeLong(RECORDING_STARTED_AT_KEY, 0L)
    if (!isRecordingIndicatorActive()) {
        removeRecordingIndicator()
        positionDiagnosticHud()
        return
    }
    val indicator = recordingIndicator ?: createRecordingIndicator().also { created ->
        recordingIndicator = created
        statusHost.addView(
            created,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                marginStart = safeMarginHorizontal()
                topMargin = safeMarginVertical()
            },
        )
    }
    val elapsed = if (startedAt > 0L) (System.currentTimeMillis() - startedAt).coerceAtLeast(0L) / 1_000L else 0L
    val duration = String.format(Locale.ROOT, "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60)
    recordingIndicatorText?.text = getString(R.string.player_status_recording, duration)
    indicator.contentDescription = getString(R.string.player_status_recording_accessibility, duration)
    if (!recordingIndicatorBlinking) {
        recordingIndicatorBlinking = true
        updateRecordingIndicatorBlink()
    }
    if (!recordingIndicatorCollapsed && recordingIndicatorText?.visibility == View.VISIBLE &&
        (!isPlayerInitialized || player.playWhenReady)
    ) {
        val timeoutMs = panelTimeoutSeconds() * 1_000L
        indicator.postDelayed({
            if (recordingIndicator !== indicator || isFinishing || isDestroyed) return@postDelayed
            if (isPlayerInitialized && !player.playWhenReady) recordingIndicatorCollapsed = false
            else collapseRecordingIndicator()
        }, timeoutMs)
        recordingIndicatorCollapsed = true
    }
    positionDiagnosticHud()
    scheduleRecordingStatusTicker()
}

private fun MainActivity.updateRecordingIndicatorBlink() {
    val icon = recordingIndicatorIcon ?: return
    if (icon.parent == null || !isRecordingIndicatorActive()) {
        recordingIndicatorBlinking = false
        return
    }
    icon.animate().alpha(0.25f).setDuration(RECORDING_BLINK_MS).withEndAction {
        icon.animate().alpha(1f).setDuration(RECORDING_BLINK_MS).withEndAction {
            if (recordingIndicatorBlinking) updateRecordingIndicatorBlink()
        }.start()
    }.start()
}

private fun MainActivity.collapseRecordingIndicator() {
    val indicator = recordingIndicator ?: return
    val label = recordingIndicatorText ?: return
    val icon = recordingIndicatorIcon ?: return
    label.animate().alpha(0f).setDuration(RECORDING_COLLAPSE_MS).withEndAction {
        label.visibility = View.GONE
        indicator.background = null
        indicator.setPadding(0, 0, 0, 0)
        (icon.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.marginEnd = 0
            icon.layoutParams = params
        }
    }.start()
}

internal fun MainActivity.revealRecordingIndicator() {
    val indicator = recordingIndicator ?: return
    val label = recordingIndicatorText ?: return
    val icon = recordingIndicatorIcon ?: return
    label.animate().cancel()
    label.alpha = 1f
    label.visibility = View.VISIBLE
    indicator.background = null
    indicator.setPadding(dp(16), dp(8), dp(16), dp(8))
    (icon.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
        params.marginEnd = TvStyle.px(this, R.dimen.tv_status_icon_gap)
        icon.layoutParams = params
    }
    recordingIndicatorCollapsed = false
}

internal fun MainActivity.scheduleRecordingStatusTicker() {
    if (recordingStatusTickerScheduled) return
    recordingStatusTickerScheduled = true
    handler.postDelayed(recordingStatusTicker, integerMs(R.integer.tv_status_recording_ticker_ms))
}

internal fun MainActivity.addStatusActions(parent: LinearLayout, retry: () -> Unit, settings: () -> Unit): Button {
    val host = this
    val actions = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, TvStyle.px(host, R.dimen.tv_status_action_top_gap), 0, 0)
    }
    val primary = TvStyle.primaryButton(host, getString(R.string.player_status_retry), getString(R.string.player_status_retry_action), retry)
    val secondary = TvStyle.secondaryButton(host, getString(R.string.player_status_settings), getString(R.string.player_status_settings_action), settings)
    actions.addView(primary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    actions.addView(
        secondary,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = TvStyle.px(host, R.dimen.tv_status_action_gap) },
    )
    parent.addView(actions)
    return primary
}

private fun MainActivity.resetStatusPanelTransform() {
    status.alpha = 1f
    status.scaleX = 1f
    status.scaleY = 1f
    status.translationY = 0f
}

internal fun MainActivity.revealStatusPanel() {
    val entering = status.visibility != View.VISIBLE || status.alpha < 1f
    status.animate().cancel()
    status.visibility = View.VISIBLE
    if (!entering) {
        resetStatusPanelTransform()
        return
    }
    status.alpha = 0f
    status.scaleX = 0.95f
    status.scaleY = 0.95f
    status.translationY = dp(10).toFloat()
    status.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
        .setDuration(STATUS_REVEAL_DURATION_MS)
        .setInterpolator(DecelerateInterpolator(2f))
        .withLayer()
        .start()
}

internal fun MainActivity.fadeOutStatusPanel() {
    status.animate().cancel()
    status.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).translationY(dp(8).toFloat())
        .setDuration(STATUS_HIDE_DURATION_MS)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            if (!diagnosticsVisible && !playbackPanelVisible) status.visibility = View.GONE
            resetStatusPanelTransform()
        }.start()
}


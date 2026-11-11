package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.BuildConfig
import Nexotv.iptv.player.MainActivity
import Nexotv.iptv.player.R
import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.menu.applyTvFocus
import Nexotv.iptv.player.tv.dp
import Nexotv.iptv.player.menu.menuCardSurface
import Nexotv.iptv.player.menu.menuDarkButton
import Nexotv.iptv.player.menu.menuMainPanel
import Nexotv.iptv.player.player.safeInt
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout

internal data class GuideDetailCardBinding(
    val view: LinearLayout,
    val posterImage: ImageView,
    val posterBadgeLive: TextView,
    val posterBadgeCategory: TextView,
    val title: TextView,
    val meta: TextView,
    val statuses: LinearLayout,
    val description: TextView,
    val reminderButton: TextView,
    val recordButton: TextView,
    val savedButton: TextView,
    val tooltip: TextView,
    val restorePlayer: () -> Unit,
)

private const val DETAIL_ERROR_TAG = "GUIDE_ERROR"

@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.buildGuideDetailCard(colorMuted: Int, colorAccent: Int): GuideDetailCardBinding {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = menuMainPanel(this@buildGuideDetailCard)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        clipChildren = false
        clipToPadding = false
    }
    val poster = FrameLayout(this).apply {
        background = menuCardSurface(this@buildGuideDetailCard, 18f)
        clipToOutline = true
    }
    val image = ImageView(this).apply {
        setImageResource(R.drawable.guide_placeholder)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    poster.addView(image, FrameLayout.LayoutParams(-1, -1))

    val originalParent = runCatching { playerView.parent as? ViewGroup }.getOrNull()
    val originalIndex = originalParent?.indexOfChild(playerView) ?: -1
    val originalParams = runCatching { playerView.layoutParams }.getOrNull()
    val originalResizeMode = runCatching { playerView.resizeMode }
        .getOrDefault(AspectRatioFrameLayout.RESIZE_MODE_FIT)

    fun reattachPlayer(target: ViewGroup, index: Int, params: ViewGroup.LayoutParams?) {
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        target.addView(
            playerView,
            index.coerceIn(0, target.childCount),
            params ?: FrameLayout.LayoutParams(-1, -1),
        )
    }

    if (originalParent != null) {
        try {
            originalParent.removeView(playerView)
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            poster.addView(playerView, poster.childCount.coerceAtMost(1), FrameLayout.LayoutParams(-1, -1))
        } catch (e: Exception) {
            DiagnosticTracker.log(DETAIL_ERROR_TAG, "detailCard.movePlayer: ${e.javaClass.simpleName}")
            try {
                poster.removeView(playerView)
                runCatching { playerView.resizeMode = originalResizeMode }
                reattachPlayer(originalParent, originalIndex, originalParams)
            } catch (inner: Exception) {
                DiagnosticTracker.log(DETAIL_ERROR_TAG, "detailCard.rollbackPlayer: ${inner.javaClass.simpleName}")
            }
        }
    }

    val restorePlayer: () -> Unit = restore@{
        try {
            if (playerView.parent !== poster) return@restore
            poster.removeView(playerView)
            val attachedOriginal = originalParent?.takeIf { it.isAttachedToWindow }
            if (attachedOriginal != null) {
                playerView.resizeMode = originalResizeMode
                reattachPlayer(attachedOriginal, originalIndex, originalParams)
            } else {
                playerView.resizeMode = runCatching {
                    getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                        .safeInt(MainActivity.KEY_RESIZE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
                }.getOrDefault(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                reattachPlayer(statusHost, 0, null)
            }
        } catch (e: Exception) {
            DiagnosticTracker.log(DETAIL_ERROR_TAG, "detailCard.restorePlayer: ${e.javaClass.simpleName}")
        }
    }

    poster.addView(
        View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xDD07101B.toInt()),
            )
        },
        FrameLayout.LayoutParams(-1, dp(50), Gravity.BOTTOM),
    )

    fun badge(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 9.5f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(color)
        }
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    val liveBadge = badge("● EN VIVO", getColor(R.color.tv_live))
    val categoryBadge = badge("Noticias", 0xCC0B1422.toInt())
    poster.addView(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(liveBadge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            addView(categoryBadge)
        },
        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START),
    )
    card.addView(poster, LinearLayout.LayoutParams(-1, dp(168)).apply { bottomMargin = dp(12) })

    val title = TextView(this).apply {
        text = "Selecciona un programa"
        setTextColor(Color.WHITE)
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    val meta = TextView(this).apply {
        text = "${BuildConfig.BRAND_NAME} ${BuildConfig.CHANNEL_NAME} · Costa Rica"
        setTextColor(colorMuted)
        textSize = 10.5f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    val statuses = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val description = TextView(this).apply {
        text = "Explora la programación de ${BuildConfig.BRAND_NAME} ${BuildConfig.CHANNEL_NAME}. " +
            "Puedes guardar programas, programar recordatorios y grabar directamente."
        setTextColor(colorMuted)
        textSize = 11f
        setLineSpacing(0f, 1.15f)
    }
    card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
    card.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    card.addView(statuses, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    card.addView(
        ScrollView(this).apply { addView(description) },
        LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(10) },
    )

    fun action(label: String, scale: Float = 1.04f): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 10.5f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = menuDarkButton(this@buildGuideDetailCard)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        applyTvFocus(this, scaleTo = scale)
    }

    val reminder = action("Recordarme")
    val record = action("Grabar")
    val saved = action("Guardar en Mis progs", 1.03f)
    card.addView(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(reminder, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(record, LinearLayout.LayoutParams(0, -2, 1f))
        },
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
    )
    card.addView(saved, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    val tooltip = TextView(this).apply {
        setTextColor(colorMuted)
        textSize = 9.2f
        setLineSpacing(0f, 1.15f)
    }
    card.addView(tooltip, LinearLayout.LayoutParams(-1, -2))
    return GuideDetailCardBinding(
        card, image, liveBadge, categoryBadge, title, meta, statuses, description,
        reminder, record, saved, tooltip, restorePlayer,
    )
}
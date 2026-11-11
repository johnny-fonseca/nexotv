// ============================================================================
// Archivo: RecordingLibraryHeroCard.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class RecordingLibraryHeroCard(
    val card: FrameLayout,
    val heroImage: ImageView,
    val heroBadgeTv: TextView,
    val heroDatePillTv: TextView,
    val heroFormatPillTv: TextView,
    val playButton: FrameLayout,
    val heroTitleTv: TextView,
    val heroSubtitleTv: TextView,
    val heroProgressFill: View,
)

/** 3.1 Tarjeta principal con la última grabación (58% de alto) */
internal fun MainActivity.createRecordingLibraryHeroCard(
    onPlay: () -> Unit,
): RecordingLibraryHeroCard {
    val heroCard = FrameLayout(this).apply {
        background = menuCardSurface(this@createRecordingLibraryHeroCard, 18f)
        clipToOutline = true
    }

    val heroImage = ImageView(this).apply {
        setImageResource(R.drawable.recording_placeholder)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    heroCard.addView(heroImage, FrameLayout.LayoutParams(-1, -1))

    val gradientScrim = View(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, COLOR_SCRIM_END)
        )
    }
    heroCard.addView(gradientScrim, FrameLayout.LayoutParams(-1, dp(130), Gravity.BOTTOM))

    val heroBadgeTv = TextView(this).apply {
        text = "● ÚLTIMA GRABACIÓN"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
    }
    val heroBadgeBox = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(getColor(R.color.tv_menu_red_btn))
        }
        setPadding(dp(10), dp(4), dp(10), dp(4))
        addView(heroBadgeTv)
    }

    val heroDatePillTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }
    val heroDatePillBox = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(this@createRecordingLibraryHeroCard, 0xC0070E1A.toInt(), 0xFF24364D.toInt())
        setPadding(dp(12), dp(4), dp(12), dp(4))
        addView(heroDatePillTv)
    }

    val heroFormatPillTv = TextView(this).apply {
        text = "TV · 1080p"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
    }
    val heroFormatPillBox = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(this@createRecordingLibraryHeroCard, 0xC0070E1A.toInt(), 0xFF24364D.toInt())
        setPadding(dp(10), dp(4), dp(10), dp(4))
        addView(ImageView(this@createRecordingLibraryHeroCard).apply {
            setImageResource(R.drawable.tv_icon_database)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
        addView(heroFormatPillTv)
    }

    val heroTopBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(heroBadgeBox, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        addView(heroDatePillBox, LinearLayout.LayoutParams(-2, -2))
        addView(View(this@createRecordingLibraryHeroCard), LinearLayout.LayoutParams(0, 0, 1f))
        addView(heroFormatPillBox, LinearLayout.LayoutParams(-2, -2))
    }
    heroCard.addView(heroTopBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

    val playButton = FrameLayout(this).apply {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        val playNormal = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF0C1F32.toInt())
        }
        val playFocused = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(R.color.tv_turquoise))
            setStroke(dp(3), Color.WHITE)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), playFocused)
            addState(intArrayOf(android.R.attr.state_pressed), playFocused)
            addState(intArrayOf(), playNormal)
        }
        val playTriangle = ImageView(this@createRecordingLibraryHeroCard).apply {
            setImageResource(R.drawable.tv_icon_play)
            imageTintList = ColorStateList.valueOf(0xFF8DEBFF.toInt())
        }
        addView(playTriangle, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))

        setOnFocusChangeListener { view, focused ->
            playTriangle.imageTintList = ColorStateList.valueOf(
                if (focused) 0xFF07111D.toInt() else 0xFF8DEBFF.toInt()
            )
            animateTvFocus(view, focused, focusedScale = 1.025f, elevationDp = 8f)
        }
        setOnClickListener { onPlay() }
    }

    val heroTitleTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTypeface(typeface, Typeface.BOLD)
    }
    val heroSubtitleTv = TextView(this).apply {
        setTextColor(COLOR_HERO_SUBTITLE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }
    val heroProgressFill = View(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(getColor(R.color.tv_turquoise))
        }
    }
    val heroProgressTrack = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(0xFF162335.toInt())
        }
        addView(heroProgressFill, FrameLayout.LayoutParams(dp(40), -1))
    }

    val heroBottomRightLabel = TextView(this).apply {
        text = "${BuildConfig.BRAND_NAME} · ${BuildConfig.CHANNEL_NAME}"
        setTextColor(COLOR_HERO_LABEL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    val heroBottomBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        addView(playButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })

        val heroInfo = LinearLayout(this@createRecordingLibraryHeroCard).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(heroTitleTv)
            addView(heroSubtitleTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(6) })
            addView(heroProgressTrack, LinearLayout.LayoutParams(-1, dp(4)))
        }
        addView(heroInfo, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(14) })
        addView(heroBottomRightLabel, LinearLayout.LayoutParams(-2, -2))
    }
    heroCard.addView(heroBottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

    return RecordingLibraryHeroCard(
        card = heroCard,
        heroImage = heroImage,
        heroBadgeTv = heroBadgeTv,
        heroDatePillTv = heroDatePillTv,
        heroFormatPillTv = heroFormatPillTv,
        playButton = playButton,
        heroTitleTv = heroTitleTv,
        heroSubtitleTv = heroSubtitleTv,
        heroProgressFill = heroProgressFill,
    )
}

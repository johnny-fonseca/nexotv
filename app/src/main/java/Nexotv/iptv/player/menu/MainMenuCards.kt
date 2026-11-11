// MainMenuCards.kt
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

internal fun MainActivity.menuScreenBuildHero(onPlay: () -> Unit): MenuScreenHero {
    val activity = this
    val turquoise = MenuScreenPalette.reminderAccent
    val redButton = 0xFF58635B.toInt()

    val card = FrameLayout(activity).apply {
        background = menuCardSurface(activity, 18f)
        clipToOutline = true
        applyTvCardDepth(this, elevationDp = 4f)
    }
    val studioImage = ImageView(activity).apply {
        setImageResource(R.drawable.carrusel_01)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    card.addView(studioImage, FrameLayout.LayoutParams(MENU_MATCH, MENU_MATCH))

    val scrim = View(activity).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, MenuScreenPalette.heroScrim)
        )
    }
    card.addView(scrim, FrameLayout.LayoutParams(MENU_MATCH, dp(MENU_HERO_SCRIM_HEIGHT_DP), Gravity.BOTTOM))

    // Degradado superior para que el encabezado del canal se lea sobre cualquier ilustración
    val topScrim = View(activity).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(MenuScreenPalette.heroTopScrim, Color.TRANSPARENT)
        )
    }
    card.addView(topScrim, FrameLayout.LayoutParams(MENU_MATCH, dp(MENU_HERO_TOP_SCRIM_HEIGHT_DP), Gravity.TOP))

    val channelInfo = menuScreenLabel(11f)
    val subtitlesBadge = menuScreenLabel(11f, color = Color.WHITE, bold = true).apply { visibility = View.GONE }
    val liveBadge = menuScreenLabel(11f, activity.getString(R.string.tv_menu_live_badge), Color.WHITE, bold = true)

    val brandBadge = ImageView(activity).apply {
        setImageResource(R.drawable.channel_icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = BuildConfig.BRAND_NAME
    }
    val channelHeader = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            brandBadge,
            LinearLayout.LayoutParams(dp(MENU_HERO_BRAND_ICON_DP), dp(MENU_HERO_BRAND_ICON_DP)).apply {
                marginEnd = dp(MENU_BRAND_ICON_MARGIN_DP)
            }
        )
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menuScreenLabel(16f, activity.getString(R.string.tv_menu_brand_channel_line, BuildConfig.BRAND_NAME, BuildConfig.CHANNEL_NAME), Color.WHITE, bold = true))
            addView(channelInfo)
        }, LinearLayout.LayoutParams(MENU_WRAP, MENU_WRAP))
    }

    val topBar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(MENU_PILL_PAD_H_DP), dp(MENU_HERO_TOP_PAD_DP), dp(MENU_PILL_PAD_H_DP), dp(MENU_HERO_TOP_PAD_DP))

        addView(channelHeader, LinearLayout.LayoutParams(MENU_WRAP, MENU_WRAP))
        addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = menuPill(
                activity,
                MenuScreenPalette.overlayPillFill,
                MenuScreenPalette.overlayPillStroke
            )
            setPadding(dp(MENU_HERO_LIVE_PAD_H_DP), dp(MENU_HERO_LIVE_PAD_V_DP), dp(MENU_HERO_LIVE_PAD_H_DP), dp(MENU_HERO_LIVE_PAD_V_DP))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.tv_icon_subtitles)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(MENU_HERO_SUB_ICON_DP), dp(MENU_HERO_SUB_ICON_DP)).apply {
                marginEnd = dp(MENU_HERO_SUB_ICON_MARGIN_DP)
            })
            addView(subtitlesBadge)
        }, LinearLayout.LayoutParams(MENU_WRAP, MENU_WRAP).apply { marginEnd = dp(MENU_HERO_LIVE_MARGIN_DP) })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(MENU_HERO_LIVE_CORNER_DP).toFloat()
                setColor(redButton)
            }
            setPadding(dp(MENU_HERO_LIVE_PAD_H_DP), dp(MENU_HERO_LIVE_PAD_V_DP), dp(MENU_HERO_LIVE_PAD_H_DP), dp(MENU_HERO_LIVE_PAD_V_DP))
            addView(liveBadge)
        }, LinearLayout.LayoutParams(MENU_WRAP, MENU_WRAP))
    }
    card.addView(topBar, FrameLayout.LayoutParams(MENU_MATCH, MENU_WRAP, Gravity.TOP))

    val playButton = FrameLayout(activity).apply {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        contentDescription = activity.getString(R.string.tv_menu_play_button_desc)
        val idleShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MenuScreenPalette.playIdleFill)
        }
        val focusedShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(turquoise)
            setStroke(dp(MENU_HERO_PLAY_STROKE_DP), Color.WHITE)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedShape)
            addState(intArrayOf(android.R.attr.state_pressed), focusedShape)
            addState(intArrayOf(), idleShape)
        }
        applyMenuGlow(MenuScreenPalette.glow)
        val playIcon = ImageView(activity).apply {
            setImageResource(R.drawable.tv_icon_play)
            imageTintList = ColorStateList.valueOf(MenuScreenPalette.playIdleTint)
        }
        addView(playIcon, FrameLayout.LayoutParams(dp(MENU_HERO_PLAY_ICON_DP), dp(MENU_HERO_PLAY_ICON_DP), Gravity.CENTER))

        setOnFocusChangeListener { view, focused ->
            playIcon.imageTintList = ColorStateList.valueOf(
                if (focused) MenuScreenPalette.playFocusedTint else MenuScreenPalette.playIdleTint
            )
            view.menuScreenAnimateFocusLift(focused, MENU_HERO_PLAY_LIFT_Z)
        }
        setOnClickListener { menuScreenGuard("clicReproducir") { onPlay() } }
    }

    val title = menuScreenLabel(MENU_HERO_TITLE_MAX_SP.toFloat(), color = Color.WHITE, bold = true).apply {
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setAutoSizeTextTypeUniformWithConfiguration(
                MENU_HERO_TITLE_MIN_SP, MENU_HERO_TITLE_MAX_SP,
                MENU_HERO_TITLE_STEP_SP, TypedValue.COMPLEX_UNIT_SP
            )
        }
    }
    val subtitle = menuScreenLabel(14f, color = MenuScreenPalette.heroSubtitle).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    val progressFill = View(activity).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(turquoise)
        }
    }
    val progressTrack = FrameLayout(activity).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(MenuScreenPalette.progressTrack)
        }
        addView(progressFill, FrameLayout.LayoutParams(dp(MENU_HERO_PROGRESS_TRACK_WIDTH_DP), MENU_MATCH))
    }

    val bottomBar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(MENU_PILL_PAD_H_DP), dp(MENU_HERO_BOTTOM_PAD_V_DP), dp(MENU_PILL_PAD_H_DP), dp(MENU_HERO_BOTTOM_PAD_BOTTOM_DP))
        addView(playButton, LinearLayout.LayoutParams(dp(MENU_HERO_PLAY_SIZE_DP), dp(MENU_HERO_PLAY_SIZE_DP)).apply {
            marginEnd = dp(MENU_HERO_PLAY_MARGIN_DP)
        })

        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP))
            addView(subtitle, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP).apply {
                topMargin = dp(MENU_HERO_SUBTITLE_MARGIN_TOP_DP)
                bottomMargin = dp(MENU_HERO_SUBTITLE_MARGIN_BOTTOM_DP)
            })
            addView(progressTrack, LinearLayout.LayoutParams(MENU_MATCH, dp(MENU_HERO_PROGRESS_HEIGHT_DP)))
        }
        addView(info, LinearLayout.LayoutParams(0, MENU_WRAP, 1f).apply {
            marginEnd = dp(MENU_HERO_INFO_MARGIN_DP)
        })
    }
    card.addView(bottomBar, FrameLayout.LayoutParams(MENU_MATCH, MENU_WRAP, Gravity.BOTTOM))

    return MenuScreenHero(
        card, studioImage, liveBadge, channelInfo, subtitlesBadge,
        playButton, title, subtitle, progressTrack, progressFill
    )
}

internal fun MainActivity.menuScreenBuildInfoCard(
    iconRes: Int,
    accent: Int,
    header: String,
    leftButton: View,
    rightButton: View
): MenuScreenInfoCard {
    val activity = this
    val title = menuScreenLabel(15f, color = Color.WHITE, bold = true)
    val subtitle = menuScreenLabel(11f, color = MenuScreenPalette.cardSubtitle).apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = menuScreenGlassSurface()
        applyTvCardDepth(this)
        setPadding(dp(MENU_CARD_PAD_H_DP), dp(MENU_CARD_PAD_V_DP), dp(MENU_CARD_PAD_H_DP), dp(MENU_CARD_PAD_V_DP))
        // Sin recorte: el resplandor de los botones enfocados debe poder salir de la fila de botones
        clipChildren = false
        clipToPadding = false

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(dp(MENU_CARD_ICON_DP), dp(MENU_CARD_ICON_DP)).apply {
                marginEnd = dp(MENU_CARD_ICON_MARGIN_DP)
            })
            addView(menuScreenLabel(11f, header, accent, bold = true).apply { letterSpacing = 0.1f })
        }
        addView(headerRow, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP).apply {
            bottomMargin = dp(MENU_CARD_HEADER_MARGIN_DP)
        })
        addView(title, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP).apply {
            bottomMargin = dp(MENU_CARD_TITLE_MARGIN_DP)
        })
        addView(subtitle, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP))
        addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))

        val buttonsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(leftButton, LinearLayout.LayoutParams(0, MENU_WRAP, 1f).apply {
                marginEnd = dp(MENU_CARD_BTN_MARGIN_DP)
            })
            addView(rightButton, LinearLayout.LayoutParams(0, MENU_WRAP, 1f))
        }
        addView(buttonsRow, LinearLayout.LayoutParams(MENU_MATCH, MENU_WRAP))
    }
    return MenuScreenInfoCard(root, title, subtitle)
}

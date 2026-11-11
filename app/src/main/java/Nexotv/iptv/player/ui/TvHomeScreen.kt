@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvHomeScreen.kt
package Nexotv.iptv.player.ui

import Nexotv.iptv.player.BuildConfig
import Nexotv.iptv.player.R
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil
import java.util.Date
import java.util.Locale

/**
 * Home / main menu for the rebuilt UI.
 *
 * Information architecture (no previously approved alternative exists in the repo):
 * left navigation panel + live hero + "A continuación" + "Más para ver" rail.
 * Maximum depth is 2: home -> guide/search/clips/more or home -> program actions.
 *
 * Initial focus: the hero primary action ("Ver ahora"). When the screen is reopened from
 * a destination, the navigation item for that destination receives focus instead, so the
 * Back path always lands where the user left. A rail card that had focus is restored with
 * its scroll position (TvHomeMemory).
 * Focus map: hero actions are side by side; Down enters the rail; rail-card Up returns to the
 * last upper control. Left from the first rail card and empty/error action returns to the last
 * focused nav row (default En vivo); each nav-focus event refreshes both explicit Left targets.
 */
internal class TvHomeScreen(
    context: Context,
    private val actions: TvHomeActions,
    private val initialDestination: TvHomeDestination?,
) : FrameLayout(context) {

    private data class NavViews(val root: LinearLayout, val label: TextView, val subtitle: TextView? = null)
    private data class ActionButton(val root: LinearLayout, val label: TextView, val icon: ImageView)
    private data class NavSpec(
        val destination: TvHomeDestination,
        val labelRes: Int,
        val descriptionRes: Int,
        val id: Int,
    )

    private enum class BadgeMode { LIVE, RECORDING, WARN, ERROR }
    private enum class NavDotMode { LIVE, WARN, ERROR }
    private enum class NextMode { LOADING, PROGRAM, RETRY, GUIDE }
    private enum class RailMode { LOADING, READY, RETRY, GUIDE }

    private val timeFormat = DateFormat.getTimeFormat(context)
    private val homeMetrics = computeHomeMetrics(context)
    private val displayDensity = resources.displayMetrics.density
    private val safeHorizontal = (resources.displayMetrics.widthPixels *
        TvTokens.fraction(context, R.fraction.tv_safe_margin_horizontal)).toInt()
    private val safeVertical = (resources.displayMetrics.heightPixels *
        TvTokens.fraction(context, R.fraction.tv_safe_margin_vertical)).toInt()
    private val progressMax = TvTokens.integer(context, R.integer.tv_ui_progress_max)
    private val heroGap = TvTokens.px(context, R.dimen.tv_ui_hero_gap)
    private val gap1 = TvTokens.px(context, R.dimen.tv_ui_gap_1)
    private val gap2 = TvTokens.px(context, R.dimen.tv_ui_gap_2)
    private val gap3 = TvTokens.px(context, R.dimen.tv_ui_gap_3)
    private val navWidth = (homeMetrics.navWidthDp * displayDensity).toInt()
    private val navPad = TvTokens.px(context, R.dimen.tv_ui_nav_pad)
    private val navItemGap = TvTokens.px(context, R.dimen.tv_ui_nav_item_gap)
    private val brandMark = TvTokens.px(context, R.dimen.tv_ui_brand_mark)
    private val heroPad = TvTokens.px(context, R.dimen.tv_ui_hero_pad)
    private val cardPad = TvTokens.px(context, R.dimen.tv_ui_card_pad)
    private val rowHeight = TvTokens.px(context, R.dimen.tv_ui_row_height)
    private val buttonHeight = TvTokens.px(context, R.dimen.tv_ui_button_height)
    private val iconSm = TvTokens.px(context, R.dimen.tv_ui_icon_sm)
    private val iconMd = TvTokens.px(context, R.dimen.tv_ui_icon_md)
    private val iconLg = TvTokens.px(context, R.dimen.tv_ui_icon_lg)
    private val dotSize = TvTokens.px(context, R.dimen.tv_ui_live_dot)
    private val badgePadH = TvTokens.px(context, R.dimen.tv_ui_badge_pad_h)
    private val badgePadV = TvTokens.px(context, R.dimen.tv_ui_badge_pad_v)
    private val progressHeight = TvTokens.px(context, R.dimen.tv_ui_progress_height)
    private val railCardWidth = (homeMetrics.railCardWidthDp * displayDensity).toInt()
    private val railCardHeight = (homeMetrics.railCardHeightDp * displayDensity).toInt()
    private val railGap = TvTokens.px(context, R.dimen.tv_ui_rail_gap)
    private val railScaleRoom = (homeMetrics.railFocusRoomDp * displayDensity).toInt()
    private val fadeMs = TvTokens.integer(context, R.integer.tv_ui_fade_ms).toLong()
    private val skeletonCount = TvTokens.integer(context, R.integer.tv_ui_home_rail_skeleton).coerceAtLeast(1)
    private val heroWeight = TvTokens.fraction(context, R.fraction.tv_ui_home_hero_weight)
    private val nextWeight = TvTokens.fraction(context, R.fraction.tv_ui_home_next_weight)

    private val navViews = LinkedHashMap<TvHomeDestination, NavViews>(5)
    private val railAdapter = TvHomeRailAdapter(
        cardWidthPx = railCardWidth,
        cardHeightPx = railCardHeight,
        leftNavId = { lastNavId },
        upperFocusTarget = ::railCardUpTarget,
        onFocusedId = { id -> rememberContentFocus(id) },
        onProgram = { actions.onOpenProgram(it) },
    )

    private lateinit var navStatusDot: View
    private lateinit var navStatusText: TextView
    private lateinit var navClock: TextView

    private lateinit var heroCard: FrameLayout
    private lateinit var heroPlay: ActionButton
    private lateinit var heroRecord: ActionButton
    private lateinit var liveBadge: TextView
    private lateinit var channelLabel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroTime: TextView
    private lateinit var heroProgress: ProgressBar

    private lateinit var nextTitle: TextView
    private lateinit var nextTime: TextView
    private lateinit var nextAction: ActionButton
    private lateinit var nextSkeleton: LinearLayout

    private lateinit var railRecycler: RecyclerView
    private lateinit var railSkeleton: LinearLayout
    private lateinit var railState: LinearLayout
    private lateinit var railStateIcon: ImageView
    private lateinit var railStateText: TextView
    private lateinit var railStateAction: ActionButton

    private lateinit var exitHint: LinearLayout
    private lateinit var focusController: TvFocusController

    private var lastNavId = View.NO_ID
    private var lastContentId = R.id.tv_ui_hero_play
    private var lastContentRailKey: String? = null
    private var lastUpperControlId = R.id.tv_ui_hero_play
    private var lastHeroButtonId = R.id.tv_ui_hero_play
    private var lastUpcoming: List<TvProgramUi>? = null
    private var lastState: TvUiState? = null
    private var badgeMode: BadgeMode? = null
    private var navDotMode: NavDotMode? = null
    private var nextMode: NextMode = NextMode.LOADING
    private var lastNextMode: NextMode? = null
    private var railMode: RailMode = RailMode.LOADING
    private var lastRailMode: RailMode? = null
    private var lastRecordingActive: Boolean? = null
    private var railScrollRestored = false
    private var railEntryScrollRequestedKey: String? = null

    init {
        id = R.id.tv_ui_home_root
        background = ColorDrawable(TvTokens.color(context, R.color.tv_ui_bg))
        setPadding(safeHorizontal, safeVertical, safeHorizontal, safeVertical)
        build()
        focusController = TvFocusController(this, TvScreens.HOME) { initialFocusView() }
        TvFocus.allowOverflow(this)
    }

    // ---------------------------------------------------------------------------------------
    // Public contract used by TvHomeDialog
    // ---------------------------------------------------------------------------------------

    fun attachFocus() {
        if (initialDestination != null) TvFocusMemory.forget(TvScreens.HOME)
        focusController.attach()
        if (initialDestination == null) restoreRailPosition()
    }

    fun detachFocus() {
        focusController.detach()
    }

    fun saveScrollState() {
        val manager = railRecycler.layoutManager as? LinearLayoutManager ?: return
        val position = manager.findFirstVisibleItemPosition()
        if (position != RecyclerView.NO_POSITION) TvHomeMemory.railFirstVisible = position
    }

    @Suppress("DEPRECATION")
    fun showExitHint() {
        if (exitHint.visibility == View.VISIBLE) return
        TvMotion.fade(exitHint, true, fadeMs)
        exitHint.announceForAccessibility(context.getString(R.string.tv_home_exit_hint))
    }

    fun hideExitHint() {
        if (exitHint.visibility != View.VISIBLE) return
        TvMotion.fade(exitHint, false, fadeMs)
    }

    fun isExitHintVisible(): Boolean = exitHint.visibility == View.VISIBLE

    fun render(state: TvUiState) {
        lastState = state
        val now = System.currentTimeMillis()
        renderBadge(state)
        renderHero(state, now)
        renderNext(state)
        renderRail(state)
        renderNav(state)
        renderRecording(state)
    }

    // ---------------------------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------------------------

    private fun build() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        addView(row, LayoutParams(MATCH, MATCH))

        val nav = buildNav()
        row.addView(
            nav,
            LinearLayout.LayoutParams(navWidth, MATCH).apply { marginEnd = heroGap },
        )
        val content = buildContent()
        row.addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))

        buildExitHint()
        addView(
            exitHint,
            LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )
    }

    private fun buildNav(): LinearLayout {
        val nav = LinearLayout(context).apply {
            id = R.id.tv_ui_home_nav
            orientation = LinearLayout.VERTICAL
            setPadding(navPad, navPad, navPad, navPad)
            background = TvTokens.surface(
                context,
                R.dimen.tv_ui_radius_panel,
                R.color.tv_ui_surface_1,
                R.color.tv_ui_panel_stroke,
            )
            clipChildren = false
            clipToPadding = false
        }

        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(context).apply {
            text = brandInitial()
            gravity = Gravity.CENTER
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            TvTokens.text(this, R.style.TvUiTextHero, R.color.tv_ui_accent, maxLines = 1)
            background = TvTokens.surface(
                context,
                R.dimen.tv_ui_radius_chip,
                R.color.tv_ui_surface_3,
                R.color.tv_ui_stroke,
            )
        }
        brand.addView(mark, LinearLayout.LayoutParams(brandMark, brandMark).apply { marginEnd = gap2 })

        val brandColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        brandColumn.addView(
            TextView(context).apply {
                text = BuildConfig.BRAND_NAME
                TvTokens.text(this, R.style.TvUiTextTitle, R.color.tv_ui_text_1, maxLines = 1)
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        brandColumn.addView(
            TextView(context).apply {
                text = BuildConfig.CHANNEL_NAME.ifBlank { context.getString(R.string.tv_home_brand_fallback) }
                TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_text_3, maxLines = 1)
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap1 / 2 },
        )
        brand.addView(brandColumn, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(brand, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = gap3 })

        val specs = listOf(
            NavSpec(
                TvHomeDestination.LIVE,
                R.string.tv_home_nav_live,
                R.string.tv_home_nav_live_desc,
                R.id.tv_ui_nav_live,
            ),
            NavSpec(
                TvHomeDestination.GUIDE,
                R.string.tv_home_nav_guide,
                R.string.tv_home_nav_guide_desc,
                R.id.tv_ui_nav_guide,
            ),
            NavSpec(
                TvHomeDestination.SEARCH,
                R.string.tv_home_nav_search,
                R.string.tv_home_nav_search_desc,
                R.id.tv_ui_nav_search,
            ),
            NavSpec(
                TvHomeDestination.CLIPS,
                R.string.tv_home_nav_clips,
                R.string.tv_home_nav_clips_desc,
                R.id.tv_ui_nav_clips,
            ),
            NavSpec(
                TvHomeDestination.MORE,
                R.string.tv_home_nav_more,
                R.string.tv_home_nav_more_desc,
                R.id.tv_ui_nav_more,
            ),
        )
        for (spec in specs) {
            val views = buildNavItem(spec)
            navViews[spec.destination] = views
            nav.addView(
                views.root,
                LinearLayout.LayoutParams(MATCH, rowHeight).apply { bottomMargin = navItemGap },
            )
        }

        nav.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
        nav.addView(
            View(context).apply { setBackgroundColor(TvTokens.color(context, R.color.tv_ui_stroke)) },
            LinearLayout.LayoutParams(MATCH, TvTokens.px(context, R.dimen.tv_ui_hairline)),
        )

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, gap1, 0, 0)
        }
        navStatusDot = View(context).apply {
            background = TvTokens.oval(context, R.color.tv_ui_live)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        statusRow.addView(navStatusDot, LinearLayout.LayoutParams(dotSize, dotSize).apply { marginEnd = gap1 })
        navStatusText = TextView(context).apply {
            TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_text_2, maxLines = 1)
        }
        statusRow.addView(navStatusText, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(statusRow, LinearLayout.LayoutParams(MATCH, WRAP))

        navClock = TextView(context).apply {
            gravity = Gravity.END
            TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_text_3, maxLines = 1)
        }
        nav.addView(navClock, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap1 / 2 })

        wireNavFocus()
        lastNavId = navViews[TvHomeDestination.LIVE]?.root?.id ?: View.NO_ID
        return nav
    }

    private fun buildNavItem(spec: NavSpec): NavViews {
        val row = LinearLayout(context).apply {
            id = spec.id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = rowHeight
            setPadding(gap2, 0, gap2, 0)
            isClickable = true
            clipChildren = false
            setOnClickListener { actions.onNavigate(spec.destination) }
        }
        TvFocus.attach(row, TvFocusStyle.ROW) { _, focused ->
            if (focused) {
                lastNavId = row.id
                TvHomeMemory.focusedRail = false
                applyContentEntry(row.id)
            }
        }
        TvFocus.key(row, "home.nav.${spec.destination.name}")
        TvFocus.describe(row, context.getString(spec.descriptionRes))

        val label = TextView(context).apply {
            setText(spec.labelRes)
            TvTokens.text(this, R.style.TvUiTextBody, R.color.tv_ui_text_1, maxLines = 1)
            setTextColor(
                TvTokens.focusColorStateList(context, R.color.tv_ui_text_2, R.color.tv_ui_text_1),
            )
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val subtitle = if (spec.destination == TvHomeDestination.CLIPS) {
            TextView(context).apply {
                TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_text_2, maxLines = 1)
                setTextColor(TvTokens.focusColorStateList(context, R.color.tv_ui_text_3, R.color.tv_ui_text_2))
                isDuplicateParentStateEnabled = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.GONE
            }
        } else {
            null
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(label, LinearLayout.LayoutParams(MATCH, WRAP))
        subtitle?.let { textColumn.addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        row.addView(textColumn, LinearLayout.LayoutParams(0, MATCH, 1f))
        return NavViews(row, label, subtitle)
    }

    private fun wireNavFocus() {
        val rows = TvHomeDestination.entries.mapNotNull { navViews[it]?.root }
        if (rows.isEmpty()) return
        rows.forEachIndexed { index, row ->
            row.nextFocusUpId = if (index == 0) homeConsumeEdge(row.id) else rows[index - 1].id
            row.nextFocusDownId = if (index == rows.lastIndex) homeConsumeEdge(row.id) else rows[index + 1].id
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = navRightTargetId()
        }
    }

    private fun buildContent(): LinearLayout {
        val content = LinearLayout(context).apply {
            id = R.id.tv_ui_home_content
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        val heroHeight = (homeMetrics.heroRowHeightDp * displayDensity).toInt()
        heroCard = buildHero()
        val nextCard = buildNextCard()

        val heroRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        heroRow.addView(
            heroCard,
            LinearLayout.LayoutParams(0, heroHeight, heroWeight).apply { marginEnd = heroGap },
        )
        heroRow.addView(nextCard, LinearLayout.LayoutParams(0, heroHeight, nextWeight))
        content.addView(heroRow, LinearLayout.LayoutParams(MATCH, heroHeight))

        content.addView(buildRailSection(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        wireContentFocus()
        return content
    }

    private fun wireContentFocus() {
        heroPlay.root.nextFocusLeftId = homeContentLeftTarget(lastNavId)
        heroPlay.root.nextFocusUpId = homeConsumeEdge(heroPlay.root.id)
        heroPlay.root.nextFocusDownId = homeConsumeEdge(heroPlay.root.id)
        heroPlay.root.nextFocusRightId = homeHeroPlayRightTarget(heroRecord.root.id)

        heroRecord.root.nextFocusLeftId = homeHeroRecordLeftTarget(heroPlay.root.id)
        heroRecord.root.nextFocusUpId = homeConsumeEdge(heroRecord.root.id)
        heroRecord.root.nextFocusDownId = homeConsumeEdge(heroRecord.root.id)
        heroRecord.root.nextFocusRightId = nextAction.root.id

        nextAction.root.nextFocusLeftId = homeNextActionLeftTarget(lastHeroButtonId)
        nextAction.root.nextFocusUpId = homeConsumeEdge(nextAction.root.id)
        nextAction.root.nextFocusRightId = nextAction.root.id

        railStateAction.root.nextFocusLeftId = homeContentLeftTarget(lastNavId)
        railStateAction.root.nextFocusUpId = homeRailStateUpTarget(nextAction.root.id)
        railStateAction.root.nextFocusRightId = railStateAction.root.id
        railStateAction.root.nextFocusDownId = railStateAction.root.id
    }

    private fun applyContentEntry(navId: Int) {
        if (!::heroPlay.isInitialized || !::heroRecord.isInitialized || !::nextAction.isInitialized) {
            return
        }
        heroPlay.root.nextFocusLeftId = homeContentLeftTarget(navId)
        heroRecord.root.nextFocusLeftId = homeHeroRecordLeftTarget(heroPlay.root.id)
        val navLeftTarget = homeContentLeftTarget(navId)
        if (::railStateAction.isInitialized) {
            railStateAction.root.nextFocusLeftId = navLeftTarget
        }
        if (::railRecycler.isInitialized) {
            val firstCard = (railRecycler.findViewHolderForAdapterPosition(0) as? TvHomeRailAdapter.Holder)?.card
            if (firstCard != null) firstCard.nextFocusLeftId = navLeftTarget
        }
    }

    private fun rememberContentFocus(
        viewId: Int,
        rememberAsUpperControl: Boolean = false,
        rememberAsHeroButton: Boolean = false,
    ) {
        lastContentId = viewId
        val railCard = findViewById<TvHomeRailCard>(viewId)
        lastContentRailKey = railCard?.program?.stableKey
        if (rememberAsUpperControl) {
            lastUpperControlId = viewId
            updateRailCardUpTargets()
        }
        if (rememberAsHeroButton) {
            lastHeroButtonId = viewId
            if (::nextAction.isInitialized) {
                nextAction.root.nextFocusLeftId = homeNextActionLeftTarget(viewId)
            }
        }
        updateNavRightTargets()
        if (::railRecycler.isInitialized) updateRailEntryFocusTargets()
    }

    private fun navRightTargetId(): Int {
        val target = findViewById<View>(lastContentId)
        val available = when {
            target == null || target.visibility != View.VISIBLE || !target.isFocusable || !target.isShown -> false
            target is TvHomeRailCard -> {
                val key = lastContentRailKey
                key != null && target.program?.stableKey == key && railAdapter.indexOfKey(key) >= 0
            }
            else -> lastContentRailKey == null
        }
        return homeNavRightTarget(lastContentId, R.id.tv_ui_hero_play, available)
    }

    private fun updateNavRightTargets() {
        val targetId = navRightTargetId()
        for (views in navViews.values) views.root.nextFocusRightId = targetId
    }

    private fun railCardUpTarget(): Int {
        val target = findViewById<View>(lastUpperControlId)
        val available = target != null && target.visibility == View.VISIBLE && target.isFocusable && target.isShown
        return homeRailCardUpTarget(lastUpperControlId, R.id.tv_ui_hero_play, available)
    }

    private fun railEntryDownTargetId(sourceId: Int): Int {
        val key = TvHomeMemory.lastRailKey
        val rememberedIndex = key?.let(railAdapter::indexOfKey) ?: -1
        if (rememberedIndex >= 0) {
            val card = (railRecycler.findViewHolderForAdapterPosition(rememberedIndex) as? TvHomeRailAdapter.Holder)?.card
            if (card != null && card.isFocusable && card.isShown) return card.id
            if (railEntryScrollRequestedKey != key) {
                railEntryScrollRequestedKey = key
                railRecycler.scrollToPosition(rememberedIndex)
                railRecycler.post { updateRailEntryFocusTargets() }
            }
            return sourceId
        }
        railEntryScrollRequestedKey = null
        return homeRailEntryTarget(null, firstVisibleRailCardId()) ?: sourceId
    }

    private fun firstVisibleRailCardId(): Int? {
        val manager = railRecycler.layoutManager as? LinearLayoutManager ?: return null
        var position = manager.findFirstVisibleItemPosition()
        while (position != RecyclerView.NO_POSITION && position < railAdapter.itemCount) {
            val card = (railRecycler.findViewHolderForAdapterPosition(position) as? TvHomeRailAdapter.Holder)?.card
            if (card != null && card.isFocusable && card.isShown) return card.id
            position++
        }
        return null
    }

    private fun updateRailEntryFocusTargets() {
        if (railMode != RailMode.READY || !::railRecycler.isInitialized ||
            !::heroPlay.isInitialized || !::heroRecord.isInitialized || !::nextAction.isInitialized
        ) return
        heroPlay.root.nextFocusDownId = railEntryDownTargetId(heroPlay.root.id)
        heroRecord.root.nextFocusDownId = railEntryDownTargetId(heroRecord.root.id)
        nextAction.root.nextFocusDownId = if (nextAction.root.visibility == View.VISIBLE) {
            railEntryDownTargetId(nextAction.root.id)
        } else {
            View.NO_ID
        }
    }

    private fun updateRailCardUpTargets() {
        if (!::railRecycler.isInitialized) return
        var index = 0
        while (index < railRecycler.childCount) {
            (railRecycler.getChildAt(index) as? TvHomeRailCard)?.nextFocusUpId = railCardUpTarget()
            index++
        }
    }

    private fun buildHero(): FrameLayout {
        val hero = FrameLayout(context).apply {
            background = TvTokens.verticalSurface(
                context,
                R.dimen.tv_ui_radius_card,
                R.color.tv_ui_surface_2,
                R.color.tv_ui_surface_1,
                R.color.tv_ui_stroke,
            )
            setPadding(heroPad, heroPad, heroPad, heroPad)
            clipChildren = false
            clipToPadding = false
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        liveBadge = TextView(context).apply {
            gravity = Gravity.CENTER
            isFocusable = false
            setPadding(badgePadH, badgePadV, badgePadH, badgePadV)
            TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_on_live, maxLines = 1)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.tv_ui_live_dot, 0, 0, 0)
            compoundDrawablePadding = gap1
            background = TvTokens.surface(
                context,
                R.dimen.tv_ui_radius_chip,
                R.color.tv_ui_live,
            )
        }
        top.addView(liveBadge, LinearLayout.LayoutParams(WRAP, WRAP))
        channelLabel = TextView(context).apply {
            gravity = Gravity.END
            TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_text_2, maxLines = 1)
        }
        top.addView(
            channelLabel,
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = gap2 },
        )
        hero.addView(top, LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        heroTitle = TextView(context).apply {
            TvTokens.text(this, R.style.TvUiTextHero, R.color.tv_ui_text_1, maxLines = 2)
        }
        bottom.addView(heroTitle, LinearLayout.LayoutParams(MATCH, WRAP))

        heroTime = TextView(context).apply {
            TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_text_2, maxLines = 1)
        }
        bottom.addView(
            heroTime,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap1 },
        )

        heroProgress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = progressMax
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            progressTintList = ColorStateList.valueOf(TvTokens.color(context, R.color.tv_ui_accent))
            progressBackgroundTintList = ColorStateList.valueOf(
                TvTokens.color(context, R.color.tv_ui_stroke),
            )
        }
        bottom.addView(
            heroProgress,
            LinearLayout.LayoutParams(MATCH, progressHeight).apply { topMargin = gap1 },
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        heroPlay = buildActionButton(
            R.id.tv_ui_hero_play,
            R.drawable.tv_ui_icon_play,
            R.string.tv_home_watch_now,
            accent = true,
            rememberAsUpperControl = true,
            rememberAsHeroButton = true,
        ) { actions.onWatchNow() }
        TvFocus.describe(heroPlay.root, context.getString(R.string.tv_home_watch_now_desc))
        TvFocus.key(heroPlay.root, "home.hero.play")
        heroRecord = buildActionButton(
            R.id.tv_ui_hero_record,
            R.drawable.tv_menu_record,
            R.string.tv_home_record,
            accent = false,
            rememberAsUpperControl = true,
            rememberAsHeroButton = true,
        ) { actions.onToggleRecording() }
        TvFocus.describe(heroRecord.root, context.getString(R.string.tv_home_record_desc))
        TvFocus.key(heroRecord.root, "home.hero.record")
        buttons.addView(
            heroPlay.root,
            LinearLayout.LayoutParams(WRAP, buttonHeight).apply { marginEnd = gap2 },
        )
        buttons.addView(heroRecord.root, LinearLayout.LayoutParams(WRAP, buttonHeight))
        bottom.addView(
            buttons,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap2 },
        )

        hero.addView(bottom, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return hero
    }

    private fun buildNextCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
            background = TvTokens.surface(
                context,
                R.dimen.tv_ui_radius_panel,
                R.color.tv_ui_surface_1,
                R.color.tv_ui_panel_stroke,
            )
            clipChildren = false
            clipToPadding = false
        }
        card.addView(
            TextView(context).apply {
                setText(R.string.tv_home_up_next_eyebrow)
                TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_text_3, maxLines = 1)
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        nextTitle = TextView(context).apply {
            TvTokens.text(this, R.style.TvUiTextTitle, R.color.tv_ui_text_1, maxLines = 2)
        }
        card.addView(
            nextTitle,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap1 },
        )

        nextTime = TextView(context).apply {
            TvTokens.text(this, R.style.TvUiTextSecondary, R.color.tv_ui_text_2, maxLines = 1)
        }
        card.addView(
            nextTime,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap1 },
        )

        nextSkeleton = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.tv_home_skeleton_desc)
        }
        nextSkeleton.addView(skeletonLine(MATCH, R.dimen.tv_ui_skeleton_line))
        nextSkeleton.addView(
            skeletonLine(MATCH, R.dimen.tv_ui_skeleton_line).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, TvTokens.px(context, R.dimen.tv_ui_skeleton_line)).apply {
                    topMargin = TvTokens.px(context, R.dimen.tv_ui_skeleton_gap)
                }
            },
        )
        card.addView(nextSkeleton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap1 })

        card.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))

        nextAction = buildActionButton(
            R.id.tv_ui_next_action,
            R.drawable.tv_icon_guide,
            R.string.tv_home_up_next_action,
            accent = true,
            rememberAsUpperControl = true,
        ) {
            when (nextMode) {
                NextMode.PROGRAM -> lastState?.nextProgram?.let(actions::onOpenProgram)
                NextMode.RETRY -> actions.onRetryGuide()
                NextMode.GUIDE -> actions.onNavigate(TvHomeDestination.GUIDE)
                NextMode.LOADING -> Unit
            }
        }
        TvFocus.key(nextAction.root, "home.next.action")
        card.addView(
            nextAction.root,
            LinearLayout.LayoutParams(MATCH, buttonHeight).apply { topMargin = gap2 },
        )
        return card
    }

    private fun buildRailSection(): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        section.addView(
            TextView(context).apply {
                setText(R.string.tv_home_more_to_watch)
                TvTokens.text(this, R.style.TvUiTextTitle, R.color.tv_ui_text_1, maxLines = 1)
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap3 },
        )

        val host = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val hostHeight = railCardHeight + railScaleRoom
        val cardTop = railScaleRoom / 2

        railRecycler = RecyclerView(context).apply {
            id = R.id.tv_ui_rail
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = railAdapter
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setHasFixedSize(true)
            itemAnimator = null
            clipChildren = false
            clipToPadding = false
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val position = manager.findFirstVisibleItemPosition()
                        if (position != RecyclerView.NO_POSITION) {
                            TvHomeMemory.railFirstVisible = position
                        }
                    }
                },
            )
        }
        host.addView(
            railRecycler,
            FrameLayout.LayoutParams(MATCH, railCardHeight, Gravity.TOP).apply { topMargin = cardTop },
        )

        railSkeleton = buildRailSkeleton()
        host.addView(
            railSkeleton,
            FrameLayout.LayoutParams(MATCH, railCardHeight, Gravity.TOP).apply { topMargin = cardTop },
        )

        railState = buildRailState()
        host.addView(
            railState,
            FrameLayout.LayoutParams(MATCH, railCardHeight, Gravity.TOP).apply { topMargin = cardTop },
        )

        section.addView(host, LinearLayout.LayoutParams(MATCH, hostHeight).apply { topMargin = gap2 })
        return section
    }

    private fun buildRailSkeleton(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.tv_home_skeleton_desc)
        }
        repeat(skeletonCount) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(cardPad, cardPad, cardPad, cardPad)
                background = TvTokens.surface(
                    context,
                    R.dimen.tv_ui_radius_card,
                    R.color.tv_ui_surface_1,
                    R.color.tv_ui_stroke,
                )
            }
            card.addView(skeletonLine(MATCH, R.dimen.tv_ui_skeleton_line))
            card.addView(
                skeletonLine(MATCH, R.dimen.tv_ui_skeleton_line).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, TvTokens.px(context, R.dimen.tv_ui_skeleton_line)).apply {
                        topMargin = TvTokens.px(context, R.dimen.tv_ui_skeleton_gap)
                    }
                },
            )
            card.addView(
                skeletonLine(railCardWidth / 2, R.dimen.tv_ui_skeleton_line).apply {
                    layoutParams = LinearLayout.LayoutParams(railCardWidth / 2, TvTokens.px(context, R.dimen.tv_ui_skeleton_line)).apply {
                        topMargin = TvTokens.px(context, R.dimen.tv_ui_skeleton_gap)
                    }
                },
            )
            row.addView(
                card,
                LinearLayout.LayoutParams(railCardWidth, railCardHeight).apply { marginEnd = railGap },
            )
        }
        return row
    }

    private fun buildRailState(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
            visibility = View.GONE
            background = TvTokens.surface(
                context,
                R.dimen.tv_ui_radius_panel,
                R.color.tv_ui_surface_1,
                R.color.tv_ui_panel_stroke,
            )
        }
        railStateIcon = ImageView(context).apply {
            setImageResource(R.drawable.tv_icon_guide)
            imageTintList = ColorStateList.valueOf(TvTokens.color(context, R.color.tv_ui_text_2))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel.addView(railStateIcon, LinearLayout.LayoutParams(iconLg, iconLg).apply { marginEnd = gap2 })

        railStateText = TextView(context).apply {
            TvTokens.text(this, R.style.TvUiTextBody, R.color.tv_ui_text_1, maxLines = 2)
        }
        panel.addView(railStateText, LinearLayout.LayoutParams(0, WRAP, 1f))

        railStateAction = buildActionButton(
            R.id.tv_ui_rail_state_action,
            R.drawable.tv_icon_guide,
            R.string.tv_home_open_guide,
            accent = true,
        ) {
            when (railMode) {
                RailMode.RETRY -> actions.onRetryGuide()
                RailMode.GUIDE -> actions.onNavigate(TvHomeDestination.GUIDE)
                RailMode.LOADING, RailMode.READY -> Unit
            }
        }
        TvFocus.key(railStateAction.root, "home.rail.state")
        panel.addView(
            railStateAction.root,
            LinearLayout.LayoutParams(WRAP, buttonHeight).apply { marginStart = gap2 },
        )
        return panel
    }

    private fun buildExitHint() {
        exitHint = LinearLayout(context).apply {
            id = R.id.tv_ui_exit_hint
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(gap2, gap1, gap2, gap1)
            background = TvTokens.surface(
                context,
                R.dimen.tv_ui_radius_chip,
                R.color.tv_ui_surface_2,
                R.color.tv_ui_warn,
                R.dimen.tv_ui_focus_ring_width,
            )
            contentDescription = context.getString(R.string.tv_home_exit_hint_desc)
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.tv_icon_warning)
            imageTintList = ColorStateList.valueOf(TvTokens.color(context, R.color.tv_ui_warn))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        exitHint.addView(icon, LinearLayout.LayoutParams(iconMd, iconMd).apply { marginEnd = gap1 })
        exitHint.addView(
            TextView(context).apply {
                setText(R.string.tv_home_exit_hint)
                TvTokens.text(this, R.style.TvUiTextBody, R.color.tv_ui_text_1, maxLines = 1)
            },
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
    }

    private fun buildActionButton(
        id: Int,
        iconResource: Int,
        labelResource: Int,
        accent: Boolean,
        rememberAsUpperControl: Boolean = false,
        rememberAsHeroButton: Boolean = false,
        onClick: () -> Unit,
    ): ActionButton {
        val root = LinearLayout(context).apply {
            this.id = id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = buttonHeight
            setPadding(gap2, 0, gap2, 0)
            isClickable = true
            clipChildren = false
            setOnClickListener { onClick() }
        }
        TvFocus.attach(root, TvFocusStyle.CONTROL) { _, focused ->
            if (focused) {
                TvHomeMemory.focusedRail = false
                rememberContentFocus(root.id, rememberAsUpperControl, rememberAsHeroButton)
            }
        }
        val tint = if (accent) R.color.tv_ui_accent else R.color.tv_ui_text_2
        val icon = ImageView(context).apply {
            setImageResource(iconResource)
            imageTintList = ColorStateList.valueOf(TvTokens.color(context, tint))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(context).apply {
            setText(labelResource)
            TvTokens.text(
                this,
                R.style.TvUiTextBody,
                if (accent) R.color.tv_ui_accent else R.color.tv_ui_text_1,
                maxLines = 1,
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(icon, LinearLayout.LayoutParams(iconSm, iconSm).apply { marginEnd = gap1 })
        root.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
        return ActionButton(root, label, icon)
    }

    private fun skeletonLine(width: Int, heightRes: Int): View = View(context).apply {
        background = TvTokens.surface(
            context,
            R.dimen.tv_ui_radius_control,
            R.color.tv_ui_skeleton,
        )
        layoutParams = LinearLayout.LayoutParams(width, TvTokens.px(context, heightRes))
    }

    // ---------------------------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------------------------

    private fun renderBadge(state: TvUiState) {
        val recording = state.recording.active
        val badge = when {
            recording -> BadgeSpec(R.string.tv_home_recording_badge, R.color.tv_ui_live, BadgeMode.RECORDING)
            state.playback is TvPlaybackState.Reconnecting ->
                BadgeSpec(R.string.tv_home_reconnecting_badge, R.color.tv_ui_warn, BadgeMode.WARN)
            state.playback is TvPlaybackState.Buffering ||
                state.playback is TvPlaybackState.Connecting ->
                BadgeSpec(R.string.tv_home_connecting_badge, R.color.tv_ui_warn, BadgeMode.WARN)
            state.playback is TvPlaybackState.NetworkProblem ||
                state.playback is TvPlaybackState.Offline ->
                BadgeSpec(R.string.tv_home_offline_badge, R.color.tv_ui_error, BadgeMode.ERROR)
            state.playback is TvPlaybackState.StreamError ->
                BadgeSpec(R.string.tv_home_error_badge, R.color.tv_ui_error, BadgeMode.ERROR)
            else -> BadgeSpec(R.string.tv_home_live_badge, R.color.tv_ui_live, BadgeMode.LIVE)
        }
        if (badgeMode != badge.mode) {
            badgeMode = badge.mode
            liveBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (badge.mode == BadgeMode.ERROR) R.drawable.tv_ui_error_mark else R.drawable.tv_ui_live_dot,
                0,
                0,
                0,
            )
            liveBadge.background = TvTokens.surface(
                context,
                R.dimen.tv_ui_radius_chip,
                badge.fillColor,
            )
        }
        liveBadge.setText(badge.textRes)
        liveBadge.contentDescription = context.getString(
            R.string.tv_home_playback_status_desc,
            context.getString(badge.textRes),
        )
    }

    private fun renderHero(state: TvUiState, now: Long) {
        val channel = state.channel
        val channelText = channelLine(channel)
        channelLabel.text = channelText
        channelLabel.contentDescription = context.getString(R.string.tv_home_channel_desc, channelText)

        val program = state.program
        heroTitle.text = program?.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.tv_home_brand_fallback)
        if (state.recording.active) {
            heroTime.text = context.getString(
                R.string.tv_common_recording_time,
                homeRecordingElapsedLabel(state.recording.elapsedMs),
            )
            heroTime.visibility = View.VISIBLE
        } else if (program != null) {
            heroTime.text = timeRange(program)
            heroTime.visibility = View.VISIBLE
        } else {
            heroTime.text = ""
            heroTime.visibility = View.INVISIBLE
        }
        if (program != null) {
            heroProgress.visibility = View.VISIBLE
            heroProgress.progress = progressPermille(program, now, progressMax)
            heroProgress.contentDescription = context.getString(
                R.string.tv_home_progress_desc,
                (heroProgress.progress * 100 / progressMax.coerceAtLeast(1)),
            )
        } else {
            heroProgress.visibility = View.INVISIBLE
            heroProgress.progress = 0
            heroProgress.contentDescription = null
        }
    }

    private fun renderNext(state: TvUiState) {
        val next = state.nextProgram
        val mode = when {
            next != null -> NextMode.PROGRAM
            state.guideLoading -> NextMode.LOADING
            state.guideError != null -> NextMode.RETRY
            else -> NextMode.GUIDE
        }
        val hadFocus = nextAction.root.hasFocus()
        nextMode = mode
        val wasNextVisible = nextAction.root.visibility == View.VISIBLE
        nextSkeleton.visibility = if (mode == NextMode.LOADING) View.VISIBLE else View.GONE
        nextTitle.visibility = if (mode == NextMode.LOADING) View.GONE else View.VISIBLE
        nextTime.visibility = if (mode == NextMode.LOADING) View.GONE else View.VISIBLE
        nextAction.root.visibility = if (mode == NextMode.LOADING) View.GONE else View.VISIBLE

        val nextVisible = nextAction.root.visibility == View.VISIBLE
        if (wasNextVisible != nextVisible) updateRailCardUpTargets()
        if (nextVisible) {
            nextAction.root.nextFocusLeftId = homeNextActionLeftTarget(lastHeroButtonId)
            nextAction.root.nextFocusUpId = homeConsumeEdge(nextAction.root.id)
            nextAction.root.nextFocusRightId = nextAction.root.id
            nextAction.root.nextFocusDownId = homeConsumeEdge(nextAction.root.id)
        } else {
            nextAction.root.nextFocusLeftId = View.NO_ID
            nextAction.root.nextFocusUpId = View.NO_ID
            nextAction.root.nextFocusRightId = View.NO_ID
            nextAction.root.nextFocusDownId = View.NO_ID
        }
        heroRecord.root.nextFocusRightId = if (nextVisible) nextAction.root.id else heroRecord.root.id
        railStateAction.root.nextFocusUpId = if (nextVisible) nextAction.root.id else railStateAction.root.id

        if (mode != lastNextMode) {
            lastNextMode = mode
            when (mode) {
                NextMode.PROGRAM -> {
                    nextAction.label.setText(R.string.tv_home_up_next_action)
                    nextAction.icon.setImageResource(R.drawable.tv_icon_guide)
                }
                NextMode.RETRY -> {
                    nextAction.label.setText(R.string.tv_home_retry)
                    nextAction.icon.setImageResource(R.drawable.tv_icon_warning)
                }
                NextMode.GUIDE -> {
                    nextAction.label.setText(R.string.tv_home_open_guide)
                    nextAction.icon.setImageResource(R.drawable.tv_icon_guide)
                }
                NextMode.LOADING -> Unit
            }
        }

        when (mode) {
            NextMode.PROGRAM -> {
                val title = next?.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.tv_home_program_no_title)
                nextTitle.text = title
                nextTime.text = next?.let { timeRange(it) }.orEmpty()
                TvFocus.describe(
                    nextAction.root,
                    context.getString(R.string.tv_home_up_next_action_desc, title),
                )
            }
            NextMode.RETRY -> {
                nextTitle.text = context.getString(R.string.tv_home_guide_error)
                nextTime.text = ""
                TvFocus.describe(nextAction.root, context.getString(R.string.tv_home_retry_desc))
            }
            NextMode.GUIDE -> {
                nextTitle.text = context.getString(R.string.tv_home_up_next_empty)
                nextTime.text = ""
                TvFocus.describe(nextAction.root, context.getString(R.string.tv_home_open_guide_desc))
            }
            NextMode.LOADING -> Unit
        }

        if (hadFocus && nextAction.root.visibility != View.VISIBLE) {
            post { focusController.ensureFocus() }
        }
        updateNavRightTargets()
    }

    private fun renderRail(state: TvUiState) {
        val items = state.upcoming
        val mode = when {
            items.isNotEmpty() -> RailMode.READY
            state.guideLoading -> RailMode.LOADING
            state.guideError != null -> RailMode.RETRY
            else -> RailMode.GUIDE
        }
        val modeChanged = mode != lastRailMode
        val hadFocus = railRecycler.hasFocus() || railStateAction.root.hasFocus()
        railMode = mode
        railRecycler.visibility = if (mode == RailMode.READY) View.VISIBLE else View.GONE
        railSkeleton.visibility = if (mode == RailMode.LOADING) View.VISIBLE else View.GONE
        railState.visibility = if (mode == RailMode.RETRY || mode == RailMode.GUIDE) View.VISIBLE else View.GONE

        if (mode == RailMode.READY && items != lastUpcoming) {
            railEntryScrollRequestedKey = null
            lastUpcoming = items
            railAdapter.submit(items)
            if (!railScrollRestored) {
                railScrollRestored = true
                if (TvHomeMemory.railFirstVisible > 0) {
                    railRecycler.scrollToPosition(TvHomeMemory.railFirstVisible)
                }
            }
            railRecycler.post { updateRailEntryFocusTargets() }
        }

        if (modeChanged) {
            lastRailMode = mode
            when (mode) {
                RailMode.RETRY -> {
                    railStateIcon.setImageResource(R.drawable.tv_icon_warning)
                    railStateText.setText(R.string.tv_home_rail_error)
                    railStateAction.label.setText(R.string.tv_home_retry)
                    railStateAction.icon.setImageResource(R.drawable.tv_icon_warning)
                    TvFocus.describe(
                        railStateAction.root,
                        context.getString(R.string.tv_home_retry_desc),
                    )
                }
                RailMode.GUIDE -> {
                    railStateIcon.setImageResource(R.drawable.tv_icon_guide)
                    railStateText.setText(R.string.tv_home_rail_empty)
                    railStateAction.label.setText(R.string.tv_home_open_guide)
                    railStateAction.icon.setImageResource(R.drawable.tv_icon_guide)
                    TvFocus.describe(
                        railStateAction.root,
                        context.getString(R.string.tv_home_open_guide_desc),
                    )
                }
                RailMode.LOADING, RailMode.READY -> Unit
            }
        }

        when (homeRecordDownTarget(mode == RailMode.LOADING, items.isNotEmpty())) {
            TvHomeDownTarget.PLATFORM_SEARCH -> {
                heroPlay.root.nextFocusDownId = railEntryDownTargetId(heroPlay.root.id)
                heroRecord.root.nextFocusDownId = railEntryDownTargetId(heroRecord.root.id)
            }
            TvHomeDownTarget.CONSUME -> {
                heroPlay.root.nextFocusDownId = heroPlay.root.id
                heroRecord.root.nextFocusDownId = heroRecord.root.id
            }
            TvHomeDownTarget.RAIL_STATE_ACTION -> {
                heroPlay.root.nextFocusDownId = railStateAction.root.id
                heroRecord.root.nextFocusDownId = railStateAction.root.id
            }
        }
        nextAction.root.nextFocusDownId = if (nextAction.root.visibility == View.VISIBLE) {
            when (homeNextActionDownTarget(mode == RailMode.LOADING, items.isNotEmpty())) {
                TvHomeDownTarget.CONSUME -> nextAction.root.id
                TvHomeDownTarget.PLATFORM_SEARCH -> railEntryDownTargetId(nextAction.root.id)
                TvHomeDownTarget.RAIL_STATE_ACTION -> railStateAction.root.id
            }
        } else {
            View.NO_ID
        }

        if (hadFocus && modeChanged) {
            when (mode) {
                RailMode.READY -> focusFirstRailItem()
                RailMode.RETRY, RailMode.GUIDE -> railStateAction.root.requestFocus()
                RailMode.LOADING -> post { focusController.ensureFocus() }
            }
        }
        updateNavRightTargets()
        updateRailEntryFocusTargets()
    }

    private fun renderNav(state: TvUiState) {
        val status = when (state.playback) {
            is TvPlaybackState.Buffering, is TvPlaybackState.Connecting ->
                NavStatus(R.string.tv_home_status_connecting, R.color.tv_ui_warn, NavDotMode.WARN)
            is TvPlaybackState.Reconnecting ->
                NavStatus(R.string.tv_home_status_reconnecting, R.color.tv_ui_warn, NavDotMode.WARN)
            is TvPlaybackState.NetworkProblem, is TvPlaybackState.Offline ->
                NavStatus(R.string.tv_home_status_offline, R.color.tv_ui_error, NavDotMode.ERROR)
            is TvPlaybackState.StreamError ->
                NavStatus(R.string.tv_home_status_error, R.color.tv_ui_error, NavDotMode.ERROR)
            else -> NavStatus(R.string.tv_home_status_live, R.color.tv_ui_live, NavDotMode.LIVE)
        }
        if (navDotMode != status.mode) {
            navDotMode = status.mode
            navStatusDot.background = TvTokens.oval(context, status.dotColor)
        }
        navStatusText.setText(status.textRes)
        navStatusText.contentDescription = context.getString(status.textRes)

        navViews[TvHomeDestination.CLIPS]?.let { clipsViews ->
            clipsViews.subtitle?.let { subtitle ->
                val summary = state.clipsSubtitle.orEmpty()
                if (subtitle.text.toString() != summary) {
                    subtitle.text = summary
                    subtitle.visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
                    val baseDescription = context.getString(R.string.tv_home_nav_clips_desc)
                    TvFocus.describe(
                        clipsViews.root,
                        if (summary.isBlank()) baseDescription else "$baseDescription $summary",
                    )
                }
            }
        }

        val clock = state.clock
        navClock.text = clock
        navClock.visibility = if (clock.isBlank()) View.GONE else View.VISIBLE
        navClock.contentDescription = if (clock.isBlank()) {
            null
        } else {
            context.getString(R.string.tv_home_clock_desc, clock)
        }
    }

    private fun renderRecording(state: TvUiState) {
        val active = state.recording.active
        if (lastRecordingActive == active) return
        lastRecordingActive = active
        val label = if (active) R.string.tv_home_stop_record else R.string.tv_home_record
        val description = if (active) R.string.tv_home_stop_record_desc else R.string.tv_home_record_desc
        heroRecord.label.setText(label)
        heroRecord.icon.imageTintList = ColorStateList.valueOf(
            TvTokens.color(
                context,
                if (active) R.color.tv_ui_error else R.color.tv_ui_text_2,
            ),
        )
        TvFocus.describe(heroRecord.root, context.getString(description))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun initialFocusView(): View? =
        initialDestination?.let { navViews[it]?.root } ?: heroPlay.root

    private fun restoreRailPosition() {
        post {
            if (TvHomeMemory.focusedRail) {
                val key = TvHomeMemory.lastRailKey
                val index = if (key != null) railAdapter.indexOfKey(key) else -1
                if (index >= 0) {
                    railRecycler.scrollToPosition(index)
                    railRecycler.post {
                        val holder = railRecycler.findViewHolderForAdapterPosition(index)
                        val view = holder?.itemView
                        if (view != null && view.isFocusable) view.requestFocus()
                        else focusController.ensureFocus()
                    }
                    return@post
                }
            }
            if (railRecycler.visibility == View.VISIBLE && TvHomeMemory.railFirstVisible > 0) {
                railRecycler.scrollToPosition(TvHomeMemory.railFirstVisible)
            }
            focusController.ensureFocus()
        }
    }

    private fun focusFirstRailItem() {
        railRecycler.post {
            val holder = railRecycler.findViewHolderForAdapterPosition(0)
            val view = holder?.itemView
            if (view != null && view.isFocusable) view.requestFocus()
            else focusController.ensureFocus()
        }
    }

    private fun computeHomeMetrics(context: Context): TvHomeMetrics {
        val density = resources.displayMetrics.density
        fun dimenDp(id: Int) = (resources.getDimension(id) / density).toInt()
        fun lineHeightDp(style: Int): Int {
            val sample = TextView(context).apply {
                setTextAppearance(style)
                includeFontPadding = false
            }
            val font = sample.paint.fontMetricsInt
            return ceil((font.descent - font.ascent) / density).toInt()
        }
        val secondaryLine = lineHeightDp(R.style.TvUiTextSecondary)
        val heroTitleLine = lineHeightDp(R.style.TvUiTextHero)
        val badgeRowHeight = secondaryLine + 2 * dimenDp(R.dimen.tv_ui_badge_pad_v)
        val heroBottomHeight = 2 * heroTitleLine + dimenDp(R.dimen.tv_ui_gap_1) + secondaryLine +
            dimenDp(R.dimen.tv_ui_gap_1) + dimenDp(R.dimen.tv_ui_progress_height) +
            dimenDp(R.dimen.tv_ui_gap_2) + dimenDp(R.dimen.tv_ui_button_height)
        val heroMinimumHeight = 2 * dimenDp(R.dimen.tv_ui_hero_pad) + badgeRowHeight +
            dimenDp(R.dimen.tv_ui_gap_1) + heroBottomHeight
        return TvHomeMetrics.compute(
            widthDp = (resources.displayMetrics.widthPixels / density).toInt(),
            heightDp = (resources.displayMetrics.heightPixels / density).toInt(),
            safeMargin = TvTokens.fraction(context, R.fraction.tv_safe_margin),
            navWidthFraction = TvTokens.fraction(context, R.fraction.tv_ui_home_nav_width),
            heroHeightFraction = TvTokens.fraction(context, R.fraction.tv_ui_home_hero_height),
            railCardWidthFraction = TvTokens.fraction(context, R.fraction.tv_ui_home_rail_card_width),
            focusScale = TvTokens.integer(context, R.integer.tv_ui_focus_scale_percent) / 100f,
            ringDp = dimenDp(R.dimen.tv_ui_focus_ring_width),
            aspectWidth = TvTokens.integer(context, R.integer.tv_ui_card_aspect_w),
            aspectHeight = TvTokens.integer(context, R.integer.tv_ui_card_aspect_h),
            heroToRailGapDp = dimenDp(R.dimen.tv_ui_hero_gap),
            railTitleHeightDp = dimenDp(R.dimen.tv_ui_home_rail_title_height),
            titleToCardGapDp = dimenDp(R.dimen.tv_ui_gap_2),
            heroMinimumHeightDp = heroMinimumHeight,
        )
    }

    private fun brandInitial(): String {
        val key = BuildConfig.BRAND_KEY
        return key.take(1).uppercase(Locale.ROOT).ifBlank {
            context.getString(R.string.tv_home_brand_mark_fallback)
        }
    }

    private fun channelLine(channel: TvChannelUi?): String {
        val name = channel?.name?.takeIf { it.isNotBlank() } ?: BuildConfig.BRAND_NAME
        val number = channel?.number?.takeIf { it.isNotBlank() && it != name }
        return if (number == null) name else context.getString(R.string.tv_home_channel_line, name, number)
    }

    private fun timeRange(program: TvProgramUi): String = context.getString(
        R.string.player_schedule_time_range,
        timeFormat.format(Date(program.startMs)),
        timeFormat.format(Date(program.endMs)),
    )

    override fun onDetachedFromWindow() {
        TvMotion.cancel(exitHint)
        super.onDetachedFromWindow()
    }

    private data class BadgeSpec(val textRes: Int, val fillColor: Int, val mode: BadgeMode)
    private data class NavStatus(val textRes: Int, val dotColor: Int, val mode: NavDotMode)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

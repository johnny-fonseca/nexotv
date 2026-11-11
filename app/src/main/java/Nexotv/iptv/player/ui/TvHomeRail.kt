@file:Suppress("PackageName")

// app/src/main/java/Nexotv/iptv/player/ui/TvHomeRail.kt
package Nexotv.iptv.player.ui

import Nexotv.iptv.player.R
import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

/**
 * A fixed 16:9 program card for the home rail. All binding work is text and focus ids;
 * no decode, no layout inflation and no listener allocation per bind.
 */
internal class TvHomeRailCard(
    context: Context,
    private val onFocusedId: (Int) -> Unit,
    private val upperFocusTarget: () -> Int,
) : FrameLayout(context) {

    var program: TvProgramUi? = null
        private set

    private val timeFormat = DateFormat.getTimeFormat(context)
    private val timeView = TextView(context)
    private val titleView = TextView(context)
    private val rangeView = TextView(context)

    init {
        id = View.generateViewId()
        clipChildren = false
        isClickable = true
        setPadding(
            TvTokens.px(context, R.dimen.tv_ui_card_pad),
            TvTokens.px(context, R.dimen.tv_ui_card_pad),
            TvTokens.px(context, R.dimen.tv_ui_card_pad),
            TvTokens.px(context, R.dimen.tv_ui_card_pad),
        )
        TvFocus.attach(this, TvFocusStyle.CARD) { view, focused ->
            if (focused) {
                nextFocusUpId = upperFocusTarget()
                TvHomeMemory.focusedRail = true
                TvHomeMemory.lastRailKey = view.tag as? String
                onFocusedId(view.id)
            }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        TvTokens.text(timeView, R.style.TvUiTextSecondary, R.color.tv_ui_text_3, maxLines = 1)
        TvTokens.text(titleView, R.style.TvUiTextBody, R.color.tv_ui_text_1, maxLines = 2)
        TvTokens.text(rangeView, R.style.TvUiTextSecondary, R.color.tv_ui_text_3, maxLines = 1)
        timeView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        titleView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        rangeView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        column.addView(timeView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(
            titleView,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                topMargin = TvTokens.px(context, R.dimen.tv_ui_gap_1)
            },
        )
        column.addView(
            rangeView,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = TvTokens.px(context, R.dimen.tv_ui_gap_1)
            },
        )
        addView(column, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun bind(program: TvProgramUi, position: Int, count: Int, lastNavId: Int) {
        this.program = program
        tag = program.stableKey
        val start = timeFormat.format(Date(program.startMs))
        val end = timeFormat.format(Date(program.endMs))
        timeView.text = start
        titleView.text = program.title.ifBlank { context.getString(R.string.tv_home_program_no_title) }
        rangeView.text = context.getString(R.string.player_schedule_time_range, start, end)
        TvFocus.describe(
            this,
            context.getString(R.string.tv_home_rail_card_desc, start, titleView.text),
        )
        nextFocusDownId = homeConsumeEdge(id)
        nextFocusUpId = upperFocusTarget()
        nextFocusLeftId = if (position == 0) lastNavId else View.NO_ID
        nextFocusRightId = if (position == count - 1) homeConsumeEdge(id) else View.NO_ID
        TvFocus.key(this, "home.rail.${program.stableKey}")
    }

    fun release() {
        program = null
    }

    private companion object {
        const val MATCH_PARENT = LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = LayoutParams.WRAP_CONTENT
    }
}

/** RecyclerView adapter with stable ids and DiffUtil, as required by the TV guardrails. */
internal class TvHomeRailAdapter(
    private val cardWidthPx: Int,
    private val cardHeightPx: Int,
    private val leftNavId: () -> Int,
    private val upperFocusTarget: () -> Int,
    private val onFocusedId: (Int) -> Unit,
    private val onProgram: (TvProgramUi) -> Unit,
) : RecyclerView.Adapter<TvHomeRailAdapter.Holder>() {

    private val items = ArrayList<TvProgramUi>(INITIAL_CAPACITY)

    init {
        setHasStableIds(true)
    }

    fun submit(newItems: List<TvProgramUi>) {
        if (items == newItems) return
        val previous = items.toList()
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previous.size
                override fun getNewListSize(): Int = newItems.size

                override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                    previous[oldPosition].stableKey == newItems[newPosition].stableKey

                override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                    previous[oldPosition] == newItems[newPosition]
            },
            true,
        )
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun indexOfKey(key: String): Int {
        for (index in items.indices) {
            if (items[index].stableKey == key) return index
        }
        return -1
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        val program = items[position]
        return ((program.startMs * 31L) xor program.channelId.hashCode().toLong()) and Long.MAX_VALUE
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val card = TvHomeRailCard(parent.context, onFocusedId, upperFocusTarget)
        card.layoutParams = RecyclerView.LayoutParams(cardWidthPx, cardHeightPx)
        card.setOnClickListener {
            val position = (card.parent as? RecyclerView)?.getChildAdapterPosition(card)
            if (position != null && position != RecyclerView.NO_POSITION) {
                items.getOrNull(position)?.let(onProgram)
            }
        }
        return Holder(card)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.card.bind(items[position], position, itemCount, leftNavId())
    }

    override fun onViewRecycled(holder: Holder) {
        holder.card.release()
    }

    internal class Holder(val card: TvHomeRailCard) : RecyclerView.ViewHolder(card)

    private companion object {
        const val INITIAL_CAPACITY = 12
    }
}

// GuideProgramFocus.kt
package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.diagnostics.DiagnosticTracker
import Nexotv.iptv.player.guide.GuideProgramFocus
import Nexotv.iptv.player.guide.GuideProgramMeta
import Nexotv.iptv.player.guide.GuideUpdateAge

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FOCUS_ERROR_TAG = "GUIDE_ERROR"
private const val MAX_PANEL_TITLE_LENGTH = 300
private const val MAX_PANEL_TEXT_LENGTH = 4_000
private const val MAX_STATUS_LABEL_LENGTH = 200

private fun logGuideFocusFailure(scope: String, error: Throwable) {
    DiagnosticTracker.log(FOCUS_ERROR_TAG, "$scope: ${error.javaClass.simpleName}")
}

private inline fun guideGuard(scope: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        logGuideFocusFailure(scope, e)
    }
}

internal data class GuideProgramPanelState(val reminder: Boolean, val recording: Boolean, val saved: Boolean)

/** Binds selected-program data and action behavior to the guide detail panel. */
internal class GuideProgramDetailPanel(
    private val activity: Activity,
    private val posterImage: ImageView,
    private val posterBadgeLive: TextView,
    private val posterBadgeCategory: TextView,
    private val title: TextView,
    private val meta: TextView,
    private val description: TextView,
    private val statuses: LinearLayout,
    private val reminderButton: TextView,
    private val recordButton: TextView,
    private val savedButton: TextView,
    private val tooltip: TextView,
    private val userAgent: String,
    private val timeFormat: DateFormat,
    private val selectedDayIndex: () -> Int,
    private val reminderLead: () -> Int,
    private val updatedAt: () -> Long,
    private val stateFor: (TvProgram) -> GuideProgramPanelState,
    private val isStillSelected: (TvProgram) -> Boolean,
    private val onSetReminder: (TvProgram, Boolean, Boolean, () -> Unit) -> Unit,
    private val onSetRecording: (TvProgram, Boolean, Boolean, () -> Unit) -> Unit,
    private val onSetSaved: (TvProgram, Boolean) -> Unit,
    private val onChanged: () -> Unit,
) {
    fun update(program: TvProgram?) {
        if (!activity.guideIsUsable()) return
        try {
            if (program == null) {
                showEmpty()
            } else {
                bind(program)
            }
        } catch (e: Exception) {
            logGuideFocusFailure("detailPanel.update", e)
        }
    }

    private fun bind(program: TvProgram) {
        val now = System.currentTimeMillis()
        val live = program.startMs <= now && program.endMs > now
        val state = try {
            stateFor(program)
        } catch (e: Exception) {
            logGuideFocusFailure("detailPanel.state", e)
            GuideProgramPanelState(reminder = false, recording = false, saved = false)
        }
        val category = program.category.trim().take(MAX_PANEL_TITLE_LENGTH)
        posterBadgeLive.visibility = if (live) View.VISIBLE else View.GONE
        posterBadgeCategory.text = category
        posterBadgeCategory.visibility = if (category.isNotBlank()) View.VISIBLE else View.GONE
        loadPoster(program)

        title.text = program.title.take(MAX_PANEL_TITLE_LENGTH)
        meta.text = try {
            GuideProgramMeta.detail(program, selectedDayIndex(), timeFormat).take(MAX_PANEL_TEXT_LENGTH)
        } catch (e: Exception) {
            logGuideFocusFailure("detailPanel.meta", e)
            ""
        }
        description.text = try {
            GuideProgramMeta.description(program, activity.getString(R.string.tv_guide_missing_description))
                .take(MAX_PANEL_TEXT_LENGTH)
        } catch (e: Exception) {
            logGuideFocusFailure("detailPanel.description", e)
            activity.getString(R.string.tv_guide_missing_description)
        }
        renderStatuses(state)

        reminderButton.visibility = View.VISIBLE
        recordButton.visibility = View.VISIBLE
        savedButton.visibility = View.VISIBLE

        reminderButton.text = if (state.reminder) "Quitar aviso" else "Recordarme"
        reminderButton.setOnClickListener {
            guideGuard("detailPanel.reminder") {
                onSetReminder(program, !state.reminder, state.recording) { refresh(program) }
            }
        }
        recordButton.text = if (state.recording) "Cancelar grab." else "Grabar"
        recordButton.setOnClickListener {
            guideGuard("detailPanel.record") {
                onSetRecording(program, state.reminder, !state.recording) { refresh(program) }
            }
        }
        savedButton.text = if (state.saved) "Quitar de Mis progs" else "Guardar en Mis progs"
        savedButton.setOnClickListener {
            guideGuard("detailPanel.saved") {
                onSetSaved(program, !state.saved)
                refresh(program)
            }
        }
        tooltip.text = try {
            activity.getString(R.string.tv_guide_tooltip_format, GuideUpdateAge.format(updatedAt()))
        } catch (e: Exception) {
            logGuideFocusFailure("detailPanel.tooltip", e)
            ""
        }
    }

    private fun refresh(program: TvProgram) {
        if (!activity.guideIsUsable()) return
        update(program)
        guideGuard("detailPanel.onChanged") { onChanged() }
    }

    private fun showEmpty() {
        posterImage.tag = null
        posterImage.setImageResource(R.drawable.guide_placeholder)
        posterBadgeLive.visibility = View.GONE
        posterBadgeCategory.text = ""
        posterBadgeCategory.visibility = View.GONE
        title.text = activity.getString(R.string.tv_guide_empty_title)
        meta.text = activity.getString(R.string.tv_guide_empty_meta)
        statuses.removeAllViews()
        description.text = activity.getString(R.string.tv_guide_empty_description)
        reminderButton.visibility = View.GONE
        recordButton.visibility = View.GONE
        savedButton.visibility = View.GONE
    }

    private fun renderStatuses(state: GuideProgramPanelState) {
        statuses.removeAllViews()
        guideGuard("detailPanel.statuses") {
            if (state.recording) {
                statuses.addView(
                    GuideDetailStatusLine.create(
                        activity,
                        R.drawable.tv_menu_record,
                        "Grabación programada",
                        activity.getColor(R.color.tv_success),
                    ),
                )
            }
            if (state.saved) {
                statuses.addView(
                    GuideDetailStatusLine.create(
                        activity,
                        R.drawable.tv_icon_bookmark,
                        "Guardado en Mis programas",
                        activity.getColor(R.color.tv_turquoise),
                    ),
                )
            }
            if (state.reminder) {
                val lead = runCatching { reminderLead() }.getOrDefault(0).coerceAtLeast(0)
                statuses.addView(
                    GuideDetailStatusLine.create(
                        activity,
                        R.drawable.tv_icon_reminder,
                        "Recordatorio activo ($lead min antes)",
                        0xFFFBBF24.toInt(),
                    ),
                )
            }
        }
    }

    private fun loadPoster(program: TvProgram) {
        val selected = try {
            isStillSelected(program)
        } catch (e: Exception) {
            logGuideFocusFailure("detailPanel.selected", e)
            false
        }
        if (!selected) return
        try {
            activity.loadEpgProgramArtwork(posterImage, program, userAgent, R.drawable.guide_placeholder)
        } catch (e: Exception) {
            logGuideFocusFailure("detailPanel.poster", e)
            runCatching { posterImage.setImageResource(R.drawable.guide_placeholder) }
        }
    }
}

/** Program reminder, recording, and saved-list actions dialog. */
internal object GuideProgramActionsDialog {
    fun show(
        activity: Activity,
        program: TvProgram,
        userAgent: String,
        initialReminder: Boolean,
        initialRecording: Boolean,
        initiallySaved: Boolean,
        onSaveSchedule: (reminder: Boolean, recording: Boolean, message: String, onSaved: () -> Unit) -> Unit,
        onSavedChange: (Boolean) -> Unit,
        onStateChange: (reminder: Boolean, recording: Boolean, saved: Boolean) -> Unit,
        onDialogReady: (AlertDialog, View) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var dismissed = false
        fun dismissOnce() {
            if (dismissed) return
            dismissed = true
            guideGuard("actionsDialog.onDismiss") { onDismiss() }
        }

        if (!activity.guideIsUsable()) {
            dismissOnce()
            return
        }
        try {
            var reminder = initialReminder
            var recording = initialRecording
            var saved = initiallySaved
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    activity.resources.getDimensionPixelSize(R.dimen.tv_component_padding),
                    activity.resources.getDimensionPixelSize(R.dimen.tv_space_1),
                    activity.resources.getDimensionPixelSize(R.dimen.tv_component_padding),
                    0,
                )
            }
            if (guideIsHttpUrl(program.iconUrl)) {
                guideGuard("actionsDialog.icon") {
                    val imageHeight = activity.resources.getDimensionPixelSize(R.dimen.tv_guide_program_image_height)
                    val icon = ImageView(activity).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        maxHeight = imageHeight
                        background = menuCardSurface(activity, 14f)
                        clipToOutline = true
                    }
                    content.addView(icon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imageHeight))
                    activity.loadEpgProgramArtwork(icon, program, userAgent, R.drawable.guide_placeholder)
                }
            }
            val description = listOfNotNull(
                "${guideFormatDayClock(program.startMs)} – ${guideFormatClock(program.endMs)}",
                program.category.takeIf(String::isNotBlank)?.let { "Categoría: $it" },
                program.rating.takeIf(String::isNotBlank)?.let { "Clasificación: $it" },
                program.description.takeIf(String::isNotBlank),
            ).joinToString("\n").take(MAX_PANEL_TEXT_LENGTH)
            content.addView(TextView(activity).apply {
                text = description
                setTextColor(activity.getColor(R.color.tv_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.resources.getDimension(R.dimen.tv_text_secondary))
                setPadding(
                    0,
                    activity.resources.getDimensionPixelSize(R.dimen.tv_space_1),
                    0,
                    activity.resources.getDimensionPixelSize(R.dimen.tv_space_2),
                )
            })
            val currentState = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val detailSurface = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = menuCardSurface(activity, 18f)
                setPadding(activity.guideDp(24), activity.guideDp(8), activity.guideDp(24), activity.guideDp(20))
                addView(content)
                addView(currentState)
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(program.title.take(MAX_PANEL_TITLE_LENGTH))
                .setView(detailSurface)
                .create()

            var refreshButtons: () -> Unit = {}

            val reminderButton = TvStyle.primaryButton(
                activity,
                activity.getString(if (reminder) R.string.tv_guide_remove_reminder else R.string.tv_guide_remind_action),
            ) {
                guideGuard("actionsDialog.reminder") {
                    val enabled = !reminder
                    val message = activity.getString(
                        if (enabled) R.string.tv_guide_reminder else R.string.tv_guide_reminder_removed,
                    )
                    onSaveSchedule(enabled, recording, message) {
                        reminder = enabled
                        refreshButtons()
                    }
                }
            }
            val recordingButton = TvStyle.secondaryButton(
                activity,
                activity.getString(if (recording) R.string.tv_guide_remove_recording else R.string.tv_guide_record_action),
            ) {
                guideGuard("actionsDialog.recording") {
                    val enabled = !recording
                    val message = activity.getString(
                        if (enabled) R.string.tv_guide_recording_scheduled else R.string.tv_guide_recording_removed,
                    )
                    onSaveSchedule(reminder, enabled, message) {
                        recording = enabled
                        refreshButtons()
                    }
                }
            }
            val savedButton = TvStyle.secondaryButton(
                activity,
                activity.getString(if (saved) R.string.tv_guide_remove_saved else R.string.tv_guide_save_action),
            ) {
                val previous = saved
                try {
                    saved = !previous
                    onSavedChange(saved)
                } catch (e: Exception) {
                    saved = previous
                    logGuideFocusFailure("actionsDialog.saved", e)
                }
                refreshButtons()
            }
            refreshButtons = {
                guideGuard("actionsDialog.refresh") {
                    reminderButton.text = activity.getString(
                        if (reminder) R.string.tv_guide_remove_reminder else R.string.tv_guide_remind_action,
                    )
                    reminderButton.contentDescription = reminderButton.text
                    recordingButton.text = activity.getString(
                        if (recording) R.string.tv_guide_remove_recording else R.string.tv_guide_record_action,
                    )
                    recordingButton.contentDescription = recordingButton.text
                    savedButton.text = activity.getString(
                        if (saved) R.string.tv_guide_remove_saved else R.string.tv_guide_save_action,
                    )
                    savedButton.contentDescription = savedButton.text
                    currentState.removeAllViews()
                    if (reminder) {
                        currentState.addView(
                            GuideProgramStateViews.statusLine(
                                activity,
                                R.drawable.tv_icon_reminder,
                                activity.getString(R.string.tv_guide_reminder),
                            ),
                        )
                    }
                    if (recording) {
                        currentState.addView(
                            GuideProgramStateViews.statusLine(
                                activity,
                                R.drawable.tv_icon_recordings,
                                activity.getString(R.string.tv_guide_recording_scheduled),
                            ),
                        )
                    }
                    if (saved) {
                        currentState.addView(
                            GuideProgramStateViews.statusLine(
                                activity,
                                R.drawable.tv_icon_bookmark,
                                activity.getString(R.string.tv_guide_saved),
                            ),
                        )
                    }
                }
                guideGuard("actionsDialog.onStateChange") { onStateChange(reminder, recording, saved) }
            }

            val buttons = listOf(reminderButton, recordingButton, savedButton)
            buttons.forEach { it.id = View.generateViewId() }
            buttons.forEachIndexed { index, button ->
                content.addView(
                    button,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = activity.resources.getDimensionPixelSize(R.dimen.tv_guide_row_gap)
                    },
                )
                button.nextFocusLeftId = button.id
                button.nextFocusRightId = button.id
                button.nextFocusUpId = buttons.getOrNull(index - 1)?.id ?: button.id
                button.nextFocusDownId = buttons.getOrNull(index + 1)?.id ?: button.id
            }
            refreshButtons()
            guideGuard("actionsDialog.onDialogReady") { onDialogReady(dialog, reminderButton) }
            dialog.setOnDismissListener { dismissOnce() }
            if (activity.guideIsUsable()) dialog.show() else dismissOnce()
        } catch (e: Exception) {
            logGuideFocusFailure("actionsDialog.show", e)
            dismissOnce()
        }
    }
}

/** Status chips and row accessibility state for guide program actions. */
internal object GuideProgramStateViews {
    fun statusLine(activity: Activity, iconResource: Int, label: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = menuPill(
            activity,
            bgHex = activity.getColor(R.color.tv_menu_row_normal),
            strokeHex = activity.getColor(R.color.tv_menu_panel_stroke),
        )
        setPadding(activity.guideDp(14), activity.guideDp(6), activity.guideDp(14), activity.guideDp(6))
        val safeLabel = label.take(MAX_STATUS_LABEL_LENGTH)
        val icon = try {
            ImageView(activity).apply {
                setImageResource(iconResource)
                imageTintList = ColorStateList.valueOf(activity.getColor(R.color.tv_turquoise))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        } catch (e: Exception) {
            logGuideFocusFailure("statusLine.icon", e)
            null
        }
        if (icon != null) {
            addView(
                icon,
                LinearLayout.LayoutParams(activity.guideDp(18), activity.guideDp(18)).apply {
                    marginEnd = activity.guideDp(8)
                },
            )
        }
        addView(TextView(activity).apply {
            text = safeLabel
            setTextColor(activity.getColor(R.color.tv_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            contentDescription = safeLabel
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = activity.guideDp(6)
            marginStart = activity.guideDp(16)
        }
    }

    fun updateRow(
        activity: Activity,
        row: View?,
        program: TvProgram,
        reminder: Boolean,
        recording: Boolean,
        saved: Boolean,
    ) {
        if (!activity.guideIsUsable()) return
        try {
            val programRow = row as? LinearLayout ?: return
            if (programRow.childCount <= 2) return
            val stateView = programRow.getChildAt(2) as? TextView ?: return
            val currentTime = System.currentTimeMillis()
            val previous = stateView.text.toString().substringBefore(" · ")
            val savedState = previous.startsWith(activity.getString(R.string.tv_guide_icon_saved))
            val base = when {
                savedState && saved -> previous
                program.startMs <= currentTime && program.endMs > currentTime -> activity.getString(R.string.tv_guide_live)
                program.endMs <= currentTime -> activity.getString(R.string.tv_guide_later)
                previous.startsWith(activity.getString(R.string.tv_guide_icon_next)) -> activity.getString(R.string.tv_guide_next)
                else -> activity.getString(R.string.tv_guide_later)
            }
            val statuses = buildList {
                if (saved && !savedState) {
                    add("${activity.getString(R.string.tv_guide_icon_saved)} ${activity.getString(R.string.tv_guide_saved)}")
                }
                if (reminder) {
                    add("${activity.getString(R.string.tv_guide_icon_reminder)} ${activity.getString(R.string.tv_guide_reminder)}")
                }
                if (recording) {
                    add("${activity.getString(R.string.tv_guide_icon_recording)} ${activity.getString(R.string.tv_guide_recording_scheduled)}")
                }
            }
            val state = (listOf(base) + statuses).joinToString(" · ").take(MAX_STATUS_LABEL_LENGTH)
            stateView.text = state
            programRow.contentDescription =
                "${program.title.take(MAX_PANEL_TITLE_LENGTH)}. ${guideFormatClock(program.startMs)}–${guideFormatClock(program.endMs)} · $state"
        } catch (e: Exception) {
            logGuideFocusFailure("updateRow", e)
        }
    }
}

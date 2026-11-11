// GuideSearchScreen.kt
@file:Suppress("SetTextI18n", "NewApi", "unused")

package Nexotv.iptv.player.guide

import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.InputFilter
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Construye la pantalla de búsqueda con superficies y jerarquía visual del menú principal. */
internal class GuideSearchDialog(
    private val activity: Activity,
    private val recentSearches: List<String>,
    private val onSearch: (String) -> Unit,
    private val onVoiceSearch: (Intent) -> Unit,
    private val styleInput: (EditText) -> Unit,
    private val createSearchButton: (String, () -> Unit) -> TextView,
    private val onBack: (AlertDialog) -> Unit,
    private val onDialogCreated: (AlertDialog, EditText, LinearLayout, LinearLayout) -> Unit,
) {
    private class ResultsPane(val root: View, val title: TextView, val subtitle: TextView)

    private var lastDispatchedQuery: String? = null
    private var lastDispatchAtMs: Long = 0L

    fun show() {
        safely { showInternal() }
    }

    private fun showInternal() {
        if (!isActivityUsable()) return

        val query = buildGuideSearchInput(activity, styleInput)
        limitQueryLength(query)

        val results = LinearLayout(activity).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        val leftContainer = buildLeftColumn(query, results)
        val pane = buildResultsPane(results)

        val mainBody = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(leftContainer, LinearLayout.LayoutParams(0, MATCH, 0.36f))
            addView(pane.root, LinearLayout.LayoutParams(0, MATCH, 0.64f).apply { marginStart = dp(16) })
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = attempt { menuBackdrop(activity) }
            setPadding(dp(30), dp(22), dp(30), dp(16))
            clipChildren = false
            clipToPadding = false

            addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(18) })
            addView(mainBody, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(buildFooterBar(), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .create()
        safely { onDialogCreated(dialog, query, results, content) }

        query.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE
            val isHardwareEnter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (isSearchAction || isHardwareEnter) {
                dispatchSearch(query.text)
                true
            } else false
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) {
                safely { handleBack(dialog, query, results, pane) }
                true
            } else false
        }

        if (!isActivityUsable()) return
        dialog.show()
    }

    private fun isActivityUsable(): Boolean = !activity.isFinishing && !activity.isDestroyed

    private fun dispatchSearch(raw: CharSequence?) {
        if (!isActivityUsable()) return
        val sanitized = attempt { sanitizeQuery(raw) } ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastDispatchAtMs
        if (sanitized == lastDispatchedQuery && elapsed in 0L until DISPATCH_DEBOUNCE_MS) return
        lastDispatchedQuery = sanitized
        lastDispatchAtMs = now
        safely { onSearch(sanitized) }
    }

    private fun handleBack(dialog: AlertDialog, query: EditText, results: LinearLayout, pane: ResultsPane) {
        if (results.childCount > 0) {
            results.removeAllViews()
            pane.title.text = TITLE_RESULTS
            pane.subtitle.text = SUBTITLE_GUIDE
            query.requestFocus()
        } else {
            attempt { dialog.dismiss() }
            safely { onBack(dialog) }
        }
    }

    private fun limitQueryLength(query: EditText) {
        attempt {
            val current = query.filters ?: emptyArray<InputFilter>()
            if (current.none { it is InputFilter.LengthFilter }) {
                query.filters = current + InputFilter.LengthFilter(MAX_QUERY_LENGTH)
            }
        }
    }

    private fun buildLeftColumn(query: EditText, results: LinearLayout): View {
        val leftPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = attempt { menuMainPanel(activity) }
            setPadding(dp(22), dp(22), dp(22), dp(18))
            clipChildren = false
            clipToPadding = false
        }

        leftPanel.addView(
            buildSectionEyebrow(R.drawable.tv_icon_search, "BÚSQUEDA DE GUÍA"),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) },
        )

        leftPanel.addView(
            label("¿Qué quieres ver?", Color.WHITE, 25f, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) },
        )

        leftPanel.addView(
            label("Encuentra programas por título o descripción.", themeColor(R.color.tv_text_secondary), 13f),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(18) },
        )

        leftPanel.addView(
            label("TÍTULO O PALABRA CLAVE", themeColor(R.color.tv_text_secondary), 10f, bold = true).apply {
                letterSpacing = LETTER_SPACING_EYEBROW
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(7) },
        )
        leftPanel.addView(query, LinearLayout.LayoutParams(MATCH, dp(62)))

        val chipViews = mutableListOf<Button>()
        appendRecentSearches(leftPanel, chipViews)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(0, dp(18), 0, 0)
        }
        val btnSearch = createSearchButton("Buscar") { dispatchSearch(query.text) }.apply {
            id = View.generateViewId()
            attempt { applySearchFocusIndicator(activity) }
        }
        actions.addView(btnSearch, LinearLayout.LayoutParams(0, dp(54), 1f))

        val voiceIntent = buildVoiceIntent()
        val btnVoice: TextView? = if (voiceIntent != null && isVoiceSearchAvailable(voiceIntent)) {
            createSearchButton("Buscar por voz") { safely { onVoiceSearch(voiceIntent) } }.apply {
                id = View.generateViewId()
                attempt { applySearchFocusIndicator(activity) }
            }
        } else null
        if (btnVoice != null) {
            actions.addView(btnVoice, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginStart = dp(8)
            })
        }
        leftPanel.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))

        attempt { wireGuideSearchFocus(query, chipViews, btnSearch, btnVoice, results) }

        leftPanel.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))

        leftPanel.addView(
            label(
                "Pulsa Buscar o presiona Enter para ver coincidencias.",
                themeColor(R.color.tv_text_secondary),
                11f,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(leftPanel, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(
                label("↑ ↓  Navegar     Atrás  Menú", themeColor(R.color.tv_text_secondary), 10f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, 0)
                },
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
        }
    }

    private fun cleanRecentSearches(): List<String> = attempt {
        recentSearches.asSequence()
            .mapNotNull { raw -> attempt { sanitizeQuery(raw) } }
            .filter { term -> term.isNotEmpty() }
            .distinct()
            .take(MAX_RECENT_CHIPS)
            .toList()
    } ?: emptyList()

    private fun appendRecentSearches(panel: LinearLayout, chipViews: MutableList<Button>) {
        val terms = cleanRecentSearches()
        if (terms.isEmpty()) return

        val historyRows = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        terms.forEach { term ->
            val chip = attempt { buildGuideSearchChip(activity, term) { dispatchSearch(term) } }
                ?: return@forEach
            chipViews.add(chip)
            historyRows.addView(
                chip,
                LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) },
            )
        }
        if (chipViews.isEmpty()) return

        val recentsTitle = attempt { activity.getString(R.string.tv_guide_recent_searches) } ?: RECENTS_FALLBACK
        panel.addView(
            TextView(activity).apply {
                text = recentsTitle
                textSize = 11f
                setTextColor(themeColor(R.color.tv_text_secondary))
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = LETTER_SPACING_EYEBROW
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(22)
                bottomMargin = dp(8)
            },
        )
        panel.addView(
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                clipChildren = true
                clipToPadding = false
                addView(historyRows)
            },
            LinearLayout.LayoutParams(MATCH, dp(48)),
        )
    }

    private fun buildVoiceIntent(): Intent? = attempt {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val languageTag = Locale.getDefault().toLanguageTag()
            if (languageTag.isNotEmpty() && languageTag != UNDETERMINED_LANGUAGE_TAG) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun isVoiceSearchAvailable(intent: Intent): Boolean =
        attempt { intent.resolveActivity(activity.packageManager) != null } ?: false

    private fun buildResultsPane(results: LinearLayout): ResultsPane {
        val rightPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = attempt { menuCardSurface(activity, 18f) }
            setPadding(dp(22), dp(22), dp(22), dp(18))
            clipChildren = false
            clipToPadding = false
        }

        val resultsHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                buildSectionEyebrow(R.drawable.tv_icon_search, "PROGRAMACIÓN"),
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
        }
        rightPanel.addView(resultsHeaderRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

        val resultsTitle = label(TITLE_RESULTS, Color.WHITE, 22f, bold = true)
        rightPanel.addView(resultsTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(5) })

        val resultsSubtitle = label(SUBTITLE_GUIDE, themeColor(R.color.tv_text_secondary), 12f)
        rightPanel.addView(resultsSubtitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

        rightPanel.addView(
            View(activity).apply { setBackgroundColor(themeColor(R.color.tv_menu_panel_stroke)) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(12) },
        )

        rightPanel.addView(results, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val rightContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = false
            addView(rightPanel, LinearLayout.LayoutParams(MATCH, MATCH))
        }
        return ResultsPane(rightContainer, resultsTitle, resultsSubtitle)
    }

    private fun label(value: String, color: Int, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(activity).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun themedPill() = attempt {
        menuPill(
            activity,
            themeColor(R.color.tv_menu_row_normal),
            themeColor(R.color.tv_menu_panel_stroke),
        )
    }

    private fun buildSectionEyebrow(iconResource: Int, title: String): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                FrameLayout(activity).apply {
                    background = themedPill()
                    addView(
                        ImageView(activity).apply {
                            attempt { setImageResource(iconResource) }
                            imageTintList = ColorStateList.valueOf(themeColor(R.color.tv_turquoise))
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                        FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER),
                    )
                },
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) },
            )
            addView(
                label(title, themeColor(R.color.tv_turquoise), 10f, bold = true).apply {
                    letterSpacing = LETTER_SPACING_EYEBROW
                },
            )
        }

    private fun buildTopBar(): LinearLayout {
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val brandTexts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(BuildConfig.BRAND_NAME, Color.WHITE, 17f, bold = true))
            addView(
                label(
                    "${BuildConfig.CHANNEL_NAME} · GUÍA DE PROGRAMACIÓN",
                    themeColor(R.color.tv_text_secondary),
                    10f,
                    bold = true,
                ),
            )
        }
        val brandBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            attempt { menuBrandBadge(activity) }?.let { badge ->
                addView(
                    badge,
                    LinearLayout.LayoutParams(dp(MENU_BRAND_BADGE_SIZE_DP), dp(MENU_BRAND_BADGE_SIZE_DP)).apply {
                        marginEnd = dp(10)
                    },
                )
            }
            addView(brandTexts, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        topBar.addView(brandBox, LinearLayout.LayoutParams(0, WRAP, 1f))

        val searchIconPill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = themedPill()
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(
                ImageView(activity).apply {
                    attempt { setImageResource(R.drawable.tv_icon_search) }
                    imageTintList = ColorStateList.valueOf(themeColor(R.color.tv_turquoise))
                },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) },
            )
            addView(label("BUSCAR", themeColor(R.color.tv_turquoise), 11f, bold = true))
        }
        topBar.addView(searchIconPill, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(12) })

        val preferences = attempt { activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE) }
        val use24HourClock = attempt {
            preferences?.getString(MainActivity.KEY_CLOCK_FORMAT, CLOCK_FORMAT_12H) == CLOCK_FORMAT_24H
        } ?: false
        val showClock = attempt { preferences?.getBoolean(MainActivity.KEY_SHOW_CLOCK, true) } ?: true
        val timePattern = if (use24HourClock) "HH:mm" else "h:mm a"
        val clockLabel = attempt {
            capitalizeFirst(
                SimpleDateFormat("$timePattern  EEE d MMM", Locale.forLanguageTag("es-CR")).format(Date()),
            )
        } ?: ""
        val clockPill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = themedPill()
            setPadding(dp(14), dp(6), dp(14), dp(6))
            addView(
                ImageView(activity).apply {
                    attempt { setImageResource(R.drawable.tv_icon_clock) }
                    imageTintList = ColorStateList.valueOf(themeColor(R.color.tv_text_primary))
                },
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) },
            )
            addView(label(clockLabel, themeColor(R.color.tv_text_primary), 11f))
            visibility = if (showClock && clockLabel.isNotEmpty()) View.VISIBLE else View.GONE
        }
        topBar.addView(clockPill, LinearLayout.LayoutParams(WRAP, WRAP))
        return topBar
    }

    private fun buildFooterBar(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), 0)

        addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    label("OK", themeColor(R.color.tv_text_primary), 9f, bold = true).apply {
                        background = themedPill()
                        setPadding(dp(7), dp(3), dp(7), dp(3))
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) },
                )
                addView(label("Buscar en la programación", themeColor(R.color.tv_text_secondary), 10f))
            },
        )

        addView(
            label("↑ ↓  Navegar", themeColor(R.color.tv_text_secondary), 10f),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(18) },
        )

        addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))

        addView(
            label(
                "${BuildConfig.BRAND_NAME}  ·  ${BuildConfig.CHANNEL_NAME}",
                themeColor(R.color.tv_text_secondary),
                10f,
            ),
        )
    }

    private fun dp(value: Int): Int = activity.guideSearchDp(value)

    private fun themeColor(resId: Int): Int = attempt { activity.getColor(resId) } ?: Color.GRAY

    private inline fun <T> attempt(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TITLE_RESULTS = "Resultados de búsqueda"
        const val RECENTS_FALLBACK = "BÚSQUEDAS RECIENTES"
        const val CLOCK_FORMAT_12H = "12-hour"
        const val CLOCK_FORMAT_24H = "24-hour"
        const val UNDETERMINED_LANGUAGE_TAG = "und"
        const val MAX_QUERY_LENGTH = 120
        const val MAX_RECENT_CHIPS = 20
        const val DISPATCH_DEBOUNCE_MS = 400L
        const val LETTER_SPACING_EYEBROW = 0.1f
    }
}
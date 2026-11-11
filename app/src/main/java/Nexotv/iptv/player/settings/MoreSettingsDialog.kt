// app/src/main/java/Nexotv/iptv/player/settings/MoreSettingsDialog.kt
package Nexotv.iptv.player.settings
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Construye el diálogo de ajustes agrupado mientras la Activity provee los ajustes y las acciones. */
internal class MoreSettingsDialog(
    private val context: Activity,
    private val groupNames: List<String>,
    private val recommended: Map<String, List<Pair<Pair<String, String>, () -> Unit>>>,
    private val advancedItems: Map<String, List<Pair<Pair<String, String>, () -> Unit>>>,
    private val createFocusCard: (String, String, () -> Unit) -> View,
    private val iconFor: (String) -> Int,
    private val onDialogReady: (AlertDialog, ViewGroup, View) -> Unit,
    private val prepareEntryAction: (String, () -> Unit) -> () -> Unit = { _, action -> action },
) {
    private companion object {
        const val TAG = "MoreSettingsDialog"
        const val MODE_RECOMMENDED_LABEL = "Opciones avanzadas"
        const val MODE_ADVANCED_LABEL = "Ver recomendados"
        const val FADE_OUT_MS = 100L
        const val FADE_IN_MS = 180L
        const val FOCUS_SCALE_MS = 155L
        const val SLIDE_OFFSET_DP = 6
    }

    fun show() {
        if (groupNames.isEmpty()) {
            Log.w(TAG, "No hay secciones para mostrar")
            return
        }
        if (context.isFinishing || context.isDestroyed) return

        try {
            buildAndShow()
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo mostrar el diálogo de ajustes", error)
            Toast.makeText(context, "No se pudieron abrir los ajustes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAndShow() {
        var advancedMode = false
        var renderVersion = 0
        val currentGroup = arrayOf(groupNames.first())

        val metrics = context.resources.displayMetrics
        val screenWidthDp = metrics.widthPixels / metrics.density
        val screenHeightDp = metrics.heightPixels / metrics.density
        val categoryWidth = context.dp(((screenWidthDp - 56f) * 0.42f).toInt().coerceIn(220, 300))
        val categoryRowHeight = context.dp(
            ((screenHeightDp - 240f) / groupNames.size.coerceAtLeast(1)).toInt().coerceIn(48, 76)
        )

        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(28), context.dp(18), context.dp(28), context.dp(20))
            clipChildren = true
            clipToPadding = false
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, context.dp(12))
        }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(context).apply {
            text = "Más opciones"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.tv_text_primary))
        })
        heading.addView(TextView(context).apply {
            text = "Ajustes y ayuda, en un solo lugar"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(context.getColor(R.color.tv_text_secondary))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(4) })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))

        val modeButton = TvStyle.secondaryButton(context, MODE_RECOMMENDED_LABEL) { }.apply {
            id = View.generateViewId()
        }
        header.addView(modeButton, LinearLayout.LayoutParams(-2, context.dp(58)).apply { marginStart = context.dp(20) })
        page.addView(header)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = true
            clipToPadding = false
        }
        val categoryPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            background = menuCardSurface(context, 18f)
        }
        categoryPanel.addView(TextView(context).apply {
            text = "SECCIONES"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = .12f
            setTextColor(context.getColor(R.color.tv_text_secondary))
            setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(12))
        })

        val groupButtons = mutableListOf<LinearLayout>()
        val entries = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = false
        }
        val selectedGroupTitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.tv_text_primary))
        }
        val entryScroll = ScrollView(context).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            clipChildren = true
            clipToPadding = false
            addView(entries)
        }

        val focusScrollListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focusedView ->
            if (focusedView == null) return@OnGlobalFocusChangeListener
            var ancestor = focusedView.parent
            while (ancestor != null && ancestor !== entries) ancestor = ancestor.parent
            if (ancestor === entries) {
                focusedView.post {
                    if (!focusedView.isFocused || !entryScroll.isAttachedToWindow) return@post
                    focusedView.requestRectangleOnScreen(Rect(0, 0, focusedView.width, focusedView.height), true)
                }
            }
        }
        entryScroll.viewTreeObserver.addOnGlobalFocusChangeListener(focusScrollListener)
        page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                renderVersion++
                entries.animate().cancel()
                val observer = entryScroll.viewTreeObserver
                if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(focusScrollListener)
            }
        })

        fun updateGroupStyles() {
            groupButtons.forEachIndexed { index, button ->
                val selected = groupNames[index] == currentGroup[0]
                val focused = button.isFocused
                button.isSelected = selected
                button.background = GradientDrawable().apply {
                    cornerRadius = context.resources.getDimension(R.dimen.tv_menu_row_radius)
                    setColor(context.getColor(when {
                        focused -> R.color.tv_menu_row_focused
                        selected -> R.color.tv_menu_panel
                        else -> R.color.tv_menu_row_normal
                    }))
                    setStroke(context.dp(if (focused) 3 else 1), context.getColor(when {
                        focused -> R.color.tv_menu_row_focused_stroke
                        else -> R.color.tv_menu_row_normal_stroke
                    }))
                }
                (button.getChildAt(1) as? TextView)?.setTextColor(
                    context.getColor(if (selected) R.color.tv_text_primary else R.color.tv_text_secondary)
                )
                button.contentDescription = "${groupNames[index]}, ${if (selected) "seleccionado" else "sección"}"
            }
        }

        fun guardedAction(label: String, action: () -> Unit): () -> Unit {
            val prepared = try {
                prepareEntryAction(label, action)
            } catch (error: Exception) {
                Log.e(TAG, "Fallo al preparar la acción '$label'", error)
                action
            }
            return {
                try {
                    prepared()
                } catch (error: Exception) {
                    Log.e(TAG, "Fallo al ejecutar la acción '$label'", error)
                    Toast.makeText(context, "No se pudo abrir esta opción", Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun populate() {
            val group = groupButtons.getOrNull(groupNames.indexOf(currentGroup[0]))
            val models = (if (advancedMode) advancedItems else recommended)[currentGroup[0]].orEmpty()
            entries.removeAllViews()
            val rows = mutableListOf<View>()
            models.forEach { (labelAndDescription, action) ->
                val (label, description) = labelAndDescription
                val row = try {
                    createFocusCard(label, description, guardedAction(label, action)).apply {
                        id = View.generateViewId()
                        contentDescription = "$label. $description. Abrir para cambiar."
                        nextFocusLeftId = group?.id ?: View.NO_ID
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "No se pudo crear la fila '$label'", error)
                    null
                } ?: return@forEach
                entries.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    if (rows.isNotEmpty()) topMargin = context.dp(8)
                })
                rows += row
            }
            rows.forEachIndexed { index, row ->
                row.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: modeButton.id
                row.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: row.id
            }
            group?.let { section -> section.nextFocusRightId = rows.firstOrNull()?.id ?: section.id }
            modeButton.nextFocusDownId = rows.firstOrNull()?.id ?: groupButtons.first().id
            entryScroll.scrollTo(0, 0)
            updateGroupStyles()
        }

        fun renderEntries(animated: Boolean = false) {
            val version = ++renderVersion
            selectedGroupTitle.text = currentGroup[0]
            val offset = context.dp(SLIDE_OFFSET_DP).toFloat()

            entries.animate().cancel()
            if (animated && entries.childCount > 0) {
                entries.animate()
                    .alpha(0f)
                    .translationY(-offset)
                    .setDuration(FADE_OUT_MS)
                    .setInterpolator(DecelerateInterpolator(1.2f))
                    .withEndAction {
                        if (version != renderVersion || !entries.isAttachedToWindow) return@withEndAction
                        populate()
                        entries.translationY = offset
                        entries.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(FADE_IN_MS)
                            .setInterpolator(DecelerateInterpolator(1.4f))
                            .withLayer()
                            .start()
                    }
                    .start()
            } else {
                entries.alpha = 1f
                entries.translationY = 0f
                populate()
            }
        }

        modeButton.setOnClickListener {
            advancedMode = !advancedMode
            modeButton.text = if (advancedMode) MODE_ADVANCED_LABEL else MODE_RECOMMENDED_LABEL
            modeButton.contentDescription = "${modeButton.text}. Cambiar el nivel de ajustes."
            renderEntries(animated = true)
        }

        groupNames.forEachIndexed { index, name ->
            val category = LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(context.dp(12), context.dp(10), context.dp(10), context.dp(10))
                contentDescription = "$name, ${if (currentGroup[0] == name) "seleccionado" else "sección"}"
                addView(ImageView(context).apply {
                    runCatching { setImageResource(iconFor(name)) }
                        .onFailure { Log.w(TAG, "Ícono no disponible para '$name'", it) }
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.tv_turquoise))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply { marginEnd = context.dp(12) })
                addView(TextView(context).apply {
                    text = name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getColor(R.color.tv_text_secondary))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setOnFocusChangeListener { view, focused ->
                    view.animate().cancel()
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(FOCUS_SCALE_MS).setInterpolator(DecelerateInterpolator(2.2f)).withLayer().start()
                    if (focused && currentGroup[0] != name) {
                        currentGroup[0] = name
                        renderEntries()
                    } else {
                        updateGroupStyles()
                    }
                }
                setOnClickListener {
                    if (currentGroup[0] != name) {
                        currentGroup[0] = name
                        renderEntries()
                    }
                    requestFocus()
                }
            }
            groupButtons.add(category)
            categoryPanel.addView(category, LinearLayout.LayoutParams(-1, categoryRowHeight).apply {
                if (index > 0) topMargin = context.dp(8)
            })
        }

        groupButtons.forEachIndexed { index, button ->
            button.nextFocusUpId = groupButtons.getOrNull(index - 1)?.id ?: if (index == 0) modeButton.id else button.id
            button.nextFocusDownId = groupButtons.getOrNull(index + 1)?.id ?: button.id
            button.nextFocusLeftId = button.id
        }
        modeButton.nextFocusDownId = groupButtons.first().id

        categoryPanel.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        categoryPanel.addView(TextView(context).apply {
            text = "Atrás para volver"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.tv_text_secondary))
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(4))
            alpha = 0.75f
        })

        val detailPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(22), context.dp(20), context.dp(22), context.dp(20))
            background = menuCardSurface(context, 18f)
        }
        detailPanel.addView(selectedGroupTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(14) })
        detailPanel.addView(entryScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(categoryPanel, LinearLayout.LayoutParams(categoryWidth, -1).apply { marginEnd = context.dp(16) })
        body.addView(detailPanel, LinearLayout.LayoutParams(0, -1, 1f))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        renderEntries()

        val dialog = AlertDialog.Builder(context).setView(page).create()
        dialog.window?.setBackgroundDrawable(menuBackdrop(context))
        dialog.setOnShowListener {
            (context as? MainActivity)?.animateDialogEntrance(dialog)
        }
        onDialogReady(dialog, page, groupButtons.first())
        if (context.isFinishing || context.isDestroyed) return
        dialog.show()
    }
}

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
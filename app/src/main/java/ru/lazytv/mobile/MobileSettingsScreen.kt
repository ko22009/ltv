package ru.lazytv.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.leanback.widget.GuidedAction
import ru.lazytv.SettingsScreen
import ru.lazytv.backButton
import ru.lazytv.calmBackground
import ru.lazytv.showChoice
import ru.lazytv.uiScale

/**
 * A phone version of one settings section. The rows, their values and what they do come from [SettingsScreen]
 * (so the TV and the phone can never differ in what a setting means); only the drawing is touch-friendly:
 * switches, rows that open a choice list or an input box, and plain buttons.
 */
class MobileSettingsScreen : SettingsScreen() {
    private val scale get() = requireContext().uiScale()
    private fun px(value: Int) = (value * scale).toInt()
    private lateinit var list: LinearLayout

    private fun label(value: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(requireContext()).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = calmBackground()
            setPadding(px(16), px(16), px(16), px(8))
        }
        val back = backButton(ctx, scale) { requireActivity().finish() }
        val header = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(px(48), px(48)).apply { rightMargin = px(14) })
            addView(label(arguments?.getString("section").orEmpty().ifEmpty { "Настройки" }, 22f, Color.WHITE).apply {
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
        }
        root.addView(header)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(12), 0, px(16)) }
        root.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
        // the Leanback views are still created (the fragment touches them while resuming) but stay hidden under the phone list
        val hidden = super.onCreateView(inflater, container, savedInstanceState)
        hidden?.visibility = View.GONE
        return FrameLayout(ctx).apply {
            if (hidden != null) addView(hidden, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        }
    }

    // the stylists of the TV layout have no views here: nothing to focus, nothing to restore
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {}

    private fun row(title: CharSequence, description: CharSequence?, onClick: (() -> Unit)?, trailing: View? = null): View {
        val ctx = requireContext()
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f, Color.WHITE, bold = true))
            if (!description.isNullOrEmpty()) addView(label(description, 13f, 0xFF94A3B8.toInt()).apply { setPadding(0, px(3), 0, 0) })
        }
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1C2029.toInt()); cornerRadius = px(8).toFloat() }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (trailing != null) addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(12) })
            if (onClick != null) {
                isClickable = true
                foreground = android.util.TypedValue().let { tv ->
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    ctx.getDrawable(tv.resourceId)
                }
                setOnClickListener { onClick() }
            }
        }
    }

    /** Draws the rows again from the actions: after any change the texts of the rows are read anew. */
    private fun render() {
        list.removeAllViews()
        val ctx = requireContext()
        for (action in actions) {
            val view: View = when {
                !action.isFocusable -> row(action.title, action.description, null)
                action.checkSetId == GuidedAction.CHECKBOX_CHECK_SET_ID -> {
                    val toggle = Switch(ctx).apply {
                        isChecked = action.isChecked
                        if (Build.VERSION.SDK_INT >= 23) {
                            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                            trackTintList = android.content.res.ColorStateList(states, intArrayOf(0xFF4338CA.toInt(), 0xFF475569.toInt()))
                            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFCBD5E1.toInt()))
                        }
                    }
                    row(action.title, action.description, {
                        action.isChecked = !action.isChecked
                        toggle.isChecked = action.isChecked
                        onGuidedActionClicked(action)
                    }, toggle).also { toggle.isClickable = false }
                }
                !action.subActions.isNullOrEmpty() -> row(action.title, action.description, {
                    val subs = action.subActions
                    showChoice(requireActivity(), action.title.toString(), subs.map { it.title.toString() }, subs.indexOfFirst { it.isChecked }) { index ->
                        onSubGuidedActionClicked(subs[index])
                        render()
                    }
                })
                action.isDescriptionEditable -> row(action.title, action.description, { askText(action) })
                else -> row(action.title, action.description, { onGuidedActionClicked(action); render() })
            }
            list.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
        }
    }

    /** A value typed by hand (an address, a key): the box starts with the stored text. */
    private fun askText(action: GuidedAction) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(action.editDescription ?: "")
            setSingleLine()
            setSelection(text.length)
            setTextColor(Color.WHITE)
        }
        val box = FrameLayout(ctx).apply {
            setPadding(px(20), px(8), px(20), 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(action.title)
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val value = input.text.toString().trim()
                action.description = value
                action.editDescription = value
                onGuidedActionEditedAndProceed(action)
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}

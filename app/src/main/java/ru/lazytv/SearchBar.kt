package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/** Flat or round background that turns bright green while the view has focus. */
fun focusShape(scale: Float, normal: Int, radius: Float = 3f, circle: Boolean = false) = StateListDrawable().apply {
    fun shape(color: Int) = GradientDrawable().apply {
        setColor(color)
        if (circle) this.shape = GradientDrawable.OVAL else cornerRadius = radius * scale
    }
    addState(intArrayOf(android.R.attr.state_focused), shape(0xFF2F7D57.toInt()))
    addState(intArrayOf(), shape(normal))
}

/**
 * The input row of the search screens: the round microphone button and the text field. The activity forwards
 * [handleResult] from its onActivityResult; a phrase typed (search key) or spoken goes to [onSubmit].
 */
class SearchBar(private val activity: Activity, private val scale: Float, private val onSubmit: (String) -> Unit) : LinearLayout(activity) {
    val mic = LinearLayout(activity)
    val input = EditText(activity)
    private val spinner = Spinner(activity, (24 * scale).toInt(), 0xFFDCE8E0.toInt()).apply {
        visibility = View.GONE
        isFocusable = false
        isClickable = false
    }

    /** Left from the microphone: lets a host screen with a Back button to the left of the bar take focus cleanly. */
    var onLeft: (() -> Boolean)? = null

    /** Down from the field: the page says where it goes (the browse screen does not find it itself). */
    var onDown: (() -> Boolean)? = null

    private fun px(value: Int) = (value * scale).toInt()

    init {
        gravity = Gravity.CENTER_VERTICAL
        mic.gravity = Gravity.CENTER
        mic.isFocusable = true
        mic.isClickable = true
        mic.background = focusShape(scale, 0xFF22382B.toInt(), circle = true)
        mic.addView(ImageView(activity).apply {
            setImageDrawable(PathDrawable(MenuIcons.MIC).apply { setColorFilter(0xFFDCE8E0.toInt(), PorterDuff.Mode.SRC_IN) })
        }, LayoutParams(px(30), px(30)))
        mic.setOnClickListener { listen() }
        addView(mic, LayoutParams(px(56), px(56)).apply { rightMargin = px(14) })

        input.setSingleLine()
        input.hint = "Поиск"
        input.setHintTextColor(0xFF8FA597.toInt())
        input.setTextColor(0xFFE6EDE8.toInt())
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * scale)
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setPadding(px(18), 0, px(18), 0)
        // the field lights up when the remote reaches it and the keyboard comes up at once: the field is active, as when it is clicked;
        // it also goes away when the remote leaves the field, so the phrases below are not covered by it
        input.background = focusShape(scale, 0xFF1F3228.toInt())
        input.setOnFocusChangeListener { view, hasFocus ->
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            if (hasFocus) view.post { imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
            else view.post { imm.hideSoftInputFromWindow(view.windowToken, 0) }
        }
        input.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) onSubmit(input.text.toString())
            true
        }
        // the remote goes between the microphone and the field directly: the search for the nearest view did not reach the field
        input.id = View.generateViewId()
        input.isFocusable = true
        input.nextFocusLeftId = mic.id.takeIf { it != View.NO_ID } ?: View.generateViewId().also { mic.id = it }
        mic.nextFocusRightId = input.id
        // the browse screen answers the search for the next view itself and passes over the ids above: the keys are taken here
        mic.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.invoke() ?: false
                KeyEvent.KEYCODE_DPAD_RIGHT -> input.requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN -> onDown?.invoke() ?: false
                else -> false
            }
        }
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (input.selectionStart <= 0) mic.requestFocus() else false
                KeyEvent.KEYCODE_DPAD_DOWN -> onDown?.invoke() ?: false
                else -> false
            }
        }
        val inputBox = FrameLayout(activity).apply {
            addView(input, FrameLayout.LayoutParams(-1, -1))
            addView(spinner, FrameLayout.LayoutParams(px(24), px(24), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = px(16)
            })
        }
        addView(inputBox, LayoutParams(0, px(56), 1f))
    }

    /** Shows or hides a small progress arc inside the right end of the input field while background sources are still reporting. */
    fun setLoading(loading: Boolean) {
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        input.setPadding(px(18), 0, px(if (loading) 50 else 18), 0)
    }

    fun setPhrase(phrase: String) {
        input.setText(phrase)
        input.setSelection(phrase.length)
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Произнесите запрос")
        runCatching { activity.startActivityForResult(intent, VOICE) }
            .onFailure { Toast.makeText(activity, "На устройстве нет распознавания речи", Toast.LENGTH_LONG).show() }
    }

    /** True when the result was this bar's voice answer. */
    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != VOICE) return false
        if (resultCode == Activity.RESULT_OK) {
            val phrase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return true
            setPhrase(phrase)
            onSubmit(phrase)
        }
        return true
    }

    private companion object {
        const val VOICE = 1
    }
}

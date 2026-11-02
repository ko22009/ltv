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
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
    val input = EditText(activity)

    private fun px(value: Int) = (value * scale).toInt()

    init {
        gravity = Gravity.CENTER_VERTICAL
        val mic = LinearLayout(activity)
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
        input.background = GradientDrawable().apply { setColor(0xFF1F3228.toInt()); cornerRadius = px(3).toFloat() }
        input.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) onSubmit(input.text.toString())
            true
        }
        addView(input, LayoutParams(0, px(56), 1f))
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

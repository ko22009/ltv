package ru.lazytv

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Flat or round background that turns teal while the view has focus. */
fun focusShape(scale: Float, normal: Int, radius: Float = 3f, circle: Boolean = false, focused: Int = FOCUS_FILL) = StateListDrawable().apply {
    fun shape(color: Int) = GradientDrawable().apply {
        setColor(color)
        if (circle) this.shape = GradientDrawable.OVAL else cornerRadius = radius * scale
    }
    addState(intArrayOf(android.R.attr.state_focused), shape(focused))
    addState(intArrayOf(), shape(normal))
}

/**
 * The input row of the search screens: the round microphone button and the text field. The activity forwards
 * [handleResult] from its onActivityResult; a phrase typed (search key) or spoken goes to [onSubmit].
 */
class SearchBar(private val activity: Activity, private val scale: Float, private val onSubmit: (String) -> Unit) : LinearLayout(activity) {
    val mic = LinearLayout(activity)
    val input = EditText(activity)
    private val micIcon = ImageView(activity)
    private val spinner = Spinner(activity, (24 * scale).toInt()).apply {
        visibility = View.GONE
        isFocusable = false
        isClickable = false
    }

    private var recognizer: SpeechRecognizer? = null
    /** True while the recognizer listens: partial phrases fill the field, and the page must not search by them. */
    var listening = false
        private set
    private var previousQuery: String = ""
    private var pulseAnimator: ObjectAnimator? = null

    /** Left from the microphone: lets a host screen with a Back button to the left of the bar take focus cleanly. */
    var onLeft: (() -> Boolean)? = null

    /** Down from the field: the page says where it goes (the browse screen does not find it itself). */
    var onDown: (() -> Boolean)? = null

    private fun px(value: Int) = (value * scale).toInt()

    init {
        clipChildren = false
        clipToPadding = false
        gravity = Gravity.CENTER_VERTICAL
        mic.clipChildren = false
        mic.clipToPadding = false
        mic.gravity = Gravity.CENTER
        mic.isFocusable = true
        mic.isClickable = true
        mic.background = focusShape(scale, 0xFF1C2029.toInt(), circle = true)
        micIcon.setImageDrawable(PathDrawable(MenuIcons.MIC).apply { colorFilter = PorterDuffColorFilter(0xFFCBD5E1.toInt(), PorterDuff.Mode.SRC_IN) })
        mic.addView(micIcon, LayoutParams(px(30), px(30)))
        mic.setOnClickListener {
            if (listening) cancelListening() else listen()
        }
        mic.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && listening) cancelListening()
        }
        addView(mic, LayoutParams(px(56), px(56)).apply { rightMargin = px(14) })

        input.setSingleLine()
        input.hint = "Поиск"
        // on the teal focused plate the grey hint is hard to read: it turns almost white there
        input.setHintTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(0xCCFFFFFF.toInt(), 0xFF64748B.toInt())
        ))
        input.setTextColor(0xFFF1F5F9.toInt())
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * scale)
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setPadding(px(18), 0, px(18), 0)
        // the field lights up when the remote reaches it and the keyboard comes up at once: the field is active, as when it is clicked;
        // it also goes away when the remote leaves the field, so the phrases below are not covered by it
        input.background = focusShape(scale, PLATE_FILL, radius = TITLE_CARD_RADIUS)
        input.setOnFocusChangeListener { view, hasFocus ->
            if (listening) cancelListening()
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
                KeyEvent.KEYCODE_BACK -> {
                    if (listening) {
                        cancelListening()
                        true
                    } else false
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (listening) cancelListening()
                    onLeft?.invoke() ?: false
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (listening) cancelListening()
                    input.requestFocus()
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (listening) cancelListening()
                    onDown?.invoke() ?: false
                }
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

    fun listen() {
        if (listening) {
            cancelListening()
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            fallbackVoiceIntent()
            return
        }
        startSpeechRecognizer()
    }

    private fun fallbackVoiceIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Произнесите запрос")
        runCatching { activity.startActivityForResult(intent, VOICE) }
            .onFailure { Toast.makeText(activity, "На устройстве нет распознавания речи", Toast.LENGTH_LONG).show() }
    }

    private fun startSpeechRecognizer() {
        cancelListening()
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(activity)
        } catch (_: Throwable) {
            null
        }
        if (sr == null) {
            fallbackVoiceIntent()
            return
        }
        recognizer = sr
        listening = true
        previousQuery = input.text.toString()
        input.setText("")
        input.hint = "Слушаю..."
        updateMicVisuals(isListening = true)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                input.hint = "Обработка..."
            }
            override fun onError(error: Int) {
                val hadText = input.text.isNotEmpty()
                stopListeningState(cancel = false)
                if (!hadText && previousQuery.isNotEmpty()) {
                    setPhrase(previousQuery)
                }
                val message = when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания речи"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не разобрана, попробуйте ещё раз"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, попробуйте позже"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
                    else -> "Не удалось распознать речь"
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                stopListeningState(cancel = false)
                if (!phrase.isNullOrEmpty()) {
                    setPhrase(phrase)
                    onSubmit(phrase)
                } else if (previousQuery.isNotEmpty()) {
                    setPhrase(previousQuery)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val phrase = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (!phrase.isNullOrEmpty()) {
                    input.setText(phrase)
                    input.setSelection(phrase.length)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            sr.startListening(intent)
        } catch (_: Throwable) {
            stopListeningState(cancel = true)
            if (previousQuery.isNotEmpty()) setPhrase(previousQuery)
        }
    }

    private fun stopListeningState(cancel: Boolean = false) {
        listening = false
        updateMicVisuals(isListening = false)
        input.hint = "Поиск"
        val sr = recognizer
        recognizer = null
        runCatching {
            if (cancel) sr?.cancel()
            sr?.destroy()
        }
    }

    fun cancelListening() {
        if (!listening) return
        val hadText = input.text.isNotEmpty()
        stopListeningState(cancel = true)
        if (!hadText && previousQuery.isNotEmpty()) {
            setPhrase(previousQuery)
        }
    }

    private fun updateMicVisuals(isListening: Boolean) {
        if (isListening) {
            mic.background = focusShape(scale, 0xFFC62828.toInt(), circle = true, focused = 0xFFE53935.toInt())
            micIcon.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            pulseAnimator?.cancel()
            pulseAnimator = ObjectAnimator.ofFloat(micIcon, "scaleX", 1f, 1.22f).apply {
                duration = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { micIcon.scaleY = micIcon.scaleX }
                start()
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            micIcon.scaleX = 1f
            micIcon.scaleY = 1f
            mic.scaleX = 1f
            mic.scaleY = 1f
            mic.background = focusShape(scale, 0xFF1C2029.toInt(), circle = true)
            micIcon.colorFilter = PorterDuffColorFilter(0xFFCBD5E1.toInt(), PorterDuff.Mode.SRC_IN)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelListening()
    }

    fun handlePermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSION_AUDIO) return false
        val index = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val granted = index >= 0 && grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            listen()
        } else {
            // without the permission the system dialog of the recognizer still works
            fallbackVoiceIntent()
        }
        return true
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
        const val PERMISSION_AUDIO = 2
    }
}

package com.aliahad.wovoice.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aliahad.wovoice.R
import com.aliahad.wovoice.core.EditorPolicy
import com.aliahad.wovoice.core.KeyboardState
import com.aliahad.wovoice.core.ShiftState
import com.aliahad.wovoice.ui.dp
import com.aliahad.wovoice.ui.rounded
import com.aliahad.wovoice.ui.setButtonAccessibilityRole
import com.aliahad.wovoice.ui.styleText
import com.aliahad.wovoice.ui.systemAnimationsEnabled
import com.aliahad.wovoice.voice.WavRecorder
import java.util.Locale

@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class WoVoiceKeyboardView(context: Context) : SwipeFrameLayout(context) {
    interface Listener {
        fun onStartVoice()
        fun onFinishVoice()
        fun onCancelVoice()
        fun onSelectManual(manual: Boolean)
        fun onCommitText(text: String)
        fun onEmojiUsed(emoji: String)
        fun onDelete()
        fun onEditorAction()
        fun onOpenSettings()
        fun onSwitchToNextIme()
    }

    lateinit var listener: Listener
    private val header: LinearLayout
    private val modeSwitcher: LinearLayout
    private val modeIcon: ModeIconView
    private val languageLabel: TextView
    private val voiceBody: FrameLayout
    private val manualBody: LinearLayout
    private val status: TextView
    private val microphone: ImageView
    private val waveform: WaveformView
    private val processing: ProcessingPillView
    private val cancel: TextView
    private val sideDelete: TextView
    private val atKey: TextView
    private val voiceGlobeKey: TextView
    private val returnKey: TextView
    private val shiftState = ShiftState()
    private val letterKeyViews = mutableListOf<Pair<TextView, Char>>()
    private var shiftKeyView: TextView? = null
    private var state: KeyboardState = KeyboardState.VoiceIdle
    private var voiceEnabled = true
    private var symbolPage = 0
    private var emojiPanel: EmojiPanelView? = null
    private var emojiRecents: List<String> = emptyList()
    private var actionLabel = "↵"
    private var modeSlideDirection = 1
    private var initialRender = true
    private var hapticsEnabled = true
    private var animationsEnabled = true
    private var waveformEnabled = true
    private var bottomInsetPx = 0
    private var recordingStartedAtMs = 0L
    private val motionInterpolator = DecelerateInterpolator()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val recordingClock = object : Runnable {
        override fun run() {
            if (state !is KeyboardState.Recording) return
            status.text = recordingStatusText()
            clockHandler.postDelayed(this, 250L)
        }
    }

    init {
        setBackgroundColor(BACKGROUND)
        clipChildren = false
        clipToPadding = false
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(4), context.dp(14), context.dp(2))
        }
        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(54)))
        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = "Open LiVoice settings"
            setButtonAccessibilityRole()
        }
        brand.addView(VoiceMarkView(context), LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
        brand.addView(TextView(context).apply {
            text = "LiVoice"
            styleText(19f)
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = context.dp(8)
        })
        installTapFeedback(brand, listenerAction = { listener.onOpenSettings() }, pressedScale = 0.98f)
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        modeSwitcher = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
            background = rounded(PILL_DARK, context.dp(24).toFloat())
        }
        header.addView(modeSwitcher, LinearLayout.LayoutParams(context.dp(116), context.dp(48)))
        modeIcon = ModeIconView(context).apply {
            contentDescription = "Voice keyboard"
            setButtonAccessibilityRole()
        }
        modeSwitcher.addView(modeIcon, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        languageLabel = TextView(context).apply {
            text = "EN"
            styleText(16f, MUTED)
            gravity = Gravity.CENTER
            contentDescription = "English manual keyboard"
            setButtonAccessibilityRole()
        }
        modeSwitcher.addView(languageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        installTapFeedback(modeIcon, listenerAction = { toggleMode() })
        installTapFeedback(languageLabel, listenerAction = { toggleMode() })

        val content = FrameLayout(context)
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        voiceBody = FrameLayout(context)
        manualBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val bodyHeight = currentBodyHeightPx()
        content.addView(voiceBody, LayoutParams(LayoutParams.MATCH_PARENT, bodyHeight))
        content.addView(manualBody, LayoutParams(LayoutParams.MATCH_PARENT, bodyHeight))

        status = TextView(context).apply {
            text = "Tap to speak"
            styleText(16f, MUTED)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(context.dp(12), 0, context.dp(12), 0)
        }
        voiceBody.addView(status, frame(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = context.dp(8)
        })

        microphone = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic_keyboard)
            scaleType = ImageView.ScaleType.CENTER
            background = rounded(Color.rgb(246, 245, 244), context.dp(48).toFloat())
            contentDescription = "Start voice recording"
            setButtonAccessibilityRole()
        }
        installTapFeedback(microphone, listenerAction = { handleVoiceCenter() }, pressedScale = 0.96f)
        voiceBody.addView(microphone, frame(context.dp(180), context.dp(72), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = context.dp(56)
        })

        waveform = WaveformView(context).apply {
            visibility = View.GONE
            contentDescription = "Finish voice recording"
            setButtonAccessibilityRole()
        }
        installTapFeedback(waveform, listenerAction = { handleVoiceCenter() }, pressedScale = 0.96f)
        voiceBody.addView(waveform, frame(context.dp(120), context.dp(120), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = context.dp(34)
        })

        processing = ProcessingPillView(context).apply {
            visibility = View.GONE
            contentDescription = "Processing speech"
        }
        voiceBody.addView(processing, frame(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = context.dp(68)
        })

        cancel = circleKey("×", 28f).apply {
            visibility = View.GONE
            contentDescription = "Cancel recording"
            setButtonAccessibilityRole()
        }
        installTapFeedback(cancel, listenerAction = { listener.onCancelVoice() })
        addView(cancel, frame(context.dp(48), context.dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = context.dp(4)
            marginEnd = context.dp(14)
        })

        voiceGlobeKey = circleKey("🌐", 20f).apply {
            contentDescription = "Switch keyboard"
            setButtonAccessibilityRole()
        }
        installTapFeedback(voiceGlobeKey, listenerAction = { listener.onSwitchToNextIme() })
        voiceBody.addView(voiceGlobeKey, frame(context.dp(48), context.dp(48), Gravity.BOTTOM or Gravity.START).apply {
            marginStart = context.dp(16)
            bottomMargin = context.dp(14)
        })

        sideDelete = circleKey("⌫", 21f).apply {
            contentDescription = "Delete"
            setButtonAccessibilityRole()
        }
        installDeleteTouch(sideDelete)
        voiceBody.addView(sideDelete, frame(context.dp(50), context.dp(50), Gravity.TOP or Gravity.END).apply {
            marginEnd = context.dp(16)
            topMargin = context.dp(62)
        })

        atKey = circleKey("@", 22f).apply {
            contentDescription = "At sign"
            setButtonAccessibilityRole()
        }
        installTapFeedback(atKey, listenerAction = { listener.onCommitText("@") })
        voiceBody.addView(atKey, frame(context.dp(50), context.dp(50), Gravity.TOP or Gravity.END).apply {
            marginEnd = context.dp(16)
            topMargin = context.dp(122)
        })

        returnKey = TextView(context).apply {
            text = actionLabel
            styleText(18f)
            gravity = Gravity.CENTER
            background = rounded(KEY_DARK, context.dp(32).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            contentDescription = "Editor action"
            setButtonAccessibilityRole()
        }
        installTapFeedback(returnKey, listenerAction = { listener.onEditorAction() })
        voiceBody.addView(returnKey, frame(context.dp(168), context.dp(54), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = context.dp(12)
        })

        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            if (bottomInsetPx != bars.bottom || view.paddingLeft != bars.left || view.paddingRight != bars.right) {
                bottomInsetPx = bars.bottom
                view.setPadding(bars.left, 0, bars.right, bars.bottom)
            }
            insets
        }

        applyOrientationMetrics()
        onHorizontalSwipe = { direction -> toggleMode(direction) }
        rebuildManualKeyboard(animate = false)
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationMetrics()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun currentBodyHeightPx(): Int = context.dp(if (isLandscape()) BODY_HEIGHT_LANDSCAPE_DP else BODY_HEIGHT_PORTRAIT_DP)

    private fun applyOrientationMetrics() {
        val landscape = isLandscape()
        val bodyHeight = currentBodyHeightPx()
        header.layoutParams.height = context.dp(if (landscape) 48 else 54)
        voiceBody.layoutParams.height = bodyHeight
        manualBody.layoutParams.height = bodyHeight

        (microphone.layoutParams as FrameLayout.LayoutParams).apply {
            width = context.dp(if (landscape) 156 else 180)
            height = context.dp(if (landscape) 60 else 72)
            topMargin = context.dp(if (landscape) 42 else 56)
        }
        (waveform.layoutParams as FrameLayout.LayoutParams).apply {
            val size = context.dp(if (landscape) 92 else 120)
            width = size
            height = size
            topMargin = context.dp(if (landscape) 22 else 34)
        }
        (processing.layoutParams as FrameLayout.LayoutParams).topMargin = context.dp(if (landscape) 50 else 68)
        (sideDelete.layoutParams as FrameLayout.LayoutParams).topMargin = context.dp(if (landscape) 40 else 62)
        (atKey.layoutParams as FrameLayout.LayoutParams).topMargin = context.dp(if (landscape) 96 else 122)
        (returnKey.layoutParams as FrameLayout.LayoutParams).apply {
            width = context.dp(if (landscape) 148 else 168)
            height = context.dp(if (landscape) 48 else 54)
            bottomMargin = context.dp(if (landscape) 8 else 12)
        }
        requestLayout()
    }

    private var contextLabel: String? = null
    fun setContextLabel(value: String?) { contextLabel = value }

    fun setState(value: KeyboardState) {
        if (state == value && !initialRender) return
        val enteringRecording = state !is KeyboardState.Recording && value is KeyboardState.Recording
        state = value
        if (enteringRecording) {
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            clockHandler.removeCallbacks(recordingClock)
            clockHandler.postDelayed(recordingClock, 250L)
        } else if (value !is KeyboardState.Recording) {
            clockHandler.removeCallbacks(recordingClock)
        }
        render()
    }

    fun setPreferences(haptics: Boolean, animations: Boolean, waveform: Boolean) {
        hapticsEnabled = haptics
        animationsEnabled = animations
        waveformEnabled = waveform
        this.waveform.setAnimationsEnabled(animations)
        this.processing.setAnimationsEnabled(animations)
        emojiPanel?.setPreferences(haptics, animations)
    }

    fun setEmojiRecents(values: List<String>) {
        emojiRecents = values
        emojiPanel?.setRecents(values)
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        if (!enabled && state !is KeyboardState.ManualKeyboard) state = KeyboardState.ManualKeyboard
        modeSwitcher.alpha = if (enabled) 1f else 0.45f
        render()
    }

    fun updateEditor(editorInfo: EditorInfo?, sentenceStart: Boolean) {
        actionLabel = EditorPolicy.actionLabel(editorInfo)
        returnKey.text = actionLabel
        returnKey.contentDescription = actionLabel
        shiftState.resetForField(sentenceStart)
        val nextSymbolPage = if ((editorInfo?.inputType ?: 0) and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_NUMBER) 1 else 0
        if (symbolPage == nextSymbolPage && symbolPage == 0 && letterKeyViews.isNotEmpty()) {
            updateShiftVisualsInPlace()
            updateManualActionKeyLabel()
        } else {
            symbolPage = nextSymbolPage
            rebuildManualKeyboard(animate = false)
        }
    }

    fun updateLevel(rms: Float) {
        if (state is KeyboardState.Recording && waveformEnabled) waveform.setLevel(rms)
    }

    private fun motionAllowed(): Boolean = animationsEnabled && context.systemAnimationsEnabled()

    private fun recordingStatusText(): String {
        val elapsedSec = ((SystemClock.elapsedRealtime() - recordingStartedAtMs).coerceIn(0L, WavRecorder.MAX_DURATION_MS)) / 1000L
        val timer = String.format(Locale.ROOT, "%d:%02d / 1:00", elapsedSec / 60L, elapsedSec % 60L)
        return contextLabel?.let { "$it · $timer" } ?: "$timer · Tap to finish"
    }

    private fun toggleMode(direction: Int = 0) {
        if (state is KeyboardState.Recording || state is KeyboardState.Processing) return
        modeSlideDirection = when {
            direction != 0 -> direction
            state is KeyboardState.ManualKeyboard -> -1
            else -> 1
        }
        if (state is KeyboardState.ManualKeyboard) {
            if (voiceEnabled) listener.onSelectManual(false)
        } else {
            listener.onSelectManual(true)
        }
    }

    private fun handleVoiceCenter() {
        when (state) {
            KeyboardState.Recording -> listener.onFinishVoice()
            KeyboardState.Processing -> Unit
            KeyboardState.ManualKeyboard -> Unit
            else -> if (voiceEnabled) listener.onStartVoice()
        }
    }

    private fun render() {
        val manual = state is KeyboardState.ManualKeyboard
        val animate = isLaidOut && !initialRender && motionAllowed()
        showMode(manual, animate)
        // The left segment always represents voice; EN represents the manual English layout.
        // Selection styling, rather than a changing glyph, makes the switcher's meaning stable.
        modeIcon.setIcon(ModeIconView.Icon.VOICE)
        modeIcon.background = rounded(
            if (!manual) SEGMENT_ACTIVE else Color.TRANSPARENT,
            context.dp(22).toFloat(),
        )
        languageLabel.background = rounded(
            if (manual) SEGMENT_ACTIVE else Color.TRANSPARENT,
            context.dp(22).toFloat(),
        )
        languageLabel.setTextColor(if (manual) Color.WHITE else MUTED)
        if (manual) {
            waveform.setActive(false)
            processing.setActive(false)
            setElementVisible(modeSwitcher, true, animate)
            setElementVisible(cancel, false, animate)
            initialRender = false
            return
        }

        val recording = state is KeyboardState.Recording
        val thinking = state is KeyboardState.Processing
        val error = state as? KeyboardState.Error
        val statusText = when {
            recording -> recordingStatusText()
            thinking -> null
            error != null -> error.message
            else -> "Tap to speak"
        }
        updateStatus(statusText, recording, error != null, animate)
        microphone.contentDescription = if (recording) "Finish voice recording" else "Start voice recording"
        waveform.setActive(recording)
        processing.setActive(thinking)
        setElementVisible(microphone, !recording && !thinking, animate)
        setElementVisible(waveform, recording, animate)
        setElementVisible(processing, thinking, animate)
        setElementVisible(modeSwitcher, !recording && !thinking, animate)
        setElementVisible(cancel, recording || thinking, animate)
        setElementVisible(voiceGlobeKey, !recording && !thinking, animate)
        setElementVisible(sideDelete, !recording && !thinking, animate)
        setElementVisible(atKey, !recording && !thinking, animate)
        setElementVisible(returnKey, !recording && !thinking, animate)
        initialRender = false
    }

    private fun showMode(manual: Boolean, animate: Boolean) {
        val incoming = if (manual) manualBody else voiceBody
        val outgoing = if (manual) voiceBody else manualBody
        if (!animate || incoming.visibility == View.VISIBLE) {
            incoming.animate().cancel()
            outgoing.animate().cancel()
            incoming.visibility = View.VISIBLE
            incoming.alpha = 1f
            incoming.translationX = 0f
            outgoing.visibility = View.GONE
            outgoing.alpha = 1f
            outgoing.translationX = 0f
            return
        }

        val distance = context.dp(22).toFloat()
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationX = -modeSlideDirection * distance
        incoming.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(MODE_TRANSITION_MS)
            .setInterpolator(motionInterpolator)
            .start()
        outgoing.animate()
            .alpha(0f)
            .translationX(modeSlideDirection * distance)
            .setDuration(MODE_TRANSITION_MS - 25L)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.alpha = 1f
                outgoing.translationX = 0f
            }
            .start()
    }

    private fun updateStatus(text: String?, recording: Boolean, isError: Boolean, animate: Boolean) {
        status.animate().cancel()
        if (text == null) {
            setElementVisible(status, false, animate)
            return
        }
        status.text = text
        status.textSize = if (isError) 14f else 16f
        status.setTextColor(if (isError) ERROR else MUTED)
        status.visibility = View.VISIBLE
        val targetY = if (recording) context.dp(if (isLandscape()) 122 else 164).toFloat() else 0f
        if (animate) {
            status.alpha = 0f
            status.translationY = targetY + if (recording) context.dp(5) else -context.dp(4)
            status.animate()
                .alpha(1f)
                .translationY(targetY)
                .setDuration(STATUS_TRANSITION_MS)
                .setInterpolator(motionInterpolator)
                .start()
        } else {
            status.alpha = 1f
            status.translationY = targetY
        }
    }

    private fun setElementVisible(view: View, visible: Boolean, animate: Boolean) {
        view.animate().cancel()
        if (!animate) {
            view.visibility = if (visible) View.VISIBLE else View.GONE
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        if (visible) {
            if (view.visibility == View.VISIBLE && view.alpha == 1f) return
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ELEMENT_IN_MS)
                .setInterpolator(motionInterpolator)
                .start()
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(ELEMENT_OUT_MS)
                .setInterpolator(motionInterpolator)
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
                .start()
        }
    }

    private fun rebuildManualKeyboard(animate: Boolean = manualBody.isShown && motionAllowed(), direction: Int = 0) {
        manualBody.removeAllViews()
        letterKeyViews.clear()
        shiftKeyView = null
        if (symbolPage == SYMBOL_PAGE_EMOJI) {
            emojiPanel = ensureEmojiPanel()
            manualBody.addView(
                emojiPanel,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f),
            )
            val row = buildKeySpecRow(
                listOf(
                    KeySpec("ABC", LETTERS, 1.35f),
                    KeySpec("🌐", NEXT_IME, 1.0f),
                    KeySpec("English", " ", 2.3f),
                    KeySpec("⌫", DELETE, 1.1f),
                    KeySpec(actionLabel, ENTER, 1.45f),
                ),
            )
            manualBody.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val rows: List<List<KeySpec>> = when (symbolPage) {
                1 -> symbolRowsOne()
                2 -> symbolRowsTwo()
                else -> letterRows()
            }
            rows.forEach { specs -> manualBody.addView(buildKeySpecRow(specs), rowParams()) }
        }
        if (animate && motionAllowed()) {
            manualBody.animate().cancel()
            manualBody.alpha = 0.72f
            manualBody.translationX = direction * context.dp(14).toFloat()
            manualBody.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(MANUAL_REBUILD_MS)
                .setInterpolator(motionInterpolator)
                .start()
        }
    }

    private fun updateShiftVisualsInPlace() {
        letterKeyViews.forEach { (keyView, baseLetter) ->
            val label = (if (shiftState.shifted) baseLetter.uppercaseChar() else baseLetter.lowercaseChar()).toString()
            keyView.text = label
            keyView.contentDescription = label
        }
        shiftKeyView?.let { shiftView ->
            shiftView.text = if (shiftState.capsLocked) "⇪" else "⇧"
            shiftView.background = rounded(
                if (shiftState.shifted) SHIFT_ACTIVE else KEY,
                context.dp(10).toFloat(),
            )
            shiftView.setTextColor(if (shiftState.shifted) Color.rgb(30, 30, 33) else Color.WHITE)
            shiftView.contentDescription = if (shiftState.capsLocked) "Caps lock on" else if (shiftState.shifted) "Shift on" else "Shift"
        }
    }

    private fun updateManualActionKeyLabel() {
        val enterView = manualBody.findViewWithTag<TextView>(ENTER) ?: return
        enterView.text = actionLabel
        enterView.contentDescription = actionLabel
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    private fun buildKeySpecRow(specs: List<KeySpec>): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(context.dp(3), context.dp(2), context.dp(3), context.dp(2))
        }
        specs.forEach { spec ->
            val key = TextView(context).apply {
                text = spec.label
                styleText(keyTextSize(spec))
                gravity = Gravity.CENTER
                background = rounded(keyColor(spec), context.dp(10).toFloat())
                when {
                    spec.action == ENTER -> {
                        tag = ENTER
                        setTextColor(Color.rgb(30, 30, 33))
                    }
                    spec.action == SHIFT && shiftState.shifted -> setTextColor(Color.rgb(30, 30, 33))
                    spec.action == " " -> setTextColor(MUTED)
                }
                setPadding(context.dp(2), 0, context.dp(2), 0)
                contentDescription = keyDescription(spec)
                setButtonAccessibilityRole()
            }
            if (spec.baseLetter != null) {
                letterKeyViews += key to spec.baseLetter
            } else if (spec.action == SHIFT) {
                shiftKeyView = key
            }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, spec.weight).apply {
                marginStart = context.dp(3)
                marginEnd = context.dp(3)
                topMargin = context.dp(2)
                bottomMargin = context.dp(2)
            }
            row.addView(key, params)
            if (spec.action == DELETE) {
                installDeleteTouch(key)
            } else {
                installKeyTouch(key) { performKey(spec) }
            }
        }
        return row
    }

    private fun performKey(spec: KeySpec) {
        when (spec.action) {
            SHIFT -> {
                shiftState.tap(SystemClock.uptimeMillis())
                updateShiftVisualsInPlace()
            }
            SYMBOLS -> {
                symbolPage = 1
                rebuildManualKeyboard(animate = motionAllowed(), direction = 1)
            }
            LETTERS -> {
                symbolPage = 0
                rebuildManualKeyboard(animate = motionAllowed(), direction = -1)
            }
            SYMBOL_PAGE -> {
                symbolPage = if (symbolPage == 1) 2 else 1
                rebuildManualKeyboard(animate = motionAllowed(), direction = if (symbolPage == 2) 1 else -1)
            }
            EMOJI -> {
                symbolPage = SYMBOL_PAGE_EMOJI
                rebuildManualKeyboard(animate = motionAllowed(), direction = 1)
            }
            NEXT_IME -> listener.onSwitchToNextIme()
            ENTER -> listener.onEditorAction()
            else -> {
                val baseLetter = spec.baseLetter
                if (baseLetter != null) {
                    val output = (if (shiftState.shifted) baseLetter.uppercaseChar() else baseLetter.lowercaseChar()).toString()
                    listener.onCommitText(output)
                    val wasShifted = shiftState.shifted
                    shiftState.consumeLetter()
                    if (wasShifted != shiftState.shifted) {
                        updateShiftVisualsInPlace()
                    }
                } else {
                    listener.onCommitText(spec.value)
                }
            }
        }
    }

    private fun letterRows(): List<List<KeySpec>> {
        fun letters(value: String) = value.map { char ->
            val output = if (shiftState.shifted) char.uppercaseChar() else char.lowercaseChar()
            KeySpec(output.toString(), output.toString(), baseLetter = char)
        }
        return listOf(
            letters("QWERTYUIOP"),
            letters("ASDFGHJKL"),
            listOf(KeySpec(if (shiftState.capsLocked) "⇪" else "⇧", SHIFT, 1.25f)) +
                letters("ZXCVBNM") +
                KeySpec("⌫", DELETE, 1.25f),
            listOf(
                KeySpec("123", SYMBOLS, 1.25f),
                KeySpec("🌐", NEXT_IME, 0.95f),
                KeySpec("🙂", EMOJI, 0.95f),
                KeySpec(",", ",", 0.8f),
                KeySpec("English", " ", 2.35f),
                KeySpec(".", ".", 0.8f),
                KeySpec(actionLabel, ENTER, 1.45f),
            ),
        )
    }

    private fun symbolRowsOne() = listOf(
        "1234567890".map { KeySpec(it.toString(), it.toString()) },
        listOf("@", "#", "\$", "%", "&", "-", "+", "(", ")").map { KeySpec(it, it) },
        listOf(KeySpec("#+=", SYMBOL_PAGE, 1.25f)) +
            listOf("*", "\"", "'", ",", ".", "!", "?").map { KeySpec(it, it) } +
            KeySpec("⌫", DELETE, 1.25f),
        listOf(
            KeySpec("ABC", LETTERS, 1.35f),
            KeySpec("🌐", NEXT_IME, 1.0f),
            KeySpec("🙂", EMOJI, 1.0f),
            KeySpec("English", " ", 2.4f),
            KeySpec(actionLabel, ENTER, 1.5f),
        ),
    )

    private fun symbolRowsTwo() = listOf(
        listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { KeySpec(it, it) },
        listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥").map { KeySpec(it, it) },
        listOf(KeySpec("123", SYMBOL_PAGE, 1.25f)) +
            listOf("•", "`", ":", ";", "©", "✓", "÷").map { KeySpec(it, it) } +
            KeySpec("⌫", DELETE, 1.25f),
        listOf(
            KeySpec("ABC", LETTERS, 1.35f),
            KeySpec("🌐", NEXT_IME, 1.0f),
            KeySpec("🙂", EMOJI, 1.0f),
            KeySpec("English", " ", 2.4f),
            KeySpec(actionLabel, ENTER, 1.5f),
        ),
    )

    private fun keyTextSize(spec: KeySpec): Float = when {
        spec.action == " " -> 13f
        spec.action == EMOJI || spec.action == NEXT_IME -> 18f
        spec.label.length >= 4 -> 14.5f
        spec.label.length == 3 -> 16.5f
        else -> 20f
    }

    private fun keyColor(spec: KeySpec): Int = when {
        spec.action == ENTER -> Color.rgb(242, 241, 240)
        spec.action == SHIFT && shiftState.shifted -> SHIFT_ACTIVE
        else -> KEY
    }

    private fun keyDescription(spec: KeySpec): String = when (spec.action) {
        SHIFT -> if (shiftState.capsLocked) "Caps lock on" else if (shiftState.shifted) "Shift on" else "Shift"
        DELETE -> "Delete"
        SYMBOLS -> "Numbers and symbols"
        LETTERS -> "Letters"
        SYMBOL_PAGE -> "More symbols"
        EMOJI -> "Emoji page"
        NEXT_IME -> "Switch keyboard"
        ENTER -> actionLabel
        " " -> "Space"
        "," -> "Comma"
        "." -> "Period"
        else -> spec.label
    }

    private fun ensureEmojiPanel(): EmojiPanelView {
        val existing = emojiPanel
        if (existing != null) {
            existing.setPreferences(hapticsEnabled, animationsEnabled)
            return existing
        }
        val panel = EmojiPanelView(context).apply {
            setPreferences(hapticsEnabled, animationsEnabled)
            setRecents(emojiRecents)
            // Index 0 resolves to Recents when history exists, otherwise Smileys.
            selectCategory(0)
            onEmojiSelected = { emoji -> handleEmojiSelected(emoji) }
        }
        emojiPanel = panel
        return panel
    }

    private fun handleEmojiSelected(emoji: String) {
        listener.onCommitText(emoji)
        emojiRecents = EmojiRecents.updated(emojiRecents, emoji)
        emojiPanel?.setRecents(emojiRecents)
        listener.onEmojiUsed(emoji)
    }

    private fun installKeyTouch(view: View, action: () -> Unit) {
        view.setOnClickListener { action() }
        view.setOnTouchListener { key, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animatePressed(key, true)
                    if (hapticsEnabled) key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    animatePressed(key, false)
                    if (event.x in 0f..key.width.toFloat() && event.y in 0f..key.height.toFloat()) key.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { animatePressed(key, false); true }
                else -> true
            }
        }
    }

    // Kept as fields so a teardown mid-press can cancel the auto-repeat; a
    // self-rescheduling runnable left running would keep deleting text in
    // whatever editor is active.
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var deleteRepeated = false
    private val deleteRepeater = object : Runnable {
        override fun run() {
            deleteRepeated = true
            listener.onDelete()
            deleteHandler.postDelayed(this, 55)
        }
    }

    private fun installDeleteTouch(view: TextView) {
        view.setOnClickListener { listener.onDelete() }
        view.setOnTouchListener { key, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteRepeated = false
                    animatePressed(key, true)
                    if (hapticsEnabled) key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    deleteHandler.postDelayed(deleteRepeater, 350)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    deleteHandler.removeCallbacks(deleteRepeater)
                    animatePressed(key, false)
                    if (!deleteRepeated && event.x in 0f..key.width.toFloat() && event.y in 0f..key.height.toFloat()) key.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { deleteHandler.removeCallbacks(deleteRepeater); animatePressed(key, false); true }
                else -> true
            }
        }
    }

    private fun installTapFeedback(
        view: View,
        listenerAction: () -> Unit,
        pressedScale: Float = 0.93f,
    ) {
        view.setOnClickListener { listenerAction() }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    if (motionAllowed()) {
                        target.animate()
                            .scaleX(pressedScale)
                            .scaleY(pressedScale)
                            .alpha(0.78f)
                            .setDuration(PRESS_IN_MS)
                            .start()
                    } else {
                        target.alpha = 0.78f
                    }
                    if (hapticsEnabled) target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    animatePressed(target, false)
                    if (event.x in 0f..target.width.toFloat() && event.y in 0f..target.height.toFloat()) target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    animatePressed(target, false)
                    true
                }
                else -> true
            }
        }
    }

    private fun animatePressed(view: View, pressed: Boolean) {
        view.animate().cancel()
        if (!motionAllowed()) {
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = if (pressed) 0.78f else 1f
            return
        }
        view.animate()
            .scaleX(if (pressed) 0.94f else 1f)
            .scaleY(if (pressed) 0.94f else 1f)
            .alpha(if (pressed) 0.72f else 1f)
            .setDuration(if (pressed) PRESS_IN_MS else PRESS_OUT_MS)
            .setInterpolator(motionInterpolator)
            .start()
    }

    private fun circleKey(label: String, textSize: Float) = TextView(context).apply {
        text = label
        styleText(textSize, MUTED)
        gravity = Gravity.CENTER
        background = rounded(KEY_DARK, context.dp(40).toFloat())
    }

    override fun onDetachedFromWindow() {
        deleteHandler.removeCallbacks(deleteRepeater)
        clockHandler.removeCallbacks(recordingClock)
        waveform.setActive(false)
        processing.setActive(false)
        super.onDetachedFromWindow()
    }

    private fun frame(width: Int, height: Int, gravity: Int) = FrameLayout.LayoutParams(width, height, gravity)
    private data class KeySpec(
        val label: String,
        val action: String,
        val weight: Float = 1f,
        val baseLetter: Char? = null,
    ) {
        val value: String get() = action
    }

    private companion object {
        val BACKGROUND = Color.rgb(48, 48, 50)
        val KEY = Color.rgb(82, 82, 85)
        val KEY_DARK = Color.rgb(74, 74, 77)
        val PILL_DARK = Color.rgb(35, 35, 38)
        val SEGMENT_ACTIVE = Color.rgb(78, 78, 82)
        val SHIFT_ACTIVE = Color.rgb(224, 223, 224)
        val MUTED = Color.rgb(199, 198, 202)
        val ERROR = Color.rgb(255, 150, 150)
        const val BODY_HEIGHT_PORTRAIT_DP = 256
        const val BODY_HEIGHT_LANDSCAPE_DP = 192
        const val PRESS_IN_MS = 45L
        const val PRESS_OUT_MS = 90L
        const val ELEMENT_IN_MS = 175L
        const val ELEMENT_OUT_MS = 105L
        const val STATUS_TRANSITION_MS = 180L
        const val MODE_TRANSITION_MS = 185L
        const val MANUAL_REBUILD_MS = 115L
        const val SHIFT = "__shift"
        const val DELETE = "__delete"
        const val SYMBOLS = "__symbols"
        const val LETTERS = "__letters"
        const val SYMBOL_PAGE = "__symbol_page"
        const val EMOJI = "__emoji"
        const val NEXT_IME = "__next_ime"
        const val ENTER = "__enter"
        const val SYMBOL_PAGE_EMOJI = 3
    }
}

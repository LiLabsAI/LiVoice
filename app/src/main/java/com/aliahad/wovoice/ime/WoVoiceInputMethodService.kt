package com.aliahad.wovoice.ime

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.aliahad.wovoice.BuildConfig
import com.aliahad.wovoice.account.AccountResult
import com.aliahad.wovoice.account.SessionManager
import com.aliahad.wovoice.bubble.BubbleRetryWindow
import com.aliahad.wovoice.context.ContextCapture
import com.aliahad.wovoice.context.DictationContext
import com.aliahad.wovoice.core.CorrectionLearning
import com.aliahad.wovoice.core.DeletePolicy
import com.aliahad.wovoice.core.EditorPolicy
import com.aliahad.wovoice.core.EditorSessionGuard
import com.aliahad.wovoice.core.KeyboardState
import com.aliahad.wovoice.core.TextCommitPolicy
import com.aliahad.wovoice.data.AndroidGraph
import com.aliahad.wovoice.network.TranscriptionClient
import com.aliahad.wovoice.settings.SecretStore
import com.aliahad.wovoice.settings.SettingsStore
import com.aliahad.wovoice.settings.SetupActivity
import com.aliahad.wovoice.settings.androidDeviceName
import com.aliahad.wovoice.sync.SyncResult
import com.aliahad.wovoice.voice.VoiceCaptureService
import com.aliahad.wovoice.voice.WavRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class WoVoiceInputMethodService : InputMethodService(), WoVoiceKeyboardView.Listener,
    VoiceCaptureService.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backgroundSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = EditorSessionGuard()
    private val client = TranscriptionClient()
    private lateinit var settings: SettingsStore
    private lateinit var account: SessionManager
    private val repository by lazy { AndroidGraph.repository(this) }
    private var keyboard: WoVoiceKeyboardView? = null
    private var state: KeyboardState = KeyboardState.VoiceIdle
    private var currentSession = 0L
    private var captureSession = 0L
    private var sentenceStartForCapture = true
    private var contextCapture: ContextCapture? = null
    private var captureOwner: String? = null
    private var networkJob: Job? = null
    private var correctionJob: Job? = null
    private var retryExpiryJob: Job? = null
    private var activeFile: File? = null
    private var pendingRetry: PendingRetry? = null
    private var captureBinder: VoiceCaptureService.LocalBinder? = null
    private var pendingCapture = false
    private var bound = false
    private var pendingCorrection: PendingCorrection? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            captureBinder = service as? VoiceCaptureService.LocalBinder
            if (pendingCapture) {
                pendingCapture = false
                beginBoundCapture()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureBinder = null
            bound = false
            if (state is KeyboardState.Recording) showError("The recorder disconnected. Tap to try again.")
        }

        override fun onBindingDied(name: ComponentName?) {
            captureBinder = null
            bound = false
            if (state is KeyboardState.Recording) showError("The recorder disconnected. Tap to try again.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        account = SessionManager.get(settings, SecretStore(this), { settings.workerUrl }, ::androidDeviceName)
        ensureCaptureServiceBound()
    }

    private fun ensureCaptureServiceBound() {
        if (bound && captureBinder != null) return
        if (bound) {
            runCatching { unbindService(serviceConnection) }
            bound = false
        }
        bound = runCatching {
            bindService(
                Intent(this, VoiceCaptureService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
    }

    override fun onCreateInputView(): View {
        return WoVoiceKeyboardView(this).also {
            it.listener = this
            keyboard = it
            it.setPreferences(settings.hapticsEnabled, settings.animationsEnabled, settings.waveformEnabled)
            it.setEmojiRecents(settings.recentEmojis)
            configureForEditor(currentInputEditorInfo)
            it.setState(state)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cancelActiveWork()
        currentSession = sessions.begin()
        clearPendingCorrection()
        state = if (EditorPolicy.isSensitive(attribute?.inputType ?: 0)) {
            KeyboardState.ManualKeyboard
        } else {
            KeyboardState.VoiceIdle
        }
        configureForEditor(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!sessions.isActive(currentSession)) currentSession = sessions.begin()
        configureForEditor(info)
        keyboard?.setPreferences(settings.hapticsEnabled, settings.animationsEnabled, settings.waveformEnabled)
        keyboard?.setState(state)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clearPendingCorrection()
        cancelActiveWork()
        discardTransientState()
        sessions.invalidate()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        clearPendingCorrection()
        cancelActiveWork()
        discardTransientState()
        sessions.invalidate()
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        clearPendingCorrection()
        cancelActiveWork()
        discardTransientState()
        sessions.invalidate()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        clearPendingCorrection()
        cancelActiveWork()
        if (bound) runCatching { unbindService(serviceConnection) }
        bound = false
        scope.cancel()
        backgroundSyncScope.cancel()
        super.onDestroy()
    }

    override fun onStartVoice() {
        if (EditorPolicy.isSensitive(currentInputEditorInfo?.inputType ?: 0)) {
            state = KeyboardState.ManualKeyboard
            keyboard?.setVoiceEnabled(false)
            keyboard?.setState(state)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showError("Allow microphone access in LiVoice settings.")
            return
        }
        if (!account.signedIn) {
            showError("Sign in to LiVoice to use voice input.")
            return
        }
        if (!account.cloudServicesAllowed) {
            showError(
                account.accountStatus.publicMessage
                    ?: "This account cannot use voice input right now. Manual typing remains available.",
            )
            return
        }

        // If a transient transcription failure retained the recent audio file inside
        // the retry window, retry uploading it instead of forcing the user to re-speak.
        val retry = pendingRetry
        val now = SystemClock.elapsedRealtime()
        if (retry != null &&
            retry.session == currentSession &&
            retry.owner == settings.accountId &&
            retry.file.exists() &&
            retry.window.allowsRetry(now, true)
        ) {
            retryExpiryJob?.cancel()
            retryExpiryJob = null
            pendingRetry = retry.copy(window = retry.window.nextAttempt())
            captureSession = retry.session
            sentenceStartForCapture = retry.sentenceStart
            captureOwner = retry.owner
            activeFile = retry.file
            state = KeyboardState.Processing
            keyboard?.setState(state)
            transcribe(
                file = retry.file,
                session = retry.session,
                audioDurationMs = retry.durationMs,
                preCapturedContext = retry.capturedContext,
            )
            return
        }

        cancelActiveWork(resetState = false)
        ensureCaptureServiceBound()
        captureSession = currentSession
        sentenceStartForCapture = TextCommitPolicy.isSentenceStartAfter(textBeforeCursor())
        captureOwner = settings.accountId
        contextCapture = ContextCapture.start(scope, settings, currentInputEditorInfo, currentInputConnection)
        keyboard?.setContextLabel(contextCapture?.label(settings))
        state = KeyboardState.Recording
        keyboard?.setState(state)
        try {
            if (captureBinder == null) {
                pendingCapture = true
                if (!bound) showError("The microphone could not start. Open LiVoice settings and try again.")
            } else {
                beginBoundCapture()
            }
        } catch (_: Exception) {
            showError("The microphone could not start. Open LiVoice settings and try again.")
        }
    }

    override fun onFinishVoice() {
        if (state !is KeyboardState.Recording) return
        // The capture service may still be binding: with no binder nothing is
        // recording, so finishCapture() could never arrive and the keyboard
        // would sit in Processing forever. Return to idle instead — and make
        // sure the pending bind does not start a capture nobody will finish.
        val binder = captureBinder
        if (binder == null) {
            pendingCapture = false
            showError("Recording hadn't started yet — tap to try again.")
            return
        }
        state = KeyboardState.Processing
        keyboard?.setState(state)
        binder.finishCapture()
    }

    override fun onCancelVoice() {
        cancelActiveWork()
        state = KeyboardState.VoiceIdle
        keyboard?.setState(state)
    }

    override fun onSelectManual(manual: Boolean) {
        cancelActiveWork()
        state = if (manual) KeyboardState.ManualKeyboard else KeyboardState.VoiceIdle
        keyboard?.setState(state)
    }

    override fun onCommitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onEmojiUsed(emoji: String) {
        settings.recentEmojis = EmojiRecents.updated(settings.recentEmojis, emoji)
    }

    override fun onDelete() {
        val connection = currentInputConnection ?: return
        if (!connection.getSelectedText(0).isNullOrEmpty()) {
            connection.commitText("", 1)
            return
        }
        val before = connection.getTextBeforeCursor(DELETE_LOOKBACK_CHARS, 0)
        if (!before.isNullOrEmpty()) {
            val units = DeletePolicy.utf16UnitsForLastGrapheme(before)
            if (units > 0) {
                connection.deleteSurroundingText(units, 0)
                return
            }
        }
        connection.deleteSurroundingTextInCodePoints(1, 0)
    }

    override fun onEditorAction() {
        val connection = currentInputConnection ?: return
        val action = EditorPolicy.effectiveAction(currentInputEditorInfo)
        if (action in setOf(
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_DONE,
            )
        ) {
            connection.performEditorAction(action)
        } else {
            connection.commitText("\n", 1)
        }
    }

    override fun onOpenSettings() {
        runCatching {
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onSwitchToNextIme() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { switchToNextInputMethod(false) }.getOrDefault(false)
        } else {
            false
        }
        if (!switched) {
            runCatching { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        }
    }

    override fun onRecordingStarted() {
        if (!isCaptureActive()) captureBinder?.cancelCapture()
    }

    override fun onLevel(rms: Float) {
        if (isCaptureActive()) keyboard?.updateLevel(rms)
    }

    override fun onRecordingComplete(result: WavRecorder.RecordingResult) {
        if (BuildConfig.DEBUG) {
            Log.i(
                CAPTURE_LOG_TAG,
                "durationMs=${result.durationMs} activeMs=${result.activeSpeechMs} " +
                    "averageRms=${result.averageRms} peakRms=${result.peakRms} " +
                    "accepted=${result.containsSpeech}",
            )
        }
        if (!isCaptureActive() && state !is KeyboardState.Processing) {
            result.file.delete()
            return
        }
        if (!sessions.isActive(captureSession) || captureSession != currentSession) {
            result.file.delete()
            return
        }
        if (!result.containsSpeech) {
            result.file.delete()
            showError("No clear speech. Tap to try again.")
            return
        }
        state = KeyboardState.Processing
        keyboard?.setState(state)
        activeFile = result.file
        transcribe(result.file, captureSession, result.durationMs)
    }

    override fun onRecordingError(message: String) {
        if (sessions.isActive(captureSession)) showError(message)
    }

    private fun beginBoundCapture() {
        if (state !is KeyboardState.Recording || !sessions.isActive(captureSession)) return
        val started = captureBinder?.startCapture(this) == true
        if (!started) {
            showError("The microphone could not start. Open LiVoice settings and try again.")
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, VoiceCaptureService::class.java)
                    .putExtra(VoiceCaptureService.EXTRA_FOREGROUND_REQUEST, true),
            )
        }
    }

    private fun transcribe(
        file: File,
        session: Long,
        audioDurationMs: Long,
        preCapturedContext: DictationContext? = null,
    ) {
        val workerUrl = settings.workerUrl
        val locale = settings.locale
        val polish = settings.polish
        networkJob?.cancel()
        val capture = contextCapture
        val owner = captureOwner
        networkJob = scope.launch {
            var resolvedContext = preCapturedContext
            try {
                if (resolvedContext == null) {
                    resolvedContext = capture?.snapshot(settings)
                }
                if (!sessions.isActive(session) || session != currentSession) return@launch
                if (owner != settings.accountId) { showError("Account changed. Speak again."); return@launch }
                val result = withContext(Dispatchers.IO) {
                    val glossary = repository.bestGlossary()
                    val auth = account.validAccessToken()
                    if (auth is AccountResult.Error) {
                        TranscriptionClient.Result.Error(auth.message, auth.retryable, auth.code, auth.status)
                    } else {
                        val token = (auth as AccountResult.Success).value
                        if (owner != settings.accountId) return@withContext TranscriptionClient.Result.Error("Account changed. Speak again.", false)
                        val first = client.transcribe(
                            baseUrl = workerUrl,
                            token = token,
                            audioFile = file,
                            sentenceStart = sentenceStartForCapture,
                            glossary = glossary,
                            context = resolvedContext?.restricted(settings.contextLevel),
                            locale = locale,
                            polish = polish,
                        )
                        if (first is TranscriptionClient.Result.Error &&
                            (first.code == "TOKEN_EXPIRED" || first.code == "AUTH_REQUIRED")
                        ) {
                            when (val refreshed = account.refreshAfterRejected(token)) {
                                is AccountResult.Success -> if (owner != settings.accountId) TranscriptionClient.Result.Error("Account changed. Speak again.", false) else client.transcribe(
                                    baseUrl = workerUrl,
                                    token = refreshed.value,
                                    audioFile = file,
                                    sentenceStart = sentenceStartForCapture,
                                    glossary = glossary,
                                    context = resolvedContext?.restricted(settings.contextLevel),
                                    locale = locale,
                                    polish = polish,
                                )
                                is AccountResult.Error -> TranscriptionClient.Result.Error(
                                    refreshed.message,
                                    refreshed.retryable,
                                    refreshed.code,
                                    refreshed.status,
                                )
                            }
                        } else first
                    }
                }
                if (!sessions.isActive(session) || session != currentSession) return@launch
                if (owner != settings.accountId) { showError("Account changed. Speak again."); return@launch }
                if (!isInputViewShown) {
                    // The keyboard can reappear within the same input session (e.g. the
                    // notification shade). Never leave it rendering a stale Processing state.
                    discardTransientState()
                    return@launch
                }
                when (result) {
                    is TranscriptionClient.Result.Success -> {
                        clearPendingRetry()
                        // Insert immediately — the Worker already verified account standing
                        // and billed quota, so an extra /v1/me RTT must never discard speech.
                        commitFinalText(result, audioDurationMs)
                    }
                    is TranscriptionClient.Result.Error -> {
                        if (result.code == "ACCOUNT_SUSPENDED" || result.code == "ACCOUNT_BANNED") {
                            account.markRestricted(result.code, result.message)
                            clearPendingRetry()
                        } else if (offerRetry(file, audioDurationMs, session, owner, resolvedContext, result)) {
                            return@launch
                        } else {
                            clearPendingRetry()
                        }
                        showError(
                            account.accountStatus.publicMessage?.takeIf {
                                result.code == "ACCOUNT_SUSPENDED" || result.code == "ACCOUNT_BANNED"
                            } ?: result.message,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                capture?.cancel()
                if (contextCapture === capture) contextCapture = null
                val retained = pendingRetry?.file == file && sessions.isActive(session) && session == currentSession
                if (!retained) {
                    file.delete()
                    if (activeFile == file) activeFile = null
                }
            }
        }
    }

    private fun offerRetry(
        file: File,
        durationMs: Long,
        session: Long,
        owner: String?,
        capturedContext: DictationContext?,
        error: TranscriptionClient.Result.Error,
    ): Boolean {
        if (!error.retryable || error.code == "EMPTY_RESULT" || error.code == "CANCELLED") return false
        val now = SystemClock.elapsedRealtime()
        val window = pendingRetry?.takeIf { it.file == file }?.window ?: BubbleRetryWindow.afterFirstFailure(now)
        if (!window.allowsRetry(now, true) || !file.exists()) return false
        pendingRetry = PendingRetry(
            file = file,
            durationMs = durationMs,
            session = session,
            sentenceStart = sentenceStartForCapture,
            owner = owner,
            capturedContext = capturedContext,
            window = window,
        )
        retryExpiryJob?.cancel()
        retryExpiryJob = scope.launch {
            delay((window.expiresAtMs - now).coerceAtLeast(1L))
            if (pendingRetry?.file == file) {
                clearPendingRetry()
                if (state is KeyboardState.Error) {
                    showError("Recording expired. Tap mic to speak again.")
                }
            }
        }
        showError("Connection failed. Tap mic to retry.")
        return true
    }

    private fun clearPendingRetry() {
        retryExpiryJob?.cancel()
        retryExpiryJob = null
        val stale = pendingRetry
        pendingRetry = null
        stale?.file?.delete()
        if (activeFile == stale?.file) activeFile = null
    }

    private fun commitFinalText(result: TranscriptionClient.Result.Success, audioDurationMs: Long) {
        val connection = currentInputConnection
        if (connection == null) {
            discardTransientState()
            return
        }
        val text = TextCommitPolicy.withLocalSpacing(previousCharacter(), result.text)
        val committed = text.isNotEmpty() && connection.commitText(text, 1)
        if (committed) {
            val analyticsSyncId = UUID.randomUUID().toString()
            backgroundSyncScope.launch {
                repository.recordSuccessfulDictation(
                    result = result,
                    committedText = result.text,
                    audioDurationMs = audioDurationMs,
                    keepHistory = settings.historyEnabled,
                    analyticsSyncId = analyticsSyncId,
                )
                account.loadProfile()
                if (account.cloudServicesAllowed) {
                    val synced = AndroidGraph.sync(this@WoVoiceInputMethodService).syncNow()
                    if (synced is SyncResult.Success) {
                        settings.syncWarning = synced.warning
                    }
                }
            }
            if (settings.learningSuggestionsEnabled && EditorPolicy.allowsLearning(currentInputEditorInfo)) {
                pendingCorrection = PendingCorrection(
                    session = currentSession,
                    generatedText = result.text,
                    createdAtUptimeMs = SystemClock.uptimeMillis(),
                    analyticsSyncId = analyticsSyncId,
                )
            }
        }
        state = KeyboardState.VoiceIdle
        keyboard?.setState(state)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val pending = pendingCorrection ?: return
        if (
            !settings.learningSuggestionsEnabled ||
            !EditorPolicy.allowsLearning(currentInputEditorInfo) ||
            pending.session != currentSession ||
            !sessions.isActive(pending.session) ||
            SystemClock.uptimeMillis() - pending.createdAtUptimeMs > CORRECTION_WINDOW_MS ||
            newSelStart != newSelEnd
        ) {
            clearPendingCorrection()
            return
        }
        // Debounce correction inspection so deleting a single character doesn't
        // learn half-deleted typos before the user finishes retyping the word.
        correctionJob?.cancel()
        val tailChar = currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
        val wordBoundaryCompleted = tailChar != null && tailChar in WORD_BOUNDARY_CHARS && newSelStart > oldSelStart
        correctionJob = scope.launch {
            if (!wordBoundaryCompleted) delay(CORRECTION_DEBOUNCE_MS)
            evaluatePendingCorrection(pending)
        }
    }

    private fun evaluatePendingCorrection(pending: PendingCorrection) {
        if (pendingCorrection !== pending) return
        if (
            !settings.learningSuggestionsEnabled ||
            !EditorPolicy.allowsLearning(currentInputEditorInfo) ||
            pending.session != currentSession ||
            !sessions.isActive(pending.session) ||
            SystemClock.uptimeMillis() - pending.createdAtUptimeMs > CORRECTION_WINDOW_MS
        ) {
            clearPendingCorrection()
            return
        }
        val tailLength = (pending.generatedText.length + 64).coerceAtMost(MAX_CORRECTION_WINDOW_CHARS)
        val tail = currentInputConnection?.getTextBeforeCursor(tailLength, 0)?.toString() ?: return
        val suggestion = CorrectionLearning.suggestion(pending.generatedText, tail) ?: return
        clearPendingCorrection()
        backgroundSyncScope.launch {
            if (repository.addSuggestion(suggestion)) repository.noteCorrection(pending.analyticsSyncId)
        }
    }

    private fun clearPendingCorrection() {
        correctionJob?.cancel()
        correctionJob = null
        pendingCorrection = null
    }

    private fun configureForEditor(info: EditorInfo?) {
        val sensitive = EditorPolicy.isSensitive(info?.inputType ?: 0)
        keyboard?.setVoiceEnabled(!sensitive)
        if (sensitive) state = KeyboardState.ManualKeyboard
        keyboard?.updateEditor(info, TextCommitPolicy.isSentenceStartAfter(textBeforeCursor()))
        keyboard?.setState(state)
    }

    private fun previousCharacter(): Char? = currentInputConnection
        ?.getTextBeforeCursor(1, 0)
        ?.lastOrNull()

    private fun textBeforeCursor(): CharSequence? = currentInputConnection
        ?.getTextBeforeCursor(SENTENCE_LOOKBACK_CHARS, 0)

    private fun isCaptureActive(): Boolean =
        sessions.isActive(captureSession) && captureSession == currentSession &&
            (state is KeyboardState.Recording || state is KeyboardState.Processing)

    private fun showError(message: String) {
        state = KeyboardState.Error(truncateAtWord(message, MAX_ERROR_CHARS))
        keyboard?.setState(state)
    }

    /**
     * Clears Processing/Error states that no worker will ever resolve — left
     * behind when the keyboard hides mid-transcription or a capture never
     * started — so a re-shown keyboard doesn't render a stale pill forever.
     */
    private fun discardTransientState() {
        if (state is KeyboardState.Processing || state is KeyboardState.Error) {
            state = KeyboardState.VoiceIdle
            keyboard?.setState(state)
        }
    }

    private fun cancelActiveWork(resetState: Boolean = false) {
        pendingCapture = false
        captureBinder?.cancelCapture()
        networkJob?.cancel()
        networkJob = null
        contextCapture?.cancel()
        contextCapture = null
        client.cancel()
        clearPendingRetry()
        activeFile?.delete()
        activeFile = null
        if (resetState) {
            state = KeyboardState.VoiceIdle
            keyboard?.setState(state)
        }
    }

    private fun truncateAtWord(message: String, maxChars: Int): String {
        val clean = message.trim()
        if (clean.length <= maxChars) return clean
        val boundary = clean.lastIndexOf(' ', maxChars - 1)
        val cut = if (boundary >= maxChars / 2) clean.substring(0, boundary) else clean.substring(0, maxChars - 1)
        return "${cut.trimEnd('.', ',', ';', ':')}…"
    }

    private companion object {
        const val CAPTURE_LOG_TAG = "WoVoiceCapture"
        const val CORRECTION_WINDOW_MS = 30_000L
        const val CORRECTION_DEBOUNCE_MS = 1_200L
        const val MAX_CORRECTION_WINDOW_CHARS = 256
        const val SENTENCE_LOOKBACK_CHARS = 16
        const val DELETE_LOOKBACK_CHARS = 32
        const val MAX_ERROR_CHARS = 120
        val WORD_BOUNDARY_CHARS = setOf(' ', '.', ',', '!', '?', ';', ':', '\n')
    }

    private data class PendingCorrection(
        val session: Long,
        val generatedText: String,
        val createdAtUptimeMs: Long,
        val analyticsSyncId: String,
    )

    private data class PendingRetry(
        val file: File,
        val durationMs: Long,
        val session: Long,
        val sentenceStart: Boolean,
        val owner: String?,
        val capturedContext: DictationContext?,
        val window: BubbleRetryWindow,
    )
}

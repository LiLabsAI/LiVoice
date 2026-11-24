package com.aliahad.wovoice.bubble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import com.aliahad.wovoice.context.ContextCapture
import com.aliahad.wovoice.context.ContextLevel
import com.aliahad.wovoice.context.ContextPolicy
import com.aliahad.wovoice.context.DictationContext
import com.aliahad.wovoice.core.EditorPolicy
import com.aliahad.wovoice.core.TextCommitPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class BubbleCommitResult { ACCEPTED, REJECTED, UNCONFIRMED, UNSUPPORTED }

/** Android 13's Accessibility input connection edits a selection without replacing its document. */
@RequiresApi(33)
internal class BubbleInputMethod(
    private val service: AccessibilityService,
    private val changed: () -> Unit,
) : InputMethod(service) {
    private var generation = 0L
    private var selectionEpoch = 0L
    private var settledSelection: Pair<Int, Int>? = null

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        generation++
        selectionEpoch = 0L
        settledSelection = if (attribute.initialSelStart >= 0 && attribute.initialSelEnd >= 0) {
            attribute.initialSelStart to attribute.initialSelEnd
        } else {
            null
        }
        changed()
    }

    override fun onFinishInput() {
        generation++
        selectionEpoch = 0L
        settledSelection = null
        changed()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        if (newSelStart >= 0 && newSelEnd >= 0) {
            val next = newSelStart to newSelEnd
            val previous = settledSelection
            if (previous == null) {
                settledSelection = next
            } else if (previous != next) {
                settledSelection = next
                selectionEpoch++
            }
        }
        changed()
    }

    fun activePackageName(): String? {
        val info = currentInputEditorInfo ?: return null
        return if (
            BubbleNodePolicy.supportsInputMethod(
                inputType = info.inputType,
                packageName = info.packageName,
                ownPackageName = service.packageName,
                inputStarted = currentInputStarted,
                hasConnection = currentInputConnection != null,
            )
        ) {
            info.packageName
        } else {
            null
        }
    }

    fun capture(node: AccessibilityNodeInfo?): Target? {
        val info = currentInputEditorInfo ?: return null
        if (!BubbleNodePolicy.supportsInputMethod(
                inputType = info.inputType,
                packageName = info.packageName,
                ownPackageName = service.packageName,
                inputStarted = currentInputStarted,
                hasConnection = currentInputConnection != null,
            )
        ) {
            return null
        }
        if (node != null && (
                node.packageName?.toString() != info.packageName ||
                    node.isPassword ||
                    EditorPolicy.isSensitive(node.inputType)
                )
        ) {
            return null
        }
        return currentInputConnection?.let {
            Target(
                connection = it,
                session = generation,
                ownerPackage = info.packageName,
                inputType = info.inputType,
                hintText = info.hintText?.toString().orEmpty(),
                imeOptions = info.imeOptions,
                capturedSelectionEpoch = selectionEpoch,
            )
        }
    }

    inner class Target(
        private val connection: AccessibilityInputConnection,
        private val session: Long,
        val ownerPackage: String,
        private val inputType: Int,
        private val hintText: String,
        private val imeOptions: Int,
        private val capturedSelectionEpoch: Long,
    ) {
        private var baselineJob: Deferred<BubbleNodePolicy.SurroundingSnapshot?>? = null
        var baseline: BubbleNodePolicy.SurroundingSnapshot? = null
            private set

        val allowsContext: Boolean
            get() = isCurrent() && imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0

        fun isCurrent(): Boolean = generation == session &&
            currentInputStarted &&
            currentInputEditorInfo?.packageName == ownerPackage

        fun hasSelectionMoved(): Boolean = selectionEpoch != capturedSelectionEpoch

        fun captureBaselineAsync(scope: CoroutineScope) {
            if (baselineJob != null || baseline != null) return
            baselineJob = scope.async(Dispatchers.IO) { readSnapshot() }
        }

        suspend fun awaitBaseline(timeoutMs: Long = 150): BubbleNodePolicy.SurroundingSnapshot? {
            baseline?.let { return it }
            val job = baselineJob ?: return null
            val loaded = withTimeoutOrNull(timeoutMs) {
                try {
                    job.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    null
                }
            }
            if (loaded != null) baseline = loaded
            return baseline
        }

        fun cancelBaseline() {
            baselineJob?.cancel()
            baselineJob = null
        }

        fun baselineContext(node: AccessibilityNodeInfo?, level: ContextLevel, style: String): DictationContext? {
            if (!allowsContext || level == ContextLevel.OFF) return null
            if (node != null && Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) return null
            // If no node could be retrieved on API 34+, avoid sending nearby text across an unknown boundary.
            if (node == null && Build.VERSION.SDK_INT >= 34) return null
            val field = ContextCapture.field(inputType, imeOptions)
            val snap = baseline
            val before = if (level == ContextLevel.NEARBY && field == "text" && snap != null) snap.beforeCursor(512) else ""
            val after = if (level == ContextLevel.NEARBY && field == "text" && snap != null) snap.afterCursor(128) else ""
            return ContextPolicy.detect(ownerPackage, hintText, field, level, style, before, after)
        }

        suspend fun insert(
            editor: FocusedEditor?,
            transcript: String,
            nodeMaxLength: Int? = null,
            current: () -> AccessibilityNodeInfo?,
        ): BubbleCommitResult {
            if (!isCurrent()) return BubbleCommitResult.REJECTED
            if (editor != null && editor.packageName != ownerPackage) return BubbleCommitResult.REJECTED
            if (!nodeSafeForCommit(current(), editor)) return BubbleCommitResult.REJECTED

            // This IPC can wait on another application: keep it off the service's main thread.
            val surrounding = withContext(Dispatchers.IO) { readSnapshot() }
            if (!isCurrent() || !nodeSafeForCommit(current(), editor)) return BubbleCommitResult.REJECTED

            return if (editor != null) {
                insertWithEditor(editor, transcript, nodeMaxLength, surrounding, current)
            } else {
                insertWithConnectionOnly(transcript, nodeMaxLength, surrounding, current)
            }
        }

        private suspend fun insertWithEditor(
            editor: FocusedEditor,
            transcript: String,
            nodeMaxLength: Int?,
            surrounding: BubbleNodePolicy.SurroundingSnapshot?,
            current: () -> AccessibilityNodeInfo?,
        ): BubbleCommitResult {
            if (surrounding != null && !editor.matchesInputWindow(
                    surrounding.text,
                    surrounding.selectionStart,
                    surrounding.selectionEnd,
                    surrounding.offset,
                )
            ) {
                return BubbleCommitResult.REJECTED
            }
            val insertion = editor.prepare(transcript, current()) ?: return BubbleCommitResult.REJECTED
            val surroundingPlan = surrounding?.createInsertion(transcript, nodeMaxLength ?: -1)

            // The framework finishes the other IME's composing span in the same batch before committing.
            // This is a single selection edit; HTML, spans, links, and surrounding paragraphs stay owned by the editor.
            connection.commitText(editor.fragment(insertion), 1, null)

            // commitText has no success return. Confirm acceptance before counting History/analytics.
            // Never attempt a second transport after submission: an unacknowledged edit may still arrive.
            repeat(12) {
                delay(50)
                if (editor.matchesResult(current(), insertion)) return BubbleCommitResult.ACCEPTED
                if (surrounding != null && surroundingPlan != null) {
                    val after = withContext(Dispatchers.IO) { readSnapshot() }
                    if (after != null && surrounding.confirmsCommit(after, surroundingPlan)) {
                        return BubbleCommitResult.ACCEPTED
                    }
                }
            }
            return BubbleCommitResult.UNCONFIRMED
        }

        private suspend fun insertWithConnectionOnly(
            transcript: String,
            nodeMaxLength: Int?,
            surrounding: BubbleNodePolicy.SurroundingSnapshot?,
            current: () -> AccessibilityNodeInfo?,
        ): BubbleCommitResult {
            if (hasSelectionMoved()) return BubbleCommitResult.REJECTED
            val base = baseline
            if (base != null) {
                if (surrounding == null || !base.matchesWindow(surrounding)) {
                    return BubbleCommitResult.REJECTED
                }
                val maxLen = nodeMaxLength ?: current()?.maxTextLength?.takeIf { it >= 0 } ?: -1
                val plan = base.createInsertion(transcript, maxLen) ?: return BubbleCommitResult.REJECTED
                connection.commitText(plan.fragment, 1, null)
                repeat(12) {
                    delay(50)
                    val after = withContext(Dispatchers.IO) { readSnapshot() }
                    if (after != null && base.confirmsCommit(after, plan)) {
                        return BubbleCommitResult.ACCEPTED
                    }
                    val node = current()
                    if (node != null && node.refresh() &&
                        plan.insertion.matchesObservedText(node.text?.toString().orEmpty()) &&
                        node.textSelectionStart == plan.insertion.cursor &&
                        node.textSelectionEnd == plan.insertion.cursor
                    ) {
                        return BubbleCommitResult.ACCEPTED
                    }
                }
                return BubbleCommitResult.UNCONFIRMED
            }

            val fragment = TextCommitPolicy.withLocalSpacing(null, transcript.trim())
            if (fragment.isBlank()) return BubbleCommitResult.REJECTED
            connection.commitText(fragment, 1, null)
            return BubbleCommitResult.UNCONFIRMED
        }

        private fun nodeSafeForCommit(node: AccessibilityNodeInfo?, editor: FocusedEditor?): Boolean {
            if (node == null) return true
            if (node.packageName?.toString() != ownerPackage) return false
            if (node.isPassword || EditorPolicy.isSensitive(node.inputType)) return false
            if (editor != null && !editor.isSameNode(node)) return false
            return true
        }

        private fun readSnapshot(): BubbleNodePolicy.SurroundingSnapshot? {
            val raw = runCatching { connection.getSurroundingText(2_048, 2_048, 0) }.getOrNull() ?: return null
            return BubbleNodePolicy.SurroundingSnapshot(
                text = raw.text.toString(),
                selectionStart = raw.selectionStart,
                selectionEnd = raw.selectionEnd,
                offset = raw.offset,
            ).takeIf { it.isValid }
        }
    }
}

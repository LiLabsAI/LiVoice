package com.aliahad.wovoice.bubble

import android.text.InputType
import com.aliahad.wovoice.core.EditorPolicy
import com.aliahad.wovoice.core.TextCommitPolicy

/**
 * Pure policies for interpreting accessibility editor nodes, validating API 33+ Accessibility
 * InputMethod sessions, and verifying pre/post-commit SurroundingText snapshots.
 */
internal object BubbleNodePolicy {
    data class NodeTextState(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val hasValidSelection: Boolean,
    )

    data class SurroundingInsertion(
        val insertion: BubbleTextInsertion,
        val fragment: String,
        val beforeSlice: String,
        val afterSlice: String,
    )

    data class SurroundingSnapshot(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val offset: Int,
    ) {
        val isValid: Boolean
            get() = selectionStart in 0..text.length && selectionEnd in 0..text.length

        val from: Int
            get() = minOf(selectionStart, selectionEnd)

        val to: Int
            get() = maxOf(selectionStart, selectionEnd)

        val sentenceStart: Boolean
            get() = TextCommitPolicy.isSentenceStartAfter(beforeCursor(16))

        fun beforeCursor(maxChars: Int = 512): String =
            if (!isValid) "" else text.substring(0, from).takeLast(maxChars)

        fun afterCursor(maxChars: Int = 128): String =
            if (!isValid) "" else text.substring(to).take(maxChars)

        fun matchesWindow(other: SurroundingSnapshot): Boolean =
            isValid && other.isValid &&
                text == other.text &&
                selectionStart == other.selectionStart &&
                selectionEnd == other.selectionEnd &&
                offset == other.offset

        fun createInsertion(transcript: String, maximumLength: Int = -1): SurroundingInsertion? {
            if (!isValid) return null
            val insertion = BubbleTextInsertion.create(text, selectionStart, selectionEnd, transcript)
                ?: return null
            if (maximumLength >= 0) {
                val baseOffset = offset.coerceAtLeast(0)
                if (baseOffset + insertion.text.length > maximumLength) return null
            }
            return SurroundingInsertion(
                insertion = insertion,
                fragment = insertion.text.substring(from, insertion.cursor),
                beforeSlice = text.substring(0, from),
                afterSlice = text.substring(to),
            )
        }

        fun confirmsCommit(after: SurroundingSnapshot, plan: SurroundingInsertion): Boolean {
            if (!isValid || !after.isValid || after.selectionStart != after.selectionEnd) return false
            if (after.offset == offset &&
                plan.insertion.matchesObservedText(after.text) &&
                after.selectionStart == plan.insertion.cursor
            ) {
                return true
            }

            if (offset >= 0 && after.offset >= 0 &&
                after.offset + after.selectionStart != offset + from + plan.fragment.length
            ) {
                return false
            }

            val normFragment = normalizeSpaces(plan.fragment)
            val afterBefore = normalizeSpaces(after.text.substring(0, after.selectionStart))
            if (afterBefore.length < normFragment.length || !afterBefore.endsWith(normFragment)) {
                return false
            }

            val actualPrefix = afterBefore.dropLast(normFragment.length)
            val normExpectedPrefix = normalizeSpaces(plan.beforeSlice)
            val prefixOverlap = minOf(actualPrefix.length, normExpectedPrefix.length)
            if (actualPrefix.takeLast(prefixOverlap) != normExpectedPrefix.takeLast(prefixOverlap)) {
                return false
            }

            val actualSuffix = normalizeSpaces(after.text.substring(after.selectionStart))
            val normExpectedSuffix = normalizeSpaces(plan.afterSlice)
            val suffixOverlap = minOf(actualSuffix.length, normExpectedSuffix.length)
            if (actualSuffix.take(suffixOverlap) != normExpectedSuffix.take(suffixOverlap)) {
                return false
            }

            return true
        }

        private fun normalizeSpaces(value: String): String = value.replace('\u00a0', ' ')
    }

    fun resolveNodeTextState(
        rawText: String,
        showingHintText: Boolean,
        hintText: String?,
        selectionStart: Int,
        selectionEnd: Int,
    ): NodeTextState {
        // Custom EditTexts (including chat composers) can surface hint text in node.text without
        // flagging isShowingHintText; Android leaves selection bounds (-1, -1) when idle on a hint.
        val leakedHint = !showingHintText &&
            selectionStart == -1 &&
            selectionEnd == -1 &&
            !hintText.isNullOrEmpty() &&
            rawText == hintText
        val resolvedText = if (showingHintText || leakedHint) "" else rawText
        val normalizedSelection = if (resolvedText.isEmpty() && selectionStart == -1 && selectionEnd == -1) {
            0 to 0
        } else {
            selectionStart to selectionEnd
        }
        val valid = normalizedSelection.first in 0..resolvedText.length &&
            normalizedSelection.second in 0..resolvedText.length
        return NodeTextState(
            text = resolvedText,
            selectionStart = normalizedSelection.first,
            selectionEnd = normalizedSelection.second,
            hasValidSelection = valid,
        )
    }

    fun supportsInputMethod(
        inputType: Int,
        packageName: String?,
        ownPackageName: String,
        inputStarted: Boolean,
        hasConnection: Boolean,
    ): Boolean = inputStarted &&
        hasConnection &&
        !packageName.isNullOrBlank() &&
        packageName != ownPackageName &&
        inputType != InputType.TYPE_NULL &&
        !EditorPolicy.isSensitive(inputType)
}

package com.aliahad.wovoice.bubble

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleNodePolicyTest {
    @Test
    fun normalizesLeakedHintWithMissingSelectionToEmptyStart() {
        val state = BubbleNodePolicy.resolveNodeTextState(
            rawText = "Message",
            showingHintText = false,
            hintText = "Message",
            selectionStart = -1,
            selectionEnd = -1,
        )
        assertEquals("", state.text)
        assertEquals(0, state.selectionStart)
        assertEquals(0, state.selectionEnd)
        assertTrue(state.hasValidSelection)
    }

    @Test
    fun preservesGenuineUserTextThatMatchesHintWhenCursorIsPresent() {
        val state = BubbleNodePolicy.resolveNodeTextState(
            rawText = "Message",
            showingHintText = false,
            hintText = "Message",
            selectionStart = 7,
            selectionEnd = 7,
        )
        assertEquals("Message", state.text)
        assertEquals(7, state.selectionStart)
        assertEquals(7, state.selectionEnd)
        assertTrue(state.hasValidSelection)
    }

    @Test
    fun keepsNonEmptyNodeWithMissingSelectionInvalidForWholeTextReplacement() {
        val state = BubbleNodePolicy.resolveNodeTextState(
            rawText = "Draft message",
            showingHintText = false,
            hintText = "Message",
            selectionStart = -1,
            selectionEnd = -1,
        )
        assertEquals("Draft message", state.text)
        assertFalse(state.hasValidSelection)
    }

    @Test
    fun validatesInputMethodEditorSessionsAcrossChatAndSensitiveFields() {
        val chatInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertTrue(
            BubbleNodePolicy.supportsInputMethod(
                inputType = chatInputType,
                packageName = "com.whatsapp",
                ownPackageName = "com.aliahad.wovoice",
                inputStarted = true,
                hasConnection = true,
            ),
        )
        assertFalse(
            BubbleNodePolicy.supportsInputMethod(
                inputType = InputType.TYPE_NULL,
                packageName = "com.whatsapp",
                ownPackageName = "com.aliahad.wovoice",
                inputStarted = true,
                hasConnection = true,
            ),
        )
        assertFalse(
            BubbleNodePolicy.supportsInputMethod(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                packageName = "com.whatsapp",
                ownPackageName = "com.aliahad.wovoice",
                inputStarted = true,
                hasConnection = true,
            ),
        )
        assertFalse(
            BubbleNodePolicy.supportsInputMethod(
                inputType = chatInputType,
                packageName = "com.aliahad.wovoice",
                ownPackageName = "com.aliahad.wovoice",
                inputStarted = true,
                hasConnection = true,
            ),
        )
        assertFalse(
            BubbleNodePolicy.supportsInputMethod(
                inputType = chatInputType,
                packageName = "com.whatsapp",
                ownPackageName = "com.aliahad.wovoice",
                inputStarted = false,
                hasConnection = true,
            ),
        )
    }

    @Test
    fun surroundingSnapshotDetectsSentenceStateChangesAndConfirmsCommits() {
        val baseline = BubbleNodePolicy.SurroundingSnapshot(
            text = "Hey there",
            selectionStart = 3,
            selectionEnd = 3,
            offset = 0,
        )
        assertFalse(baseline.sentenceStart)

        val sameWindow = BubbleNodePolicy.SurroundingSnapshot("Hey there", 3, 3, 0)
        val editedWindow = BubbleNodePolicy.SurroundingSnapshot("Hey friend", 3, 3, 0)
        assertTrue(baseline.matchesWindow(sameWindow))
        assertFalse(baseline.matchesWindow(editedWindow))

        val plan = baseline.createInsertion("my friend")!!
        assertEquals(" my friend", plan.fragment)

        val committedExact = BubbleNodePolicy.SurroundingSnapshot("Hey my friend there", 13, 13, 0)
        assertTrue(baseline.confirmsCommit(committedExact, plan))

        val committedShifted = BubbleNodePolicy.SurroundingSnapshot("y my friend there", 11, 11, 2)
        assertTrue(baseline.confirmsCommit(committedShifted, plan))

        val committedWithNbsp = BubbleNodePolicy.SurroundingSnapshot("Hey\u00a0my friend there", 13, 13, 0)
        assertTrue(baseline.confirmsCommit(committedWithNbsp, plan))

        val tightSpacing = BubbleNodePolicy.SurroundingSnapshot("Heythere", 3, 3, 0)
        val tightPlan = tightSpacing.createInsertion("my friend")!!
        assertEquals(" my friend ", tightPlan.fragment)
        assertTrue(tightSpacing.confirmsCommit(BubbleNodePolicy.SurroundingSnapshot("Hey my friend there", 14, 14, 0), tightPlan))

        val mismatchedCommit = BubbleNodePolicy.SurroundingSnapshot("Hey something else", 18, 18, 0)
        assertFalse(baseline.confirmsCommit(mismatchedCommit, plan))
    }

    @Test
    fun surroundingSnapshotEnforcesMaxLengthBeforeCommit() {
        val baseline = BubbleNodePolicy.SurroundingSnapshot("Hello", 5, 5, 10)
        assertNull(baseline.createInsertion("world", maximumLength = 18))
        assertNotNull(baseline.createInsertion("world", maximumLength = 25))
    }
}

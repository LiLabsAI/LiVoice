package com.aliahad.wovoice.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPolicyTest {
    @Test
    fun protectsTextAndNumberPasswords() {
        assertTrue(EditorPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(EditorPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(EditorPolicy.isSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun leavesOrdinaryFieldsVoiceEnabled() {
        assertFalse(EditorPolicy.isSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(EditorPolicy.isSensitive(InputType.TYPE_CLASS_NUMBER))
    }

    @Test
    fun respectsImeFlagNoEnterActionInActionAndLabel() {
        assertEquals(EditorInfo.IME_ACTION_SEND, EditorPolicy.effectiveAction(EditorInfo.IME_ACTION_SEND))
        assertEquals("Send", EditorPolicy.actionLabel(EditorInfo.IME_ACTION_SEND))

        val multilineSend = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(EditorInfo.IME_ACTION_NONE, EditorPolicy.effectiveAction(multilineSend))
        assertEquals("↵", EditorPolicy.actionLabel(multilineSend))

        val multilineSearch = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(EditorInfo.IME_ACTION_NONE, EditorPolicy.effectiveAction(multilineSearch))
        assertEquals("↵", EditorPolicy.actionLabel(multilineSearch))
    }
}

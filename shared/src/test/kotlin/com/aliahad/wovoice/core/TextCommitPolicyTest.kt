package com.aliahad.wovoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCommitPolicyTest {
    @Test
    fun addsOneSpaceAfterAWord() {
        assertEquals(" hello", TextCommitPolicy.withLocalSpacing('x', "hello"))
    }

    @Test
    fun doesNotAddSpaceAfterWhitespaceOrBeforePunctuation() {
        assertEquals("hello", TextCommitPolicy.withLocalSpacing(' ', "hello"))
        assertEquals("?", TextCommitPolicy.withLocalSpacing('x', "?"))
        assertEquals("\nNext", TextCommitPolicy.withLocalSpacing('x', "\nNext"))
    }

    @Test
    fun detectsSentenceBoundaryFromAdjacentCharacterOnly() {
        assertTrue(TextCommitPolicy.isSentenceStart(null))
        assertTrue(TextCommitPolicy.isSentenceStart('.'))
        assertFalse(TextCommitPolicy.isSentenceStart(','))
    }

    @Test
    fun sentenceStartLooksPastTrailingSpaces() {
        assertTrue(TextCommitPolicy.isSentenceStartAfter(null))
        assertTrue(TextCommitPolicy.isSentenceStartAfter(""))
        assertTrue(TextCommitPolicy.isSentenceStartAfter("   "))
        assertTrue(TextCommitPolicy.isSentenceStartAfter("Hello. "))
        assertTrue(TextCommitPolicy.isSentenceStartAfter("Really?\u00A0\u00A0"))
        assertTrue(TextCommitPolicy.isSentenceStartAfter("First line\n"))
        assertFalse(TextCommitPolicy.isSentenceStartAfter("Hello "))
        assertFalse(TextCommitPolicy.isSentenceStartAfter("Hello, "))
    }

    @Test
    fun unicodeDeleteCountsSurrogatePair() {
        assertEquals(2, DeletePolicy.utf16UnitsForLastCodePoint("a😀"))
        assertEquals(1, DeletePolicy.utf16UnitsForLastCodePoint("é"))
        assertEquals(0, DeletePolicy.utf16UnitsForLastGrapheme(""))
    }

    @Test
    fun graphemeDeleteKeepsZwjFlagsVs16AndModifiersIntact() {
        val cyclist = "\uD83D\uDEB4\u200D\u2640\uFE0F" // 🚴‍♀️
        assertEquals(cyclist.length, DeletePolicy.utf16UnitsForLastGrapheme("ride $cyclist"))

        val rainbowFlag = "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08" // 🏳️‍🌈
        assertEquals(rainbowFlag.length, DeletePolicy.utf16UnitsForLastGrapheme("hi $rainbowFlag"))

        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66" // 👨‍👩‍👧‍👦
        assertEquals(family.length, DeletePolicy.utf16UnitsForLastGrapheme("x$family"))

        val indiaFlag = "\uD83C\uDDEE\uD83C\uDDF3" // 🇮🇳
        val usFlag = "\uD83C\uDDFA\uD83C\uDDF8" // 🇺🇸
        assertEquals(indiaFlag.length, DeletePolicy.utf16UnitsForLastGrapheme("go $indiaFlag"))
        // Adjacent flags delete only the last flag, leaving the previous flag intact.
        assertEquals(indiaFlag.length, DeletePolicy.utf16UnitsForLastGrapheme("$usFlag$indiaFlag"))

        val heartVs16 = "\u2764\uFE0F" // ❤️
        assertEquals(heartVs16.length, DeletePolicy.utf16UnitsForLastGrapheme("love $heartVs16"))

        val skinToneThumb = "\uD83D\uDC4D\uD83C\uDFFD" // 👍🏽
        assertEquals(skinToneThumb.length, DeletePolicy.utf16UnitsForLastGrapheme("ok $skinToneThumb"))

        val keycapOne = "1\uFE0F\u20E3" // 1️⃣
        assertEquals(keycapOne.length, DeletePolicy.utf16UnitsForLastGrapheme("step $keycapOne"))

        val decomposedAcute = "e\u0301"
        assertEquals(decomposedAcute.length, DeletePolicy.utf16UnitsForLastGrapheme("caf$decomposedAcute"))
    }
}

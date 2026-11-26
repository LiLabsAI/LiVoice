package com.aliahad.wovoice.core

import java.text.BreakIterator
import java.util.Locale

object TextCommitPolicy {
    private val noSpaceBefore = setOf('.', ',', '!', '?', ';', ':', '%', ')', ']', '}')
    private val opening = setOf('(', '[', '{', '/', '\n')

    fun withLocalSpacing(previousCharacter: Char?, transcript: String): String {
        val text = transcript.trimEnd()
        if (text.isEmpty() || previousCharacter == null) return text
        val first = text.first()
        if (previousCharacter.isWhitespace() || previousCharacter in opening || first in noSpaceBefore || first == '\n') {
            return text
        }
        return " $text"
    }

    fun isSentenceStart(previousCharacter: Char?): Boolean =
        previousCharacter == null || previousCharacter == '\n' || previousCharacter in setOf('.', '!', '?')

    /**
     * Sentence boundary judged from the text before the cursor, looking past spaces:
     * after "Hello. " the adjacent character is a space, which the single-character
     * check reads as mid-sentence, so dictation lost its capital and the keyboard its shift.
     */
    fun isSentenceStartAfter(textBeforeCursor: CharSequence?): Boolean =
        isSentenceStart(textBeforeCursor?.trimEnd { it == ' ' || it == '\t' || it == '\u00A0' }?.lastOrNull())
}

object DeletePolicy {
    private const val ZWJ = 0x200D
    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3

    /**
     * Returns the UTF-16 code-unit length of the last user-perceived character
     * (grapheme cluster) in [text], keeping ZWJ sequences (🚴‍♀️, 🏳️‍🌈),
     * regional-indicator flag pairs (🇮🇳, 🇺🇸), skin-tone modifiers (👍🏽),
     * variation selectors (❤️), keycaps (1️⃣), and surrogate pairs intact.
     */
    fun utf16UnitsForLastGrapheme(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val value = text.toString()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        val end = iterator.last()
        var start = iterator.previous()
        if (start == BreakIterator.DONE) return value.length

        // Supplement JDK 11 / older Android ICU BreakIterator so ZWJ emoji,
        // regional-indicator flags, variation selectors, keycaps, skin-tone
        // modifiers, and emoji tag sequences are never split mid-cluster.
        while (start > 0) {
            val currCp = Character.codePointAt(value, start)
            val prevCp = Character.codePointBefore(value, start)
            when {
                isEmojiJoinerTail(currCp) -> {
                    start = stepBack(iterator, value, start)
                }
                currCp == ZWJ -> {
                    start = stepBack(iterator, value, start)
                }
                prevCp == ZWJ -> {
                    val beforeZwj = start - Character.charCount(prevCp)
                    start = if (beforeZwj > 0) stepBack(iterator, value, beforeZwj) else 0
                }
                isRegionalIndicator(currCp) && isRegionalIndicator(prevCp) -> {
                    if (precedingRegionalIndicatorCount(value, start) % 2 == 1) {
                        start -= Character.charCount(prevCp)
                    } else {
                        break
                    }
                }
                else -> break
            }
        }
        return end - start
    }

    /** Backwards-compatible alias for [utf16UnitsForLastGrapheme]. */
    fun utf16UnitsForLastCodePoint(text: CharSequence): Int = utf16UnitsForLastGrapheme(text)

    private fun stepBack(iterator: BreakIterator, value: String, index: Int): Int {
        val prev = iterator.preceding(index)
        if (prev != BreakIterator.DONE && prev < index) return prev
        return (index - Character.charCount(Character.codePointBefore(value, index))).coerceAtLeast(0)
    }

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun isEmojiJoinerTail(cp: Int): Boolean {
        if (cp == VS15 || cp == VS16 || cp == COMBINING_ENCLOSING_KEYCAP) return true
        if (cp in 0x1F3FB..0x1F3FF) return true // Fitzpatrick skin-tone modifiers
        if (cp in 0xE0020..0xE007F) return true // Emoji subdivision flag tags
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun precedingRegionalIndicatorCount(value: String, index: Int): Int {
        var count = 0
        var pos = index
        while (pos > 0) {
            val cp = Character.codePointBefore(value, pos)
            if (!isRegionalIndicator(cp)) break
            count++
            pos -= Character.charCount(cp)
        }
        return count
    }
}

class ShiftState {
    var shifted: Boolean = true
        private set
    var capsLocked: Boolean = false
        private set
    private var lastTapAt = 0L

    fun tap(now: Long) {
        if (now - lastTapAt <= 350L && lastTapAt != 0L) {
            capsLocked = true
            shifted = true
        } else if (capsLocked) {
            capsLocked = false
            shifted = false
        } else {
            shifted = !shifted
        }
        lastTapAt = now
    }

    fun consumeLetter() {
        if (shifted && !capsLocked) shifted = false
    }

    fun resetForField(sentenceStart: Boolean) {
        capsLocked = false
        shifted = sentenceStart
        lastTapAt = 0L
    }
}

/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.ui.swing.render

/**
 * Shared presentation policy for native emoji dispatch and font preference.
 *
 * Classification does not resolve fonts or initialize a native rasterizer.
 * Text presentation (VS15) wins throughout a sequence. Joiners, VS16 and keycaps
 * request emoji presentation only when the sequence contains an emoji base.
 */
internal object TerminalEmojiPresentation {
    /** Whether a scalar uses the renderer's default emoji presentation policy. */
    fun usesEmojiPresentation(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x231A..0x231B ||
            codePoint in 0x23E9..0x23EC ||
            codePoint == 0x23F0 ||
            codePoint == 0x23F3 ||
            codePoint in 0x25FD..0x25FE ||
            codePoint in 0x2614..0x2615 ||
            codePoint in 0x2648..0x2653 ||
            codePoint == 0x267F ||
            codePoint == 0x2693 ||
            codePoint == 0x26A1 ||
            codePoint in 0x26AA..0x26AB ||
            codePoint in 0x26BD..0x26BE ||
            codePoint in 0x26C4..0x26C5 ||
            codePoint == 0x26CE ||
            codePoint == 0x26D4 ||
            codePoint == 0x26EA ||
            codePoint in 0x26F2..0x26F3 ||
            codePoint == 0x26F5 ||
            codePoint == 0x26FA ||
            codePoint == 0x26FD ||
            codePoint == 0x2705 ||
            codePoint in 0x270A..0x270B ||
            codePoint == 0x2728 ||
            codePoint == 0x274C ||
            codePoint == 0x274E ||
            codePoint in 0x2753..0x2755 ||
            codePoint == 0x2757 ||
            codePoint in 0x2795..0x2797 ||
            codePoint == 0x27B0 ||
            codePoint == 0x27BF ||
            codePoint in 0x2B1B..0x2B1C ||
            codePoint == 0x2B50 ||
            codePoint == 0x2B55

    /** Classifies the requested code point slice without copying its storage. */
    fun usesEmojiPresentation(
        codepoints: IntArray,
        offset: Int,
        length: Int,
    ): Boolean = classify(length, utf16 = false) { codepoints[offset + it] }

    /** Classifies UTF-16 text without materializing code points or substrings. */
    fun usesEmojiPresentation(text: String): Boolean = classify(text.length, utf16 = true) { text.codePointAt(it) }

    private inline fun classify(
        length: Int,
        utf16: Boolean,
        codePointAt: (Int) -> Int,
    ): Boolean {
        var defaultPresentation = false
        var explicitPresentation = false
        var emojiBase = false
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            when (codePoint) {
                VARIATION_SELECTOR_15 -> return false
                VARIATION_SELECTOR_16, ZERO_WIDTH_JOINER, COMBINING_ENCLOSING_KEYCAP -> explicitPresentation = true
                else -> {
                    defaultPresentation = defaultPresentation || usesEmojiPresentation(codePoint)
                    emojiBase = emojiBase || Character.isEmoji(codePoint)
                }
            }
            index += if (utf16) Character.charCount(codePoint) else 1
        }
        return defaultPresentation || explicitPresentation && emojiBase
    }

    private const val VARIATION_SELECTOR_15 = 0xFE0E
    private const val VARIATION_SELECTOR_16 = 0xFE0F
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
}

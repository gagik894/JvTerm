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

import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import java.awt.Font

internal fun terminalFontStyle(attr: Long): Int {
    var style = Font.PLAIN
    if (TerminalRenderAttrs.isBold(attr)) style = style or Font.BOLD
    if (TerminalRenderAttrs.isItalic(attr)) style = style or Font.ITALIC
    return style
}

internal fun hasDrawableText(flags: Int): Boolean =
    flags and TerminalRenderCellFlags.CODEPOINT != 0 ||
        flags and TerminalRenderCellFlags.CLUSTER != 0

/**
 * Suppresses glyphs and their decorations before shaping, native emoji, or foreground overrides.
 * Cell backgrounds and cursor geometry remain visible independently of text visibility.
 */
internal fun isTextHidden(
    attr: Long,
    textBlinkVisible: Boolean,
): Boolean = TerminalRenderAttrs.isInvisible(attr) || (!textBlinkVisible && TerminalRenderAttrs.isBlink(attr))

internal fun isFastAsciiCell(
    flags: Int,
    codeWord: Int,
): Boolean = flags == TerminalRenderCellFlags.CODEPOINT && codeWord in 0x20..0x7e

internal fun cellSpan(flags: Int): Int = if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1

internal fun visualCellRangeStart(
    flags: Int,
    column: Int,
): Int = if (flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) maxOf(0, column - 1) else column

internal fun visualCellRangeSpan(
    flags: Int,
    column: Int,
    columns: Int,
): Int {
    val start = visualCellRangeStart(flags, column)
    val span =
        when {
            flags and TerminalRenderCellFlags.WIDE_LEADING != 0 -> 2
            flags and TerminalRenderCellFlags.WIDE_TRAILING != 0 && column > 0 -> 2
            else -> 1
        }
    return minOf(span, columns - start)
}

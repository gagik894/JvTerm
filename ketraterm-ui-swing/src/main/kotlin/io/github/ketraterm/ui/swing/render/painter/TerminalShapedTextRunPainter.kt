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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.cache.FontCache
import io.github.ketraterm.ui.swing.render.cache.TerminalShapedGlyphVectorCache
import io.github.ketraterm.ui.swing.render.primitives.TerminalCellPrimitivePainter
import io.github.ketraterm.ui.swing.render.primitives.TerminalPlatformEmojiPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D
import java.awt.font.FontRenderContext

/**
 * Shapes compatible text with its neighboring context, then paints it at terminal-cell positions.
 *
 * Font, script and direction delimit shaping spans. Foreground, decorations and visibility only
 * delimit paint spans: changing one cell's presentation must not reshape its neighbors. The block
 * cursor resolves the same bounded span and reuses its positioned glyph vector.
 *
 * Primitive and native-emoji cells retain [TerminalTextPainter]'s shared cell dispatch. Reusable
 * UTF-16 and ownership arrays keep unchanged shaped-run lookups allocation-free.
 */
internal class TerminalShapedTextRunPainter(
    private val colorCache: AwtColorCache,
    private val decorationPainter: TerminalDecorationPainter,
    private val fontCache: FontCache,
    private val runStyle: TerminalTextRunStyle,
    private val cellPrimitives: TerminalCellPrimitivePainter,
    private val platformEmojiPainter: TerminalPlatformEmojiPainter,
) {
    private val glyphVectors = TerminalShapedGlyphVectorCache()
    private var chars = CharArray(INITIAL_TEXT_RUN_CAPACITY)
    private var charColumns = IntArray(INITIAL_TEXT_RUN_CAPACITY)

    fun clear() {
        glyphVectors.clear()
    }

    /** Whether this cell can start a contextual span in its bidi direction. */
    fun isShapingCell(
        cache: TerminalRenderCache,
        index: Int,
        rtl: Boolean,
    ): Boolean {
        val flags = cache.flags[index]
        if (!hasDrawableText(flags) || flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) return false
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val ref = cache.clusterRefs[index]
            if (ref == 0L) return false
            val offset = cache.clusterOffset(ref)
            val length = cache.clusterLength(ref)
            if (length > MAX_RUN_CODEPOINTS || platformEmojiPainter.usesEmojiPresentation(cache.clusterCodepoints, offset, length)) {
                return false
            }
            if (rtl) return true
            var cp = offset
            val end = offset + length
            while (cp < end) {
                if (isComplexShapingCodePoint(cache.clusterCodepoints[cp++])) return true
            }
            return false
        }
        val codePoint = cache.codeWords[index]
        return !(cellPrimitives.canPaint(codePoint) || platformEmojiPainter.usesEmojiPresentation(codePoint)) && (rtl || isComplexShapingCodePoint(codePoint))
    }

    /** Paints one bounded contextual span and returns its next logical column. */
    fun paintRun(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        startColumn: Int,
        runLimit: Int,
        fontRenderContext: FontRenderContext,
        bidi: TerminalBidiLayout.Row?,
    ): Int {
        val rowOffset = cache.rowOffset(row)
        val rtl = bidi?.isRtl(startColumn) == true
        val endColumn = runEnd(cache, rowOffset, startColumn, runLimit, rtl)
        val shapedRun = positionedRun(cache, metrics, rowOffset, startColumn, endColumn, rtl, fontRenderContext)
        val runVisualStart = visualStart(cache, rowOffset, startColumn, endColumn, bidi)
        var column = startColumn
        while (column < endColumn) {
            runStyle.begin(cache, palette, rowOffset, column)
            var styleLimit = minOf(endColumn, column + cellSpan(cache.flags[rowOffset + column]))
            while (styleLimit < endColumn && runStyle.matches(cache, palette, rowOffset, styleLimit)) {
                styleLimit += minOf(cellSpan(cache.flags[rowOffset + styleLimit]), endColumn - styleLimit)
            }
            if (!runStyle.textHidden) {
                val styleVisualStart = visualStart(cache, rowOffset, column, styleLimit, bidi)
                drawGlyphVector(
                    g,
                    shapedRun,
                    metrics,
                    row,
                    runVisualStart,
                    styleVisualStart,
                    styleLimit - column,
                    runStyle.foreground,
                )
                decorationPainter.paintTextRun(
                    g,
                    palette,
                    runStyle,
                    styleVisualStart,
                    styleVisualStart + styleLimit - column,
                    row,
                    metrics,
                )
            }
            column = styleLimit
        }
        return endColumn
    }

    /**
     * Repaints the positioned contextual span containing [column], under the caller's cursor clip.
     * Returns false when that cell belongs to the ordinary ASCII or specialized-cell path.
     */
    fun paintCellForeground(
        g: Graphics2D,
        cache: TerminalRenderCache,
        metrics: SwingMetrics,
        column: Int,
        row: Int,
        foreground: Int,
        fontRenderContext: FontRenderContext,
        bidi: TerminalBidiLayout.Row?,
    ): Boolean {
        val rowOffset = cache.rowOffset(row)
        var startColumn = 0
        while (startColumn <= column) {
            val runLimit = bidi?.runLimit(startColumn) ?: cache.columns
            if (runLimit <= column) {
                startColumn = runLimit
                continue
            }
            val rtl = bidi?.isRtl(startColumn) == true
            while (startColumn <= column) {
                val index = rowOffset + startColumn
                if (!isShapingCell(cache, index, rtl)) {
                    startColumn += minOf(cellSpan(cache.flags[index]), runLimit - startColumn)
                    continue
                }
                val endColumn = runEnd(cache, rowOffset, startColumn, runLimit, rtl)
                if (column < endColumn) {
                    val shapedRun = positionedRun(cache, metrics, rowOffset, startColumn, endColumn, rtl, fontRenderContext)
                    g.color = colorCache.color(foreground)
                    val runVisualStart = visualStart(cache, rowOffset, startColumn, endColumn, bidi)
                    val cellVisualStart = bidi?.visualColumn(column) ?: column
                    val clipStart = (cellVisualStart - runVisualStart) * metrics.cellWidth.toFloat()
                    val span = minOf(cellSpan(cache.flags[rowOffset + column]), cache.columns - column)
                    shapedRun.draw(
                        g,
                        (runVisualStart * metrics.cellWidth).toFloat(),
                        (row * metrics.cellHeight + metrics.baseline).toFloat(),
                        clipStart,
                        clipStart + span * metrics.cellWidth,
                    )
                    return true
                }
                startColumn = endColumn
            }
        }
        return false
    }

    private fun runEnd(
        cache: TerminalRenderCache,
        rowOffset: Int,
        startColumn: Int,
        runLimit: Int,
        rtl: Boolean,
    ): Int {
        val fontStyle = terminalFontStyle(cache.attrWords[rowOffset + startColumn])
        var script = COMMON_SCRIPT
        var codepoints = 0
        var column = startColumn
        while (column < runLimit) {
            val index = rowOffset + column
            if (!isShapingCell(cache, index, rtl) && !isShapingSpace(cache, rowOffset, column, runLimit, rtl)) break
            if (terminalFontStyle(cache.attrWords[index]) != fontStyle) break
            val currentScript = cellScript(cache, index)
            if (currentScript != COMMON_SCRIPT && script != COMMON_SCRIPT && currentScript != script) break
            val ref = cache.clusterRefs[index]
            val length = if (cache.flags[index] and TerminalRenderCellFlags.CLUSTER != 0 && ref != 0L) cache.clusterLength(ref) else 1
            if (codepoints + length > MAX_RUN_CODEPOINTS) break
            codepoints += length
            if (currentScript != COMMON_SCRIPT) script = currentScript
            column += minOf(cellSpan(cache.flags[index]), runLimit - column)
        }
        return column
    }

    private fun isShapingSpace(
        cache: TerminalRenderCache,
        rowOffset: Int,
        column: Int,
        runLimit: Int,
        rtl: Boolean,
    ): Boolean =
        cache.flags[rowOffset + column] == TerminalRenderCellFlags.CODEPOINT &&
            cache.codeWords[rowOffset + column] == SPACE_CODE_POINT &&
            column + 1 < runLimit &&
            isShapingCell(cache, rowOffset + column + 1, rtl)

    private fun positionedRun(
        cache: TerminalRenderCache,
        metrics: SwingMetrics,
        rowOffset: Int,
        startColumn: Int,
        endColumn: Int,
        rtl: Boolean,
        fontRenderContext: FontRenderContext,
    ): TerminalShapedGlyphVectorCache.Run {
        val length = fillChars(cache, rowOffset, startColumn, endColumn)
        return glyphVectors.run(
            chars = chars,
            length = length,
            charColumns = charColumns,
            columns = endColumn - startColumn,
            style = terminalFontStyle(cache.attrWords[rowOffset + startColumn]),
            cellWidth = metrics.cellWidth,
            fontCache = fontCache,
            fontRenderContext = fontRenderContext,
            rtl = rtl,
        )
    }

    private fun drawGlyphVector(
        g: Graphics2D,
        shapedRun: TerminalShapedGlyphVectorCache.Run,
        metrics: SwingMetrics,
        row: Int,
        runVisualStart: Int,
        styleVisualStart: Int,
        styleColumns: Int,
        foreground: Int,
    ) {
        val oldClip = g.clip
        try {
            g.clipRect(styleVisualStart * metrics.cellWidth, row * metrics.cellHeight, styleColumns * metrics.cellWidth, metrics.cellHeight)
            g.color = colorCache.color(foreground)
            val clipStart = (styleVisualStart - runVisualStart) * metrics.cellWidth.toFloat()
            shapedRun.draw(
                g,
                (runVisualStart * metrics.cellWidth).toFloat(),
                (row * metrics.cellHeight + metrics.baseline).toFloat(),
                clipStart,
                clipStart + styleColumns * metrics.cellWidth,
            )
        } finally {
            g.clip = oldClip
        }
    }

    private fun fillChars(
        cache: TerminalRenderCache,
        rowOffset: Int,
        startColumn: Int,
        endColumn: Int,
    ): Int {
        var length = 0
        var column = startColumn
        while (column < endColumn) {
            val index = rowOffset + column
            val flags = cache.flags[index]
            val ref = cache.clusterRefs[index]
            if (flags and TerminalRenderCellFlags.CLUSTER != 0 && ref != 0L) {
                var cp = cache.clusterOffset(ref)
                val end = cp + cache.clusterLength(ref)
                while (cp < end) length = appendCodePoint(cache.clusterCodepoints[cp++], column - startColumn, length)
            } else {
                length = appendCodePoint(cache.codeWords[index], column - startColumn, length)
            }
            column += minOf(cellSpan(flags), endColumn - column)
        }
        return length
    }

    private fun appendCodePoint(
        codePoint: Int,
        column: Int,
        offset: Int,
    ): Int {
        if (offset + 2 > chars.size) {
            val capacity = minOf(TerminalShapedGlyphVectorCache.MAX_RUN_LENGTH, chars.size * 2)
            chars = chars.copyOf(capacity)
            charColumns = charColumns.copyOf(capacity)
        }
        val safeCodePoint = if (Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF) codePoint else 0xFFFD
        val charCount = Character.toChars(safeCodePoint, chars, offset)
        charColumns[offset] = column
        if (charCount == 2) charColumns[offset + 1] = column
        return offset + charCount
    }

    private fun visualStart(
        cache: TerminalRenderCache,
        rowOffset: Int,
        startColumn: Int,
        endColumn: Int,
        bidi: TerminalBidiLayout.Row?,
    ): Int {
        if (bidi == null) return startColumn
        val lastColumn = endColumn - 1
        val lastOwner = visualCellRangeStart(cache.flags[rowOffset + lastColumn], lastColumn)
        return minOf(bidi.visualColumn(startColumn), bidi.visualColumn(lastOwner))
    }

    private fun cellScript(
        cache: TerminalRenderCache,
        index: Int,
    ): Int {
        if (cache.flags[index] and TerminalRenderCellFlags.CLUSTER != 0) {
            val ref = cache.clusterRefs[index]
            if (ref == 0L) return COMMON_SCRIPT
            var cp = cache.clusterOffset(ref)
            val end = cp + cache.clusterLength(ref)
            while (cp < end) {
                val script = codePointScript(cache.clusterCodepoints[cp++])
                if (script != COMMON_SCRIPT) return script
            }
            return COMMON_SCRIPT
        }
        return codePointScript(cache.codeWords[index])
    }

    private companion object {
        private const val INITIAL_TEXT_RUN_CAPACITY = 256
        private const val MAX_RUN_CODEPOINTS = TerminalShapedGlyphVectorCache.MAX_RUN_LENGTH / 2
        private const val SPACE_CODE_POINT = 0x20
        private const val COMMON_SCRIPT = 0

        private fun codePointScript(codePoint: Int): Int =
            if (!Character.isValidCodePoint(codePoint)) {
                COMMON_SCRIPT
            } else {
                when (val script = Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.COMMON,
                    Character.UnicodeScript.INHERITED,
                    Character.UnicodeScript.UNKNOWN,
                    -> COMMON_SCRIPT

                    else -> script.ordinal + 1
                }
            }

        private fun isComplexShapingCodePoint(codePoint: Int): Boolean =
            codePoint in 0x0900..0x0DFF ||
                codePoint in 0x0E00..0x0EFF ||
                codePoint in 0x1200..0x139F ||
                codePoint in 0x2D80..0x2DDF ||
                codePoint in 0xAB00..0xAB2F ||
                codePoint in 0x1780..0x17FF ||
                codePoint in 0x19E0..0x19FF ||
                codePoint in 0x1A20..0x1AAF ||
                codePoint in 0xA8E0..0xA8FF ||
                codePoint in 0xAA60..0xAA7F
    }
}

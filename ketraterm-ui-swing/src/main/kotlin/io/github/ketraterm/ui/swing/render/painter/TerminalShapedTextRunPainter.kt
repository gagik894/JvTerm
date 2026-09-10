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
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.cache.FontCache
import io.github.ketraterm.ui.swing.render.cache.TerminalComplexTextLayoutCache
import io.github.ketraterm.ui.swing.render.hasDrawableText
import io.github.ketraterm.ui.swing.render.isFastAsciiCell
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.text.Bidi

/**
 * Paints row-shaped complex text spans that cannot be rendered correctly one
 * terminal cell at a time.
 *
 * This helper owns Unicode Bidirectional Algorithm row planning and
 * complex-script run shaping for Brahmic and Southeast Asian scripts. The
 * ordinary ASCII path remains in [TerminalTextPainter] so the common repaint
 * path does not pay for Bidi or script-run machinery.
 */
internal class TerminalShapedTextRunPainter(
    private val colorCache: AwtColorCache,
    private val decorationPainter: TerminalDecorationPainter,
    private val fontCache: FontCache,
    private val complexTextLayouts: TerminalComplexTextLayoutCache,
    private val runStyle: TerminalTextRunStyle,
) {
    private var rowChars = CharArray(INITIAL_TEXT_RUN_CAPACITY)
    private var segmentCodepoints = IntArray(INITIAL_TEXT_RUN_CAPACITY)
    private var bidiRows = arrayOfNulls<Bidi>(0)
    private var rowGenerations = LongArray(0)
    private var rowLineIds = LongArray(0)
    private var rowHasStrongRtl = BooleanArray(0)
    private var cachedColumns = 0

    fun cachedRowContainsStrongRtl(
        cache: TerminalRenderCache,
        row: Int,
    ): Boolean {
        ensureBidiRowCache(cache)
        val generation = cache.lineGenerations[row]
        val lineId = cache.lineIds[row]
        if (rowGenerations[row] == generation && rowLineIds[row] == lineId) return rowHasStrongRtl[row]

        val hasStrongRtl = rowContainsStrongRtl(cache, row)
        bidiRows[row] = null
        rowGenerations[row] = generation
        rowLineIds[row] = lineId
        rowHasStrongRtl[row] = hasStrongRtl
        return hasStrongRtl
    }

    fun paintBidiRow(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val bidi = bidiForRow(cache, row)
        val baselineY = row * metrics.cellHeight + metrics.baseline
        var visualStartColumn = 0
        var runIndex = 0
        while (runIndex < bidi.runCount) {
            val runStart = bidi.getRunStart(runIndex)
            val runLimit = bidi.getRunLimit(runIndex)
            val rtlRun = bidi.getRunLevel(runIndex) and 1 != 0
            var segmentStart = runStart
            while (segmentStart < runLimit) {
                runStyle.begin(cache, palette, cache.rowOffset(row), segmentStart)
                val segmentLimit =
                    bidiSegmentLimit(
                        cache = cache,
                        palette = palette,
                        row = row,
                        startColumn = segmentStart,
                        runLimit = runLimit,
                    )
                val segmentVisualStart =
                    if (rtlRun) {
                        visualStartColumn + runLimit - segmentLimit
                    } else {
                        visualStartColumn + segmentStart - runStart
                    }
                paintShapedLogicalSegment(
                    g = g,
                    cache = cache,
                    palette = palette,
                    metrics = metrics,
                    row = row,
                    startColumn = segmentStart,
                    endColumn = segmentLimit,
                    visualStartColumn = segmentVisualStart,
                    baselineY = baselineY,
                    fontRenderContext = fontRenderContext,
                )
                segmentStart = segmentLimit
            }
            visualStartColumn += runLimit - runStart
            runIndex++
        }
    }

    fun paintComplexShapingRun(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        startColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ): Int {
        runStyle.begin(cache, palette, cache.rowOffset(row), startColumn)
        val endColumn =
            complexShapingRunEnd(
                cache = cache,
                palette = palette,
                row = row,
                startColumn = startColumn,
            )
        paintShapedLogicalSegment(
            g = g,
            cache = cache,
            palette = palette,
            metrics = metrics,
            row = row,
            startColumn = startColumn,
            endColumn = endColumn,
            visualStartColumn = startColumn,
            baselineY = baselineY,
            fontRenderContext = fontRenderContext,
        )
        return endColumn
    }

    fun isComplexShapingCell(
        cache: TerminalRenderCache,
        index: Int,
    ): Boolean {
        val flags = cache.flags[index]
        if (!hasDrawableText(flags) || flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) return false
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val clusterRef = cache.clusterRefs[index]
            if (clusterRef == 0L) return false
            val offset = cache.clusterOffset(clusterRef)
            val end = offset + cache.clusterLength(clusterRef)
            var clusterIndex = offset
            while (clusterIndex < end) {
                if (isComplexShapingCodePoint(cache.clusterCodepoints[clusterIndex])) return true
                clusterIndex++
            }
            return false
        }
        return isComplexShapingCodePoint(cache.codeWords[index])
    }

    private fun bidiForRow(
        cache: TerminalRenderCache,
        row: Int,
    ): Bidi {
        ensureBidiRowCache(cache)
        val generation = cache.lineGenerations[row]
        val lineId = cache.lineIds[row]
        val cached = bidiRows[row]
        if (cached != null && rowGenerations[row] == generation && rowLineIds[row] == lineId) return cached

        ensureRowCharCapacity(cache.columns)
        fillBidiRowChars(cache, row)
        val bidi =
            Bidi(
                rowChars,
                0,
                null,
                0,
                cache.columns,
                Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT,
            )
        bidiRows[row] = bidi
        rowGenerations[row] = generation
        rowLineIds[row] = lineId
        return bidi
    }

    private fun cellCategory(
        cache: TerminalRenderCache,
        index: Int,
    ): Int {
        val flags = cache.flags[index]
        val codeWord = cache.codeWords[index]
        return when {
            isFastAsciiCell(flags, codeWord) -> 0
            isComplexShapingCell(cache, index) -> 1
            else -> 2
        }
    }

    private fun bidiSegmentLimit(
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        row: Int,
        startColumn: Int,
        runLimit: Int,
    ): Int {
        val rowOffset = cache.rowOffset(row)
        val category = cellCategory(cache, rowOffset + startColumn)
        val script = scriptKeyForSegment(cache, rowOffset, startColumn, runLimit)
        var column = startColumn + 1
        while (column < runLimit) {
            if (cellCategory(cache, rowOffset + column) != category) break
            if (!isCompatibleScriptCell(cache, rowOffset, column, runLimit, script)) break
            if (!runStyle.matches(cache, palette, rowOffset, column)) break
            column++
        }
        return column
    }

    private fun complexShapingRunEnd(
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        row: Int,
        startColumn: Int,
    ): Int {
        val rowOffset = cache.rowOffset(row)
        val script = scriptKeyForSegment(cache, rowOffset, startColumn, cache.columns)
        var column = startColumn + 1
        while (column < cache.columns) {
            if (!isComplexShapingRunContinuation(cache, rowOffset, column)) break
            if (!isCompatibleScriptCell(cache, rowOffset, column, cache.columns, script)) break
            if (!runStyle.matches(cache, palette, rowOffset, column)) break
            column++
        }
        return column
    }

    private fun isComplexShapingRunContinuation(
        cache: TerminalRenderCache,
        rowOffset: Int,
        column: Int,
    ): Boolean {
        val index = rowOffset + column
        if (isComplexShapingCell(cache, index)) return true
        if (isAsciiSpaceCell(cache.flags[index], cache.codeWords[index])) {
            val nextColumn = column + 1
            return nextColumn < cache.columns && isComplexShapingCell(cache, rowOffset + nextColumn)
        }
        return false
    }

    private fun paintShapedLogicalSegment(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        startColumn: Int,
        endColumn: Int,
        visualStartColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        if (runStyle.textHidden) return

        val cellPixelWidth = metrics.cellWidth * (endColumn - startColumn)
        val x = visualStartColumn * metrics.cellWidth
        val length = fillSegmentCodepoints(cache, row, startColumn, endColumn)
        if (length > 0) {
            g.font = fontCache.font(runStyle.fontStyle)
            g.color = colorCache.color(runStyle.foreground)
            val oldClip = g.clip
            try {
                g.clipRect(x, row * metrics.cellHeight, cellPixelWidth, metrics.cellHeight)
                val layout =
                    complexTextLayouts.scriptRunLayout(
                        segmentCodepoints,
                        0,
                        length,
                        runStyle.fontStyle,
                        fontRenderContext,
                        fontCache,
                    )
                drawFittedLayout(g, layout, x.toFloat(), baselineY.toFloat(), x + cellPixelWidth)
            } finally {
                g.clip = oldClip
            }
        }

        decorationPainter.paintTextRun(
            g = g,
            palette = palette,
            style = runStyle,
            startColumn = visualStartColumn,
            endColumn = visualStartColumn + endColumn - startColumn,
            row = row,
            metrics = metrics,
        )
    }

    private fun rowContainsStrongRtl(
        cache: TerminalRenderCache,
        row: Int,
    ): Boolean {
        val rowOffset = cache.rowOffset(row)
        var column = 0
        while (column < cache.columns) {
            val index = rowOffset + column
            val flags = cache.flags[index]
            if (hasDrawableText(flags)) {
                if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                    val clusterRef = cache.clusterRefs[index]
                    if (clusterRef != 0L) {
                        val offset = cache.clusterOffset(clusterRef)
                        val end = offset + cache.clusterLength(clusterRef)
                        var clusterIndex = offset
                        while (clusterIndex < end) {
                            if (isStrongRtl(cache.clusterCodepoints[clusterIndex])) return true
                            clusterIndex++
                        }
                    }
                } else if (isStrongRtl(cache.codeWords[index])) {
                    return true
                }
            }
            column++
        }
        return false
    }

    private fun ensureBidiRowCache(cache: TerminalRenderCache) {
        if (bidiRows.size == cache.rows && cachedColumns == cache.columns) return

        bidiRows = arrayOfNulls(cache.rows)
        rowGenerations = LongArray(cache.rows) { INVALID_GENERATION }
        rowLineIds = LongArray(cache.rows)
        rowHasStrongRtl = BooleanArray(cache.rows)
        cachedColumns = cache.columns
    }

    private fun fillBidiRowChars(
        cache: TerminalRenderCache,
        row: Int,
    ) {
        val rowOffset = cache.rowOffset(row)
        var column = 0
        while (column < cache.columns) {
            val index = rowOffset + column
            rowChars[column] = bidiClassChar(cache, index)
            column++
        }
    }

    private fun bidiClassChar(
        cache: TerminalRenderCache,
        index: Int,
    ): Char {
        val flags = cache.flags[index]
        if (!hasDrawableText(flags) || flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) return ' '
        val codePoint =
            if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                val clusterRef = cache.clusterRefs[index]
                if (clusterRef == 0L) {
                    SPACE_CODE_POINT
                } else {
                    cache.clusterCodepoints[cache.clusterOffset(clusterRef)]
                }
            } else {
                cache.codeWords[index]
            }
        return if (codePoint in 0..0xffff) codePoint.toChar() else REPLACEMENT_CHAR
    }

    private fun fillSegmentCodepoints(
        cache: TerminalRenderCache,
        row: Int,
        startColumn: Int,
        endColumn: Int,
    ): Int {
        ensureSegmentCodepointCapacity((endColumn - startColumn) * MAX_CODEPOINTS_PER_CELL)
        val rowOffset = cache.rowOffset(row)
        var length = 0
        var column = startColumn
        while (column < endColumn) {
            val index = rowOffset + column
            val flags = cache.flags[index]
            if (!hasDrawableText(flags) || flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
                segmentCodepoints[length++] = SPACE_CODE_POINT
            } else if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                val clusterRef = cache.clusterRefs[index]
                if (clusterRef == 0L) {
                    segmentCodepoints[length++] = SPACE_CODE_POINT
                } else {
                    val offset = cache.clusterOffset(clusterRef)
                    val clusterLength = cache.clusterLength(clusterRef)
                    ensureSegmentCodepointCapacity(length + clusterLength)
                    System.arraycopy(cache.clusterCodepoints, offset, segmentCodepoints, length, clusterLength)
                    length += clusterLength
                }
            } else {
                segmentCodepoints[length++] = cache.codeWords[index]
            }
            column++
        }
        return length
    }

    private fun scriptKeyForSegment(
        cache: TerminalRenderCache,
        rowOffset: Int,
        startColumn: Int,
        limitColumn: Int,
    ): Int {
        var column = startColumn
        while (column < limitColumn) {
            val script = cellScript(cache, rowOffset + column)
            if (script != COMMON_SCRIPT) return script
            column++
        }
        return COMMON_SCRIPT
    }

    private fun isCompatibleScriptCell(
        cache: TerminalRenderCache,
        rowOffset: Int,
        column: Int,
        limitColumn: Int,
        script: Int,
    ): Boolean {
        val currentScript = cellScript(cache, rowOffset + column)
        if (currentScript == COMMON_SCRIPT || currentScript == script) return true
        if (script != COMMON_SCRIPT) return false
        return currentScript == scriptKeyForSegment(cache, rowOffset, column, limitColumn)
    }

    private fun cellScript(
        cache: TerminalRenderCache,
        index: Int,
    ): Int {
        val flags = cache.flags[index]
        if (!hasDrawableText(flags) || flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) return COMMON_SCRIPT
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val clusterRef = cache.clusterRefs[index]
            if (clusterRef == 0L) return COMMON_SCRIPT
            val offset = cache.clusterOffset(clusterRef)
            val end = offset + cache.clusterLength(clusterRef)
            var clusterIndex = offset
            while (clusterIndex < end) {
                val script = codePointScript(cache.clusterCodepoints[clusterIndex])
                if (script != COMMON_SCRIPT) return script
                clusterIndex++
            }
            return COMMON_SCRIPT
        }
        return codePointScript(cache.codeWords[index])
    }

    private fun ensureRowCharCapacity(columns: Int) {
        if (rowChars.size >= columns) return
        var capacity = rowChars.size
        while (capacity < columns) {
            capacity *= 2
        }
        rowChars = rowChars.copyOf(capacity)
    }

    private fun ensureSegmentCodepointCapacity(required: Int) {
        if (segmentCodepoints.size >= required) return
        var capacity = segmentCodepoints.size
        while (capacity < required) {
            capacity *= 2
        }
        segmentCodepoints = segmentCodepoints.copyOf(capacity)
    }

    private fun isAsciiSpaceCell(
        flags: Int,
        codeWord: Int,
    ): Boolean = flags == TerminalRenderCellFlags.CODEPOINT && codeWord == SPACE_CODE_POINT

    private fun drawFittedLayout(
        g: Graphics2D,
        layout: TextLayout,
        x: Float,
        baselineY: Float,
        spanEndX: Int,
    ) {
        val available = spanEndX - x
        val advance = layout.advance
        if (available <= 0f || advance <= 0f) return
        if (advance <= available) {
            layout.draw(g, x, baselineY)
            return
        }

        val oldTransform = g.transform
        try {
            val scaleX = available / advance
            g.translate(x.toDouble(), 0.0)
            g.scale(scaleX.toDouble(), 1.0)
            layout.draw(g, 0f, baselineY)
        } finally {
            g.transform = oldTransform
        }
    }

    private companion object {
        private const val INITIAL_TEXT_RUN_CAPACITY = 256
        private const val SPACE_CODE_POINT = 0x20
        private const val MAX_CODEPOINTS_PER_CELL = 4
        private const val REPLACEMENT_CHAR = '\uFFFD'
        private const val INVALID_GENERATION = Long.MIN_VALUE
        private const val COMMON_SCRIPT = 0

        @JvmStatic
        private fun codePointScript(codePoint: Int): Int =
            when (val script = Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.COMMON,
                Character.UnicodeScript.INHERITED,
                Character.UnicodeScript.UNKNOWN,
                -> COMMON_SCRIPT

                else -> script.ordinal + 1
            }

        @JvmStatic
        private fun isStrongRtl(codePoint: Int): Boolean =
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> true
                else -> false
            }

        @JvmStatic
        private fun isComplexShapingCodePoint(codePoint: Int): Boolean =
            codePoint in 0x0900..0x0DFF ||
                codePoint in 0x0E00..0x0EFF ||
                codePoint in 0x1200..0x139F ||
                // Ethiopic & Supplement
                codePoint in 0x2D80..0x2DDF ||
                // Ethiopic Extended
                codePoint in 0xAB00..0xAB2F ||
                // Ethiopic Extended-A
                codePoint in 0x1780..0x17FF ||
                codePoint in 0x19E0..0x19FF ||
                codePoint in 0x1A20..0x1AAF ||
                codePoint in 0xA8E0..0xA8FF ||
                codePoint in 0xAA60..0xAA7F
    }
}

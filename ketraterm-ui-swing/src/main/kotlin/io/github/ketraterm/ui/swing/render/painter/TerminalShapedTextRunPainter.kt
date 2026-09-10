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
import io.github.ketraterm.ui.swing.render.cache.TerminalComplexTextLayoutCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.text.Bidi

/**
 * Paints row-shaped complex text spans that cannot be rendered correctly one
 * terminal cell at a time.
 *
 * This helper shapes direction- and script-compatible spans using the shared cell mapping. The
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
    private var segmentCodepoints = IntArray(INITIAL_TEXT_RUN_CAPACITY)

    fun paintBidiRow(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        fontRenderContext: FontRenderContext,
        bidi: TerminalBidiLayout.Row,
    ) {
        val baselineY = row * metrics.cellHeight + metrics.baseline
        var runStart = 0
        while (runStart < cache.columns) {
            val runLimit = bidi.runLimit(runStart)
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
                val lastColumn = segmentLimit - 1
                val lastOwner = visualCellRangeStart(cache.flags[cache.rowOffset(row) + lastColumn], lastColumn)
                val segmentVisualStart = minOf(bidi.visualColumn(segmentStart), bidi.visualColumn(lastOwner))
                paintShapedLogicalSegment(
                    g = g,
                    cache = cache,
                    palette = palette,
                    metrics = metrics,
                    row = row,
                    startColumn = segmentStart,
                    endColumn = segmentLimit,
                    visualStartColumn = segmentVisualStart,
                    direction = if (bidi.isRtl(runStart)) Bidi.DIRECTION_RIGHT_TO_LEFT else Bidi.DIRECTION_LEFT_TO_RIGHT,
                    baselineY = baselineY,
                    fontRenderContext = fontRenderContext,
                )
                segmentStart = segmentLimit
            }
            runStart = runLimit
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
        var column = minOf(runLimit, startColumn + cellSpan(cache.flags[rowOffset + startColumn]))
        while (column < runLimit) {
            if (cellCategory(cache, rowOffset + column) != category) break
            if (!isCompatibleScriptCell(cache, rowOffset, column, runLimit, script)) break
            if (!runStyle.matches(cache, palette, rowOffset, column)) break
            column += minOf(cellSpan(cache.flags[rowOffset + column]), runLimit - column)
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
        direction: Int = Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT,
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
                        direction = direction,
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
            if (flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
                column++
                continue
            }
            if (!hasDrawableText(flags)) {
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

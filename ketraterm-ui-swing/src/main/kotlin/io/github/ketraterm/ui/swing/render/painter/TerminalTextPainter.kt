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
import io.github.ketraterm.ui.swing.api.TerminalFontResolver
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.*
import io.github.ketraterm.ui.swing.render.font.TerminalTextRunBuffer
import io.github.ketraterm.ui.swing.render.primitives.TerminalCellPrimitivePainter
import io.github.ketraterm.ui.swing.render.primitives.TerminalPlatformEmojiPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout

/**
 * Paints terminal cell text runs and text-only cursor foreground.
 */
internal class TerminalTextPainter(
    private val colorCache: AwtColorCache,
    private val decorationPainter: TerminalDecorationPainter,
    private val platformEmojiPainter: TerminalPlatformEmojiPainter = TerminalPlatformEmojiPainter(),
    fontResolver: TerminalFontResolver? = null,
    private val cellGeometry: TerminalBidiLayout = TerminalBidiLayout(),
) {
    private val fontCache = FontCache(fontResolver = fontResolver)
    private val complexTextLayouts = TerminalComplexTextLayoutCache()
    private val asciiGlyphVectors = TerminalAsciiGlyphVectorCache()
    private val asciiDrawChars = TerminalAsciiDrawCharsCache()
    private val cellPrimitives = TerminalCellPrimitivePainter()
    private val textRun = TerminalTextRunBuffer(INITIAL_TEXT_RUN_CAPACITY)
    private val asciiClipBounds = Rectangle()
    private val runStyle = TerminalTextRunStyle()
    private val shapedTextRuns =
        TerminalShapedTextRunPainter(
            colorCache = colorCache,
            decorationPainter = decorationPainter,
            fontCache = fontCache,
            runStyle = runStyle,
            cellPrimitives = cellPrimitives,
        )

    /**
     * Updates font-dependent caches for a settings snapshot.
     */
    fun updateSettings(settings: SwingSettings) {
        if (fontCache.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts)) {
            complexTextLayouts.clear()
            shapedTextRuns.clear()
            asciiGlyphVectors.clear()
            asciiDrawChars.clear()
        }
    }

    /**
     * Returns a cached font for [style].
     */
    fun font(style: Int): Font = fontCache.font(style)

    /**
     * Paints all drawable text runs in [row].
     */
    fun paintRow(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        fontRenderContext: FontRenderContext,
        textBlinkVisible: Boolean = true,
        hyperlinkIds: IntArray = cache.hyperlinkIds,
        hoveredHyperlinkId: Int = NO_HYPERLINK_ID,
        hoveredHyperlinkStartRow: Int = DEFAULT_HOVER_START_ROW,
        hoveredHyperlinkStartColumn: Int = 0,
        hoveredHyperlinkEndRow: Int = DEFAULT_HOVER_END_ROW,
        hoveredHyperlinkEndColumn: Int = DEFAULT_HOVER_END_COLUMN,
        hyperlinkActivationHover: Boolean = false,
        hyperlinkActivationForeground: Int = DEFAULT_HYPERLINK_ACTIVATION_FOREGROUND,
    ) {
        runStyle.configureRow(
            row = row,
            textBlinkVisible = textBlinkVisible,
            hyperlinkIds = hyperlinkIds,
            hoveredHyperlinkId = hoveredHyperlinkId,
            hoveredHyperlinkStartRow = hoveredHyperlinkStartRow,
            hoveredHyperlinkStartColumn = hoveredHyperlinkStartColumn,
            hoveredHyperlinkEndRow = hoveredHyperlinkEndRow,
            hoveredHyperlinkEndColumn = hoveredHyperlinkEndColumn,
            hyperlinkActivationHover = hyperlinkActivationHover,
            hyperlinkActivationForeground = hyperlinkActivationForeground,
        )
        val bidi = cellGeometry.row(cache, row)
        val flagsPlane = cache.flags
        val codeWords = cache.codeWords
        val rowOffset = cache.rowOffset(row)
        val baselineY = row * metrics.cellHeight + metrics.baseline
        var column = 0
        var runLimit = 0

        while (column < cache.columns) {
            if (column >= runLimit) runLimit = bidi?.runLimit(column) ?: cache.columns
            val rtl = bidi?.isRtl(column) == true
            val visualColumn = bidi?.visualColumn(column) ?: column
            val index = rowOffset + column
            val flags = flagsPlane[index]
            if (!hasDrawableText(flags)) {
                column++
                continue
            }

            val codeWord = codeWords[index]
            column =
                when {
                    isFastAsciiCell(flags, codeWord) && !rtl ->
                        paintAsciiRun(
                            g = g,
                            cache = cache,
                            palette = palette,
                            metrics = metrics,
                            row = row,
                            startColumn = column,
                            runLimit = runLimit,
                            visualStartColumn = visualColumn,
                            baselineY = baselineY,
                            fontRenderContext = fontRenderContext,
                        )

                    shapedTextRuns.isShapingCell(cache, index, rtl) ->
                        shapedTextRuns.paintRun(
                            g = g,
                            cache = cache,
                            palette = palette,
                            metrics = metrics,
                            row = row,
                            startColumn = column,
                            runLimit = runLimit,
                            fontRenderContext = fontRenderContext,
                            bidi = bidi,
                        )

                    else ->
                        paintComplexCell(
                            g = g,
                            cache = cache,
                            palette = palette,
                            metrics = metrics,
                            row = row,
                            column = column,
                            visualColumn = visualColumn,
                            baselineY = baselineY,
                            fontRenderContext = fontRenderContext,
                        )
                }
        }
    }

    /**
     * Paints the logical [column]'s text clipped to a block cursor at [visualColumn].
     */
    fun paintCellForeground(
        g: Graphics2D,
        cache: TerminalRenderCache,
        metrics: SwingMetrics,
        column: Int,
        row: Int,
        columnSpan: Int = 1,
        foreground: Int,
        fontRenderContext: FontRenderContext,
        textBlinkVisible: Boolean = true,
        visualColumn: Int = column,
    ) {
        val index = cache.rowOffset(row) + column
        val flags = cache.flags[index]
        if (!hasDrawableText(flags) || isTextHidden(cache.attrWords[index], textBlinkVisible)) return

        val oldClip = g.clip
        try {
            g.clipRect(
                visualColumn * metrics.cellWidth,
                row * metrics.cellHeight,
                metrics.cellWidth * maxOf(1, columnSpan),
                metrics.cellHeight,
            )
            val bidi = cellGeometry.row(cache, row)
            val mayBelongToShapedRun =
                shapedTextRuns.isShapingCell(cache, index, bidi?.isRtl(column) == true) ||
                    isFastAsciiCell(flags, cache.codeWords[index]) &&
                    cache.codeWords[index] == 0x20
            val paintedShapedRun =
                mayBelongToShapedRun &&
                    shapedTextRuns.paintCellForeground(
                        g = g,
                        cache = cache,
                        metrics = metrics,
                        column = column,
                        row = row,
                        foreground = foreground,
                        fontRenderContext = fontRenderContext,
                        bidi = bidi,
                    )
            if (!paintedShapedRun) {
                paintCellGlyph(
                    g = g,
                    cache = cache,
                    metrics = metrics,
                    column = column,
                    visualColumn = visualColumn,
                    row = row,
                    columnSpan = maxOf(1, columnSpan),
                    fontStyle = terminalFontStyle(cache.attrWords[index]),
                    foreground = foreground,
                    baselineY = row * metrics.cellHeight + metrics.baseline,
                    fontRenderContext = fontRenderContext,
                )
            }
        } finally {
            g.clip = oldClip
        }
    }

    private fun paintAsciiRun(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        startColumn: Int,
        runLimit: Int,
        visualStartColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ): Int {
        val flagsPlane = cache.flags
        val codeWords = cache.codeWords
        val rowOffset = cache.rowOffset(row)
        runStyle.begin(cache, palette, rowOffset, startColumn)

        textRun.clear()
        textRun.appendAscii(codeWords[rowOffset + startColumn])
        var column = startColumn + 1
        while (column < runLimit) {
            val index = rowOffset + column
            val codeWord = codeWords[index]
            if (!isFastAsciiCell(flagsPlane[index], codeWord) || !runStyle.matches(cache, palette, rowOffset, column)) break

            textRun.appendAscii(codeWord)
            column++
        }

        if (runStyle.textHidden) return column

        // Matching advances do not constrain glyph ink: italic or antialiased
        // ASCII must respect the same paint-span boundaries as shaped text.
        val x = visualStartColumn * metrics.cellWidth
        val y = row * metrics.cellHeight
        val width = (column - startColumn) * metrics.cellWidth
        // getClipBounds leaves the supplied rectangle unchanged for a null clip.
        val clip = asciiClipBounds
        clip.setBounds(0, 0, -1, -1)
        g.getClipBounds(clip)
        val needsClip =
            clip.width < 0 ||
                clip.x < x ||
                clip.y < y ||
                clip.x.toLong() + clip.width > x.toLong() + width ||
                clip.y.toLong() + clip.height > y.toLong() + metrics.cellHeight
        // A caller's narrower clip already enforces the boundary, including
        // nonrectangular clips. Avoid copying/intersecting Java2D clip state.
        val oldClip = if (needsClip) g.clip else null
        try {
            if (needsClip) g.clipRect(x, y, width, metrics.cellHeight)
            g.font = fontCache.font(runStyle.fontStyle)
            g.color = colorCache.color(runStyle.foreground)
            drawAsciiRun(g, metrics, visualStartColumn, baselineY, runStyle.fontStyle, fontRenderContext)
            decorationPainter.paintTextRun(g, palette, runStyle, visualStartColumn, visualStartColumn + column - startColumn, row, metrics)
        } finally {
            if (needsClip) g.clip = oldClip
        }
        return column
    }

    private fun paintComplexCell(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        column: Int,
        visualColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ): Int {
        val rowOffset = cache.rowOffset(row)
        val endColumn = minOf(cache.columns, column + cellSpan(cache.flags[rowOffset + column]))
        runStyle.begin(cache, palette, rowOffset, column)
        if (runStyle.textHidden) return endColumn

        val oldClip = g.clip
        try {
            g.clipRect(
                visualColumn * metrics.cellWidth,
                row * metrics.cellHeight,
                metrics.cellWidth * (endColumn - column),
                metrics.cellHeight,
            )
            paintCellGlyph(
                g = g,
                cache = cache,
                metrics = metrics,
                column = column,
                visualColumn = visualColumn,
                row = row,
                columnSpan = endColumn - column,
                fontStyle = runStyle.fontStyle,
                foreground = runStyle.foreground,
                baselineY = baselineY,
                fontRenderContext = fontRenderContext,
            )
            decorationPainter.paintTextRun(
                g,
                palette,
                runStyle,
                visualColumn,
                visualColumn + endColumn - column,
                row,
                metrics,
            )
        } finally {
            g.clip = oldClip
        }
        return endColumn
    }

    /** Shared cell dispatch for ordinary rows, bidi rows and cursor foreground. */
    private fun paintCellGlyph(
        g: Graphics2D,
        cache: TerminalRenderCache,
        metrics: SwingMetrics,
        column: Int,
        visualColumn: Int,
        row: Int,
        columnSpan: Int,
        fontStyle: Int,
        foreground: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val index = cache.rowOffset(row) + column
        val flags = cache.flags[index]
        val codePoint = cache.codeWords[index]
        g.font = fontCache.font(fontStyle)
        g.color = colorCache.color(foreground)

        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val ref = cache.clusterRefs[index]
            if (ref == 0L) return
            val offset = cache.clusterOffset(ref)
            val length = cache.clusterLength(ref)
            if (!platformEmojiPainter.paintCluster(
                    g,
                    cache.clusterCodepoints,
                    offset,
                    length,
                    visualColumn,
                    row,
                    columnSpan,
                    metrics,
                )
            ) {
                drawComplexCluster(
                    g,
                    cache.clusterCodepoints,
                    offset,
                    length,
                    fontStyle,
                    visualColumn * metrics.cellWidth,
                    metrics.cellWidth * columnSpan,
                    baselineY,
                    fontRenderContext,
                )
            }
        } else if (cellPrimitives.canPaint(codePoint)) {
            cellPrimitives.paint(g, codePoint, visualColumn, row, metrics)
        } else if (isFastAsciiCell(flags, codePoint)) {
            textRun.clear()
            textRun.appendAscii(codePoint)
            drawAsciiRun(g, metrics, visualColumn, baselineY, fontStyle, fontRenderContext)
        } else if (!platformEmojiPainter.paintCodePoint(g, codePoint, visualColumn, row, columnSpan, metrics)) {
            drawComplexCodePoint(
                g,
                codePoint,
                fontStyle,
                visualColumn * metrics.cellWidth,
                metrics.cellWidth * columnSpan,
                baselineY,
                fontRenderContext,
            )
        }
    }

    private fun drawAsciiRun(
        g: Graphics2D,
        metrics: SwingMetrics,
        startColumn: Int,
        baselineY: Int,
        fontStyle: Int,
        fontRenderContext: FontRenderContext,
    ) {
        if (asciiDrawChars.canDrawChars(g.font, fontStyle, metrics.cellWidth, fontRenderContext)) {
            g.drawChars(textRun.chars, 0, textRun.length, startColumn * metrics.cellWidth, baselineY)
            return
        }

        val glyphVector =
            asciiGlyphVectors.glyphVector(
                chars = textRun.chars,
                offset = 0,
                length = textRun.length,
                font = g.font,
                style = fontStyle,
                cellWidth = metrics.cellWidth,
                fontRenderContext = fontRenderContext,
            )
        g.drawGlyphVector(glyphVector, (startColumn * metrics.cellWidth).toFloat(), baselineY.toFloat())
    }

    private fun drawComplexCluster(
        g: Graphics2D,
        codepoints: IntArray,
        offset: Int,
        length: Int,
        fontStyle: Int,
        x: Int,
        cellPixelWidth: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val shapedLength = minOf(length, TerminalComplexTextLayoutCache.MAX_CLUSTER_LENGTH)
        val baseline = baselineY.toFloat()
        var drawX = x.toFloat()

        val layout =
            complexTextLayouts
                .clusterLayout(codepoints, offset, shapedLength, fontStyle, fontRenderContext, fontCache)
        drawFittedLayout(g, layout, drawX, baseline, x + cellPixelWidth)
        drawX += minOf(layout.advance, cellPixelWidth.toFloat())

        var index = offset + shapedLength
        val end = offset + length
        while (index < end) {
            val codePointLayout =
                complexTextLayouts
                    .codePointLayout(codepoints[index], fontStyle, fontRenderContext, fontCache)
            drawFittedLayout(g, codePointLayout, drawX, baseline, x + cellPixelWidth)
            drawX += minOf(codePointLayout.advance, maxOf(0f, x + cellPixelWidth - drawX))
            index++
        }
    }

    private fun drawComplexCodePoint(
        g: Graphics2D,
        codePoint: Int,
        fontStyle: Int,
        x: Int,
        cellPixelWidth: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val layout =
            complexTextLayouts
                .codePointLayout(codePoint, fontStyle, fontRenderContext, fontCache)
        drawFittedLayout(g, layout, x.toFloat(), baselineY.toFloat(), x + cellPixelWidth)
    }

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
        private const val NO_HYPERLINK_ID = 0
        private const val DEFAULT_HOVER_START_ROW = 0
        private const val DEFAULT_HOVER_END_ROW = Int.MAX_VALUE
        private const val DEFAULT_HOVER_END_COLUMN = Int.MAX_VALUE
        private const val DEFAULT_HYPERLINK_ACTIVATION_FOREGROUND = 0xFF4DA3FF.toInt()
    }
}

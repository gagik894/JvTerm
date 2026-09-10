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
import io.github.ketraterm.render.api.TerminalRenderAttrs
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
) {
    private val fontCache = FontCache(fontResolver = fontResolver)
    private val complexTextLayouts = TerminalComplexTextLayoutCache()
    private val asciiGlyphVectors = TerminalAsciiGlyphVectorCache()
    private val asciiDrawChars = TerminalAsciiDrawCharsCache()
    private val cellPrimitives = TerminalCellPrimitivePainter()
    private val textRun = TerminalTextRunBuffer(INITIAL_TEXT_RUN_CAPACITY)
    private val shapedTextRuns =
        TerminalShapedTextRunPainter(
            colorCache = colorCache,
            decorationPainter = decorationPainter,
            fontCache = fontCache,
            complexTextLayouts = complexTextLayouts,
        )

    /**
     * Updates font-dependent caches for a settings snapshot.
     */
    fun updateSettings(settings: SwingSettings) {
        if (fontCache.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts)) {
            complexTextLayouts.clear()
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
        if (shapedTextRuns.cachedRowContainsStrongRtl(cache, row)) {
            shapedTextRuns.paintBidiRow(
                g = g,
                cache = cache,
                palette = palette,
                metrics = metrics,
                row = row,
                fontRenderContext = fontRenderContext,
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
            return
        }

        val flagsPlane = cache.flags
        val attrWords = cache.attrWords
        val codeWords = cache.codeWords
        val rowOffset = cache.rowOffset(row)
        val baselineY = row * metrics.cellHeight + metrics.baseline
        var column = 0

        while (column < cache.columns) {
            val index = rowOffset + column
            val flags = flagsPlane[index]
            if (!hasDrawableText(flags)) {
                column++
                continue
            }

            if (isTextHidden(attrWords[index], textBlinkVisible)) {
                column++
                continue
            }

            val codeWord = codeWords[index]
            column =
                when {
                    isFastAsciiCell(flags, codeWord) ->
                        paintAsciiRun(
                            g = g,
                            cache = cache,
                            palette = palette,
                            metrics = metrics,
                            row = row,
                            startColumn = column,
                            baselineY = baselineY,
                            fontRenderContext = fontRenderContext,
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

                    shapedTextRuns.isComplexShapingCell(cache, index) ->
                        shapedTextRuns.paintComplexShapingRun(
                            g = g,
                            cache = cache,
                            palette = palette,
                            metrics = metrics,
                            row = row,
                            startColumn = column,
                            baselineY = baselineY,
                            fontRenderContext = fontRenderContext,
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

                    else ->
                        paintComplexCell(
                            g = g,
                            cache = cache,
                            palette = palette,
                            metrics = metrics,
                            row = row,
                            column = column,
                            baselineY = baselineY,
                            fontRenderContext = fontRenderContext,
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
                }
        }
    }

    /**
     * Paints one cell's text clipped to a block cursor cell.
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
    ) {
        val flagsPlane = cache.flags
        val attrWords = cache.attrWords
        val codeWords = cache.codeWords
        val clusterRefs = cache.clusterRefs
        val index = cache.rowOffset(row) + column
        val flags = flagsPlane[index]
        if (!hasDrawableText(flags)) return

        val attr = attrWords[index]
        if (isTextHidden(attr, textBlinkVisible)) return

        val codeWord = codeWords[index]
        val isPrimitive = flags and TerminalRenderCellFlags.CLUSTER == 0 && cellPrimitives.canPaint(codeWord)
        if (isPrimitive) {
            g.color = colorCache.color(foreground)
            cellPrimitives.paint(g, codeWord, column, row, metrics)
        } else {
            val safeColumnSpan = maxOf(1, columnSpan)
            val oldClip = g.clip
            try {
                g.clipRect(
                    column * metrics.cellWidth,
                    row * metrics.cellHeight,
                    metrics.cellWidth * safeColumnSpan,
                    metrics.cellHeight,
                )
                g.font = fontCache.font(terminalFontStyle(attr))
                g.color = colorCache.color(foreground)

                val baselineY = row * metrics.cellHeight + metrics.baseline
                if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                    val clusterRef = clusterRefs[index]
                    if (clusterRef != 0L) {
                        val offset = cache.clusterOffset(clusterRef)
                        val length = cache.clusterLength(clusterRef)
                        val paintedEmoji =
                            platformEmojiPainter.paintCluster(
                                g = g,
                                codepoints = cache.clusterCodepoints,
                                offset = offset,
                                length = length,
                                column = column,
                                row = row,
                                columnSpan = safeColumnSpan,
                                metrics = metrics,
                            )
                        if (!paintedEmoji) {
                            drawComplexCluster(
                                g = g,
                                codepoints = cache.clusterCodepoints,
                                offset = offset,
                                length = length,
                                fontStyle = terminalFontStyle(attr),
                                x = column * metrics.cellWidth,
                                cellPixelWidth = metrics.cellWidth * safeColumnSpan,
                                baselineY = baselineY,
                                fontRenderContext = fontRenderContext,
                            )
                        }
                    }
                } else if (platformEmojiPainter.paintCodePoint(
                        g = g,
                        codePoint = codeWord,
                        column = column,
                        row = row,
                        columnSpan = safeColumnSpan,
                        metrics = metrics,
                    )
                ) {
                    // Painted by the native platform text stack.
                } else {
                    drawComplexCodePoint(
                        g = g,
                        codePoint = codeWord,
                        fontStyle = terminalFontStyle(attr),
                        x = column * metrics.cellWidth,
                        cellPixelWidth = metrics.cellWidth * safeColumnSpan,
                        baselineY = baselineY,
                        fontRenderContext = fontRenderContext,
                    )
                }
            } finally {
                g.clip = oldClip
            }
        }
    }

    private fun paintAsciiRun(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        startColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
        textBlinkVisible: Boolean,
        hyperlinkIds: IntArray,
        hoveredHyperlinkId: Int,
        hoveredHyperlinkStartRow: Int,
        hoveredHyperlinkStartColumn: Int,
        hoveredHyperlinkEndRow: Int,
        hoveredHyperlinkEndColumn: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ): Int {
        val flagsPlane = cache.flags
        val attrWords = cache.attrWords
        val extraAttrWords = cache.extraAttrWords
        val codeWords = cache.codeWords
        val rowOffset = cache.rowOffset(row)
        val startIndex = rowOffset + startColumn
        val attr = attrWords[startIndex]
        val extraAttr = extraAttrWords[startIndex]
        val hyperlinkId = hyperlinkIds[startIndex]
        val hovered =
            isHoveredHyperlink(
                hyperlinkId,
                row,
                startColumn,
                hoveredHyperlinkId,
                hoveredHyperlinkStartRow,
                hoveredHyperlinkStartColumn,
                hoveredHyperlinkEndRow,
                hoveredHyperlinkEndColumn,
            )
        val foreground =
            effectiveForeground(
                palette = palette,
                attr = attr,
                hovered = hovered,
                hyperlinkActivationHover = hyperlinkActivationHover,
                hyperlinkActivationForeground = hyperlinkActivationForeground,
            )
        val fontStyle = terminalFontStyle(attr)
        val decoration = decorationKey(attr, extraAttr)
        var column = startColumn

        textRun.clear()
        while (column < cache.columns) {
            val index = rowOffset + column
            val flags = flagsPlane[index]
            val codeWord = codeWords[index]
            val currentAttr = attrWords[index]
            val currentExtraAttr = extraAttrWords[index]
            val currentHyperlinkId = hyperlinkIds[index]
            val currentTextHidden = isTextHidden(currentAttr, textBlinkVisible)
            val currentHovered =
                isHoveredHyperlink(
                    currentHyperlinkId,
                    row,
                    column,
                    hoveredHyperlinkId,
                    hoveredHyperlinkStartRow,
                    hoveredHyperlinkStartColumn,
                    hoveredHyperlinkEndRow,
                    hoveredHyperlinkEndColumn,
                )
            val currentForeground =
                effectiveForeground(
                    palette = palette,
                    attr = currentAttr,
                    hovered = currentHovered,
                    hyperlinkActivationHover = hyperlinkActivationHover,
                    hyperlinkActivationForeground = hyperlinkActivationForeground,
                )
            if (
                currentTextHidden ||
                !isFastAsciiCell(flags, codeWord) ||
                currentForeground != foreground ||
                terminalFontStyle(currentAttr) != fontStyle ||
                decorationKey(currentAttr, currentExtraAttr) != decoration ||
                currentHyperlinkId != hyperlinkId
            ) {
                break
            }

            textRun.appendAscii(codeWord)
            column++
        }

        g.font = fontCache.font(fontStyle)
        g.color = colorCache.color(foreground)
        drawAsciiRun(g, metrics, startColumn, baselineY, fontStyle, fontRenderContext)
        decorationPainter.paint(g, palette, attr, extraAttr, foreground, startColumn, column, row, metrics)
        paintHyperlinkDecoration(
            g = g,
            hyperlinkId = hyperlinkId,
            hovered = hovered,
            color = foreground,
            startColumn = startColumn,
            endColumn = column,
            row = row,
            metrics = metrics,
        )
        return column
    }

    private fun paintComplexCell(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        column: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
        textBlinkVisible: Boolean,
        hyperlinkIds: IntArray,
        hoveredHyperlinkId: Int,
        hoveredHyperlinkStartRow: Int,
        hoveredHyperlinkStartColumn: Int,
        hoveredHyperlinkEndRow: Int,
        hoveredHyperlinkEndColumn: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ): Int {
        val flagsPlane = cache.flags
        val attrWords = cache.attrWords
        val extraAttrWords = cache.extraAttrWords
        val codeWords = cache.codeWords
        val clusterRefs = cache.clusterRefs
        val index = cache.rowOffset(row) + column
        val flags = flagsPlane[index]
        val attr = attrWords[index]
        if (isTextHidden(attr, textBlinkVisible)) return endColumnForHiddenCell(cache, flags, column)

        val extraAttr = extraAttrWords[index]
        val hyperlinkId = hyperlinkIds[index]
        val hovered =
            isHoveredHyperlink(
                hyperlinkId,
                row,
                column,
                hoveredHyperlinkId,
                hoveredHyperlinkStartRow,
                hoveredHyperlinkStartColumn,
                hoveredHyperlinkEndRow,
                hoveredHyperlinkEndColumn,
            )
        val codeWord = codeWords[index]
        val foreground =
            effectiveForeground(
                palette = palette,
                attr = attr,
                codePoint = codeWord,
                hovered = hovered,
                hyperlinkActivationHover = hyperlinkActivationHover,
                hyperlinkActivationForeground = hyperlinkActivationForeground,
            )
        val fontStyle = terminalFontStyle(attr)
        val endColumn = minOf(cache.columns, column + cellSpan(flags))
        val isPrimitive = flags and TerminalRenderCellFlags.CLUSTER == 0 && cellPrimitives.canPaint(codeWord)

        if (isPrimitive) {
            g.color = colorCache.color(foreground)
            cellPrimitives.paint(g, codeWord, column, row, metrics)
            decorationPainter.paint(g, palette, attr, extraAttr, foreground, column, endColumn, row, metrics)
            paintHyperlinkDecoration(
                g = g,
                hyperlinkId = hyperlinkId,
                hovered = hovered,
                color = foreground,
                startColumn = column,
                endColumn = endColumn,
                row = row,
                metrics = metrics,
            )
        } else {
            val oldClip = g.clip
            g.font = fontCache.font(fontStyle)
            g.color = colorCache.color(foreground)
            try {
                g.clipRect(
                    column * metrics.cellWidth,
                    row * metrics.cellHeight,
                    metrics.cellWidth * (endColumn - column),
                    metrics.cellHeight,
                )

                if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                    val clusterRef = clusterRefs[index]
                    if (clusterRef != 0L) {
                        val offset = cache.clusterOffset(clusterRef)
                        val length = cache.clusterLength(clusterRef)
                        val paintedEmoji =
                            platformEmojiPainter.paintCluster(
                                g = g,
                                codepoints = cache.clusterCodepoints,
                                offset = offset,
                                length = length,
                                column = column,
                                row = row,
                                columnSpan = endColumn - column,
                                metrics = metrics,
                            )
                        if (!paintedEmoji) {
                            drawComplexCluster(
                                g = g,
                                codepoints = cache.clusterCodepoints,
                                offset = offset,
                                length = length,
                                fontStyle = fontStyle,
                                x = column * metrics.cellWidth,
                                cellPixelWidth = metrics.cellWidth * (endColumn - column),
                                baselineY = baselineY,
                                fontRenderContext = fontRenderContext,
                            )
                        }
                    }
                } else if (platformEmojiPainter.paintCodePoint(
                        g = g,
                        codePoint = codeWord,
                        column = column,
                        row = row,
                        columnSpan = endColumn - column,
                        metrics = metrics,
                    )
                ) {
                    // Painted by the native platform text stack.
                } else {
                    drawComplexCodePoint(
                        g = g,
                        codePoint = codeWord,
                        fontStyle = fontStyle,
                        x = column * metrics.cellWidth,
                        cellPixelWidth = metrics.cellWidth * (endColumn - column),
                        baselineY = baselineY,
                        fontRenderContext = fontRenderContext,
                    )
                }

                decorationPainter.paint(g, palette, attr, extraAttr, foreground, column, endColumn, row, metrics)
                paintHyperlinkDecoration(
                    g = g,
                    hyperlinkId = hyperlinkId,
                    hovered = hovered,
                    color = foreground,
                    startColumn = column,
                    endColumn = endColumn,
                    row = row,
                    metrics = metrics,
                )
            } finally {
                g.clip = oldClip
            }
        }
        return endColumn
    }

    private fun isHoveredHyperlink(
        hyperlinkId: Int,
        row: Int,
        column: Int,
        hoveredHyperlinkId: Int,
        hoveredHyperlinkStartRow: Int,
        hoveredHyperlinkStartColumn: Int,
        hoveredHyperlinkEndRow: Int,
        hoveredHyperlinkEndColumn: Int,
    ): Boolean =
        hyperlinkId != NO_HYPERLINK_ID &&
            hyperlinkId == hoveredHyperlinkId &&
            row >= hoveredHyperlinkStartRow &&
            row <= hoveredHyperlinkEndRow &&
            (row > hoveredHyperlinkStartRow || column >= hoveredHyperlinkStartColumn) &&
            (row < hoveredHyperlinkEndRow || column < hoveredHyperlinkEndColumn)

    private fun effectiveForeground(
        palette: TerminalColorPalette,
        attr: Long,
        codePoint: Int = 0,
        hovered: Boolean,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ): Int =
        if (hovered && hyperlinkActivationHover) {
            hyperlinkActivationForeground
        } else {
            SwingColors.foreground(palette, attr, codePoint)
        }

    private fun endColumnForHiddenCell(
        cache: TerminalRenderCache,
        flags: Int,
        column: Int,
    ): Int = minOf(cache.columns, column + cellSpan(flags))

    private fun paintHyperlinkDecoration(
        g: Graphics2D,
        hyperlinkId: Int,
        hovered: Boolean,
        color: Int,
        startColumn: Int,
        endColumn: Int,
        row: Int,
        metrics: SwingMetrics,
    ) {
        if (hyperlinkId == NO_HYPERLINK_ID) return
        decorationPainter.paintHyperlink(
            g = g,
            color = color,
            startColumn = startColumn,
            endColumn = endColumn,
            row = row,
            metrics = metrics,
            hovered = hovered,
        )
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

    private fun decorationKey(
        attr: Long,
        extraAttr: Long,
    ): Long =
        TerminalRenderAttrs.underlineStyle(attr).toLong() or
            (if (TerminalRenderAttrs.isStrikethrough(attr)) STRIKETHROUGH_KEY else 0L) or
            (extraAttr shl EXTRA_ATTR_KEY_SHIFT)

    private companion object {
        private const val INITIAL_TEXT_RUN_CAPACITY = 256
        private const val STRIKETHROUGH_KEY = 1L shl 8
        private const val EXTRA_ATTR_KEY_SHIFT = 9
        private const val NO_HYPERLINK_ID = 0
        private const val DEFAULT_HOVER_START_ROW = 0
        private const val DEFAULT_HOVER_END_ROW = Int.MAX_VALUE
        private const val DEFAULT_HOVER_END_COLUMN = Int.MAX_VALUE
        private const val DEFAULT_HYPERLINK_ACTIVATION_FOREGROUND = 0xFF4DA3FF.toInt()
    }
}

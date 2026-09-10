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

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.api.TerminalFontResolver
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.painter.*
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Java2D renderer facade for cached primitive terminal frames.
 *
 * The facade owns renderer-local caches and delegates row backgrounds, text,
 * decorations, and cursor presentation to smaller collaborators. Callers keep a
 * single component-owned painter instance so those caches are reused across
 * paint calls.
 */
internal class GridPainter(
    fontResolver: TerminalFontResolver? = null,
    private val cellGeometry: TerminalBidiLayout = TerminalBidiLayout(),
) {
    private val colorCache = AwtColorCache()
    private val backgroundPainter = TerminalBackgroundPainter(colorCache)
    private val selectionPainter = TerminalSelectionPainter(colorCache)
    private val searchPainter = TerminalSearchPainter(colorCache)
    private val shellIntegrationDecorationPainter = TerminalShellIntegrationDecorationPainter(colorCache)
    private val decorationPainter = TerminalDecorationPainter(colorCache)
    private val textPainter = TerminalTextPainter(colorCache, decorationPainter, fontResolver = fontResolver, cellGeometry = cellGeometry)
    private val cursorPainter = TerminalCursorPainter(colorCache, textPainter)
    private val clipScratch = Rectangle()

    /**
     * Clears [width] x [height] with the terminal default background.
     */
    fun clear(
        g: Graphics2D,
        palette: TerminalColorPalette,
        width: Int,
        height: Int,
    ) {
        backgroundPainter.clear(g, palette, width, height)
    }

    /**
     * Paints [cache] into the supplied graphics context.
     */
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        settings: SwingSettings,
        metrics: SwingMetrics,
        width: Int,
        height: Int,
        cursorBlinkVisible: Boolean,
        textBlinkVisible: Boolean = true,
        cursorVisible: Boolean = true,
        visualGeometry: TerminalVisualViewportGeometry? = null,
        selection: CellSelection? = null,
        searchHighlights: TerminalSearchViewportHighlights? = null,
        shellIntegrationDecorations: TerminalShellIntegrationViewportDecorations? = null,
        hoveredPromptMarkerRow: Int = -1,
        hyperlinkIds: IntArray = cache.hyperlinkIds,
        hoveredHyperlinkId: Int = 0,
        hoveredHyperlinkStartRow: Int = 0,
        hoveredHyperlinkStartColumn: Int = 0,
        hoveredHyperlinkEndRow: Int = Int.MAX_VALUE,
        hoveredHyperlinkEndColumn: Int = Int.MAX_VALUE,
        hyperlinkActivationHover: Boolean = false,
    ) {
        val palette = cache.palette
        textPainter.updateSettings(settings)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, TEXT_LCD_CONTRAST)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
        g.font = textPainter.font(Font.PLAIN)
        val fontRenderContext = g.fontRenderContext

        val clip = g.getClipBounds(clipScratch)
        backgroundPainter.clear(g, palette, width, height)

        val paddingLeft = SwingTerminalChrome.left(settings, cache.activeBuffer)
        val paddingTop = SwingTerminalChrome.top(settings, cache.activeBuffer)
        val paddingBottom = SwingTerminalChrome.bottom(settings, cache.activeBuffer)
        val gridPaintHeight = height - paddingTop - paddingBottom
        if (gridPaintHeight <= 0) return

        val promptGutterWidth = SwingTerminalChrome.promptDecorationGutterWidth(settings, cache.activeBuffer)
        val geometry = visualGeometry?.takeIf { it.rowCount == cache.rows }
        val shellDecorations =
            if (cache.activeBuffer == TerminalRenderBufferKind.ALTERNATE || promptGutterWidth <= 0) {
                null
            } else {
                shellIntegrationDecorations
            }
        val contentOriginY = geometry?.contentOriginY ?: 0.0
        val firstRow = firstPaintRow(clip, metrics, contentOriginY, paddingTop, geometry)
        val rows = lastPaintRowExclusive(clip, cache, metrics, height, contentOriginY, paddingTop, paddingBottom, geometry)
        val originalClip = g.clip
        g.clipRect(0, paddingTop, width, gridPaintHeight)
        g.translate(paddingLeft.toDouble(), paddingTop.toDouble() + contentOriginY)
        try {
            var row = firstRow
            while (row < rows) {
                val bidi = cellGeometry.row(cache, row)
                backgroundPainter.paintRow(g, cache, palette, metrics, row, bidi)
                shellIntegrationDecorationPainter.paint(
                    g = g,
                    settings = settings,
                    metrics = metrics,
                    decorations = shellDecorations,
                    gutterWidth = promptGutterWidth,
                    row = row,
                    hovered = row == hoveredPromptMarkerRow,
                    palette = palette,
                )
                searchPainter.paint(
                    g = g,
                    metrics = metrics,
                    row = row,
                    highlights = searchHighlights,
                    matchBackground = settings.searchMatchBackground,
                    activeMatchBackground = settings.searchActiveMatchBackground,
                    bidi = bidi,
                )
                selectionPainter.paint(g, cache, metrics, row, selection, settings.selectionBackground, palette, bidi)
                textPainter.paintRow(
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
                    hyperlinkActivationForeground = settings.hyperlinkActivationForeground,
                )
                row++
            }

            cursorPainter.paint(
                g,
                cache,
                palette,
                metrics,
                cursorBlinkVisible,
                textBlinkVisible,
                fontRenderContext,
                cursorVisible = cursorVisible,
                bidi = cellGeometry.row(cache, cache.cursorRow),
            )
        } finally {
            g.translate(-paddingLeft.toDouble(), -(paddingTop.toDouble() + contentOriginY))
            g.clip = originalClip
        }
    }

    private fun firstPaintRow(
        clip: Rectangle?,
        metrics: SwingMetrics,
        contentOriginY: Double,
        paddingTop: Int,
        geometry: TerminalVisualViewportGeometry?,
    ): Int {
        if (clip == null) return 0
        if (geometry != null) {
            return geometry.firstPaintRow(clip, paddingTop)
        }
        return maxOf(0, floor((clip.y - paddingTop - contentOriginY) / metrics.cellHeight).toInt())
    }

    private fun lastPaintRowExclusive(
        clip: Rectangle?,
        cache: TerminalRenderCache,
        metrics: SwingMetrics,
        componentHeight: Int,
        contentOriginY: Double,
        paddingTop: Int,
        paddingBottom: Int,
        geometry: TerminalVisualViewportGeometry?,
    ): Int {
        val availableHeight = componentHeight - paddingTop - paddingBottom
        val visibleRows =
            if (geometry != null) {
                geometry.visibleRowsExclusive(componentHeight, paddingTop, paddingBottom)
            } else {
                minOf(cache.rows, maxOf(1, availableHeight / metrics.cellHeight) + 2)
            }
        if (clip == null || clip.height <= 0) return visibleRows

        val clipBottom = clip.y + clip.height
        if (clipBottom <= 0) return 0
        val clippedRows =
            if (geometry != null) {
                geometry.lastPaintRowExclusive(clip, componentHeight, paddingTop, paddingBottom)
            } else {
                ceil((clipBottom - paddingTop - contentOriginY) / metrics.cellHeight).toInt()
            }
        return clippedRows.coerceIn(0, visibleRows)
    }

    private companion object {
        private const val TEXT_LCD_CONTRAST = 140
    }
}

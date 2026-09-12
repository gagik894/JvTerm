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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D

/**
 * Paints text decorations exported through render attribute words.
 */
internal class TerminalDecorationPainter(
    private val colorCache: AwtColorCache,
) {
    /**
     * Paints terminal and hyperlink decorations using the style retained by the run scanner.
     */
    fun paintTextRun(
        g: Graphics2D,
        palette: TerminalColorPalette,
        style: TerminalTextRunStyle,
        startColumn: Int,
        endColumn: Int,
        row: Int,
        metrics: SwingMetrics,
    ) {
        if (style.textHidden) return
        paint(g, palette, style.attr, style.extraAttr, style.foreground, startColumn, endColumn, row, metrics)
        if (style.hyperlinkId > 0 || style.hovered) {
            // A terminal-authored underline owns its decoration and color. The activation
            // foreground still indicates hover without painting over that underline.
            if (TerminalRenderAttrs.underlineStyle(style.attr) == TerminalRenderUnderline.NONE) {
                paintHyperlink(g, style.foreground, startColumn, endColumn, row, metrics, style.hovered)
            }
        }
    }

    /**
     * Paints underline, strikethrough, and overline for a contiguous cell span.
     */
    fun paint(
        g: Graphics2D,
        palette: TerminalColorPalette,
        attr: Long,
        extraAttr: Long,
        foreground: Int,
        startColumn: Int,
        endColumn: Int,
        row: Int,
        metrics: SwingMetrics,
    ) {
        val underline = TerminalRenderAttrs.underlineStyle(attr)
        val strikethrough = TerminalRenderAttrs.isStrikethrough(attr)
        val overline = TerminalRenderExtraAttrs.isOverline(extraAttr)
        if (underline == TerminalRenderUnderline.NONE && !strikethrough && !overline) return

        val x = startColumn * metrics.cellWidth
        val width = (endColumn - startColumn) * metrics.cellWidth
        val rowY = row * metrics.cellHeight

        if (underline != TerminalRenderUnderline.NONE) {
            g.color = colorCache.color(underlineColor(palette, extraAttr, foreground))
            val y = rowY + metrics.underlineY
            g.fillRect(x, y, width, DECORATION_THICKNESS)
            if (underline == TerminalRenderUnderline.DOUBLE) {
                val secondY = minOf(rowY + metrics.cellHeight - 1, y + DOUBLE_UNDERLINE_OFFSET)
                g.fillRect(x, secondY, width, DECORATION_THICKNESS)
            }
        }

        if (strikethrough) {
            g.color = colorCache.color(foreground)
            val y = rowY + metrics.strikethroughY
            g.fillRect(x, y, width, DECORATION_THICKNESS)
        }

        if (overline) {
            g.color = colorCache.color(foreground)
            val y = rowY + metrics.overlineY
            g.fillRect(x, y, width, DECORATION_THICKNESS)
        }
    }

    /**
     * Paints the UI hyperlink affordance for a contiguous linked span.
     */
    fun paintHyperlink(
        g: Graphics2D,
        color: Int,
        startColumn: Int,
        endColumn: Int,
        row: Int,
        metrics: SwingMetrics,
        hovered: Boolean,
    ) {
        if (startColumn >= endColumn) return

        val x = startColumn * metrics.cellWidth
        val width = (endColumn - startColumn) * metrics.cellWidth
        val rowY = row * metrics.cellHeight
        val y = rowY + metrics.underlineY
        g.color = colorCache.color(color)
        if (hovered) {
            g.fillRect(x, y, width, minOf(HOVER_DECORATION_THICKNESS, metrics.cellHeight - metrics.underlineY))
        } else {
            // Anchor the pattern to the row, so text/style run boundaries cannot restart it.
            var dotX = x + (DOT_PERIOD - x % DOT_PERIOD) % DOT_PERIOD
            val endX = x + width
            while (dotX < endX) {
                g.fillRect(dotX, y, DECORATION_THICKNESS, DECORATION_THICKNESS)
                dotX += DOT_PERIOD
            }
        }
    }

    private fun underlineColor(
        palette: TerminalColorPalette,
        extraAttr: Long,
        defaultColor: Int,
    ): Int {
        val value = TerminalRenderExtraAttrs.underlineColorValue(extraAttr)
        return when (TerminalRenderExtraAttrs.underlineColorKind(extraAttr)) {
            TerminalRenderColorKind.DEFAULT -> defaultColor
            TerminalRenderColorKind.INDEXED -> palette.indexedColor(value)
            TerminalRenderColorKind.RGB -> 0xFF000000.toInt() or value
            else -> defaultColor
        }
    }

    private companion object {
        private const val DECORATION_THICKNESS = 1
        private const val HOVER_DECORATION_THICKNESS = 2
        private const val DOUBLE_UNDERLINE_OFFSET = 2
        private const val DOT_PERIOD = 3
    }
}

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
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.SwingColors
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D
import java.awt.geom.AffineTransform

/**
 * Paints terminal default clears and row background runs.
 */
internal class TerminalBackgroundPainter(
    private val colorCache: AwtColorCache,
) {
    /**
     * Clears a rectangular component area with the palette default background.
     */
    fun clear(
        g: Graphics2D,
        palette: TerminalColorPalette,
        width: Int,
        height: Int,
    ) {
        fill(g, 0, 0, width, height, palette.defaultBackground)
    }

    /**
     * Paints contiguous background runs for [row].
     */
    fun paintRow(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        bidi: TerminalBidiLayout.Row? = null,
    ) {
        val attrWords = cache.attrWords
        val rowOffset = cache.rowOffset(row)

        val transform = g.transform
        val scaleX = transform.scaleX
        val scaleY = transform.scaleY
        val transX = transform.translateX
        val transY = transform.translateY

        val top = row * metrics.cellHeight
        val bottom = (row + 1) * metrics.cellHeight
        val y1 = kotlin.math.round(top * scaleY + transY).toInt()
        val y2 = kotlin.math.round(bottom * scaleY + transY).toInt()

        var column = 0
        while (column < cache.columns) {
            val background = SwingColors.background(palette, attrWords[rowOffset + (bidi?.logicalColumn(column) ?: column)])
            val start = column

            column++
            while (
                column < cache.columns &&
                SwingColors.background(palette, attrWords[rowOffset + (bidi?.logicalColumn(column) ?: column)]) == background
            ) {
                column++
            }

            val left = start * metrics.cellWidth
            val right = column * metrics.cellWidth
            val x1 = kotlin.math.round(left * scaleX + transX).toInt()
            val x2 = kotlin.math.round(right * scaleX + transX).toInt()

            val oldTransform = g.transform
            g.transform = IDENTITY_TRANSFORM
            try {
                g.color = colorCache.color(background)
                g.fillRect(x1, y1, x2 - x1, y2 - y1)
            } finally {
                g.transform = oldTransform
            }
        }
    }

    private fun fill(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        argb: Int,
    ) {
        g.color = colorCache.color(argb)
        g.fillRect(x, y, width, height)
    }

    private companion object {
        private val IDENTITY_TRANSFORM = AffineTransform()
    }
}

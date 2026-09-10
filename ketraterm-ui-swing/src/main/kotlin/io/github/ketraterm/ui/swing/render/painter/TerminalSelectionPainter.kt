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
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.render.SwingColors
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.forEachVisualCellSpan
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D

/**
 * Paints selected terminal cell ranges as per-row rectangles.
 */
internal class TerminalSelectionPainter(
    private val colorCache: AwtColorCache,
) {
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        metrics: SwingMetrics,
        row: Int,
        selection: CellSelection?,
        selectionBackground: Int,
        palette: TerminalColorPalette,
        bidi: TerminalBidiLayout.Row? = null,
    ) {
        if (selection == null) return

        val range = selection.packedColumnRange(row, cache.columns, cache)
        if (range == CellSelection.NO_RANGE) return

        val startColumn = CellSelection.rangeStart(range)
        val endColumn = CellSelection.rangeEnd(range)

        val baseSelBg =
            if (selectionBackground == 0x66FFFFFF) {
                val raw = palette.selectionBackground
                val alpha = (raw ushr 24) and 0xFF
                if (alpha == 0xFF) {
                    (raw and 0x00FFFFFF) or 0x66000000
                } else {
                    raw
                }
            } else {
                selectionBackground
            }

        var selColor = baseSelBg
        val bg = palette.defaultBackground
        val blended = SwingColors.blendOver(selColor, bg)
        if (SwingColors.contrastRatio(blended, bg) < 1.15) {
            selColor =
                if (SwingColors.relativeLuminance(bg) < 0.5) {
                    0x66FFFFFF
                } else {
                    0x33000000
                }
        }

        g.color = colorCache.color(selColor)
        forEachVisualCellSpan(bidi, startColumn, endColumn) { visualStart, visualEnd ->
            g.fillRect(
                visualStart * metrics.cellWidth,
                row * metrics.cellHeight,
                (visualEnd - visualStart) * metrics.cellWidth,
                metrics.cellHeight,
            )
        }
    }
}

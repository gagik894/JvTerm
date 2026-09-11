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

import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Rectangle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * EDT-owned viewport geometry with fixed row pitch and shared bidi cell mapping.
 *
 * Terminal rows always have the same pitch as the PTY-visible grid:
 * `rowTop(row) = row * cellHeight`. Shell-integration prompt metadata is a
 * renderer decoration only and must not change row height, viewport capacity,
 * hit testing, cursor geometry, mouse coordinates, or scrollback math.
 */
internal class TerminalVisualViewportGeometry {
    /** Cell permutation shared by rendering, pointer input and repaint planning. */
    val bidiLayout = TerminalBidiLayout()

    var rowCount: Int = 0
        private set
    var cellHeight: Int = 0
        private set
    var visualHeight: Int = 0
        private set
    var viewportPixelHeight: Int = 0
        private set
    var contentOriginY: Double = 0.0
        private set

    /**
     * Rebuilds fixed row positions for the current render cache.
     *
     * @return true when fixed geometry metrics changed.
     */
    fun updateLayout(
        metrics: SwingMetrics,
        rows: Int,
        viewportPixelHeight: Int,
    ): Boolean {
        require(rows >= 0) { "rows must be >= 0, was $rows" }
        require(viewportPixelHeight >= 0) { "viewportPixelHeight must be >= 0, was $viewportPixelHeight" }

        val previousRowCount = rowCount
        val previousCellHeight = cellHeight
        val previousVisualHeight = visualHeight
        val previousViewportPixelHeight = this.viewportPixelHeight

        rowCount = rows
        cellHeight = metrics.cellHeight
        this.viewportPixelHeight = viewportPixelHeight
        visualHeight = rows * metrics.cellHeight

        val changed =
            previousRowCount != rows ||
                previousCellHeight != metrics.cellHeight ||
                previousVisualHeight != visualHeight ||
                previousViewportPixelHeight != viewportPixelHeight
        return changed
    }

    /**
     * Updates the current content origin.
     *
     * @return true when the origin changed.
     */
    fun updateContentOrigin(contentOriginY: Double): Boolean {
        require(!contentOriginY.isNaN()) { "contentOriginY must not be NaN" }
        if (this.contentOriginY == contentOriginY) return false
        this.contentOriginY = contentOriginY
        return true
    }

    /**
     * Clears retained row geometry.
     */
    fun reset() {
        bidiLayout.reset()
        rowCount = 0
        cellHeight = 0
        visualHeight = 0
        viewportPixelHeight = 0
        contentOriginY = 0.0
    }

    /**
     * Returns the fixed visual top of terminal [row], excluding [contentOriginY].
     */
    fun rowTop(row: Int): Int = row * cellHeight

    /**
     * Returns the fixed visual bottom of terminal [row], excluding [contentOriginY].
     */
    fun rowBottom(row: Int): Int = rowTop(row) + cellHeight

    /**
     * Returns the fixed visual height occupied by the first [rows] terminal rows.
     */
    fun visualHeightForRows(rows: Int): Int {
        val safeRows = rows.coerceIn(0, rowCount)
        return safeRows * cellHeight
    }

    /**
     * Maps visual local y, excluding [contentOriginY], to a terminal row.
     */
    fun rowAt(visualY: Int): Int {
        if (rowCount <= 0) return 0
        return (visualY / cellHeight).coerceIn(0, rowCount - 1)
    }

    /**
     * Maps a component-local y coordinate to a terminal row.
     */
    fun rowAtComponentY(
        y: Int,
        paddingTop: Int,
    ): Int = rowAt(floor(y.toDouble() - paddingTop.toDouble() - contentOriginY).toInt())

    /**
     * Converts visual local y into terminal-native pixel y.
     */
    fun terminalPixelY(
        visualY: Int,
        row: Int,
    ): Int {
        val safeRow = row.coerceIn(0, maxOf(0, rowCount - 1))
        val yInCell = (visualY - rowTop(safeRow)).coerceIn(0, maxOf(0, cellHeight - 1))
        return safeRow * cellHeight + yInCell
    }

    /**
     * Converts a component-local y coordinate into terminal-native pixel y.
     */
    fun terminalPixelYAtComponentY(
        y: Int,
        paddingTop: Int,
    ): Int {
        val localY = floor(y.toDouble() - paddingTop.toDouble() - contentOriginY).toInt()
        val row = rowAt(localY)
        return terminalPixelY(localY, row)
    }

    /**
     * Returns the first row that should be considered for painting [clip].
     */
    fun firstPaintRow(
        clip: Rectangle?,
        paddingTop: Int,
    ): Int {
        if (clip == null) return 0
        val localY = floor(clip.y.toDouble() - paddingTop.toDouble() - contentOriginY).toInt()
        return rowAt(localY)
    }

    /**
     * Returns one past the last row that should be considered for painting.
     */
    fun lastPaintRowExclusive(
        clip: Rectangle?,
        componentHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
    ): Int {
        val availableHeight = componentHeight - paddingTop - paddingBottom
        val visibleRows = minOf(rowCount, maxOf(1, ceil((availableHeight.toDouble() - contentOriginY) / cellHeight).toInt()) + 1)
        if (clip == null || clip.height <= 0) return visibleRows

        val clipBottom = clip.y + clip.height
        if (clipBottom <= 0) return 0
        val localBottom = ceil(clipBottom.toDouble() - paddingTop.toDouble() - contentOriginY).toInt()
        return (rowAt(localBottom) + 1).coerceIn(0, visibleRows)
    }

    /**
     * Returns one past the last row visible in the component.
     */
    fun visibleRowsExclusive(
        componentHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
    ): Int {
        val availableHeight = componentHeight - paddingTop - paddingBottom
        return minOf(rowCount, maxOf(1, ceil((availableHeight.toDouble() - contentOriginY) / cellHeight).toInt()) + 1)
    }

    /**
     * Returns the first fully visible row, useful for command navigation.
     */
    fun firstFullyVisibleRow(): Int {
        if (rowCount <= 0) return 0
        return ceil(-contentOriginY / cellHeight).toInt().coerceIn(0, rowCount - 1)
    }
}

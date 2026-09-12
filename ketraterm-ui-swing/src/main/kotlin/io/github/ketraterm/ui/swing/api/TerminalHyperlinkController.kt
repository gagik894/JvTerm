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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.cache.TerminalRenderCache
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

internal interface TerminalHyperlinkHost {
    val renderCache: TerminalRenderCache
    var cursor: Cursor

    fun cellAt(
        x: Int,
        y: Int,
    ): Long

    fun repaintHyperlinkSpan(
        startRow: Int,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
    )

    fun hyperlinkIdAt(
        row: Int,
        column: Int,
    ): Int

    fun isHyperlinkResolvable(hyperlinkId: Int): Boolean

    fun openHyperlink(hyperlinkId: Int): Boolean
}

internal class TerminalHyperlinkController(
    private val host: TerminalHyperlinkHost,
) {
    companion object {
        private const val NO_HYPERLINK_ID = 0
        private const val NO_HYPERLINK_ROW = -1
        private val HAND_CURSOR: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        private val DEFAULT_CURSOR: Cursor = Cursor.getDefaultCursor()

        private fun unpackCellColumn(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }

    var hoveredHyperlinkId: Int = NO_HYPERLINK_ID
        private set
    var hoveredHyperlinkStartRow: Int = NO_HYPERLINK_ROW
        private set
    var hoveredHyperlinkStartColumn: Int = 0
        private set
    var hoveredHyperlinkEndRow: Int = NO_HYPERLINK_ROW
        private set
    var hoveredHyperlinkEndColumn: Int = 0
        private set
    var hyperlinkActivationHover: Boolean = false
        private set
    private var pointerKnown = false
    private var pointerX = 0
    private var pointerY = 0
    private var controlDown = false

    fun handleMouseMoved(event: MouseEvent) {
        pointerKnown = true
        pointerX = event.x
        pointerY = event.y
        controlDown = event.isControlDown
        refreshHyperlinkHover()
    }

    fun handleMouseExited() {
        clearHyperlinkHover()
    }

    fun handleMousePressed(event: MouseEvent): Boolean {
        if (!SwingUtilities.isLeftMouseButton(event) || !event.isControlDown) return false
        val hyperlinkId = hyperlinkIdAt(event)
        if (hyperlinkId == NO_HYPERLINK_ID) return false
        if (!host.openHyperlink(hyperlinkId)) return false
        event.consume()
        return true
    }

    fun hyperlinkIdAt(event: MouseEvent): Int {
        val cache = host.renderCache
        if (cache.columns <= 0 || cache.rows <= 0) return NO_HYPERLINK_ID
        return resolvableHyperlinkIdAt(host.cellAt(event.x, event.y))
    }

    fun updateHyperlinkActivationHover(active: Boolean) {
        controlDown = active
        if (hoveredHyperlinkId == NO_HYPERLINK_ID || hyperlinkActivationHover == active) return
        hyperlinkActivationHover = active
        repaintHyperlinkSpan(
            hoveredHyperlinkId,
            hoveredHyperlinkStartRow,
            hoveredHyperlinkStartColumn,
            hoveredHyperlinkEndRow,
            hoveredHyperlinkEndColumn,
        )
    }

    fun clearHyperlinkHover() {
        pointerKnown = false
        controlDown = false
        clearHoveredSpan()
        applyHyperlinkHover(NO_HYPERLINK_ID, activationHover = false)
    }

    /** Re-hit-tests the last pointer position after frame, geometry, or detected-link changes. */
    fun refreshHyperlinkHover() {
        if (!pointerKnown) return
        val cache = host.renderCache
        val cell = if (cache.columns > 0 && cache.rows > 0) host.cellAt(pointerX, pointerY) else -1L
        val hyperlinkId = resolvableHyperlinkIdAt(cell)
        if (hyperlinkId != NO_HYPERLINK_ID) {
            resolveHoveredSpan(cell, hyperlinkId)
        } else {
            clearHoveredSpan()
        }
        applyHyperlinkHover(hyperlinkId, activationHover = controlDown)
    }

    private fun applyHyperlinkHover(
        hyperlinkId: Int,
        activationHover: Boolean,
    ) {
        val normalizedActivationHover = hyperlinkId != NO_HYPERLINK_ID && activationHover
        val previousHyperlinkId = hoveredHyperlinkId
        val previousStartRow = hoveredHyperlinkStartRow
        val previousStartColumn = hoveredHyperlinkStartColumn
        val previousEndRow = hoveredHyperlinkEndRow
        val previousEndColumn = hoveredHyperlinkEndColumn
        val changed =
            hoveredHyperlinkId != hyperlinkId ||
                hyperlinkActivationHover != normalizedActivationHover ||
                hoveredHyperlinkStartRow != pendingHoverStartRow ||
                hoveredHyperlinkStartColumn != pendingHoverStartColumn ||
                hoveredHyperlinkEndRow != pendingHoverEndRow ||
                hoveredHyperlinkEndColumn != pendingHoverEndColumn
        hoveredHyperlinkId = hyperlinkId
        hoveredHyperlinkStartRow = pendingHoverStartRow
        hoveredHyperlinkStartColumn = pendingHoverStartColumn
        hoveredHyperlinkEndRow = pendingHoverEndRow
        hoveredHyperlinkEndColumn = pendingHoverEndColumn
        hyperlinkActivationHover = normalizedActivationHover
        val nextCursor = if (hyperlinkId != NO_HYPERLINK_ID) HAND_CURSOR else DEFAULT_CURSOR
        if (host.cursor !== nextCursor) host.cursor = nextCursor
        if (changed) {
            repaintHyperlinkSpan(previousHyperlinkId, previousStartRow, previousStartColumn, previousEndRow, previousEndColumn)
            repaintHyperlinkSpan(hyperlinkId, pendingHoverStartRow, pendingHoverStartColumn, pendingHoverEndRow, pendingHoverEndColumn)
        }
    }

    private fun repaintHyperlinkSpan(
        hyperlinkId: Int,
        startRow: Int,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
    ) {
        if (hyperlinkId == NO_HYPERLINK_ID || startRow == NO_HYPERLINK_ROW || endRow == NO_HYPERLINK_ROW) return
        host.repaintHyperlinkSpan(startRow, startColumn, endRow, endColumn)
    }

    private fun resolvableHyperlinkIdAt(cell: Long): Int {
        val cache = host.renderCache
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)
        if (row !in 0 until cache.rows || column !in 0 until cache.columns) return NO_HYPERLINK_ID
        val hyperlinkId = hyperlinkIdAt(row, column)
        if (hyperlinkId == NO_HYPERLINK_ID) return NO_HYPERLINK_ID
        return if (host.isHyperlinkResolvable(hyperlinkId)) hyperlinkId else NO_HYPERLINK_ID
    }

    private var pendingHoverStartRow: Int = NO_HYPERLINK_ROW
    private var pendingHoverStartColumn: Int = 0
    private var pendingHoverEndRow: Int = NO_HYPERLINK_ROW
    private var pendingHoverEndColumn: Int = 0

    private fun resolveHoveredSpan(
        cell: Long,
        hyperlinkId: Int,
    ) {
        val cache = host.renderCache
        var startColumn = unpackCellColumn(cell)
        var startRow = unpackCellRow(cell)
        var endColumn = startColumn + 1
        var endRow = startRow

        while (startColumn > 0 && hyperlinkIdAt(startRow, startColumn - 1) == hyperlinkId) {
            startColumn--
        }
        while (
            startColumn == 0 &&
            startRow > 0 &&
            cache.lineWrapped[startRow - 1] &&
            hyperlinkIdAt(startRow - 1, cache.columns - 1) == hyperlinkId
        ) {
            startRow--
            startColumn = cache.columns - 1
            while (startColumn > 0 && hyperlinkIdAt(startRow, startColumn - 1) == hyperlinkId) {
                startColumn--
            }
        }

        while (endColumn < cache.columns && hyperlinkIdAt(endRow, endColumn) == hyperlinkId) {
            endColumn++
        }
        while (
            endColumn == cache.columns &&
            endRow + 1 < cache.rows &&
            cache.lineWrapped[endRow] &&
            hyperlinkIdAt(endRow + 1, 0) == hyperlinkId
        ) {
            endRow++
            endColumn = 1
            while (endColumn < cache.columns && hyperlinkIdAt(endRow, endColumn) == hyperlinkId) {
                endColumn++
            }
        }

        pendingHoverStartRow = startRow
        pendingHoverStartColumn = startColumn
        pendingHoverEndRow = endRow
        pendingHoverEndColumn = endColumn
    }

    private fun clearHoveredSpan() {
        pendingHoverStartRow = NO_HYPERLINK_ROW
        pendingHoverStartColumn = 0
        pendingHoverEndRow = NO_HYPERLINK_ROW
        pendingHoverEndColumn = 0
    }

    private fun hyperlinkIdAt(
        row: Int,
        column: Int,
    ): Int = host.hyperlinkIdAt(row, column)
}

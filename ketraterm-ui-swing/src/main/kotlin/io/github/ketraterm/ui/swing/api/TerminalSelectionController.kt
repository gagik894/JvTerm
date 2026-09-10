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
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

internal interface TerminalSelectionHost {
    val settings: SwingSettings
    val metrics: SwingMetrics
    val renderCache: TerminalRenderCache
    val contentYOffset: Double
    val componentWidth: Int
    val componentHeight: Int

    /** Hits a logical cell after applying the row's bidi mapping. */
    fun cellAt(
        x: Int,
        y: Int,
    ): Long

    /** Hits a visual cell before the row's bidi mapping is applied. */
    fun visualCellAt(
        x: Int,
        y: Int,
    ): Long

    fun scrollViewportByRows(deltaRows: Int): Boolean

    fun repaint()

    fun requestFocusInWindow(): Boolean
}

internal class TerminalSelectionController(
    private val host: TerminalSelectionHost,
) {
    private val selectionTextExtractor = TerminalSelectionTextExtractor()

    // Alt can change during a drag after its anchor row has left the viewport.
    // Retain both cell coordinates; selectionAnchorColumn is a half-open edge.
    private var dragAnchorLogicalColumn: Int = 0
    private var dragAnchorVisualColumn: Int = 0

    var selectionAnchorAbsoluteRow: Long? = null
        private set
    var selectionAnchorColumn: Int = 0
        private set
    var selectionCaretAbsoluteRow: Long? = null
        private set
    var selectionCaretColumn: Int = 0
        private set
    var selectingWithMouse: Boolean = false
        private set
    var selectionIsBlock: Boolean = false
        private set
    var lastSelectionDragX: Int = 0
        private set
    var lastSelectionDragY: Int = 0
        private set

    private val selectionAutoscrollTimer =
        Timer(50) {
            handleSelectionAutoscrollTick()
        }

    fun clearSelection() {
        stopSelectionDrag()
        selectionAnchorAbsoluteRow = null
        selectionCaretAbsoluteRow = null
    }

    fun selectAbsoluteRows(
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        columns: Int,
    ) {
        require(startAbsoluteRow >= 0L) { "startAbsoluteRow must be >= 0, was $startAbsoluteRow" }
        require(endAbsoluteRow >= startAbsoluteRow) {
            "endAbsoluteRow must be >= startAbsoluteRow, was start=$startAbsoluteRow end=$endAbsoluteRow"
        }
        require(columns > 0) { "columns must be > 0, was $columns" }

        stopSelectionDrag()
        selectionIsBlock = false
        selectionAnchorAbsoluteRow = startAbsoluteRow
        selectionAnchorColumn = 0
        selectionCaretAbsoluteRow = endAbsoluteRow
        selectionCaretColumn = columns
    }

    fun stopSelectionDrag() {
        selectingWithMouse = false
        selectionAutoscrollTimer.stop()
    }

    fun handleSelectionMousePressed(event: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(event)) return
        host.requestFocusInWindow()

        selectingWithMouse = true
        selectionIsBlock = event.isAltDown && event.clickCount < 2
        lastSelectionDragX = event.x
        lastSelectionDragY = event.y

        val cache = host.renderCache
        val cell = host.cellAt(event.x, event.y)
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)
        dragAnchorLogicalColumn = column
        dragAnchorVisualColumn = unpackCellColumn(host.visualCellAt(event.x, event.y))

        when {
            event.clickCount >= 3 -> {
                val absRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
                selectionAnchorAbsoluteRow = absRow
                selectionAnchorColumn = 0
                dragAnchorLogicalColumn = 0
                selectionCaretAbsoluteRow = absRow
                selectionCaretColumn = cache.columns
            }

            event.clickCount == 2 -> {
                val wordSel = selectionTextExtractor.wordSelectionAt(cache, row, column)
                if (wordSel != null) {
                    selectionAnchorAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + wordSel.anchorRow
                    selectionAnchorColumn = wordSel.anchorColumn
                    dragAnchorLogicalColumn = wordSel.anchorColumn
                    selectionCaretAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + wordSel.caretRow
                    selectionCaretColumn = wordSel.caretColumn
                } else {
                    clearSelection()
                }
            }

            else -> {
                selectionAnchorColumn = if (selectionIsBlock) dragAnchorVisualColumn else dragAnchorLogicalColumn
                selectionAnchorAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
                selectionCaretAbsoluteRow = null
            }
        }
        updateSelectionAutoscroll()
        host.repaint()
        event.consume()
    }

    fun handleSelectionMouseDragged(event: MouseEvent) {
        if (!selectingWithMouse || selectionAnchorAbsoluteRow == null || event.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK == 0) return

        selectionIsBlock = event.isAltDown
        lastSelectionDragX = event.x
        lastSelectionDragY = event.y

        updateSelectionCaret(event.x, event.y)
        updateSelectionAutoscroll(scrollImmediately = true)
        host.repaint()
        event.consume()
    }

    fun handleSelectionMouseReleased(event: MouseEvent) {
        if (SwingUtilities.isLeftMouseButton(event)) {
            stopSelectionDrag()
            event.consume()
        }
    }

    fun getViewportSelection(cache: TerminalRenderCache?): CellSelection? {
        val anchorAbsRow = selectionAnchorAbsoluteRow ?: return null
        val caretAbsRow = selectionCaretAbsoluteRow ?: return null
        val c = cache ?: return null

        val isForward =
            caretAbsRow > anchorAbsRow ||
                (caretAbsRow == anchorAbsRow && selectionCaretColumn >= selectionAnchorColumn)

        val startAbsRow = if (isForward) anchorAbsRow else caretAbsRow
        val startCol = if (isForward) selectionAnchorColumn else selectionCaretColumn
        val endAbsRow = if (isForward) caretAbsRow else anchorAbsRow
        val endCol = if (isForward) selectionCaretColumn else selectionAnchorColumn

        val viewportTop = c.discardedCount + c.historySize - c.scrollbackOffset
        val startViewportRow = startAbsRow - viewportTop
        val endViewportRow = endAbsRow - viewportTop

        if (endViewportRow < 0 || startViewportRow >= c.rows) {
            return null
        }

        val clampedStartRow = startViewportRow.coerceIn(0L, c.rows - 1L).toInt()
        val clampedStartCol = if (!selectionIsBlock && startViewportRow < 0) 0 else startCol.coerceIn(0, c.columns)

        val clampedEndRow = endViewportRow.coerceIn(0L, c.rows - 1L).toInt()
        val clampedEndCol = if (!selectionIsBlock && endViewportRow >= c.rows) c.columns else endCol.coerceIn(0, c.columns)

        return if (isForward) {
            CellSelection(
                anchorColumn = clampedStartCol,
                anchorRow = clampedStartRow,
                caretColumn = clampedEndCol,
                caretRow = clampedEndRow,
                isBlock = selectionIsBlock,
            )
        } else {
            CellSelection(
                anchorColumn = clampedEndCol,
                anchorRow = clampedEndRow,
                caretColumn = clampedStartCol,
                caretRow = clampedStartRow,
                isBlock = selectionIsBlock,
            )
        }
    }

    fun getSelectedText(reader: io.github.ketraterm.render.api.TerminalRenderFrameReader): String? {
        val anchorAbsRow = selectionAnchorAbsoluteRow ?: return null
        val caretAbsRow = selectionCaretAbsoluteRow ?: return null

        val startAbsRow = minOf(anchorAbsRow, caretAbsRow)
        val endAbsRow = maxOf(anchorAbsRow, caretAbsRow)
        var text: String? = null
        reader.readRenderFrameForAbsoluteRange(startAbsRow, endAbsRow) { frame ->
            val frameTopAbsRow = frame.discardedCount + frame.historySize - frame.scrollbackOffset
            val frameLastAbsRow = frameTopAbsRow + frame.rows - 1L
            if (endAbsRow < frameTopAbsRow || startAbsRow > frameLastAbsRow) return@readRenderFrameForAbsoluteRange

            val tempCache = TerminalRenderCache(frame.columns, frame.rows)
            tempCache.accept(frame)
            val relativeSelection = getViewportSelection(tempCache) ?: return@readRenderFrameForAbsoluteRange
            text =
                selectionTextExtractor.selectedText(
                    cache = tempCache,
                    selection = relativeSelection,
                    joinSoftWrappedRows = !selectionIsBlock,
                )
        }
        return text
    }

    private fun updateSelectionCaret(
        x: Int,
        y: Int,
    ) {
        val anchorAbsRow = selectionAnchorAbsoluteRow ?: return
        val cache = host.renderCache
        val cell = if (selectionIsBlock) host.visualCellAt(x, y) else host.cellAt(x, y)
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)

        val caretAbsRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
        val anchorColumn = if (selectionIsBlock) dragAnchorVisualColumn else dragAnchorLogicalColumn
        selectionAnchorColumn = if (selectionIsBlock && column < anchorColumn) anchorColumn + 1 else anchorColumn
        val caretColumn =
            when {
                selectionIsBlock -> if (column < anchorColumn) column else column + 1
                caretAbsRow < anchorAbsRow || caretAbsRow == anchorAbsRow && column < anchorColumn -> column
                else -> column + 1
            }

        selectionCaretAbsoluteRow = caretAbsRow
        selectionCaretColumn = caretColumn
    }

    private fun updateSelectionAutoscroll(scrollImmediately: Boolean = false) {
        if (selectingWithMouse && selectionAutoscrollDelta(lastSelectionDragY) != 0) {
            if (!selectionAutoscrollTimer.isRunning) {
                if (scrollImmediately) handleSelectionAutoscrollTick()
                selectionAutoscrollTimer.start()
            }
        } else {
            selectionAutoscrollTimer.stop()
        }
    }

    private fun selectionAutoscrollDelta(y: Int): Int {
        val padding = host.settings.padding
        return when {
            y < padding.top -> {
                val distance = padding.top - y
                1 + distance / 20
            }

            y >= host.componentHeight - padding.bottom -> {
                val distance = y - (host.componentHeight - padding.bottom)
                -(1 + distance / 20)
            }

            else -> 0
        }
    }

    private fun handleSelectionAutoscrollTick() {
        if (!selectingWithMouse) {
            selectionAutoscrollTimer.stop()
            return
        }

        val delta = selectionAutoscrollDelta(lastSelectionDragY)
        if (delta == 0) {
            selectionAutoscrollTimer.stop()
            return
        }

        val changed = host.scrollViewportByRows(delta)
        if (changed) {
            updateSelectionCaret(lastSelectionDragX, lastSelectionDragY)
        }
    }

    private companion object {
        private fun unpackCellColumn(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }
}

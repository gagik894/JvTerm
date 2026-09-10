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
package io.github.ketraterm.core.state

import io.github.ketraterm.core.model.*
import io.github.ketraterm.render.api.TerminalRenderCursorShape

/**
 * The global hardware context of the terminal.
 *
 * Owns the global mode flags, pen, dimensions, and tab stops, and routes all
 * read/write operations to the correct [ScreenBuffer] via [activeBuffer].
 *
 * [primaryBuffer] and [altBuffer] each own their own memory arenas
 * ([ScreenBuffer.store] + [ScreenBuffer.ring]) so a resize of one buffer
 * can never corrupt the other.
 */
internal class TerminalState(
    initialWidth: Int,
    initialHeight: Int,
    maxHistory: Int,
) {
    private var nextLineId: Long = 1L

    // Global hardware state.

    val modes = TerminalModes()
    val tabStops = TabStops(initialWidth)
    val pen = Pen()
    val dimensions = GridDimensions(initialWidth, initialHeight)
    val hostResponses = HostResponseQueue()

    /** `false` for DECSACE stream extent (the terminal default), `true` for exact rectangle. */
    var isAttributeChangeExtentRectangle: Boolean = false
    var themePalette =
        io.github.ketraterm.render.api
            .TerminalColorPalette()
    var palette = themePalette
    var windowPixelWidth: Int = 0
    var windowPixelHeight: Int = 0
    var isMinimized: Boolean = false
    var windowTitle: String = ""
        set(value) {
            if (field != value) {
                field = value
                markVisualChanged()
            }
        }
    var iconTitle: String = ""
        set(value) {
            if (field != value) {
                field = value
                markVisualChanged()
            }
        }

    var defaultCursorShape: TerminalRenderCursorShape = TerminalRenderCursorShape.BLOCK

    var cursorShape: TerminalRenderCursorShape = TerminalRenderCursorShape.BLOCK
        set(value) {
            if (field != value) {
                field = value
                markCursorChanged()
            }
        }

    var frameGeneration: Long = 0L
        private set

    var contentGeneration: Long = 0L
        private set

    var structureGeneration: Long = 0L
        private set

    var cursorGeneration: Long = 0L
        private set

    var lastPrintableRow: Int = NO_PRINTABLE_CELL
        private set

    var lastPrintableCol: Int = NO_PRINTABLE_CELL
        private set

    // Physical screens.

    val primaryBuffer =
        ScreenBuffer(initialWidth, initialHeight, maxHistory, ::allocateLineId)
            .apply { clearGrid(pen.currentAttr, pen.currentExtendedAttr, initialHeight) }

    /** Alternate buffer always has zero scrollback. */
    val altBuffer =
        ScreenBuffer(initialWidth, initialHeight, maxHistory = 0, ::allocateLineId)
            .apply { clearGrid(pen.currentAttr, pen.currentExtendedAttr, initialHeight) }

    // Hot-swap pointer.

    var activeBuffer: ScreenBuffer = primaryBuffer
        private set

    val isAltScreenActive: Boolean
        get() = activeBuffer === altBuffer

    /**
     * Switches to the alternate screen.
     *
     * When [clearBeforeEnter] is true, the alternate grid, margins, cursor, and
     * saved-cursor slot are reset before activation. This is used by `1047` and
     * `1049`. Non-clearing `47` entries reuse the alternate buffer's existing
     * content and cursor state.
     *
     * No-op when already in the alternate screen.
     */
    fun enterAltScreen(clearBeforeEnter: Boolean) {
        if (isAltScreenActive) return
        activeBuffer.kittyKeyboardFlags = modes.kittyKeyboardFlags
        if (clearBeforeEnter) {
            altBuffer.clearGrid(pen.blankAttr, pen.blankExtendedAttr, dimensions.height)
            altBuffer.resetScrollRegion(dimensions.height)
            altBuffer.resetLeftRightMargins(dimensions.width)
            altBuffer.cursor.col = 0
            altBuffer.cursor.row = 0
            altBuffer.cursor.pendingWrap = false
            altBuffer.savedCursor.clear()
            altBuffer.clearKittyKeyboardStack()
        }
        activeBuffer = altBuffer
        modes.kittyKeyboardFlags = activeBuffer.kittyKeyboardFlags
    }

    /**
     * Returns to the primary screen (`CSI ? 1049 l`).
     *
     * Callers must invoke `CursorEngine.restoreCursor()` after this call (DECRC).
     * Alternate content becomes invisible after the switch and will be discarded
     * on the next alternate entry or resize.
     */
    fun exitAltScreen() {
        if (!isAltScreenActive) return
        activeBuffer.kittyKeyboardFlags = modes.kittyKeyboardFlags
        activeBuffer = primaryBuffer
        modes.kittyKeyboardFlags = activeBuffer.kittyKeyboardFlags
    }

    // Transparent engine accessors.

    val ring
        get() = activeBuffer.ring
    val cursor: Cursor
        get() = activeBuffer.cursor
    val savedCursor
        get() = activeBuffer.savedCursor
    val scrollTop
        get() = activeBuffer.scrollTop
    val scrollBottom
        get() = activeBuffer.scrollBottom
    val effectiveLeftMargin: Int
        get() = if (modes.isLeftRightMarginMode) activeBuffer.leftMargin else 0
    val effectiveRightMargin: Int
        get() = if (modes.isLeftRightMarginMode) activeBuffer.rightMargin else dimensions.width - 1

    val isFullViewportScroll: Boolean
        get() = activeBuffer.isFullViewportScroll(dimensions.height)
    val historySize: Int
        get() = (activeBuffer.ring.size - dimensions.height).coerceAtLeast(0)

    fun resolveRingIndex(viewportRow: Int): Int = liveViewportTopIndex() + viewportRow

    fun clampScrollbackOffset(scrollbackOffset: Int): Int = scrollbackOffset.coerceIn(0, historySize)

    fun resolveScrollbackRingIndex(
        viewportRow: Int,
        scrollbackOffset: Int,
    ): Int = liveViewportTopIndex() - clampScrollbackOffset(scrollbackOffset) + viewportRow

    private fun liveViewportTopIndex(): Int = (activeBuffer.ring.size - dimensions.height).coerceAtLeast(0)

    // Convenience helpers.

    fun markVisualChanged() {
        frameGeneration++
    }

    fun markLineChanged(line: Line) {
        markVisualChanged()
        contentGeneration++
        line.renderGeneration = frameGeneration
    }

    fun markVisibleLinesChanged() {
        for (row in 0 until dimensions.height) {
            markLineChanged(ring[resolveRingIndex(row)])
        }
    }

    fun markStructureChanged() {
        markVisualChanged()
        contentGeneration++
        structureGeneration++
    }

    fun markCursorChanged() {
        markVisualChanged()
        cursorGeneration++
    }

    fun allocateLineId(): Long {
        val id = nextLineId
        nextLineId++
        if (nextLineId <= 0L) {
            nextLineId = 1L
        }
        return id
    }

    fun clearLineAsNew(
        line: Line,
        attr: Long,
        extendedAttr: Long,
    ) {
        line.assignLineId(allocateLineId())
        line.clear(attr, extendedAttr)
    }

    fun rememberPrintableCell(
        row: Int,
        col: Int,
    ) {
        lastPrintableRow = row
        lastPrintableCol = col
    }

    fun clearLastPrintableCell() {
        lastPrintableRow = NO_PRINTABLE_CELL
        lastPrintableCol = NO_PRINTABLE_CELL
    }

    /**
     * Clears the phantom-column pending-wrap flag and any pending printable
     * continuation target on the active cursor.
     */
    fun cancelPendingWrap() {
        activeBuffer.cursor.pendingWrap = false
        clearLastPrintableCell()
    }

    private companion object {
        private const val NO_PRINTABLE_CELL: Int = -1
    }
}

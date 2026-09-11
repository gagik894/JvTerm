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

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout

/**
 * Half-open terminal cell selection in visible render-cache coordinates.
 *
 * [anchorColumn] and [anchorRow] identify the fixed selection edge.
 * [caretColumn] and [caretRow] identify the moving edge and may be before the
 * anchor for backward selections. Columns are caret positions between cells, so
 * selecting cells 2 through 4 on one row is represented as columns 2..5.
 * Linear selections use logical columns in stored text order. Block selections
 * use visual columns and the same horizontal interval on every selected row;
 * copying a block maps its cells back to logical order independently per row.
 *
 * @property anchorColumn zero-based anchor caret column.
 * @property anchorRow zero-based anchor row.
 * @property caretColumn zero-based moving caret column.
 * @property caretRow zero-based moving row.
 * @property isBlock whether the selection is a rectangular block selection.
 */
data class CellSelection(
    val anchorColumn: Int,
    val anchorRow: Int,
    val caretColumn: Int,
    val caretRow: Int,
    val isBlock: Boolean = false,
) {
    init {
        require(anchorColumn >= 0) { "anchorColumn must be >= 0, was $anchorColumn" }
        require(anchorRow >= 0) { "anchorRow must be >= 0, was $anchorRow" }
        require(caretColumn >= 0) { "caretColumn must be >= 0, was $caretColumn" }
        require(caretRow >= 0) { "caretRow must be >= 0, was $caretRow" }
    }

    /**
     * Returns true when the selection covers no cells.
     */
    val isEmpty: Boolean
        get() = anchorColumn == caretColumn && (isBlock || anchorRow == caretRow)

    /**
     * Returns the selected half-open column range for [row], packed as
     * `start shl 32 | end`, or [NO_RANGE] when [row] is outside the selection.
     * The range uses logical columns for linear selections and visual columns
     * for blocks. A block's visual bounds do not depend on the row's bidi order.
     *
     * @param row visible render-cache row.
     * @param columns visible render-cache column count.
     * @param cache optional cache used to include complete wide cells in linear
     * selections. Block bounds require a row's visual mapping for this adjustment.
     * @return packed half-open range, or [NO_RANGE].
     */
    fun packedColumnRange(
        row: Int,
        columns: Int,
        cache: TerminalRenderCache? = null,
    ): Long {
        if (isEmpty || row < startRow || row > endRow) return NO_RANGE

        val start = (if (isBlock || row == startRow) startColumn else 0).coerceIn(0, columns)
        val end = (if (isBlock || row == endRow) endColumn else columns).coerceIn(0, columns)
        if (start >= end) return NO_RANGE
        val range = packRange(start, end)
        return if (cache != null && !isBlock) expandWideCells(range, row, cache, null) else range
    }

    /** Resolves complete cells in the selection's coordinate space using the row's bidi mapping. */
    internal fun packedColumnRange(
        row: Int,
        cache: TerminalRenderCache,
        bidi: TerminalBidiLayout.Row?,
    ): Long {
        val range = packedColumnRange(row, cache.columns)
        return expandWideCells(range, row, cache, if (isBlock) bidi else null)
    }

    private fun expandWideCells(
        range: Long,
        row: Int,
        cache: TerminalRenderCache,
        bidi: TerminalBidiLayout.Row?,
    ): Long {
        if (range == NO_RANGE) return NO_RANGE
        var start = rangeStart(range)
        var end = rangeEnd(range)
        val columns = cache.columns
        if (row in 0 until cache.rows) {
            val rowOffset = cache.rowOffset(row)
            val flags = cache.flags

            if (start in 0 until columns) {
                val startIdx = rowOffset + (bidi?.logicalColumn(start) ?: start)
                if (startIdx in flags.indices && (flags[startIdx] and TerminalRenderCellFlags.WIDE_TRAILING) != 0) {
                    start = (start - 1).coerceAtLeast(0)
                }
            }

            if (end in 1..columns) {
                val endIdx = rowOffset + (bidi?.logicalColumn(end - 1) ?: (end - 1))
                if (endIdx in flags.indices && (flags[endIdx] and TerminalRenderCellFlags.WIDE_LEADING) != 0) {
                    end = (end + 1).coerceAtMost(columns)
                }
            }
        }

        return packRange(start, end)
    }

    /**
     * First selected row after normalizing anchor and caret order.
     */
    val startRow: Int
        get() = if (isForward) anchorRow else caretRow

    /**
     * Last selected row after normalizing anchor and caret order.
     */
    val endRow: Int
        get() = if (isForward) caretRow else anchorRow

    /**
     * First logical caret column on [startRow], or the left visual edge of a block.
     */
    val startColumn: Int
        get() =
            if (isBlock) {
                minOf(anchorColumn, caretColumn)
            } else if (isForward) {
                anchorColumn
            } else {
                caretColumn
            }

    /**
     * End-exclusive logical caret column on [endRow], or the right visual edge of a block.
     */
    val endColumn: Int
        get() =
            if (isBlock) {
                maxOf(anchorColumn, caretColumn)
            } else if (isForward) {
                caretColumn
            } else {
                anchorColumn
            }

    private val isForward: Boolean
        get() = caretRow > anchorRow || caretRow == anchorRow && caretColumn >= anchorColumn

    companion object {
        /**
         * Sentinel returned when a row is not selected.
         */
        const val NO_RANGE: Long = -1L

        /**
         * Packs a half-open column range.
         *
         * @param startColumn the starting column index.
         * @param endColumn the ending column index (exclusive).
         * @return packed column range as a [Long].
         */
        fun packRange(
            startColumn: Int,
            endColumn: Int,
        ): Long = (startColumn.toLong() shl 32) or (endColumn.toLong() and 0xffff_ffffL)

        /**
         * Extracts the start column from a packed range.
         *
         * @param range packed column range [Long].
         * @return start column index.
         */
        fun rangeStart(range: Long): Int = (range ushr 32).toInt()

        /**
         * Extracts the end-exclusive column from a packed range.
         *
         * @param range packed column range [Long].
         * @return end-exclusive column index.
         */
        fun rangeEnd(range: Long): Int = range.toInt()
    }
}

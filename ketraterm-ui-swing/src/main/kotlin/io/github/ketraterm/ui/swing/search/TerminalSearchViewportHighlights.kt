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
package io.github.ketraterm.ui.swing.search

/**
 * Paint-ready search segments in current render-cache viewport coordinates.
 *
 * Projection storage is reused across updates. Row painting reads precomputed
 * row offsets and segment flags without
 * allocating or scanning unrelated rows.
 */
internal class TerminalSearchViewportHighlights {
    private var rows: Int = 0
    var segmentCount: Int = 0
        private set

    private var rowStarts = IntArray(0)
    private var rowCounts = IntArray(0)
    private var segmentRows = IntArray(INITIAL_SEGMENT_CAPACITY)
    private var segmentStarts = IntArray(INITIAL_SEGMENT_CAPACITY)
    private var segmentEnds = IntArray(INITIAL_SEGMENT_CAPACITY)
    private var segmentActive = BooleanArray(INITIAL_SEGMENT_CAPACITY)

    fun reset(rows: Int) {
        require(rows >= 0) { "rows must be >= 0, was $rows" }
        ensureRowCapacity(rows)
        this.rows = rows
        rowStarts.fill(0, 0, rows)
        rowCounts.fill(0, 0, rows)
        segmentCount = 0
    }

    fun add(
        row: Int,
        startColumn: Int,
        endColumn: Int,
        active: Boolean,
    ) {
        if (startColumn >= endColumn) return
        ensureSegmentCapacity(segmentCount + 1)
        segmentRows[segmentCount] = row
        segmentStarts[segmentCount] = startColumn
        segmentEnds[segmentCount] = endColumn
        segmentActive[segmentCount] = active
        segmentCount++
    }

    fun finish() {
        if (segmentCount == 0) return
        var previousRow = -1
        var segmentIndex = 0
        while (segmentIndex < segmentCount) {
            val row = segmentRows[segmentIndex]
            if (row != previousRow) {
                rowStarts[row] = segmentIndex
                previousRow = row
            }
            rowCounts[row]++
            segmentIndex++
        }
    }

    fun firstSegmentForRow(row: Int): Int = rowStarts[row]

    fun segmentCountForRow(row: Int): Int = rowCounts[row]

    fun startColumn(segmentIndex: Int): Int = segmentStarts[segmentIndex]

    fun endColumn(segmentIndex: Int): Int = segmentEnds[segmentIndex]

    fun isActive(segmentIndex: Int): Boolean = segmentActive[segmentIndex]

    /** Copies a completed projection into reusable storage for repaint comparison. */
    fun copyFrom(source: TerminalSearchViewportHighlights) {
        ensureRowCapacity(source.rows)
        ensureSegmentCapacity(source.segmentCount)
        rows = source.rows
        segmentCount = source.segmentCount
        source.rowStarts.copyInto(rowStarts, endIndex = rows)
        source.rowCounts.copyInto(rowCounts, endIndex = rows)
        source.segmentRows.copyInto(segmentRows, endIndex = segmentCount)
        source.segmentStarts.copyInto(segmentStarts, endIndex = segmentCount)
        source.segmentEnds.copyInto(segmentEnds, endIndex = segmentCount)
        source.segmentActive.copyInto(segmentActive, endIndex = segmentCount)
    }

    /** Exact comparison includes active-result styling, without hashing or scanning other rows. */
    fun rowMatches(
        row: Int,
        other: TerminalSearchViewportHighlights?,
    ): Boolean {
        val count = if (row < rows) rowCounts[row] else 0
        val otherCount = if (other != null && row < other.rows) other.rowCounts[row] else 0
        if (count != otherCount) return false
        if (count == 0 || other == null) return true

        var index = rowStarts[row]
        var otherIndex = other.rowStarts[row]
        val end = index + count
        while (index < end) {
            if (segmentStarts[index] != other.segmentStarts[otherIndex] ||
                segmentEnds[index] != other.segmentEnds[otherIndex] ||
                segmentActive[index] != other.segmentActive[otherIndex]
            ) {
                return false
            }
            index++
            otherIndex++
        }
        return true
    }

    private fun ensureRowCapacity(rows: Int) {
        if (rows <= rowStarts.size) return
        rowStarts = IntArray(rows)
        rowCounts = IntArray(rows)
    }

    private fun ensureSegmentCapacity(required: Int) {
        if (required <= segmentRows.size) return
        val nextCapacity = nextCapacity(segmentRows.size, required)
        segmentRows = segmentRows.copyOf(nextCapacity)
        segmentStarts = segmentStarts.copyOf(nextCapacity)
        segmentEnds = segmentEnds.copyOf(nextCapacity)
        segmentActive = segmentActive.copyOf(nextCapacity)
    }

    private companion object {
        private const val INITIAL_SEGMENT_CAPACITY = 128

        private fun nextCapacity(
            current: Int,
            required: Int,
        ): Int {
            var next = current
            while (next < required) {
                next *= 2
            }
            return next
        }
    }
}

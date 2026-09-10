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

import io.github.ketraterm.render.cache.TerminalRenderCache

/**
 * Search matches in stable absolute terminal-row coordinates.
 *
 * Each logical search result may map to one or more visible row segments when a
 * match crosses a soft-wrapped row boundary. Segment arrays are primitive and
 * compact so painting can derive viewport highlights without retaining row text.
 */
internal class TerminalSearchHighlights {
    var resultCount: Int = 0
        private set
    var activeResultIndex: Int = NO_ACTIVE_RESULT
        private set
    var segmentCount: Int = 0
        private set

    private var resultSegmentStarts = IntArray(INITIAL_RESULT_CAPACITY)
    private var resultSegmentCounts = IntArray(INITIAL_RESULT_CAPACITY)
    private var segmentRows = LongArray(INITIAL_SEGMENT_CAPACITY)
    private var segmentRanges = LongArray(INITIAL_SEGMENT_CAPACITY)

    val hasActiveResult: Boolean
        get() = activeResultIndex in 0 until resultCount

    fun clear() {
        resultCount = 0
        activeResultIndex = NO_ACTIVE_RESULT
        segmentCount = 0
    }

    fun beginResult() {
        ensureResultCapacity(resultCount + 1)
        resultSegmentStarts[resultCount] = segmentCount
        resultSegmentCounts[resultCount] = 0
        resultCount++
    }

    fun addSegment(
        absoluteRow: Long,
        startColumn: Int,
        endColumn: Int,
    ) {
        if (startColumn >= endColumn) return
        check(resultCount > 0) { "beginResult must be called before addSegment" }
        ensureSegmentCapacity(segmentCount + 1)
        segmentRows[segmentCount] = absoluteRow
        segmentRanges[segmentCount] = packRange(startColumn, endColumn)
        segmentCount++
        resultSegmentCounts[resultCount - 1]++
    }

    fun finishResult() {
        if (resultSegmentCounts[resultCount - 1] != 0) return
        resultCount--
    }

    fun activate(index: Int) {
        activeResultIndex =
            if (index in 0 until resultCount) {
                index
            } else {
                NO_ACTIVE_RESULT
            }
    }

    fun activeStartAbsoluteRow(): Long {
        if (!hasActiveResult) return NO_ABSOLUTE_ROW
        val segmentIndex = resultSegmentStarts[activeResultIndex]
        return segmentRows[segmentIndex]
    }

    fun buildViewportHighlights(
        cache: TerminalRenderCache,
        destination: TerminalSearchViewportHighlights,
    ) {
        destination.reset(cache.rows)
        if (segmentCount == 0) return

        val firstAbsoluteRow = firstAbsoluteRow(cache)
        val lastAbsoluteRow = firstAbsoluteRow + cache.rows - 1L
        val activeStart =
            if (hasActiveResult) {
                resultSegmentStarts[activeResultIndex]
            } else {
                -1
            }
        val activeEnd =
            if (hasActiveResult) {
                activeStart + resultSegmentCounts[activeResultIndex]
            } else {
                -1
            }

        // Scanning emits segments in row order. Skip retained matches above the viewport.
        var low = 0
        var high = segmentCount
        while (low < high) {
            val middle = low + (high - low) / 2
            if (segmentRows[middle] < firstAbsoluteRow) low = middle + 1 else high = middle
        }
        var segmentIndex = low
        while (segmentIndex < segmentCount) {
            val absoluteRow = segmentRows[segmentIndex]
            if (absoluteRow > lastAbsoluteRow) break
            if (absoluteRow in firstAbsoluteRow..lastAbsoluteRow) {
                val row = (absoluteRow - firstAbsoluteRow).toInt()
                val range = segmentRanges[segmentIndex]
                destination.add(
                    row = row,
                    startColumn = rangeStart(range).coerceIn(0, cache.columns),
                    endColumn = rangeEnd(range).coerceIn(0, cache.columns),
                    active = segmentIndex in activeStart until activeEnd,
                )
            }
            segmentIndex++
        }
        destination.finish()
    }

    private fun ensureResultCapacity(required: Int) {
        if (required <= resultSegmentStarts.size) return
        val nextCapacity = nextCapacity(resultSegmentStarts.size, required)
        resultSegmentStarts = resultSegmentStarts.copyOf(nextCapacity)
        resultSegmentCounts = resultSegmentCounts.copyOf(nextCapacity)
    }

    private fun ensureSegmentCapacity(required: Int) {
        if (required <= segmentRows.size) return
        val nextCapacity = nextCapacity(segmentRows.size, required)
        segmentRows = segmentRows.copyOf(nextCapacity)
        segmentRanges = segmentRanges.copyOf(nextCapacity)
    }

    private companion object {
        private const val INITIAL_RESULT_CAPACITY = 64
        private const val INITIAL_SEGMENT_CAPACITY = 128
        private const val NO_ACTIVE_RESULT = -1
        private const val NO_ABSOLUTE_ROW = Long.MIN_VALUE

        private fun firstAbsoluteRow(cache: TerminalRenderCache): Long = cache.discardedCount + cache.historySize - cache.scrollbackOffset

        private fun packRange(
            startColumn: Int,
            endColumn: Int,
        ): Long = (startColumn.toLong() shl 32) or (endColumn.toLong() and 0xffff_ffffL)

        private fun rangeStart(range: Long): Int = (range ushr 32).toInt()

        private fun rangeEnd(range: Long): Int = range.toInt()

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

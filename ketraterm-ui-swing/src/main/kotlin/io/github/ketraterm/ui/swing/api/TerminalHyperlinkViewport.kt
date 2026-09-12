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

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.cache.TerminalRenderCache

/** EDT-owned logical-line results, bounded by the render cache (including its overscan). */
internal class TerminalHyperlinkViewport {
    private val builder = TerminalHyperlinkLineSnapshotBuilder()
    private var lines = ArrayList<Line>()
    private var nextLines = ArrayList<Line>()
    private var ids = IntArray(0)
    private var nextIds = IntArray(0)
    private val actions = ArrayList<SwingHyperlinkAction>()
    private var hasFrame = false
    private var frameGeneration = 0L
    private var structureGeneration = 0L
    private var scrollbackOffset = 0
    private var discardedCount = 0L
    private var activeBuffer = TerminalRenderBufferKind.PRIMARY
    private var columns = 0
    private var rows = 0

    val needsAnalysis: Boolean
        get() = lines.any { !it.complete }

    fun clear() {
        hasFrame = false
        lines.clear()
        nextLines.clear()
        actions.clear()
    }

    fun update(
        cache: TerminalRenderCache,
        context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.LOGICAL_LINE,
    ): Boolean {
        if (matches(cache)) return false
        if (columns != cache.columns || activeBuffer != cache.activeBuffer || discardedCount != cache.discardedCount) clear()
        nextLines.clear()
        var startRow = 0
        var previousIndex = 0
        while (startRow < cache.rows) {
            var endRow = startRow + 1
            while (endRow < cache.rows && cache.lineWrapped[endRow - 1]) endRow++
            val index = findPrevious(cache, startRow, endRow, previousIndex)
            val previous = lines.getOrNull(index)
            val snapshot =
                if (previous != null && previous.snapshot.matchesRows(cache, startRow, endRow)) {
                    previous.snapshot
                } else {
                    builder.snapshot(cache, startRow, endRow)
                }
            val sameText = previous != null && previous.snapshot.text == snapshot.text
            val line =
                if (previous != null && snapshot === previous.snapshot) {
                    previous
                } else {
                    Line(snapshot).apply {
                        if (previous != null) {
                            links =
                                if (sameText) {
                                    previous.links
                                } else {
                                    previous.links.filter {
                                        it.remainsValid(
                                            previous.snapshot.text,
                                            snapshot.text,
                                        )
                                    }
                                }
                            complete = sameText && previous.complete
                        }
                    }
                }
            line.startRow = startRow
            nextLines.add(line)
            previousIndex = if (index >= 0) index + 1 else previousIndex
            startRow = endRow
        }
        if (context == SwingHyperlinkDetectionContext.VIEWPORT &&
            (lines.size != nextLines.size || lines.indices.any { lines[it].snapshot.text != nextLines[it].snapshot.text })
        ) {
            for (line in nextLines) {
                line.links = line.links.filter { !it.viewportDependent }
                line.complete = line.snapshot.text == "\n"
            }
        }
        val oldLines = lines
        lines = nextLines
        nextLines = oldLines
        nextLines.clear()
        hasFrame = true
        frameGeneration = cache.frameGeneration
        structureGeneration = cache.structureGeneration
        scrollbackOffset = cache.scrollbackOffset
        discardedCount = cache.discardedCount
        activeBuffer = cache.activeBuffer
        columns = cache.columns
        rows = cache.rows
        return true
    }

    fun pendingLines(
        context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.LOGICAL_LINE,
    ): List<TerminalHyperlinkLineSnapshot> =
        if (context == SwingHyperlinkDetectionContext.VIEWPORT) {
            lines.map { it.snapshot }
        } else {
            lines.filter { !it.complete }.map { it.snapshot }
        }

    fun accept(
        snapshots: List<TerminalHyperlinkLineSnapshot>,
        detected: List<List<TerminalDetectedHyperlink>>,
        context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.LOGICAL_LINE,
    ) {
        val sameViewport = snapshots.size == lines.size && snapshots.indices.all { snapshots[it].text == lines[it].snapshot.text }
        for (index in snapshots.indices) {
            val source = snapshots[index]
            val line = findCurrent(source) ?: continue
            val sameText = source.text == line.snapshot.text
            line.links =
                if (sameText && (context == SwingHyperlinkDetectionContext.LOGICAL_LINE || sameViewport)) {
                    detected[index]
                } else {
                    detected[index].filter { (!it.viewportDependent || sameViewport) && it.remainsValid(source.text, line.snapshot.text) }
                }
            line.complete = sameText && (context == SwingHyperlinkDetectionContext.LOGICAL_LINE || sameViewport)
        }
    }

    fun idsFor(cache: TerminalRenderCache): IntArray = if (matches(cache)) ids else cache.hyperlinkIds

    fun actionFor(
        id: Int,
        cache: TerminalRenderCache,
    ): SwingHyperlinkAction? {
        if (id >= 0 || !matches(cache)) return null
        return actions.getOrNull(-id - 1)
    }

    fun writeOverlay(
        cache: TerminalRenderCache,
        repaint: (Int, Int, Int, Int) -> Unit,
    ) {
        if (!matches(cache)) return
        val size = rows * columns
        if (nextIds.size < size) nextIds = IntArray(size)
        cache.hyperlinkIds.copyInto(nextIds, endIndex = size)
        actions.clear()
        for (line in lines) {
            val cells = line.snapshot.cellStarts
            val ends = line.snapshot.cellEnds
            val rowOffset = cache.rowOffset(line.startRow)
            for (link in line.links) {
                val id = -(actions.size + 1)
                var accepted = false
                for (offset in link.startOffset until minOf(link.endOffset, cells.size)) {
                    for (cell in cells[offset] until ends[offset]) {
                        val target = rowOffset + cell
                        if (nextIds[target] == 0) {
                            nextIds[target] = id
                            accepted = true
                        }
                    }
                }
                if (accepted) actions.add(link.action)
            }
        }
        for (row in 0 until rows) {
            val offset = cache.rowOffset(row)
            var column = 0
            while (column < columns) {
                if (ids.getOrElse(offset + column) { 0 } == nextIds[offset + column]) {
                    column++
                    continue
                }
                val start = column++
                while (column < columns && ids.getOrElse(offset + column) { 0 } != nextIds[offset + column]) column++
                repaint(row, start, row, column)
            }
        }
        val previousIds = ids
        ids = nextIds
        nextIds = previousIds
    }

    private fun findPrevious(
        cache: TerminalRenderCache,
        start: Int,
        end: Int,
        hint: Int,
    ): Int {
        val id = cache.lineIds[start]
        if (id != 0L) {
            if (hint in lines.indices && lines[hint].snapshot.firstLineId == id) return hint
            for (index in lines.indices) if (lines[index].snapshot.firstLineId == id) return index
            return -1
        }
        var match = -1
        for (index in lines.indices) {
            if (nextLines.any { it === lines[index] }) continue
            if (lines[index].snapshot.matchesRows(cache, start, end)) {
                if (match >= 0) return -1
                match = index
            }
        }
        return match
    }

    private fun findCurrent(snapshot: TerminalHyperlinkLineSnapshot): Line? {
        if (snapshot.columns != columns || snapshot.activeBuffer != activeBuffer) return null
        lines.firstOrNull { it.snapshot === snapshot }?.let { return it }
        if (snapshot.firstLineId != 0L) return lines.firstOrNull { it.snapshot.firstLineId == snapshot.firstLineId }
        var match: Line? = null
        for (line in lines) {
            if (line.snapshot === snapshot || line.snapshot.sameIdentity(snapshot)) {
                if (match != null) return null
                match = line
            }
        }
        return match
    }

    private fun matches(cache: TerminalRenderCache): Boolean =
        hasFrame &&
            frameGeneration == cache.frameGeneration &&
            structureGeneration == cache.structureGeneration &&
            scrollbackOffset == cache.scrollbackOffset &&
            discardedCount == cache.discardedCount &&
            activeBuffer == cache.activeBuffer &&
            columns == cache.columns &&
            rows == cache.rows

    private class Line(
        val snapshot: TerminalHyperlinkLineSnapshot,
    ) {
        var startRow = 0
        var complete = snapshot.text == "\n"
        var links: List<TerminalDetectedHyperlink> = emptyList()
    }
}

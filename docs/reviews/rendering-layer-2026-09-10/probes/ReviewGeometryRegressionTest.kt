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

import com.sun.management.ThreadMXBean
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderColorKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.api.TerminalSelectionController
import io.github.ketraterm.ui.swing.api.TerminalSelectionHost
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.painter.TerminalBackgroundPainter
import io.github.ketraterm.ui.swing.search.TerminalSearchModel
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.viewport.SwingRepaintPlanner
import io.github.ketraterm.ui.swing.viewport.TerminalRepaintSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.event.InputEvent
import java.awt.Insets
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import javax.swing.JButton
import javax.swing.SwingUtilities

class ReviewGeometryRegressionTest {
    private val metrics = SwingMetrics(10, 20, 15, 16, 10, 0, 1)

    @Test
    fun asciiOverscanRowCountChangesReuseBidiStorage() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled)
        val normal = TerminalRenderCache(80, 24)
        val overscan = TerminalRenderCache(80, 25)
        val target = TerminalRenderCache(80, 24, rowCapacityReserve = 1)
        val bidi = TerminalBidiLayout()
        val threadId = Thread.currentThread().threadId()
        fun updateBatch(measure: Boolean): Long {
            var allocated = 0L
            repeat(1_000) {
                target.updateFrom(normal)
                var before = if (measure) allocationBean.getThreadAllocatedBytes(threadId) else 0L
                bidi.row(target, 0)
                if (measure) allocated += allocationBean.getThreadAllocatedBytes(threadId) - before
                target.updateFrom(overscan)
                before = if (measure) allocationBean.getThreadAllocatedBytes(threadId) else 0L
                bidi.row(target, 0)
                if (measure) allocated += allocationBean.getThreadAllocatedBytes(threadId) - before
            }
            return allocated
        }
        repeat(5) { updateBatch(measure = false) }
        var minimum = Long.MAX_VALUE
        repeat(5) {
            minimum = minOf(minimum, updateBatch(measure = true))
        }
        println("Review bidi allocation bytes per overscan row-count transition: ${minimum / 2_000}")
        assertEquals(0L, minimum, "warmed ASCII smooth-scroll overscan should reuse bidi storage")
    }

    @Test
    fun wrappedSearchChangeRepaintsUnchangedFirstRow() {
        val cache = TerminalRenderCache(3, 2)
        "abcdef".forEachIndexed { index, character ->
            cache.codeWords[index] = character.code
            cache.flags[index] = TerminalRenderCellFlags.CODEPOINT
        }
        cache.lineWrapped[0] = true
        val search = TerminalSearchModel()
        val highlights = TerminalSearchViewportHighlights()
        search.search(cache, "cde", ignoreCase = false).buildViewportHighlights(cache, highlights)
        assertEquals(1, highlights.segmentCountForRow(0))

        var repaintFirstRow = false
        val sink = object : TerminalRepaintSink {
            override fun requestFullRepaint() { repaintFirstRow = true }
            override fun requestRegionRepaint(x: Int, y: Int, width: Int, height: Int) {
                if (y < metrics.cellHeight && y + height > 0) repaintFirstRow = true
            }
        }
        val planner = SwingRepaintPlanner()
        val padding = Insets(0, 0, 0, 0)
        planner.requestFrameRepaint(cache, metrics, 30, 40, padding, sink)
        repaintFirstRow = false

        cache.codeWords[3] = 'x'.code
        cache.lineGenerations[1]++
        search.search(cache, "cde", ignoreCase = false).buildViewportHighlights(cache, highlights)
        assertEquals(0, highlights.segmentCountForRow(0))
        planner.requestFrameRepaint(cache, metrics, 30, 40, padding, sink)

        assertTrue(repaintFirstRow, "removing the match on the next wrapped row must clear the first row's old search pixels")
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 2.0])
    fun backgroundRunsAllocateNoMemoryAfterWarmup(scale: Double) {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled)
        val cache = TerminalRenderCache(80, 1)
        for (column in 0 until cache.columns) {
            cache.attrWords[column] = TerminalRenderAttrs.pack(
                backgroundKind = TerminalRenderColorKind.RGB,
                backgroundValue = if (column % 2 == 0) 0x112233 else 0x445566,
            )
        }
        val image = BufferedImage(1600, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(scale, scale)
        g.clipRect(0, 0, 800, 20)
        val painter = TerminalBackgroundPainter(AwtColorCache())
        fun paintBatch() {
            var iteration = 0
            while (iteration++ < 2_000) painter.paintRow(g, cache, cache.palette, metrics, 0)
        }
        try {
            repeat(5) { paintBatch() }
            var minimum = Long.MAX_VALUE
            val threadId = Thread.currentThread().threadId()
            repeat(5) {
                val before = allocationBean.getThreadAllocatedBytes(threadId)
                paintBatch()
                minimum = minOf(minimum, allocationBean.getThreadAllocatedBytes(threadId) - before)
            }
            println("Review background allocation bytes per 80-run row at scale $scale: ${minimum / 2_000}")
            assertEquals(0L, minimum, "warm background painting should not allocate")
        } finally {
            g.dispose()
        }
    }

    @Test
    fun bidiBlockDragKeepsOneVisualColumnAcrossRows() {
        SwingUtilities.invokeAndWait {
            val cache = renderCache(TestRenderFrame(arrayOf(
                arrayOf(TestCell('A'.code, TerminalRenderCellFlags.CODEPOINT), TestCell('B'.code, TerminalRenderCellFlags.CODEPOINT), TestCell('C'.code, TerminalRenderCellFlags.CODEPOINT)),
                arrayOf(TestCell('\u05D0'.code, TerminalRenderCellFlags.CODEPOINT), TestCell('\u05D1'.code, TerminalRenderCellFlags.CODEPOINT), TestCell('\u05D2'.code, TerminalRenderCellFlags.CODEPOINT)),
            )))
            val bidi = TerminalBidiLayout()
            val host = selectionHost(cache)
            val controller = TerminalSelectionController(host)
            val source = JButton()
            val mods = InputEvent.BUTTON1_DOWN_MASK or InputEvent.ALT_DOWN_MASK
            try {
                controller.handleSelectionMousePressed(MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0, mods, 1, 1, 1, false, MouseEvent.BUTTON1))
                controller.handleSelectionMouseDragged(MouseEvent(source, MouseEvent.MOUSE_DRAGGED, 0, mods, 1, 21, 0, false, MouseEvent.BUTTON1))
                val selection = requireNotNull(controller.getViewportSelection(cache))
                for (row in 0 until cache.rows) {
                    val range = selection.packedColumnRange(row, cache.columns, cache)
                    var selectedVisualCells = 0
                    forEachVisualCellSpan(bidi.row(cache, row), CellSelection.rangeStart(range), CellSelection.rangeEnd(range)) { start, end ->
                        selectedVisualCells += end - start
                    }
                    assertEquals(1, selectedVisualCells, "vertical Alt-drag at a fixed x must select one visual column on row $row")
                }
            } finally {
                controller.stopSelectionDrag()
            }
        }
    }

    @Test
    fun verticallyClippedBlockSelectionPreservesColumnBounds() {
        SwingUtilities.invokeAndWait {
            val cache = TerminalRenderCache(6, 3)
            val controller = TerminalSelectionController(selectionHost(cache))
            val source = JButton()
            val mods = InputEvent.BUTTON1_DOWN_MASK or InputEvent.ALT_DOWN_MASK
            try {
                controller.handleSelectionMousePressed(MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0, mods, 11, 1, 1, false, MouseEvent.BUTTON1))
                controller.handleSelectionMouseDragged(MouseEvent(source, MouseEvent.MOUSE_DRAGGED, 0, mods, 21, 41, 0, false, MouseEvent.BUTTON1))
                val viewport = renderCache(object : TestRenderFrame(Array(2) { Array(6) { TestCell() } }) {
                    override val historySize = 1
                })
                val selection = requireNotNull(controller.getViewportSelection(viewport))
                val range = selection.packedColumnRange(0, viewport.columns)
                assertEquals(1, CellSelection.rangeStart(range), "vertical clipping must preserve a rectangular selection's left edge")
                assertEquals(3, CellSelection.rangeEnd(range))
            } finally {
                controller.stopSelectionDrag()
            }
        }
    }

    private fun selectionHost(cache: TerminalRenderCache): TerminalSelectionHost = object : TerminalSelectionHost {
        private val bidi = TerminalBidiLayout()
        override val renderCache = cache
        override val settings = SwingSettings(padding = SwingPadding())
        override val metrics = this@ReviewGeometryRegressionTest.metrics
        override val contentYOffset = 0.0
        override val componentWidth = cache.columns * metrics.cellWidth
        override val componentHeight = cache.rows * metrics.cellHeight
        override fun cellAt(x: Int, y: Int): Long {
            val row = y / metrics.cellHeight
            val visual = x / metrics.cellWidth
            val logical = bidi.row(cache, row)?.logicalColumn(visual) ?: visual
            return (logical.toLong() shl 32) or row.toLong()
        }
        override fun scrollViewportByRows(deltaRows: Int) = false
        override fun repaint() = Unit
        override fun requestFocusInWindow() = true
    }
}

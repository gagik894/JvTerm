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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JButton

class TerminalSelectionControllerTest {
    private class FakeSelectionHost(
        override val renderCache: TerminalRenderCache,
    ) : TerminalSelectionHost {
        override val settings = SwingSettings(padding = SwingPadding(0, 0, 0, 0))
        override val metrics =
            SwingMetrics(
                cellWidth = 10,
                cellHeight = 20,
                baseline = 15,
                underlineY = 16,
                strikethroughY = 10,
                overlineY = 0,
                cursorStrokeWidth = 2,
            )
        override val contentYOffset = 0.0
        override val componentWidth = 100
        override val componentHeight = 200

        var cellAtCallCount = 0
        var cellAtX = -1
        var cellAtY = -1
        var scrollDeltaRows = 0
        var repaints = 0
        var focusRequests = 0

        override fun cellAt(
            x: Int,
            y: Int,
        ): Long {
            cellAtCallCount++
            cellAtX = x
            cellAtY = y
            val col = x / 10
            val row = y / 20
            return (col.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }

        override fun scrollViewportByRows(deltaRows: Int): Boolean {
            scrollDeltaRows = deltaRows
            return true
        }

        override fun repaint() {
            repaints++
        }

        override fun requestFocusInWindow(): Boolean {
            focusRequests++
            return true
        }
    }

    private class FakeFrameReader(
        private val frame: TerminalRenderFrame,
    ) : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(frame)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(frame)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(frame)
        }
    }

    private class FakeFrame(
        val content: String,
    ) : TerminalRenderFrame {
        override val columns = 10
        override val rows = 1
        override val historySize = 0
        override val scrollbackOffset = 0
        override val frameGeneration = 1L
        override val structureGeneration = 1L
        override val activeBuffer = TerminalRenderBufferKind.PRIMARY
        override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1L)
        override val discardedCount = 0L

        override fun lineGeneration(row: Int) = 1L

        override fun lineWrapped(row: Int) = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            for (col in 0 until minOf(columns, content.length)) {
                val idx = codeOffset + col
                codeWords[idx] = content[col].code
                attrWords[idx] = TerminalRenderAttrs.DEFAULT
                flags[idx] = TerminalRenderCellFlags.CODEPOINT
            }
        }
    }

    @Test
    fun `clearSelection resets selection rows`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)

        controller.clearSelection()

        assertNull(controller.selectionAnchorAbsoluteRow)
        assertNull(controller.selectionCaretAbsoluteRow)
    }

    @Test
    fun `mousePressed on left button requests focus and sets anchor`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)

        val button = JButton()
        val event =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                25,
                45,
                1,
                false,
                MouseEvent.BUTTON1,
            )

        controller.handleSelectionMousePressed(event)

        assertEquals(1, host.focusRequests)
        assertTrue(controller.selectingWithMouse)
        assertEquals(2, controller.selectionAnchorColumn)
        assertEquals(2L, controller.selectionAnchorAbsoluteRow)
    }

    @Test
    fun `mouseDragged updates caret and triggers repaint`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)

        val button = JButton()
        val pressEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                15,
                25,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        controller.handleSelectionMousePressed(pressEvent)

        val dragEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                35,
                65,
                0,
                false,
                MouseEvent.BUTTON1,
            )
        controller.handleSelectionMouseDragged(dragEvent)

        assertTrue(controller.selectingWithMouse)
        assertEquals(1, controller.selectionAnchorColumn)
        assertEquals(4, controller.selectionCaretColumn)
    }

    @Test
    fun `double click on mousePressed selects word`() {
        val cache = TerminalRenderCache(10, 1)
        val frame = FakeFrame("hello")
        cache.updateFrom(FakeFrameReader(frame))

        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)
        val button = JButton()

        val doubleClickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                15,
                5,
                2,
                false,
                MouseEvent.BUTTON1,
            )
        controller.handleSelectionMousePressed(doubleClickEvent)

        assertEquals(0, controller.selectionAnchorColumn)
        assertEquals(5, controller.selectionCaretColumn)
        assertEquals(0L, controller.selectionAnchorAbsoluteRow)
    }

    @Test
    fun `triple click selects line`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeSelectionHost(cache)
        val controller = TerminalSelectionController(host)
        val button = JButton()

        val tripleClickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                15,
                25,
                3,
                false,
                MouseEvent.BUTTON1,
            )
        controller.handleSelectionMousePressed(tripleClickEvent)

        assertEquals(0, controller.selectionAnchorColumn)
        assertEquals(10, controller.selectionCaretColumn)
        assertEquals(1L, controller.selectionAnchorAbsoluteRow)
        assertEquals(1L, controller.selectionCaretAbsoluteRow)
    }

    @Test
    fun `getSelectedText extracts text from frame reader for selection spanning history`() {
        val lines =
            listOf(
                "hist0",
                "hist1",
                "hist2",
                "hist3",
                "hist4",
                "screen0",
                "screen1",
                "screen2",
                "screen3",
                "screen4",
            )

        class MultiLineFakeFrame(
            override val historySize: Int,
            override val scrollbackOffset: Int,
            override val rows: Int,
            val frameLines: List<String>,
        ) : TerminalRenderFrame {
            override val columns = 10
            override val frameGeneration = 1L
            override val structureGeneration = 1L
            override val activeBuffer = TerminalRenderBufferKind.PRIMARY
            override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1L)
            override val discardedCount = 0L

            override fun lineGeneration(row: Int) = 1L

            override fun lineWrapped(row: Int) = false

            override fun copyLine(
                row: Int,
                codeWords: IntArray,
                codeOffset: Int,
                attrWords: LongArray,
                attrOffset: Int,
                flags: IntArray,
                flagOffset: Int,
                extraAttrWords: LongArray?,
                extraAttrOffset: Int,
                hyperlinkIds: IntArray?,
                hyperlinkOffset: Int,
                clusterSink: TerminalRenderClusterSink?,
                clusterDataSink: TerminalRenderClusterDataSink?,
            ) {
                val lineContent = frameLines[row]
                for (col in 0 until minOf(columns, lineContent.length)) {
                    val idx = codeOffset + col
                    codeWords[idx] = lineContent[col].code
                    attrWords[idx] = TerminalRenderAttrs.DEFAULT
                    flags[idx] = TerminalRenderCellFlags.CODEPOINT
                }
            }
        }

        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {}

                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {}

                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    viewportRows: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    val startAbsRow = 5 - scrollbackOffset
                    val frameLinesSlice = lines.subList(startAbsRow, minOf(lines.size, startAbsRow + viewportRows))
                    consumer.accept(
                        MultiLineFakeFrame(
                            historySize = 5,
                            scrollbackOffset = scrollbackOffset,
                            rows = frameLinesSlice.size,
                            frameLines = frameLinesSlice,
                        ),
                    )
                }

                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    val start = startAbsoluteRow.toInt().coerceIn(0, lines.lastIndex)
                    val end = endAbsoluteRow.toInt().coerceIn(start, lines.lastIndex)
                    val scrollbackOffset = (5 - start).coerceAtLeast(0)
                    val frameTop = 5 - scrollbackOffset
                    consumer.accept(
                        MultiLineFakeFrame(
                            historySize = 5,
                            scrollbackOffset = scrollbackOffset,
                            rows = end - frameTop + 1,
                            frameLines = lines.subList(frameTop, end + 1),
                        ),
                    )
                }
            }

        val liveCache = TerminalRenderCache(columns = 10, rows = 5)
        val liveFrame = MultiLineFakeFrame(historySize = 5, scrollbackOffset = 0, rows = 5, frameLines = lines.subList(5, 10))
        liveCache.accept(liveFrame)

        val host = FakeSelectionHost(liveCache)
        val controller = TerminalSelectionController(host)

        controller.selectAbsoluteRows(2L, 7L, 10)

        val text = controller.getSelectedText(reader)
        val expected = "hist2\nhist3\nhist4\nscreen0\nscreen1\nscreen2"
        assertEquals(expected, text)
    }

    @Test
    fun `getSelectedText keeps absolute rows stable when output advances beyond live cache`() {
        val staleLiveCache = TerminalRenderCache(columns = 8, rows = 3)
        staleLiveCache.accept(AbsoluteLinesFrame(lines = listOf("old5", "old6", "old7"), historySize = 5))
        val currentLines = (0..8).map { "row$it" }
        val reader = AbsoluteLinesReader(lines = currentLines, historySize = 6, screenRows = 3)
        val controller = TerminalSelectionController(FakeSelectionHost(staleLiveCache))
        controller.selectAbsoluteRows(startAbsoluteRow = 2L, endAbsoluteRow = 7L, columns = 8)

        val text = controller.getSelectedText(reader)

        assertEquals("row2\nrow3\nrow4\nrow5\nrow6\nrow7", text)
        assertEquals(2L, reader.requestedStartAbsoluteRow)
        assertEquals(7L, reader.requestedEndAbsoluteRow)
    }

    @Test
    fun `getSelectedText does not substitute retained rows for a fully discarded selection`() {
        val cache = TerminalRenderCache(columns = 8, rows = 3)
        cache.accept(AbsoluteLinesFrame(lines = listOf("row5", "row6", "row7"), historySize = 5))
        val reader = AbsoluteLinesReader(lines = (2..9).map { "row$it" }, historySize = 5, screenRows = 3, discardedCount = 2L)
        val controller = TerminalSelectionController(FakeSelectionHost(cache))
        controller.selectAbsoluteRows(startAbsoluteRow = 0L, endAbsoluteRow = 1L, columns = 8)

        assertNull(controller.getSelectedText(reader))
    }

    private class AbsoluteLinesReader(
        private val lines: List<String>,
        private val historySize: Int,
        private val screenRows: Int,
        private val discardedCount: Long = 0L,
    ) : TerminalRenderFrameReader {
        var requestedStartAbsoluteRow: Long = -1L
            private set
        var requestedEndAbsoluteRow: Long = -1L
            private set

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            val liveStart = historySize
            consumer.accept(
                AbsoluteLinesFrame(
                    lines = lines.subList(liveStart, liveStart + screenRows),
                    historySize = historySize,
                    discardedCount = discardedCount,
                ),
            )
        }

        override fun readRenderFrameForAbsoluteRange(
            startAbsoluteRow: Long,
            endAbsoluteRow: Long,
            consumer: TerminalRenderFrameConsumer,
        ) {
            requestedStartAbsoluteRow = startAbsoluteRow
            requestedEndAbsoluteRow = endAbsoluteRow
            val retainedFirst = discardedCount
            val retainedLast = discardedCount + lines.lastIndex
            val resolvedStart = startAbsoluteRow.coerceIn(retainedFirst, retainedLast)
            val resolvedEnd = endAbsoluteRow.coerceIn(resolvedStart, retainedLast)
            val liveTop = discardedCount + historySize
            val frameTop = minOf(resolvedStart, liveTop)
            val firstIndex = (frameTop - discardedCount).toInt()
            val lastIndex = (resolvedEnd - discardedCount).toInt()
            consumer.accept(
                AbsoluteLinesFrame(
                    lines = lines.subList(firstIndex, lastIndex + 1),
                    historySize = historySize,
                    scrollbackOffset = (liveTop - frameTop).toInt(),
                    discardedCount = discardedCount,
                ),
            )
        }
    }

    private class AbsoluteLinesFrame(
        private val lines: List<String>,
        override val historySize: Int,
        override val scrollbackOffset: Int = 0,
        override val discardedCount: Long = 0L,
    ) : TerminalRenderFrame {
        override val columns: Int = 8
        override val rows: Int = lines.size
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1L)

        override fun lineGeneration(row: Int): Long = row.toLong() + 1L

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            val text = lines[row]
            var column = 0
            while (column < minOf(columns, text.length)) {
                codeWords[codeOffset + column] = text[column].code
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                column++
            }
        }
    }
}

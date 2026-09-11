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
package io.github.ketraterm.core.render

import io.github.ketraterm.core.buffer.DefaultTerminalBuffer
import io.github.ketraterm.core.buffer.impl.TerminalModeControllerImpl
import io.github.ketraterm.core.engine.CursorEngine
import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.core.model.UnderlineStyle
import io.github.ketraterm.core.state.TerminalState
import io.github.ketraterm.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CoreTerminalRenderFrameTest {
    @Test
    fun `content generation ignores cursor title and viewport changes`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        buffer.writeText("one")
        buffer.carriageReturn()
        buffer.newLine()
        buffer.newLine()
        var contentGeneration = 0L
        var frameGeneration = 0L
        buffer.readRenderFrame {
            contentGeneration = it.contentGeneration
            frameGeneration = it.frameGeneration
        }

        buffer.positionCursor(1, 0)
        buffer.setCursorShape(TerminalRenderCursorShape.UNDERLINE)
        buffer.setWindowTitle("changed title")
        buffer.readRenderFrame(scrollbackOffset = 1) {
            assertNotEquals(frameGeneration, it.frameGeneration)
            assertEquals(contentGeneration, it.contentGeneration)
        }
    }

    @Test
    fun `content generation includes mutations outside the requested viewport`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        buffer.writeText("old")
        buffer.newLine()
        buffer.newLine()
        var contentGeneration = 0L
        var lineGeneration = 0L
        buffer.readRenderFrame(scrollbackOffset = 1, viewportRows = 1) {
            contentGeneration = it.contentGeneration
            lineGeneration = it.lineGeneration(0)
        }

        buffer.carriageReturn()
        buffer.writeText("new")

        buffer.readRenderFrame(scrollbackOffset = 1, viewportRows = 1) {
            assertNotEquals(contentGeneration, it.contentGeneration)
            assertEquals(lineGeneration, it.lineGeneration(0))
        }
    }

    @Test
    fun `content generation changes for edits scrolling resize buffer switches and reset`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)

        fun assertContentChanges(change: () -> Unit) {
            var previous = 0L
            buffer.readRenderFrame { previous = it.contentGeneration }
            change()
            buffer.readRenderFrame { assertNotEquals(previous, it.contentGeneration) }
        }

        assertContentChanges { buffer.writeText("text") }
        assertContentChanges { buffer.eraseCurrentLine() }
        buffer.positionCursor(0, 1)
        assertContentChanges { buffer.newLine() }
        assertContentChanges { buffer.resize(5, 3) }
        assertContentChanges { buffer.enterAltBuffer() }
        assertContentChanges { buffer.exitAltBuffer() }
        assertContentChanges { buffer.eraseScreenAndHistory() }
        assertContentChanges { buffer.reset() }
    }

    @Test
    fun `terminal buffer exposes render frame reader callback`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            assertAll(
                { assertEquals(3, frame.columns) },
                { assertEquals(2, frame.rows) },
                { assertEquals(TerminalRenderBufferKind.PRIMARY, frame.activeBuffer) },
                { assertEquals(0, frame.cursor.column) },
                { assertEquals(0, frame.cursor.row) },
                { assertTrue(frame.cursor.visible) },
                { assertTrue(frame.cursor.blinking) },
                { assertEquals(TerminalRenderCursorShape.BLOCK, frame.cursor.shape) },
            )
        }
    }

    @Test
    fun `terminal render frame reflects mutated cursor shape`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader

        buffer.setCursorShape(TerminalRenderCursorShape.UNDERLINE)

        reader.readRenderFrame { frame ->
            assertEquals(TerminalRenderCursorShape.UNDERLINE, frame.cursor.shape)
        }

        var reportedShape: TerminalRenderCursorShape? = null
        reader.readRenderFrame { frame ->
            frame.copyCursor { _, _, _, _, shape, _ ->
                reportedShape = shape
            }
        }
        assertEquals(TerminalRenderCursorShape.UNDERLINE, reportedShape)
    }

    @Test
    fun `render frame exposes positive unique line ids for visible rows`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val first = frame.lineId(0)
            val second = frame.lineId(1)

            assertAll(
                { assertTrue(first > 0L) },
                { assertTrue(second > 0L) },
                { assertNotEquals(first, second) },
            )
        }
    }

    @Test
    fun `line ids move with content when the live viewport scrolls`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader
        buffer.writeText("one")
        buffer.carriageReturn()
        buffer.newLine()
        buffer.writeText("two")

        var secondLineId = 0L
        reader.readRenderFrame { frame ->
            secondLineId = frame.lineId(1)
        }

        buffer.carriageReturn()
        buffer.newLine()

        reader.readRenderFrame { frame ->
            assertEquals(secondLineId, frame.lineId(0))
        }
    }

    @Test
    fun `absolute range read maps retained history without a stale relative offset`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader
        buffer.writeText("a")
        buffer.carriageReturn()
        buffer.newLine()
        buffer.writeText("b")
        buffer.carriageReturn()
        buffer.newLine()
        buffer.writeText("c")

        reader.readRenderFrameForAbsoluteRange(startAbsoluteRow = 0L, endAbsoluteRow = 1L) { frame ->
            val first = copyRow(frame, row = 0)
            val second = copyRow(frame, row = 1)
            assertAll(
                { assertEquals(1, frame.scrollbackOffset) },
                { assertEquals(2, frame.rows) },
                { assertEquals('a'.code, first.codeWords[0]) },
                { assertEquals('b'.code, second.codeWords[0]) },
            )
        }
    }

    @Test
    fun `resize reflow preserves logical line id across rewrapped physical rows`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        buffer.writeText("abcdef")

        var sourceLineId = 0L
        reader.readRenderFrame { frame ->
            sourceLineId = frame.lineId(0)
        }

        buffer.resize(newWidth = 3, newHeight = 3)

        reader.readRenderFrame { frame ->
            assertAll(
                { assertEquals(sourceLineId, frame.lineId(0)) },
                { assertEquals(sourceLineId, frame.lineId(1)) },
            )
        }
    }

    @Test
    fun `empty line copies empty flags and default attrs`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 1)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertAll(
                { assertEquals(listOf(0, 0, 0), row.codeWords.toList()) },
                {
                    assertEquals(
                        listOf(
                            TerminalRenderCellFlags.EMPTY,
                            TerminalRenderCellFlags.EMPTY,
                            TerminalRenderCellFlags.EMPTY,
                        ),
                        row.flags.toList(),
                    )
                },
                { assertTrue(row.attrWords.all { it == TerminalRenderAttrs.DEFAULT }) },
            )
        }
    }

    @Test
    fun `ascii cells copy as codepoints`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeText("AB")
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertAll(
                { assertEquals('A'.code, row.codeWords[0]) },
                { assertEquals('B'.code, row.codeWords[1]) },
                { assertEquals(TerminalRenderCellFlags.CODEPOINT, row.flags[0]) },
                { assertEquals(TerminalRenderCellFlags.CODEPOINT, row.flags[1]) },
                { assertEquals(TerminalRenderCellFlags.EMPTY, row.flags[2]) },
            )
        }
    }

    @Test
    fun `wide codepoint copies leader and trailing flags`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeCodepoint(0x1F600)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertAll(
                {
                    assertEquals(
                        TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                        row.flags[0],
                    )
                },
                { assertEquals(TerminalRenderCellFlags.WIDE_TRAILING, row.flags[1]) },
                { assertEquals(0x1F600, row.codeWords[0]) },
                { assertEquals(0, row.codeWords[1]) },
            )
        }
    }

    @Test
    fun `cluster cells call sink and copy stable cluster flags`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeCluster(intArrayOf('e'.code, 0x0301), length = 2)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val clusters = mutableMapOf<Int, String>()
            val row = copyRow(frame, clusterSink = { col, text -> clusters[col] = text })

            assertAll(
                { assertEquals(TerminalRenderCellFlags.CLUSTER, row.flags[0]) },
                { assertEquals(0, row.codeWords[0]) },
                { assertEquals("e\u0301", clusters[0]) },
            )
        }
    }

    @Test
    fun `cluster cells can copy primitive cluster data without text sink`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeCluster(intArrayOf('e'.code, 0x0301), length = 2)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val copiedCluster = IntArray(4)
            var copiedColumn = -1
            var copiedLength = 0
            val row =
                copyRow(
                    frame = frame,
                    clusterDataSink = { column, codepoints, offset, length ->
                        copiedColumn = column
                        copiedLength = length
                        System.arraycopy(codepoints, offset, copiedCluster, 0, length)
                    },
                )

            assertAll(
                { assertEquals(TerminalRenderCellFlags.CLUSTER, row.flags[0]) },
                { assertEquals(0, copiedColumn) },
                { assertEquals(2, copiedLength) },
                { assertEquals(listOf('e'.code, 0x0301), copiedCluster.copyOf(copiedLength).toList()) },
            )
        }
    }

    @Test
    fun `wide cluster copies leader and trailing flags`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeCluster(intArrayOf(0x1F468, 0x200D, 0x1F469), length = 3)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val clusters = mutableMapOf<Int, String>()
            val row = copyRow(frame, clusterSink = { col, text -> clusters[col] = text })

            assertAll(
                {
                    assertEquals(
                        TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING,
                        row.flags[0],
                    )
                },
                { assertEquals(TerminalRenderCellFlags.WIDE_TRAILING, row.flags[1]) },
                { assertEquals(String(intArrayOf(0x1F468, 0x200D, 0x1F469), 0, 3), clusters[0]) },
            )
        }
    }

    @Test
    fun `cell attributes translate to public render ABI`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 1)
        buffer.setPenColors(
            foreground = CellColor.rgb(0x12_34_56),
            background = CellColor.indexed(42),
            underlineColor = CellColor.rgb(0x65_43_21),
            bold = true,
            faint = true,
            italic = true,
            underlineStyle = UnderlineStyle.DASHED,
            strikethrough = true,
            overline = true,
            blink = true,
            inverse = true,
            conceal = true,
        )
        buffer.writeCodepoint('X'.code)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)
            val attr = row.attrWords[0]
            val extra = row.extraAttrWords[0]

            assertAll(
                { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderAttrs.foregroundKind(attr)) },
                { assertEquals(0x12_34_56, TerminalRenderAttrs.foregroundValue(attr)) },
                { assertEquals(TerminalRenderColorKind.INDEXED, TerminalRenderAttrs.backgroundKind(attr)) },
                { assertEquals(42, TerminalRenderAttrs.backgroundValue(attr)) },
                { assertTrue(TerminalRenderAttrs.isBold(attr)) },
                { assertTrue(TerminalRenderAttrs.isFaint(attr)) },
                { assertTrue(TerminalRenderAttrs.isItalic(attr)) },
                { assertEquals(TerminalRenderUnderline.DASHED, TerminalRenderAttrs.underlineStyle(attr)) },
                { assertTrue(TerminalRenderAttrs.isBlink(attr)) },
                { assertTrue(TerminalRenderAttrs.isInverse(attr)) },
                { assertTrue(TerminalRenderAttrs.isInvisible(attr)) },
                { assertTrue(TerminalRenderAttrs.isStrikethrough(attr)) },
                { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderExtraAttrs.underlineColorKind(extra)) },
                { assertEquals(0x65_43_21, TerminalRenderExtraAttrs.underlineColorValue(extra)) },
                { assertTrue(TerminalRenderExtraAttrs.isOverline(extra)) },
            )
        }
    }

    @Test
    fun `indexed cell colors translate to public render ABI`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 1)
        buffer.setPenColors(
            foreground = CellColor.indexed(0),
            background = CellColor.indexed(255),
        )
        buffer.writeCodepoint('X'.code)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val attr = copyRow(frame).attrWords[0]

            assertAll(
                { assertEquals(TerminalRenderColorKind.INDEXED, TerminalRenderAttrs.foregroundKind(attr)) },
                { assertEquals(0, TerminalRenderAttrs.foregroundValue(attr)) },
                { assertEquals(TerminalRenderColorKind.INDEXED, TerminalRenderAttrs.backgroundKind(attr)) },
                { assertEquals(255, TerminalRenderAttrs.backgroundValue(attr)) },
            )
        }
    }

    @Test
    fun `rgb cell colors translate to public render ABI`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 1)
        buffer.setPenColors(
            foreground = CellColor.rgb(0x00_00_00),
            background = CellColor.rgb(0xFF_FF_FF),
        )
        buffer.writeCodepoint('X'.code)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val attr = copyRow(frame).attrWords[0]

            assertAll(
                { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderAttrs.foregroundKind(attr)) },
                { assertEquals(0x00_00_00, TerminalRenderAttrs.foregroundValue(attr)) },
                { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderAttrs.backgroundKind(attr)) },
                { assertEquals(0xFF_FF_FF, TerminalRenderAttrs.backgroundValue(attr)) },
            )
        }
    }

    @Test
    fun `reverse video is reflected in copied public attrs`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 1)
        buffer.setPenColors(
            foreground = CellColor.rgb(0x12_34_56),
            background = CellColor.indexed(42),
        )
        buffer.writeCodepoint('X'.code)
        buffer.setReverseVideo(true)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)
            val attr = row.attrWords[0]

            assertAll(
                { assertTrue(TerminalRenderAttrs.isInverse(attr)) },
                { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderAttrs.foregroundKind(attr)) },
                { assertEquals(0x12_34_56, TerminalRenderAttrs.foregroundValue(attr)) },
                { assertEquals(TerminalRenderColorKind.INDEXED, TerminalRenderAttrs.backgroundKind(attr)) },
                { assertEquals(42, TerminalRenderAttrs.backgroundValue(attr)) },
            )
        }
    }

    @Test
    fun `reverse video invalidates every retained row without changing content or mapping`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2, maxHistory = 4)
        repeat(3) { buffer.writeLogicalLine("L$it") }
        val generations = LongArray(4)
        val lineIds = LongArray(4)
        val contents = arrayOfNulls<IntArray>(4)
        var contentGeneration = 0L
        var structureGeneration = 0L
        buffer.readRenderFrame(scrollbackOffset = Int.MAX_VALUE, viewportRows = Int.MAX_VALUE) { frame ->
            assertEquals(2, frame.historySize)
            assertEquals(4, frame.rows)
            contentGeneration = frame.contentGeneration
            structureGeneration = frame.structureGeneration
            for (row in 0 until frame.rows) {
                generations[row] = frame.lineGeneration(row)
                lineIds[row] = frame.lineId(row)
                contents[row] = copyRow(frame, row).codeWords
            }
        }

        for (enabled in booleanArrayOf(true, false)) {
            buffer.setReverseVideo(enabled)

            buffer.readRenderFrame(scrollbackOffset = Int.MAX_VALUE, viewportRows = Int.MAX_VALUE) { frame ->
                for (row in 0 until frame.rows) {
                    assertNotEquals(generations[row], frame.lineGeneration(row), "render row $row")
                    assertEquals(lineIds[row], frame.lineId(row))
                    val copied = copyRow(frame, row)
                    assertArrayEquals(contents[row], copied.codeWords)
                    assertTrue(copied.attrWords.all { TerminalRenderAttrs.isInverse(it) == enabled })
                    generations[row] = frame.lineGeneration(row)
                }
                assertEquals(contentGeneration, frame.contentGeneration)
                assertEquals(structureGeneration, frame.structureGeneration)
            }
        }
    }

    @Test
    fun `setting the current reverse video mode preserves frame and row generations`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        for (enabled in booleanArrayOf(false, true)) {
            buffer.setReverseVideo(enabled)
            var generation = 0L
            val rows = LongArray(2)
            buffer.readRenderFrame { frame ->
                generation = frame.frameGeneration
                for (row in rows.indices) rows[row] = frame.lineGeneration(row)
            }

            buffer.setReverseVideo(enabled)

            buffer.readRenderFrame { frame ->
                assertEquals(generation, frame.frameGeneration)
                for (row in rows.indices) assertEquals(rows[row], frame.lineGeneration(row))
            }
        }
    }

    @Test
    fun `editing after global attribute invalidation still invalidates only the edited row`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2, maxHistory = 4)
        repeat(3) { buffer.writeLogicalLine("L$it") }
        buffer.setReverseVideo(true)
        val generations = LongArray(4)
        buffer.readRenderFrame(scrollbackOffset = Int.MAX_VALUE, viewportRows = Int.MAX_VALUE) { frame ->
            for (row in generations.indices) generations[row] = frame.lineGeneration(row)
        }

        buffer.positionCursor(col = 0, row = 0)
        buffer.writeCodepoint('X'.code)

        buffer.readRenderFrame(scrollbackOffset = Int.MAX_VALUE, viewportRows = Int.MAX_VALUE) { frame ->
            for (row in generations.indices) {
                if (row == frame.historySize) {
                    assertNotEquals(generations[row], frame.lineGeneration(row))
                    assertEquals('X'.code, copyRow(frame, row).codeWords[0])
                } else {
                    assertEquals(generations[row], frame.lineGeneration(row))
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `soft reset invalidates primary history even while the alternate buffer is active`(alternate: Boolean) {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2, maxHistory = 4)
        repeat(3) { buffer.writeLogicalLine("L$it") }
        buffer.setReverseVideo(true)
        val generations = LongArray(4)
        val lineIds = LongArray(4)
        buffer.readRenderFrame(scrollbackOffset = Int.MAX_VALUE, viewportRows = Int.MAX_VALUE) { frame ->
            for (row in generations.indices) {
                generations[row] = frame.lineGeneration(row)
                lineIds[row] = frame.lineId(row)
            }
        }
        if (alternate) {
            buffer.enterAltBuffer()
            buffer.writeCodepoint('A'.code)
        }
        var contentGeneration = 0L
        buffer.readRenderFrame { contentGeneration = it.contentGeneration }

        buffer.softReset()

        buffer.readRenderFrame { frame ->
            assertEquals(contentGeneration, frame.contentGeneration)
            for (row in 0 until frame.rows) {
                assertTrue(copyRow(frame, row).attrWords.none(TerminalRenderAttrs::isInverse))
            }
        }
        if (alternate) buffer.exitAltBuffer()
        buffer.readRenderFrame(scrollbackOffset = Int.MAX_VALUE, viewportRows = Int.MAX_VALUE) { frame ->
            for (row in generations.indices) {
                assertNotEquals(generations[row], frame.lineGeneration(row), "primary row $row")
                assertEquals(lineIds[row], frame.lineId(row))
                assertTrue(copyRow(frame, row).attrWords.none(TerminalRenderAttrs::isInverse))
            }
        }
    }

    @Test
    fun `soft reset preserves row generations when reverse video is already disabled`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val generations = LongArray(2)
        var contentGeneration = 0L
        buffer.readRenderFrame { frame ->
            contentGeneration = frame.contentGeneration
            for (row in generations.indices) generations[row] = frame.lineGeneration(row)
        }

        buffer.softReset()

        buffer.readRenderFrame { frame ->
            assertEquals(contentGeneration, frame.contentGeneration)
            for (row in generations.indices) assertEquals(generations[row], frame.lineGeneration(row))
        }
    }

    @Test
    fun `full reset invalidates rendered attributes of preserved inactive alternate rows`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        buffer.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
        buffer.writeCodepoint('A'.code)
        buffer.setReverseVideo(true)
        var generation = 0L
        var lineId = 0L
        buffer.readRenderFrame { frame ->
            generation = frame.lineGeneration(0)
            lineId = frame.lineId(0)
            assertTrue(TerminalRenderAttrs.isInverse(copyRow(frame).attrWords[0]))
        }
        buffer.exitAltBufferWithoutCursorRestore()

        buffer.reset()

        buffer.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
        buffer.readRenderFrame { frame ->
            assertEquals(lineId, frame.lineId(0))
            assertNotEquals(generation, frame.lineGeneration(0))
            val row = copyRow(frame)
            assertEquals('A'.code, row.codeWords[0])
            assertFalse(TerminalRenderAttrs.isInverse(row.attrWords[0]))
        }
    }

    @Test
    fun `global attribute invalidation changes row generations across signed overflow`() {
        val state = TerminalState(initialWidth = 2, initialHeight = 1, maxHistory = 0)
        state.ring[0].renderGeneration = Long.MAX_VALUE
        val modes = TerminalModeControllerImpl(state, CursorEngine(state))
        val frame = CoreTerminalRenderFrame(state)
        var generation = frame.use(scrollbackOffset = 0) { frame.lineGeneration(0) }

        for (enabled in booleanArrayOf(true, false)) {
            modes.setReverseVideo(enabled)
            frame.use(scrollbackOffset = 0) {
                assertNotEquals(generation, frame.lineGeneration(0))
                generation = frame.lineGeneration(0)
                assertTrue(generation < 0L)
            }
        }
    }

    @Test
    fun `hyperlink ids copy through optional array`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 1)
        buffer.setHyperlinkId(77)
        buffer.writeCodepoint('X'.code)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertEquals(77, row.hyperlinkIds[0])
        }
    }

    @Test
    fun `line metadata exposes generations and wrap flag`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader
        var oldFrame = 0L
        var oldLine = 0L

        reader.readRenderFrame { before ->
            oldFrame = before.frameGeneration
            oldLine = before.lineGeneration(0)
        }

        buffer.writeText("AB")
        buffer.writeCodepoint('C'.code)

        reader.readRenderFrame { after ->
            assertAll(
                { assertNotEquals(oldFrame, after.frameGeneration) },
                { assertNotEquals(oldLine, after.lineGeneration(0)) },
                { assertTrue(after.lineWrapped(0)) },
            )
        }
    }

    @Test
    fun `scrollback offset maps render rows without mutating live viewport`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 3, maxHistory = 5)
        val reader = buffer as TerminalRenderFrameReader
        repeat(6) { buffer.writeLogicalLine("L$it") }

        reader.readRenderFrame(scrollbackOffset = 2) { frame ->
            assertAll(
                { assertEquals(4, frame.historySize) },
                { assertEquals(2, frame.scrollbackOffset) },
                { assertFalse(frame.cursor.visible) },
                { assertEquals("L2", rowText(frame, 0)) },
                { assertEquals("L3", rowText(frame, 1)) },
                { assertEquals("L4", rowText(frame, 2)) },
            )
        }

        reader.readRenderFrame(scrollbackOffset = Int.MAX_VALUE) { frame ->
            assertAll(
                { assertEquals(frame.historySize, frame.scrollbackOffset) },
                { assertEquals("L0", rowText(frame, 0)) },
            )
        }

        reader.readRenderFrame { frame ->
            assertAll(
                { assertEquals(0, frame.scrollbackOffset) },
                { assertTrue(frame.cursor.visible) },
                { assertEquals("L4", rowText(frame, 0)) },
                { assertEquals("L5", rowText(frame, 1)) },
            )
        }
    }

    @Test
    fun `scrollback cursor row is translated into render viewport and clipped`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 3, maxHistory = 5)
        val reader = buffer as TerminalRenderFrameReader
        repeat(6) { buffer.writeLogicalLine("L$it") }
        buffer.positionCursor(col = 1, row = 0)

        reader.readRenderFrame(scrollbackOffset = 1) { frame ->
            assertAll(
                { assertEquals(1, frame.cursor.column) },
                { assertEquals(1, frame.cursor.row) },
                { assertTrue(frame.cursor.visible) },
            )
        }

        reader.readRenderFrame(scrollbackOffset = 3) { frame ->
            assertAll(
                { assertEquals(3, frame.cursor.row) },
                { assertFalse(frame.cursor.visible) },
            )
        }
    }

    @Test
    fun `scrollback render viewport can expose one overscan row`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 3, maxHistory = 5)
        val reader = buffer as TerminalRenderFrameReader
        repeat(6) { buffer.writeLogicalLine("L$it") }

        reader.readRenderFrame(scrollbackOffset = 1, viewportRows = 4) { frame ->
            assertAll(
                { assertEquals(4, frame.rows) },
                { assertEquals(1, frame.scrollbackOffset) },
                { assertEquals(3, frame.cursor.row) },
                { assertTrue(frame.cursor.visible) },
                { assertEquals("L3", rowText(frame, 0)) },
                { assertEquals("L4", rowText(frame, 1)) },
                { assertEquals("L5", rowText(frame, 2)) },
            )
        }

        reader.readRenderFrame(scrollbackOffset = 0, viewportRows = 4) { frame ->
            assertEquals(3, frame.rows)
        }
    }

    @Test
    fun `active buffer reports alternate after switch`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 1)
        val reader = buffer as TerminalRenderFrameReader

        buffer.enterAltBuffer()

        reader.readRenderFrame { frame ->
            assertEquals(TerminalRenderBufferKind.ALTERNATE, frame.activeBuffer)
        }
    }

    @Test
    fun `copyLine validates row and destination capacity`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 1)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            assertAll(
                {
                    assertThrows(IllegalArgumentException::class.java) {
                        frame.lineGeneration(1)
                    }
                },
                {
                    assertThrows(IllegalArgumentException::class.java) {
                        frame.copyLine(
                            row = 0,
                            codeWords = IntArray(1),
                            attrWords = LongArray(2),
                            flags = IntArray(2),
                        )
                    }
                },
            )
        }
    }

    private fun copyRow(
        frame: TerminalRenderFrame,
        row: Int = 0,
        clusterSink: ((Int, String) -> Unit)? = null,
        clusterDataSink: ((Int, IntArray, Int, Int) -> Unit)? = null,
    ): CopiedRow {
        val copied = CopiedRow(frame.columns)
        frame.copyLine(
            row = row,
            codeWords = copied.codeWords,
            attrWords = copied.attrWords,
            flags = copied.flags,
            extraAttrWords = copied.extraAttrWords,
            hyperlinkIds = copied.hyperlinkIds,
            clusterSink =
                if (clusterSink == null) {
                    null
                } else {
                    TerminalRenderClusterSink(clusterSink)
                },
            clusterDataSink =
                if (clusterDataSink == null) {
                    null
                } else {
                    TerminalRenderClusterDataSink(clusterDataSink)
                },
        )
        copied.flags.forEach {
            assertTrue(TerminalRenderCellFlags.isValidCombination(it), "invalid flag combination: $it")
        }
        return copied
    }

    private fun rowText(
        frame: TerminalRenderFrame,
        row: Int,
    ): String =
        copyRow(frame, row)
            .codeWords
            .map { if (it == 0) ' ' else it.toChar() }
            .joinToString("")
            .trimEnd()

    private fun DefaultTerminalBuffer.writeLogicalLine(text: String) {
        writeText(text)
        carriageReturn()
        newLine()
    }

    private class CopiedRow(
        columns: Int,
    ) {
        val codeWords = IntArray(columns)
        val attrWords = LongArray(columns)
        val flags = IntArray(columns)
        val extraAttrWords = LongArray(columns)
        val hyperlinkIds = IntArray(columns)
    }
}

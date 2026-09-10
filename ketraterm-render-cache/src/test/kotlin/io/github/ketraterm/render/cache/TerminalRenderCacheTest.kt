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
package io.github.ketraterm.render.cache

import io.github.ketraterm.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRenderCacheTest {
    @Test
    fun `content generation survives frame and cache copies independently of frame generation`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        val source = TerminalRenderCache(1, 1)
        val destination = TerminalRenderCache(1, 1)
        for (generation in longArrayOf(5L, Long.MAX_VALUE, Long.MIN_VALUE, 0L)) {
            frame.contentGeneration = generation
            frame.frameGeneration++
            source.updateFrom(frame.reader)
            destination.updateFrom(source)
            assertEquals(generation, source.contentGeneration)
            assertEquals(generation, destination.contentGeneration)
        }
    }

    @Test
    fun `constructor rejects non-positive dimensions`() {
        assertAll(
            { assertThrows(IllegalArgumentException::class.java) { TerminalRenderCache(0, 1) } },
            { assertThrows(IllegalArgumentException::class.java) { TerminalRenderCache(1, 0) } },
            { assertThrows(IllegalArgumentException::class.java) { TerminalRenderCache(1, 1, rowCapacityReserve = -1) } },
            { assertThrows(IllegalArgumentException::class.java) { TerminalRenderCache(1, Int.MAX_VALUE, rowCapacityReserve = 1) } },
        )
    }

    @Test
    fun `first update copies all visible rows`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)

        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals("abc", cache.rowText(0)) },
            { assertEquals("def", cache.rowText(1)) },
            { assertEquals(6, cache.codeWords.size) },
            { assertEquals(0, cache.rowOffset(0)) },
            { assertEquals(3, cache.rowOffset(1)) },
            { assertEquals(frame.frameGeneration, cache.frameGeneration) },
            { assertEquals(frame.structureGeneration, cache.structureGeneration) },
            { assertEquals(frame.cursor, cache.cursor) },
            { assertEquals(1, frame.copyCounts[0]) },
            { assertEquals(1, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `second update skips rows with unchanged structure and line generation`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)

        cache.updateFrom(frame.reader)
        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals(1, frame.copyCounts[0]) },
            { assertEquals(1, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `line id change triggers recopying of the row`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)

        cache.updateFrom(frame.reader)
        frame.setLineId(row = 1, lineId = 99)
        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals(1L, cache.lineIds[0]) },
            { assertEquals(99L, cache.lineIds[1]) },
            { assertEquals(1, frame.copyCounts[0]) },
            { assertEquals(2, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `line id storage resizes with the frame`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        val cache = TerminalRenderCache(columns = 1, rows = 1)

        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals(2, cache.lineIds.size) },
            { assertEquals(1L, cache.lineIds[0]) },
            { assertEquals(2L, cache.lineIds[1]) },
        )
    }

    @Test
    fun `line generation change recopies only that row`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)
        cache.updateFrom(frame.reader)

        frame.setRow(1, "xyz")
        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals("abc", cache.rowText(0)) },
            { assertEquals("xyz", cache.rowText(1)) },
            { assertEquals(1, frame.copyCounts[0]) },
            { assertEquals(2, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `blink text metadata is copied per row`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        frame.setBlink(row = 1, column = 1, blink = true)
        val cache = TerminalRenderCache(columns = 3, rows = 2)

        cache.updateFrom(frame.reader)

        assertAll(
            { assertTrue(cache.hasBlinkingText) },
            { assertFalse(cache.lineHasBlinkingText[0]) },
            { assertTrue(cache.lineHasBlinkingText[1]) },
        )
    }

    @Test
    fun `blink text metadata is cleared when a row is recopied without blink`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setBlink(row = 0, column = 0, blink = true)
        val cache = TerminalRenderCache(columns = 3, rows = 2)
        cache.updateFrom(frame.reader)

        frame.setBlink(row = 0, column = 0, blink = false)
        cache.updateFrom(frame.reader)

        assertAll(
            { assertFalse(cache.hasBlinkingText) },
            { assertFalse(cache.lineHasBlinkingText[0]) },
        )
    }

    @Test
    fun `blink text metadata is preserved for unchanged rows`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        frame.setBlink(row = 0, column = 1, blink = true)
        val cache = TerminalRenderCache(columns = 3, rows = 2)
        cache.updateFrom(frame.reader)

        frame.setRow(1, "xyz")
        cache.updateFrom(frame.reader)

        assertAll(
            { assertTrue(cache.hasBlinkingText) },
            { assertTrue(cache.lineHasBlinkingText[0]) },
            { assertFalse(cache.lineHasBlinkingText[1]) },
            { assertEquals(1, frame.copyCounts[0]) },
            { assertEquals(2, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `structure generation change recopies all rows`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setRow(0, "abc")
        frame.setRow(1, "def")
        val cache = TerminalRenderCache(columns = 3, rows = 2)
        cache.updateFrom(frame.reader)

        frame.structureGeneration++
        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals(2, frame.copyCounts[0]) },
            { assertEquals(2, frame.copyCounts[1]) },
        )
    }

    @Test
    fun `shape change resizes storage and recopies all rows`() {
        val frame = MutableFrame(columns = 2, rows = 1)
        frame.setRow(0, "ab")
        val cache = TerminalRenderCache(columns = 3, rows = 2)

        cache.updateFrom(frame.reader)

        assertAll(
            { assertTrue(cache.shapeChangedOnLastUpdate) },
            { assertEquals(2, cache.columns) },
            { assertEquals(1, cache.rows) },
            { assertEquals("ab", cache.rowText(0)) },
        )
    }

    @Test
    fun `cluster text is copied and cleared when row is recopied`() {
        val frame = MutableFrame(columns = 3, rows = 1)
        frame.setClusterRow("e\u0301x")
        val cache = TerminalRenderCache(columns = 3, rows = 1)
        cache.updateFrom(frame.reader)

        assertEquals("e\u0301", cache.clusterText(0, 0))

        frame.setRow(0, "abc")
        cache.updateFrom(frame.reader)

        assertAll(
            { assertNull(cache.clusterText(0, 0)) },
            { assertEquals("abc", cache.rowText(0)) },
        )
    }

    @Test
    fun `cluster copy truncates absurd reported lengths before allocating`() {
        val frame = LongClusterFrame(reportedLength = 50_000)
        val cache = TerminalRenderCache(columns = 1, rows = 1)

        cache.updateFrom(frame.reader)

        val ref = cache.clusterRefs[cache.rowOffset(0)]
        val offset = cache.clusterOffset(ref)
        val length = cache.clusterLength(ref)
        assertAll(
            { assertEquals(256, length) },
            { assertEquals('z'.code, cache.clusterCodepoints[offset]) },
            { assertEquals('z'.code, cache.clusterCodepoints[offset + length - 1]) },
            { assertEquals("z".repeat(256), cache.clusterText(0, 0)) },
        )
    }

    @Test
    fun `cursor change updates cursor without dirtying rows`() {
        val frame = MutableFrame(columns = 3, rows = 1)
        frame.setRow(0, "abc")
        val cache = TerminalRenderCache(columns = 3, rows = 1)
        cache.updateFrom(frame.reader)

        frame.cursor = frame.cursor.copy(column = 2, generation = frame.cursor.generation + 1)
        frame.frameGeneration++
        cache.updateFrom(frame.reader)

        assertAll(
            { assertTrue(cache.cursorChangedOnLastUpdate) },
            { assertEquals(2, cache.cursor?.column) },
            { assertEquals(1, frame.copyCounts[0]) },
        )
    }

    @Test
    fun `cursor copy uses primitive sink without reading cursor object`() {
        val frame = PrimitiveCursorFrame()
        val cache = TerminalRenderCache(columns = 1, rows = 1)

        cache.updateFrom(frame.reader)

        assertAll(
            { assertEquals(2, cache.cursorColumn) },
            { assertEquals(3, cache.cursorRow) },
            { assertTrue(cache.cursorVisible) },
            { assertTrue(cache.cursorBlinking) },
            { assertEquals(TerminalRenderCursorShape.BAR, cache.cursorShape) },
            { assertEquals(7L, cache.cursorGeneration) },
            { assertTrue(cache.cursorChangedOnLastUpdate) },
        )
    }

    @Test
    fun `active buffer is cached`() {
        val frame = MutableFrame(columns = 3, rows = 1)
        frame.activeBuffer = TerminalRenderBufferKind.ALTERNATE
        val cache = TerminalRenderCache(columns = 3, rows = 1)

        cache.updateFrom(frame.reader)

        assertEquals(TerminalRenderBufferKind.ALTERNATE, cache.activeBuffer)
    }

    @Test
    fun `scrollback offset change recopies rows even when generations match`() {
        val frame = OffsetFrame()
        val cache = TerminalRenderCache(columns = 3, rows = 1)

        cache.updateFrom(frame.reader, scrollbackOffset = 0)
        cache.updateFrom(frame.reader, scrollbackOffset = 1)

        assertAll(
            { assertEquals("old", cache.rowText(0)) },
            { assertEquals(2, cache.historySize) },
            { assertEquals(1, cache.scrollbackOffset) },
            { assertFalse(cache.cursor?.visible == true) },
            { assertTrue(cache.cursorChangedOnLastUpdate) },
            { assertEquals(2, frame.copyCount) },
        )
    }

    @Test
    fun `viewport row request resizes cache to resolved overscan rows`() {
        val frame = OffsetFrame()
        val cache = TerminalRenderCache(columns = 3, rows = 1)

        cache.updateFrom(frame.reader, scrollbackOffset = 1, viewportRows = 2)

        assertAll(
            { assertEquals(1, frame.lastRequestedOffset) },
            { assertEquals(2, frame.lastRequestedRows) },
            { assertEquals(2, cache.rows) },
            { assertTrue(cache.shapeChangedOnLastUpdate) },
        )
    }

    @Test
    fun `reserved rows reuse primitive planes while overscan shape toggles`() {
        val frame = OffsetFrame()
        val cache = TerminalRenderCache(columns = 3, rows = 1, rowCapacityReserve = 1)
        val initialCodeWords = cache.codeWords
        val initialAttrWords = cache.attrWords
        val initialFlags = cache.flags
        val initialLineGenerations = cache.lineGenerations

        cache.updateFrom(frame.reader, scrollbackOffset = 1, viewportRows = 2)

        assertAll(
            { assertEquals(2, cache.rows) },
            { assertTrue(cache.shapeChangedOnLastUpdate) },
            { assertSame(initialCodeWords, cache.codeWords) },
            { assertSame(initialAttrWords, cache.attrWords) },
            { assertSame(initialFlags, cache.flags) },
            { assertSame(initialLineGenerations, cache.lineGenerations) },
        )

        cache.updateFrom(frame.reader, scrollbackOffset = 0)

        assertAll(
            { assertEquals(1, cache.rows) },
            { assertTrue(cache.shapeChangedOnLastUpdate) },
            { assertSame(initialCodeWords, cache.codeWords) },
            { assertSame(initialLineGenerations, cache.lineGenerations) },
        )
    }

    @Test
    fun `cache copy preserves primitive frame state`() {
        val frame = MutableFrame(columns = 3, rows = 2)
        frame.setClusterRow("e\u0301x")
        frame.setRow(1, "xyz")
        frame.setLineId(row = 1, lineId = 99L)
        frame.setWrapped(row = 1, wrapped = true)
        frame.setBlink(row = 1, column = 2, blink = true)
        frame.activeBuffer = TerminalRenderBufferKind.ALTERNATE
        frame.cursor =
            TerminalRenderCursor(
                column = 2,
                row = 1,
                visible = true,
                blinking = true,
                shape = TerminalRenderCursorShape.BAR,
                generation = 42L,
            )
        val source = TerminalRenderCache(columns = 3, rows = 2)
        source.updateFrom(frame.reader)
        source.hyperlinkIds[source.rowOffset(1) + 1] = 17
        source.extraAttrWords[source.rowOffset(1) + 2] = 23L

        val destination = TerminalRenderCache(columns = 1, rows = 1)
        destination.updateFrom(source)

        val cellCount = source.columns * source.rows
        assertAll(
            { assertEquals(source.columns, destination.columns) },
            { assertEquals(source.rows, destination.rows) },
            { assertArrayEquals(source.codeWords.copyOf(cellCount), destination.codeWords.copyOf(cellCount)) },
            { assertArrayEquals(source.attrWords.copyOf(cellCount), destination.attrWords.copyOf(cellCount)) },
            { assertArrayEquals(source.flags.copyOf(cellCount), destination.flags.copyOf(cellCount)) },
            { assertArrayEquals(source.extraAttrWords.copyOf(cellCount), destination.extraAttrWords.copyOf(cellCount)) },
            { assertArrayEquals(source.hyperlinkIds.copyOf(cellCount), destination.hyperlinkIds.copyOf(cellCount)) },
            { assertArrayEquals(source.clusterRefs.copyOf(cellCount), destination.clusterRefs.copyOf(cellCount)) },
            { assertArrayEquals(source.lineGenerations.copyOf(source.rows), destination.lineGenerations.copyOf(destination.rows)) },
            { assertArrayEquals(source.lineIds.copyOf(source.rows), destination.lineIds.copyOf(destination.rows)) },
            { assertArrayEquals(source.lineWrapped.copyOf(source.rows), destination.lineWrapped.copyOf(destination.rows)) },
            { assertEquals("e\u0301", destination.clusterText(0, 0)) },
            { assertEquals(source.cursor, destination.cursor) },
            { assertEquals(source.palette, destination.palette) },
            { assertEquals(source.activeBuffer, destination.activeBuffer) },
            { assertEquals(source.frameGeneration, destination.frameGeneration) },
            { assertEquals(source.structureGeneration, destination.structureGeneration) },
            { assertTrue(destination.hasBlinkingText) },
            { assertTrue(destination.shapeChangedOnLastUpdate) },
            { assertTrue(destination.cursorChangedOnLastUpdate) },
        )
    }

    @Test
    fun `cache copy clears rows and clusters left stale by a smaller frame`() {
        val largeFrame = MutableFrame(columns = 3, rows = 3)
        largeFrame.setClusterRow("e\u0301x")
        largeFrame.setRow(1, "abc")
        largeFrame.setRow(2, "def")
        val largeSource = TerminalRenderCache(columns = 3, rows = 3)
        largeSource.updateFrom(largeFrame.reader)

        val destination = TerminalRenderCache(columns = 3, rows = 1, rowCapacityReserve = 3)
        destination.updateFrom(largeSource)

        val smallFrame = MutableFrame(columns = 3, rows = 1)
        smallFrame.setRow(0, "new")
        val smallSource = TerminalRenderCache(columns = 3, rows = 1)
        smallSource.updateFrom(smallFrame.reader)
        destination.updateFrom(smallSource)

        assertAll(
            { assertEquals("new", destination.rowText(0)) },
            { assertTrue(destination.codeWords.sliceArray(3 until 9).all { it == 0 }, "code words") },
            { assertTrue(destination.attrWords.sliceArray(3 until 9).all { it == TerminalRenderAttrs.DEFAULT }, "attributes") },
            { assertTrue(destination.flags.sliceArray(3 until 9).all { it == TerminalRenderCellFlags.EMPTY }, "flags") },
            { assertTrue(destination.hyperlinkIds.sliceArray(3 until 9).all { it == 0 }, "hyperlinks") },
            { assertTrue(destination.clusterRefs.sliceArray(3 until 9).all { it == 0L }, "cluster references") },
            { assertTrue(destination.clusterCodepoints.all { it == 0 }, "cluster codepoints") },
            { assertTrue(destination.lineIds.sliceArray(1 until 3).all { it == 0L }, "line ids") },
            { assertTrue(destination.lineWrapped.sliceArray(1 until 3).none { it }, "wrap flags") },
        )
    }

    private fun TerminalRenderCache.rowText(row: Int): String {
        val start = rowOffset(row)
        return buildString(columns) {
            var column = 0
            while (column < columns) {
                val code = codeWords[start + column]
                append(if (code == 0) ' ' else code.toChar())
                column++
            }
        }
    }

    private class PrimitiveCursorFrame : TerminalRenderFrame {
        override val columns: Int = 1
        override val rows: Int = 1
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor
            get() = error("cursor object must not be read by TerminalRenderCache")

        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(this@PrimitiveCursorFrame)
                }
            }

        override fun lineGeneration(row: Int): Long = 1L

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
            codeWords[codeOffset] = 0
            attrWords[attrOffset] = TerminalRenderAttrs.DEFAULT
            flags[flagOffset] = TerminalRenderCellFlags.EMPTY
            extraAttrWords?.set(extraAttrOffset, TerminalRenderExtraAttrs.DEFAULT)
            hyperlinkIds?.set(hyperlinkOffset, 0)
        }

        override fun copyCursor(sink: TerminalRenderCursorSink) {
            sink.onCursor(
                column = 2,
                row = 3,
                visible = true,
                blinking = true,
                shape = TerminalRenderCursorShape.BAR,
                generation = 7L,
            )
        }
    }

    private class LongClusterFrame(
        private val reportedLength: Int,
    ) : TerminalRenderFrame {
        override val columns: Int = 1
        override val rows: Int = 1
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1L,
            )
        private val clusterCodepoints = IntArray(256) { 'z'.code }

        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(this@LongClusterFrame)
                }
            }

        override fun lineGeneration(row: Int): Long = 1L

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
            codeWords[codeOffset] = 0
            attrWords[attrOffset] = TerminalRenderAttrs.DEFAULT
            flags[flagOffset] = TerminalRenderCellFlags.CLUSTER
            extraAttrWords?.set(extraAttrOffset, TerminalRenderExtraAttrs.DEFAULT)
            hyperlinkIds?.set(hyperlinkOffset, 0)
            clusterDataSink?.onCluster(0, clusterCodepoints, 0, reportedLength)
        }
    }

    private class MutableFrame(
        override val columns: Int,
        override val rows: Int,
    ) : TerminalRenderFrame {
        private val codeWords = Array(rows) { IntArray(columns) }
        private val flags = Array(rows) { IntArray(columns) { TerminalRenderCellFlags.EMPTY } }
        private val attrs = Array(rows) { LongArray(columns) }
        private val clusters = Array(rows) { arrayOfNulls<String>(columns) }

        val copyCounts = IntArray(rows)

        override var frameGeneration: Long = 0L
        override var contentGeneration: Long = 0L
        override var structureGeneration: Long = 0L
        override var activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override var cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = true,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 0L,
            )

        private val lineGenerations = LongArray(rows)
        private val lineIds = LongArray(rows) { row -> row + 1L }
        private val wrapped = BooleanArray(rows)

        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(this@MutableFrame)
                }
            }

        fun setRow(
            row: Int,
            text: String,
        ) {
            require(text.length <= columns)
            var col = 0
            while (col < columns) {
                val code = text.getOrNull(col)?.code ?: 0
                codeWords[row][col] = code
                flags[row][col] = if (code == 0) TerminalRenderCellFlags.EMPTY else TerminalRenderCellFlags.CODEPOINT
                attrs[row][col] = TerminalRenderAttrs.DEFAULT
                clusters[row][col] = null
                col++
            }
            lineGenerations[row]++
            frameGeneration++
        }

        fun setClusterRow(text: String) {
            codeWords[0].fill(0)
            flags[0].fill(TerminalRenderCellFlags.EMPTY)
            clusters[0].fill(null)
            flags[0][0] = TerminalRenderCellFlags.CLUSTER
            clusters[0][0] = "e\u0301"
            codeWords[0][1] = 'x'.code
            flags[0][1] = TerminalRenderCellFlags.CODEPOINT
            lineGenerations[0]++
            frameGeneration++
            require(text.isNotEmpty())
        }

        fun setBlink(
            row: Int,
            column: Int,
            blink: Boolean,
        ) {
            attrs[row][column] = TerminalRenderAttrs.pack(blink = blink)
            lineGenerations[row]++
            frameGeneration++
        }

        fun setLineId(
            row: Int,
            lineId: Long,
        ) {
            lineIds[row] = lineId
            frameGeneration++
        }

        fun setWrapped(
            row: Int,
            wrapped: Boolean,
        ) {
            this.wrapped[row] = wrapped
            frameGeneration++
        }

        override fun lineGeneration(row: Int): Long = lineGenerations[row]

        override fun lineId(row: Int): Long = lineIds[row]

        override fun lineWrapped(row: Int): Boolean = wrapped[row]

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
            copyCounts[row]++
            var col = 0
            while (col < columns) {
                codeWords[codeOffset + col] = this.codeWords[row][col]
                attrWords[attrOffset + col] = attrs[row][col]
                flags[flagOffset + col] = this.flags[row][col]
                extraAttrWords?.set(extraAttrOffset + col, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + col, 0)
                val cluster = clusters[row][col]
                if (cluster != null) {
                    clusterSink?.onCluster(col, cluster)
                    clusterDataSink?.onCluster(col, clusterCodepoints(cluster), 0, cluster.codePointCount(0, cluster.length))
                }
                col++
            }
        }

        private fun clusterCodepoints(text: String): IntArray {
            val codepoints = IntArray(text.codePointCount(0, text.length))
            var codepointIndex = 0
            var charIndex = 0
            while (charIndex < text.length) {
                val codepoint = Character.codePointAt(text, charIndex)
                codepoints[codepointIndex++] = codepoint
                charIndex += Character.charCount(codepoint)
            }
            return codepoints
        }
    }

    private class OffsetFrame : TerminalRenderFrame {
        private var currentOffset: Int = 0
        private var currentRows: Int = 1

        var copyCount: Int = 0
            private set
        var lastRequestedOffset: Int = -1
            private set
        var lastRequestedRows: Int = -1
            private set

        override val columns: Int = 3
        override val rows: Int
            get() = currentRows
        override val historySize: Int = 2
        override val scrollbackOffset: Int
            get() = currentOffset
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor
            get() =
                TerminalRenderCursor(
                    column = 0,
                    row = 0,
                    visible = currentOffset == 0,
                    blinking = false,
                    shape = TerminalRenderCursorShape.BLOCK,
                    generation = 1L,
                )

        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    readRenderFrame(scrollbackOffset = 0, consumer = consumer)
                }

                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    lastRequestedOffset = scrollbackOffset
                    lastRequestedRows = 0
                    currentOffset = scrollbackOffset.coerceIn(0, historySize)
                    currentRows = 1
                    consumer.accept(this@OffsetFrame)
                }

                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    viewportRows: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    lastRequestedOffset = scrollbackOffset
                    lastRequestedRows = viewportRows
                    currentOffset = scrollbackOffset.coerceIn(0, historySize)
                    currentRows = viewportRows.coerceAtLeast(1)
                    consumer.accept(this@OffsetFrame)
                }
            }

        override fun lineGeneration(row: Int): Long = 1L

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
            copyCount++
            val text = if (currentOffset == 0) "new" else "old"
            var col = 0
            while (col < columns) {
                codeWords[codeOffset + col] = text[col].code
                attrWords[attrOffset + col] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + col] = TerminalRenderCellFlags.CODEPOINT
                extraAttrWords?.set(extraAttrOffset + col, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + col, 0)
                col++
            }
        }
    }
}

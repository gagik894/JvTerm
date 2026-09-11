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
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.management.ManagementFactory
import java.text.Bidi
import kotlin.test.*

class TerminalBidiLayoutTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `overscan row count transitions allocate no bidi storage after warmup`(rtl: Boolean) {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled)
        val cells =
            Array(25) { Array(80) { TestCell(codeWord = if (rtl) 0x05D0 else 'A'.code, flags = TerminalRenderCellFlags.CODEPOINT) } }
        val frames = arrayOf(TestRenderFrame(cells.take(24).toTypedArray()), TestRenderFrame(cells))
        val cache = TerminalRenderCache(80, 24, rowCapacityReserve = 1)
        val geometry = TerminalBidiLayout()
        val threadId = Thread.currentThread().threadId()
        var minimum = Long.MAX_VALUE
        repeat(10) { batch ->
            var allocated = 0L
            var iteration = 0
            while (iteration < 1_000) {
                // Frame copying is deliberately outside the bidi allocation measurement.
                cache.accept(frames[iteration and 1])
                val before = allocationBean.getThreadAllocatedBytes(threadId)
                val row = geometry.row(cache, cache.rows - 1)
                allocated += allocationBean.getThreadAllocatedBytes(threadId) - before
                assertEquals(rtl, row != null)
                iteration++
            }
            if (batch >= 5) minimum = minOf(minimum, allocated)
        }
        assertEquals(0L, minimum)
    }

    @Test
    fun `row capacity growth and overscan retain unchanged permutations`() {
        val cells = Array(3) { Array(3) { TestCell(codeWord = 0x05D0 + it, flags = TerminalRenderCellFlags.CODEPOINT) } }
        val small = TestRenderFrame(arrayOf(cells[0]))
        val large = TestRenderFrame(cells)
        val cache = TerminalRenderCache(3, 1, rowCapacityReserve = 1)
        val geometry = TerminalBidiLayout()
        cache.accept(small)
        val first = assertNotNull(geometry.row(cache, 0))
        cache.accept(large)
        assertSame(first, geometry.row(cache, 0))
        val last = assertNotNull(geometry.row(cache, 2))
        cache.accept(small)
        assertNull(geometry.row(cache, 2))
        cache.accept(large)
        assertSame(first, geometry.row(cache, 0))
        assertSame(last, geometry.row(cache, 2))
    }

    @Test
    fun `inactive row invalidates when content changes before it reappears`() {
        val cells = Array(2) { Array(3) { TestCell(codeWord = 0x05D0 + it, flags = TerminalRenderCellFlags.CODEPOINT) } }
        val large = TestRenderFrame(cells)
        val cache = TerminalRenderCache(3, 1, rowCapacityReserve = 1)
        val geometry = TerminalBidiLayout()
        cache.accept(large)
        assertNotNull(geometry.row(cache, 1))
        cache.accept(TestRenderFrame(arrayOf(cells[0])))
        val changedCells = arrayOf(cells[0], Array(3) { TestCell(codeWord = 'A'.code + it, flags = TerminalRenderCellFlags.CODEPOINT) })
        val changed =
            object : TestRenderFrame(changedCells) {
                override fun lineGeneration(row: Int): Long = if (row == 1) 2 else 1
            }
        cache.accept(changed)
        assertNull(geometry.row(cache, 1))
    }

    @Test
    fun `buffer switches invalidate retained inactive rows even when metadata matches`() {
        val primaryCells = Array(2) { Array(3) { TestCell(codeWord = 0x05D0 + it, flags = TerminalRenderCellFlags.CODEPOINT) } }
        val alternateCells = Array(2) { Array(3) { TestCell(codeWord = 'A'.code + it, flags = TerminalRenderCellFlags.CODEPOINT) } }
        val primary = TestRenderFrame(primaryCells)
        val alternateSmall =
            object : TestRenderFrame(arrayOf(alternateCells[0])) {
                override val activeBuffer = TerminalRenderBufferKind.ALTERNATE
            }
        val alternateLarge =
            object : TestRenderFrame(alternateCells) {
                override val activeBuffer = TerminalRenderBufferKind.ALTERNATE
            }
        val cache = TerminalRenderCache(3, 2)
        val geometry = TerminalBidiLayout()
        cache.accept(primary)
        assertNotNull(geometry.row(cache, 1))
        cache.accept(alternateSmall)
        assertNull(geometry.row(cache, 0))
        cache.accept(alternateLarge)
        assertNull(geometry.row(cache, 1))
    }

    @ParameterizedTest
    @ValueSource(strings = ["structure", "history", "scrollback", "discarded"])
    fun `mapping changes invalidate unavailable line identities including inactive rows`(change: String) {
        val rtl = Array(3) { TestCell(codeWord = 0x05D0 + it, flags = TerminalRenderCellFlags.CODEPOINT) }
        val ltr = Array(3) { TestCell(codeWord = 'A'.code + it, flags = TerminalRenderCellFlags.CODEPOINT) }

        fun frame(
            cells: Array<Array<TestCell>>,
            afterChange: Boolean,
        ): TestRenderFrame =
            object : TestRenderFrame(cells) {
                override val structureGeneration = if (afterChange && change == "structure") 2L else 1L
                override val historySize = if (afterChange && change == "history") 2 else 1
                override val scrollbackOffset = if (afterChange && change == "scrollback") 0 else 1
                override val discardedCount = if (afterChange && change == "discarded") 1L else 0L

                override fun lineId(row: Int): Long = if (row == 1) 10L else 0L
            }
        val cache = TerminalRenderCache(3, 3)
        val geometry = TerminalBidiLayout()
        cache.accept(frame(arrayOf(rtl, rtl, rtl), afterChange = false))
        assertNotNull(geometry.row(cache, 0))
        val identified = assertNotNull(geometry.row(cache, 1))
        assertNotNull(geometry.row(cache, 2))

        cache.accept(frame(arrayOf(ltr, rtl), afterChange = true))

        assertEquals(0L, cache.lineIds[0])
        assertEquals(1L, cache.lineGenerations[0])
        assertSame(identified, geometry.row(cache, 1))
        assertNull(geometry.row(cache, 0))
        assertNull(geometry.row(cache, 2))

        cache.accept(frame(arrayOf(ltr, rtl, ltr), afterChange = true))

        assertSame(identified, geometry.row(cache, 1))
        assertNull(geometry.row(cache, 2))
    }

    @Test
    fun `cached bidi mapping and range projection allocate no memory`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled)
        val cache = renderCache(TestRenderFrame.text("AB \u05D0\u05D1\u05D2"))
        val geometry = TerminalBidiLayout()
        repeat(5) { queryRows(geometry, cache) }
        val threadId = Thread.currentThread().threadId()
        var minimum = Long.MAX_VALUE
        repeat(5) {
            val before = allocationBean.getThreadAllocatedBytes(threadId)
            val checksum = queryRows(geometry, cache)
            val allocated = allocationBean.getThreadAllocatedBytes(threadId) - before
            minimum = minOf(minimum, allocated)
            assertTrue(checksum > 0)
        }
        assertEquals(0L, minimum)
    }

    private fun queryRows(
        geometry: TerminalBidiLayout,
        cache: TerminalRenderCache,
    ): Int {
        var checksum = 0
        var iteration = 0
        while (iteration < 100_000) {
            val row = requireNotNull(geometry.row(cache, 0))
            checksum += row.logicalColumn(3)
            forEachVisualCellSpan(row, 1, 4) { start, end -> checksum += end - start }
            iteration++
        }
        return checksum
    }

    @ParameterizedTest
    @ValueSource(strings = ["אבג", "AB אבג", "אב AB גד", "אב 12 גד", "A אב 12 גד Z", "אב (AB) גד"])
    fun `cell permutation agrees with Unicode bidi levels and round trips`(text: String) {
        val cache = renderCache(TestRenderFrame.text(text))
        val row = assertNotNull(TerminalBidiLayout().row(cache, 0))
        val bidi = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        val levels = ByteArray(text.length) { bidi.getLevelAt(it).toByte() }
        val expected = Array<Any>(text.length) { it }
        Bidi.reorderVisually(levels, 0, expected, 0, expected.size)

        for (visual in expected.indices) {
            assertEquals(expected[visual], row.logicalColumn(visual))
            assertEquals(visual, row.visualColumn(row.logicalColumn(visual)))
        }
    }

    @Test
    fun `wide emoji cluster remains an adjacent leader trailer pair in rtl`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 0x05D0, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(flags = TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING, cluster = "🙂\uFE0F"),
                            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                            TestCell(codeWord = 0x05D1, flags = TerminalRenderCellFlags.CODEPOINT),
                        ),
                    ),
                ),
            )
        val row = assertNotNull(TerminalBidiLayout().row(cache, 0))
        assertContentEquals(intArrayOf(3, 1, 2, 0), IntArray(4) { row.logicalColumn(it) })
    }

    @Test
    fun `logical selection spanning directional runs produces disjoint visual ranges`() {
        val cache = renderCache(TestRenderFrame.text("AB אבג"))
        val row = assertNotNull(TerminalBidiLayout().row(cache, 0))
        val painted = BooleanArray(6)
        forEachVisualCellSpan(row, 1, 4) { start, end ->
            for (column in start until end) painted[column] = true
        }
        assertContentEquals(booleanArrayOf(false, true, true, false, false, true), painted)
    }

    @Test
    fun `row cache invalidates on content line identity cache identity and resize`() {
        val layout = TerminalBidiLayout()
        val cache = renderCache(TestRenderFrame.text("אבג"))
        val first = assertNotNull(layout.row(cache, 0))
        assertSame(first, layout.row(cache, 0))
        cache.lineIds[0]++
        assertNotSame(first, layout.row(cache, 0))
        cache.codeWords[0] = 'A'.code
        cache.codeWords[1] = 'B'.code
        cache.codeWords[2] = 'C'.code
        cache.lineGenerations[0]++
        assertNull(layout.row(cache, 0))
        val other = renderCache(TestRenderFrame.text("אבג"))
        assertNotNull(layout.row(other, 0))
        other.updateFrom(TestRenderFrame.text("AB אבג"))
        val resized = assertNotNull(layout.row(other, 0))
        assertEquals(5, resized.logicalColumn(3))
        layout.reset()
        assertNotSame(resized, layout.row(other, 0))
    }

    @Test
    fun `supplementary rtl codepoints retain their direction as single cells`() {
        val text = String(Character.toChars(0x1E900)) + String(Character.toChars(0x1E901))
        val row = assertNotNull(TerminalBidiLayout().row(renderCache(TestRenderFrame.text(text)), 0))
        assertEquals(1, row.logicalColumn(0))
        assertEquals(0, row.logicalColumn(1))
    }
}

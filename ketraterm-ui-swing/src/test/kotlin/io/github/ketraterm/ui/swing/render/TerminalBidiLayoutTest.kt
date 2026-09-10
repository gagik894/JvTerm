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

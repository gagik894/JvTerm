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
package io.github.ketraterm.ui.swing.render.painter

import com.sun.management.ThreadMXBean
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.render.renderCache
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals

class TerminalTextRunStyleTest {
    @Test
    fun `warmed row configuration and run scanning allocate no memory`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled)

        val cache = renderCache(TestRenderFrame.text("A".repeat(80)))
        cache.hyperlinkIds.fill(7)
        val style = TerminalTextRunStyle()
        val threadId = Thread.currentThread().threadId()

        // Warm class initialization and JIT compilation outside the measured region.
        repeat(5) { scanRows(style, cache, 10_000) }
        var minimumAllocated = Long.MAX_VALUE
        repeat(5) {
            val before = allocationBean.getThreadAllocatedBytes(threadId)
            val matches = scanRows(style, cache, 10_000)
            val allocated = allocationBean.getThreadAllocatedBytes(threadId) - before
            minimumAllocated = minOf(minimumAllocated, allocated)
            assertEquals(10_000 * 77, matches)
        }
        assertEquals(0L, minimumAllocated, "Style scanning must not allocate per row, run, or cell")
    }

    private fun scanRows(
        style: TerminalTextRunStyle,
        cache: TerminalRenderCache,
        rowCount: Int,
    ): Int {
        val palette = cache.palette
        var matches = 0
        var row = 0
        while (row < rowCount) {
            style.configureRow(
                row = 0,
                textBlinkVisible = row and 1 == 0,
                hyperlinkIds = cache.hyperlinkIds,
                hoveredHyperlinkId = 7,
                hoveredHyperlinkStartRow = 0,
                hoveredHyperlinkStartColumn = 20,
                hoveredHyperlinkEndRow = 0,
                hoveredHyperlinkEndColumn = 60,
                hyperlinkActivationHover = row and 1 == 0,
                hyperlinkActivationForeground = 0xFF4DA3FF.toInt(),
            )
            style.begin(cache, palette, 0, 0)
            var column = 1
            while (column < cache.columns) {
                if (style.matches(cache, palette, 0, column)) {
                    matches++
                } else {
                    style.begin(cache, palette, 0, column)
                }
                column++
            }
            row++
        }
        return matches
    }
}

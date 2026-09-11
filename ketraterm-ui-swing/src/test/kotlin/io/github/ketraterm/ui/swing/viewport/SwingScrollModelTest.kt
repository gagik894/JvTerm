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
package io.github.ketraterm.ui.swing.viewport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingScrollModelTest {
    @Test
    fun `resize installs its discard baseline without treating reflow as new output`() {
        for (scrollOnOutput in listOf(false, true)) {
            val model = SwingScrollModel()
            model.clamp(historySize = 10, discardedCount = 3L, scrollOnOutput = scrollOnOutput)
            model.scrollTo(2.0, historySize = 10)
            model.animateTo(6, nowNanos = 0L)

            model.anchorAfterResize(offset = 4, historySize = 10, discardedCount = 8L)

            assertFalse(model.isAnimating)
            assertFalse(model.clamp(historySize = 10, discardedCount = 8L, scrollOnOutput = scrollOnOutput))
            assertEquals(4.0, model.preciseScrollbackOffset)
            assertFalse(model.advance(100_000_000L))

            model.clamp(historySize = 10, discardedCount = 9L, scrollOnOutput = scrollOnOutput)
            assertEquals(if (scrollOnOutput) 0.0 else 5.0, model.preciseScrollbackOffset)
        }
    }

    @Test
    fun `fractional animation position uses a ceiling anchor and overscan`() {
        val model = SwingScrollModel()

        assertTrue(model.scrollTo(0.4, historySize = 10))
        assertEquals(1, model.requestedOffset)
        assertTrue(model.needsOverscan)

        assertTrue(model.scrollTo(1.1, historySize = 10))
        assertEquals(2, model.requestedOffset)
    }

    @Test
    fun `scroll offset clamps to available history`() {
        val model = SwingScrollModel()

        assertTrue(model.scrollTo(12.0, historySize = 5))
        assertEquals(5, model.requestedOffset)
    }

    @Test
    fun `absolute fractional input preserves precise visual position`() {
        val model = SwingScrollModel()

        assertTrue(model.scrollTo(2.5, historySize = 10))

        assertEquals(2.5, model.preciseScrollbackOffset)
        assertEquals(3, model.requestedOffset)
    }

    @Test
    fun `unchanged or clamped positions report no movement`() {
        val model = SwingScrollModel()

        assertFalse(model.scrollTo(0.0, historySize = 5))
        assertFalse(model.scrollTo(-1.0, historySize = 5))
    }

    @Test
    fun `reset returns to live viewport`() {
        val model = SwingScrollModel()

        model.scrollTo(3.0, historySize = 5)
        model.reset()
        assertEquals(0, model.requestedOffset)
    }

    @Test
    fun `fractional scroll requests an overscan row`() {
        val model = SwingScrollModel()

        model.scrollTo(0.25, historySize = 10)

        assertEquals(4, model.requestedRows(renderRows = 3))
    }

    @Test
    fun `scrollOnOutput = true snaps to bottom when history grows`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 5)

        model.clamp(historySize = 6, discardedCount = 0L, scrollOnOutput = true)
        assertEquals(0.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `scrollOnOutput = false locks content when history grows`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 5)
        model.clamp(historySize = 5, discardedCount = 0L, scrollOnOutput = false)

        model.clamp(historySize = 6, discardedCount = 0L, scrollOnOutput = false)
        assertEquals(3.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `scrollOnOutput = false locks content when history reaches capacity and discards lines`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.clamp(historySize = 10, discardedCount = 0L, scrollOnOutput = false)

        model.clamp(historySize = 10, discardedCount = 1L, scrollOnOutput = false)
        assertEquals(3.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `scrollOnOutput = false does not snap if already at bottom`() {
        val model = SwingScrollModel()
        model.clamp(historySize = 5, discardedCount = 0L, scrollOnOutput = false)

        model.clamp(historySize = 6, discardedCount = 0L, scrollOnOutput = false)
        assertEquals(0.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `alternate buffer transition clears an active fractional primary viewport and returns live`() {
        val model = SwingScrollModel()
        model.scrollTo(4.5, historySize = 10)
        model.clamp(historySize = 10, discardedCount = 7L, scrollOnOutput = false)

        model.clamp(historySize = 0, discardedCount = 0L, scrollOnOutput = false)

        assertEquals(0.0, model.preciseScrollbackOffset)
        assertEquals(0, model.requestedOffset)
        assertFalse(model.needsOverscan)

        model.clamp(historySize = 10, discardedCount = 7L, scrollOnOutput = false)

        assertEquals(0.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `output rebases active position and destination without extending its deadline`() {
        for (discardAtCapacity in listOf(false, true)) {
            val model = SwingScrollModel()
            model.scrollTo(2.0, historySize = 10)
            model.animateTo(6, nowNanos = 0L)
            model.advance(50_000_000L)
            assertEquals(5.5, model.preciseScrollbackOffset)

            assertTrue(
                model.clamp(
                    historySize = if (discardAtCapacity) 10 else 12,
                    discardedCount = if (discardAtCapacity) 2L else 0L,
                    scrollOnOutput = false,
                ),
            )

            assertEquals(7.5, model.preciseScrollbackOffset)
            assertEquals(8, model.targetRow)
            assertTrue(model.isAnimating)
            assertFalse(model.advance(50_000_000L), "Sampling the rebased timeline must not jump")
            assertTrue(model.advance(100_000_000L))
            assertEquals(8.0, model.preciseScrollbackOffset)
            assertFalse(model.isAnimating)
        }
    }

    @Test
    fun `discarded animation destination clamps without moving current content or its deadline`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(10, nowNanos = 0L)
        model.advance(25_000_000L)
        assertEquals(6.625, model.preciseScrollbackOffset)

        model.clamp(historySize = 10, discardedCount = 1L, scrollOnOutput = false)

        assertEquals(7.625, model.preciseScrollbackOffset)
        assertEquals(10, model.targetRow)
        model.advance(25_000_000L)
        assertEquals(7.625, model.preciseScrollbackOffset, 1.0e-12)
        model.advance(50_000_000L)
        assertTrue(model.preciseScrollbackOffset > 7.625)
        model.advance(100_000_000L)
        assertEquals(10.0, model.preciseScrollbackOffset)
        assertFalse(model.isAnimating)
    }

    @Test
    fun `output reaching an animated viewport boundary settles exactly on that row`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(10, nowNanos = 0L)
        model.advance(50_000_000L)
        assertEquals(9.0, model.preciseScrollbackOffset)

        model.clamp(historySize = 10, discardedCount = 2L, scrollOnOutput = false)

        assertEquals(10.0, model.preciseScrollbackOffset)
        assertEquals(10, model.targetRow)
        assertFalse(model.isAnimating)
        assertFalse(model.advance(100_000_000L))
    }

    @Test
    fun `new output rebases a requested history destination before its first tick`() {
        val model = SwingScrollModel()
        model.clamp(historySize = 10, discardedCount = 0L, scrollOnOutput = false)
        model.animateTo(3, nowNanos = 0L)

        model.clamp(historySize = 11, discardedCount = 0L, scrollOnOutput = false)

        assertEquals(1.0, model.preciseScrollbackOffset)
        assertEquals(4, model.targetRow)
        assertFalse(model.advance(0L))
        model.advance(100_000_000L)
        assertEquals(4.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `following output cancels the timeline so later ticks remain live`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)
        model.advance(50_000_000L)

        assertTrue(model.clamp(historySize = 11, discardedCount = 0L, scrollOnOutput = true))

        assertEquals(0.0, model.preciseScrollbackOffset)
        assertEquals(0, model.targetRow)
        assertFalse(model.isAnimating)
        assertFalse(model.advance(100_000_000L))
    }

    @Test
    fun `following output returns live when retained history also shrinks`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)
        model.advance(50_000_000L)

        model.clamp(historySize = 8, discardedCount = 3L, scrollOnOutput = true)

        assertEquals(0.0, model.preciseScrollbackOffset)
        assertFalse(model.isAnimating)
        assertFalse(model.advance(100_000_000L))
    }

    @Test
    fun `history shrink cancels on a surviving row and reports an overscan-only change`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)
        model.advance(50_000_000L)
        assertEquals(6, model.requestedOffset)
        assertTrue(model.needsOverscan)

        assertTrue(model.clamp(historySize = 6, discardedCount = 0L, scrollOnOutput = false))

        assertEquals(6.0, model.preciseScrollbackOffset)
        assertEquals(6, model.requestedOffset)
        assertFalse(model.needsOverscan)
        assertFalse(model.isAnimating)
        assertFalse(model.advance(100_000_000L))
    }

    @Test
    fun `buffer reset cannot leave an old timeline moving the new viewport`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)
        model.advance(50_000_000L)

        model.clamp(historySize = 0, discardedCount = 0L, scrollOnOutput = false)
        model.clamp(historySize = 10, discardedCount = 0L, scrollOnOutput = false)

        assertEquals(0.0, model.preciseScrollbackOffset)
        assertFalse(model.isAnimating)
        assertFalse(model.advance(100_000_000L))
        assertEquals(0.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `retarget samples current time and accumulates from the existing destination`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)

        assertTrue(model.animateBy(2, nowNanos = 50_000_000L))

        assertEquals(5.5, model.preciseScrollbackOffset)
        assertEquals(8, model.targetRow)
        assertFalse(model.advance(50_000_000L))
        model.advance(150_000_000L)
        assertEquals(8.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `repeating an unchanged destination preserves the original completion deadline`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)

        assertTrue(model.animateTo(6, nowNanos = 50_000_000L))
        assertEquals(5.5, model.preciseScrollbackOffset)
        model.advance(100_000_000L)

        assertEquals(6.0, model.preciseScrollbackOffset)
        assertFalse(model.isAnimating)
    }

    @Test
    fun `reversal to the current row cancels the previous destination`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)

        assertFalse(model.animateBy(-4, nowNanos = 0L))

        assertEquals(2.0, model.preciseScrollbackOffset)
        assertEquals(2, model.targetRow)
        assertFalse(model.isAnimating)
        assertFalse(model.advance(100_000_000L))
    }

    @Test
    fun `finish settles on destination but cancellation keeps the current precise position`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)
        model.advance(50_000_000L)

        model.cancelAnimation()
        assertEquals(5.5, model.preciseScrollbackOffset)
        assertFalse(model.advance(100_000_000L))
        assertFalse(model.finish())

        model.animateTo(8, nowNanos = 100_000_000L)
        assertTrue(model.finish())
        assertEquals(8.0, model.preciseScrollbackOffset)
        assertFalse(model.isAnimating)
        assertFalse(model.finish())
    }

    @Test
    fun `immediate positioning cancels old motion even when the precise position is unchanged`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)

        assertFalse(model.scrollTo(2.0, historySize = 10))

        assertFalse(model.isAnimating)
        assertFalse(model.advance(100_000_000L))
        assertEquals(2.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `cell height changes affect pixel geometry without changing history or motion`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)
        model.advance(50_000_000L)

        model.updateCellHeight(20)

        assertEquals(10, model.historySize)
        assertEquals(5.5, model.preciseScrollbackOffset)
        assertEquals(110.0, model.visualScrollOffsetPixels)
        assertEquals(200, model.visualScrollRangePixels)
        assertEquals(20, model.cellHeightPixels)
        assertTrue(model.isAnimating)
        model.advance(100_000_000L)
        assertEquals(6.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `large discarded counts cannot overflow a positive output shift`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.animateTo(6, nowNanos = 0L)
        model.advance(50_000_000L)

        model.clamp(historySize = 11, discardedCount = Long.MAX_VALUE, scrollOnOutput = false)

        assertEquals(11.0, model.preciseScrollbackOffset)
        assertFalse(model.isAnimating)
        assertFalse(model.advance(100_000_000L))

        model.clamp(historySize = 10, discardedCount = 0L, scrollOnOutput = false)
        assertEquals(10.0, model.preciseScrollbackOffset)
    }
}

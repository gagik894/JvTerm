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

class SmoothRowScrollAnimationTest {
    @Test
    fun `whole-row destination is eased and finishes exactly on grid`() {
        val animation = SmoothRowScrollAnimation()

        assertTrue(animation.retargetBy(currentOffset = 0.0, deltaRows = 3, historySize = 10, nowNanos = 0L))

        val halfway = animation.positionAt(50_000_000L)
        assertTrue(halfway > 0.0 && halfway < 3.0)
        assertTrue(animation.isActive)
        assertEquals(3.0, animation.positionAt(100_000_000L))
        assertFalse(animation.isActive)
    }

    @Test
    fun `repeated input accumulates from integer destination without visual jump`() {
        val animation = SmoothRowScrollAnimation()
        animation.retargetBy(currentOffset = 0.0, deltaRows = 3, historySize = 10, nowNanos = 0L)
        val inFlightOffset = animation.positionAt(50_000_000L)

        assertTrue(
            animation.retargetBy(
                currentOffset = inFlightOffset,
                deltaRows = 3,
                historySize = 10,
                nowNanos = 50_000_000L,
            ),
        )

        assertEquals(6, animation.targetRow)
        assertEquals(inFlightOffset, animation.positionAt(50_000_000L))
        assertEquals(6.0, animation.positionAt(150_000_000L))
    }

    @Test
    fun `destination clamps to history boundary`() {
        val animation = SmoothRowScrollAnimation()

        assertTrue(animation.retargetBy(currentOffset = 4.0, deltaRows = 20, historySize = 5, nowNanos = 0L))
        assertEquals(5.0, animation.positionAt(100_000_000L))
        assertFalse(animation.retargetBy(currentOffset = 5.0, deltaRows = 1, historySize = 5, nowNanos = 200_000_000L))
    }

    @Test
    fun `absolute integer retarget starts from current visual position`() {
        val animation = SmoothRowScrollAnimation()

        assertTrue(animation.retargetTo(currentOffset = 2.25, targetRow = 8, historySize = 10, nowNanos = 0L))
        assertEquals(2.25, animation.positionAt(0L))
        assertEquals(8.0, animation.positionAt(100_000_000L))
    }

    @Test
    fun `retargeting to current position cancels the old destination`() {
        val animation = SmoothRowScrollAnimation()
        animation.retargetTo(currentOffset = 2.0, targetRow = 8, historySize = 10, nowNanos = 0L)

        assertFalse(animation.retargetTo(currentOffset = 2.0, targetRow = 2, historySize = 10, nowNanos = 0L))

        assertFalse(animation.isActive)
        assertEquals(2, animation.targetRow)
        assertEquals(2.0, animation.positionAt(100_000_000L))
    }

    @Test
    fun `retargeting unchanged destination does not restart the deadline`() {
        val animation = SmoothRowScrollAnimation()
        animation.retargetTo(currentOffset = 0.0, targetRow = 8, historySize = 10, nowNanos = 0L)
        val current = animation.positionAt(50_000_000L)

        assertTrue(animation.retargetTo(currentOffset = current, targetRow = 8, historySize = 10, nowNanos = 50_000_000L))

        assertEquals(current, animation.positionAt(50_000_000L))
        assertEquals(8.0, animation.positionAt(100_000_000L))
        assertFalse(animation.isActive)
    }

    @Test
    fun `capped destination rebase remains stable near its completion deadline`() {
        val animation = SmoothRowScrollAnimation()
        animation.retargetTo(currentOffset = 2.0, targetRow = 10, historySize = 10, nowNanos = 0L)
        animation.positionAt(99_999_999L)

        animation.rebase(currentOffset = 9.5, deltaRows = 1L, historySize = 10)

        assertEquals(9.5, animation.positionAt(99_999_999L), 1.0e-12)
        assertEquals(10.0, animation.positionAt(100_000_000L))
        assertFalse(animation.isActive)
    }
}

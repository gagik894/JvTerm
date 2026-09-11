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

import io.github.ketraterm.ui.swing.api.TerminalViewportListener
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingViewportScrollingTest {
    @Test
    fun `unchanged programmatic scrolling does not repaint but scrollbar release publishes completion`() =
        withViewport { fixture ->
            val controller = fixture.controller
            assertTrue(controller.scrollTo(2.0, historySize = 10))
            fixture.notifications.clear()

            assertFalse(controller.scrollTo(2.0, historySize = 10))
            assertTrue(fixture.notifications.isEmpty())

            assertFalse(controller.scrollToRow(2))
            assertEquals(listOf(false to true), fixture.notifications)
        }

    @Test
    fun `precise input does not move viewport before a whole row is accumulated`() =
        withViewport { fixture ->
            val controller = fixture.controller
            repeat(3) {
                assertTrue(controller.scrollByPreciseRows(0.25))
            }
            assertEquals(0.0, controller.preciseOffset)
            assertTrue(fixture.notifications.isEmpty())

            assertTrue(controller.scrollByPreciseRows(0.25))
            controller.finishScroll()

            assertEquals(1.0, controller.preciseOffset)
            assertEquals(listOf(true to true), fixture.notifications)
        }

    @Test
    fun `direct scrollbar row cancels animation and applies without lag`() =
        withViewport { fixture ->
            val controller = fixture.controller
            assertTrue(controller.scrollByRows(4))

            assertTrue(controller.jumpToRow(2))

            assertEquals(2.0, controller.preciseOffset)
            assertEquals(listOf(true to false), fixture.notifications)
            controller.advanceScroll(System.nanoTime() + 1_000_000_000L)
            assertEquals(2.0, controller.preciseOffset)
            assertEquals(1, fixture.notifications.size)
        }

    @Test
    fun `history publication rebases pending animation before the next timer tick`() =
        withViewport { fixture ->
            val controller = fixture.controller
            controller.jumpToRow(2)
            assertTrue(controller.scrollByRows(4))
            fixture.notifications.clear()

            assertTrue(controller.clamp(historySize = 11, discardedCount = 0L, scrollOnOutput = false))

            assertEquals(3.0, controller.preciseOffset)
            assertTrue(fixture.notifications.isEmpty())
            controller.advanceScroll(System.nanoTime() + 1_000_000_000L)
            assertEquals(7.0, controller.preciseOffset)
            assertEquals(listOf(true to true), fixture.notifications)
        }

    @Test
    fun `follow output cancels pending animation and ignores a stale timer callback`() =
        withViewport { fixture ->
            val controller = fixture.controller
            controller.jumpToRow(2)
            assertTrue(controller.scrollToRow(6))
            fixture.notifications.clear()

            assertTrue(controller.clamp(historySize = 11, discardedCount = 0L, scrollOnOutput = true))
            controller.advanceScroll(System.nanoTime() + 1_000_000_000L)
            controller.finishScroll()

            assertEquals(0.0, controller.preciseOffset)
            assertEquals(0, controller.requestedOffset)
            assertTrue(fixture.notifications.isEmpty())
        }

    @Test
    fun `reset clears animation and accumulated device input before the next source`() =
        withViewport { fixture ->
            val controller = fixture.controller
            assertTrue(controller.scrollByRows(4))
            assertTrue(controller.scrollByPreciseRows(0.75))

            controller.reset()
            fixture.notifications.clear()
            controller.advanceScroll(System.nanoTime() + 1_000_000_000L)
            assertEquals(0.0, controller.preciseOffset)
            assertTrue(fixture.notifications.isEmpty())

            controller.clamp(historySize = 10, discardedCount = 0L, scrollOnOutput = false)
            assertTrue(controller.scrollByPreciseRows(0.25))
            controller.finishScroll()
            assertEquals(0.0, controller.preciseOffset)
            assertTrue(fixture.notifications.isEmpty())
        }

    @Test
    fun `cancel preserves the current position and clears pending device input`() =
        withViewport { fixture ->
            val controller = fixture.controller
            controller.scrollTo(2.25, historySize = 10)
            assertTrue(controller.scrollToRow(6))
            assertTrue(controller.scrollByPreciseRows(0.75))

            controller.cancelScroll()
            fixture.notifications.clear()
            controller.advanceScroll(System.nanoTime() + 1_000_000_000L)
            assertEquals(2.25, controller.preciseOffset)
            assertTrue(fixture.notifications.isEmpty())

            assertTrue(controller.scrollByPreciseRows(0.25))
            controller.finishScroll()
            assertEquals(2.25, controller.preciseOffset)
            assertTrue(fixture.notifications.isEmpty())
        }

    @Test
    fun `whole row input discards a partial precise input remainder`() =
        withViewport { fixture ->
            val controller = fixture.controller
            assertTrue(controller.scrollByPreciseRows(0.75))
            assertTrue(controller.scrollByRows(2))
            controller.finishScroll()
            assertEquals(2.0, controller.preciseOffset)

            fixture.notifications.clear()
            assertTrue(controller.scrollByPreciseRows(0.25))
            controller.finishScroll()
            assertEquals(2.0, controller.preciseOffset)
            assertTrue(fixture.notifications.isEmpty())
        }

    @Test
    fun `fractional motion only changes the render mapping at anchor and overscan transitions`() =
        withViewport { fixture ->
            val controller = fixture.controller
            assertTrue(controller.scrollTo(2.25, historySize = 10))
            assertEquals(3, controller.requestedOffset)
            assertEquals(25, controller.requestedRows(24))
            assertEquals(listOf(true to true), fixture.notifications)

            fixture.notifications.clear()
            assertTrue(controller.scrollTo(2.5, historySize = 10))
            assertEquals(3, controller.requestedOffset)
            assertEquals(25, controller.requestedRows(24))
            assertEquals(listOf(false to true), fixture.notifications)

            fixture.notifications.clear()
            assertTrue(controller.jumpToRow(3))
            assertEquals(3, controller.requestedOffset)
            assertEquals(24, controller.requestedRows(24))
            assertEquals(listOf(true to false), fixture.notifications)
        }

    @Test
    fun `finish applies the destination once and ignores subsequent timer callbacks`() =
        withViewport { fixture ->
            val controller = fixture.controller
            assertTrue(controller.scrollToRow(4))
            controller.finishScroll()
            assertEquals(4.0, controller.preciseOffset)
            assertEquals(listOf(true to true), fixture.notifications)

            controller.finishScroll()
            controller.advanceScroll(System.nanoTime() + 1_000_000_000L)
            assertEquals(1, fixture.notifications.size)
            assertFalse(controller.jumpToRow(4))
            assertEquals(1, fixture.notifications.size)
        }

    private fun withViewport(test: (RecordingViewport) -> Unit) {
        SwingUtilities.invokeAndWait {
            val fixture = RecordingViewport()
            try {
                test(fixture)
            } finally {
                fixture.controller.cancelScroll()
            }
        }
    }

    private class RecordingViewport {
        val notifications = mutableListOf<Pair<Boolean, Boolean>>()
        val controller =
            SwingViewportController(TerminalViewportListener.NONE) { mappingChanged, scrollComplete ->
                notifications += mappingChanged to scrollComplete
            }.apply {
                clamp(historySize = 10, discardedCount = 0L, scrollOnOutput = false)
            }
    }
}

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

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.api.TerminalViewportState
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SwingViewportControllerTest {
    private val settings = SwingSettings(padding = SwingPadding(3, 5, 7, 11), shellIntegrationDecorationGutterWidth = 0)
    private val metrics =
        SwingMetrics(
            cellWidth = 10,
            cellHeight = 20,
            baseline = 15,
            underlineY = 16,
            strikethroughY = 10,
            overlineY = 0,
            cursorStrokeWidth = 2,
        )

    @Nested
    inner class GridSizing {
        @Test
        fun `visible grid size uses full horizontal and vertical padding`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            val size =
                controller.visibleGridSizeOnEdt(
                    settings = settings,
                    metrics = metrics,
                    componentWidth = 216,
                    componentHeight = 130,
                )

            assertEquals(20, size.width)
            assertEquals(6, size.height)
            assertEquals(size, controller.visibleGridSizeSnapshot())
        }

        @Test
        fun `alternate screen visible grid uses explicit alternate chrome instead of primary gutters`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }
            val settings =
                SwingSettings(
                    padding = SwingPadding(0, 4, 8, 12),
                    alternateScreenPadding = SwingPadding(0, 8, 8, 8),
                )

            val primary =
                controller.visibleGridSizeOnEdt(
                    settings = settings,
                    metrics = metrics,
                    componentWidth = 212,
                    componentHeight = 128,
                    activeBuffer = TerminalRenderBufferKind.PRIMARY,
                )
            val alternate =
                controller.visibleGridSizeOnEdt(
                    settings = settings,
                    metrics = metrics,
                    componentWidth = 212,
                    componentHeight = 128,
                    activeBuffer = TerminalRenderBufferKind.ALTERNATE,
                )

            assertEquals(18, primary.width)
            assertEquals(19, alternate.width)
            assertEquals(primary.height, alternate.height)
        }

        @Test
        fun `visible render rows cover partial pixel rows without changing grid rows`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            assertEquals(
                6,
                controller.visibleGridRows(
                    settings = settings,
                    metrics = metrics,
                    componentHeight = 130,
                ),
            )
            assertEquals(
                7,
                controller.visibleRenderRows(
                    settings = settings,
                    metrics = metrics,
                    componentHeight = 131,
                ),
            )
        }

        @Test
        fun `partial viewport plus fractional animation requests two rows beyond the grid`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }
            val componentHeight = settings.padding.top + settings.padding.bottom + metrics.cellHeight * 11 - 1

            assertEquals(10, controller.visibleGridRows(settings, metrics, componentHeight))
            val renderRows = controller.visibleRenderRows(settings, metrics, componentHeight)
            assertEquals(11, renderRows)

            controller.scrollTo(offsetLines = 4.25, historySize = 10)

            assertEquals(12, controller.requestedRows(renderRows))
        }

        @Test
        fun `visible grid size clamps tiny components to one cell`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            val size =
                controller.visibleGridSizeOnEdt(
                    settings = settings,
                    metrics = metrics,
                    componentWidth = 1,
                    componentHeight = 1,
                )

            assertEquals(1, size.width)
            assertEquals(1, size.height)
        }
    }

    @Nested
    inner class ViewportPublishing {
        @Test
        fun `publishViewportState stores snapshot and notifies listener`() {
            val listener = RecordingViewportListener()
            val controller = SwingViewportController(listener)

            controller.updateVisualMetrics(historySize = 100, discardedCount = 0L, cellHeight = 20, visualOverflowPixels = 0)
            assertTrue(controller.scrollTo(offsetLines = 12.5, historySize = 100))
            controller.publishViewportState(
                historySize = 100,
                visibleRows = 24,
                renderRows = 25,
                viewportHeightPixels = 480,
                contentHeightPixels = 500,
            )

            val snapshot = controller.viewportStateSnapshot()
            assertEquals(
                TerminalViewportState(
                    historySize = 100,
                    scrollbackOffset = 12.5,
                    renderOffset = 13,
                    visibleRows = 24,
                    requestedRows = 26,
                    visualScrollOffsetPixels = 250.0,
                    visualScrollRangePixels = 2000,
                    viewportHeightPixels = 480,
                    contentHeightPixels = 500,
                    cellHeightPixels = 20,
                ),
                snapshot,
            )
            assertEquals(snapshot, listener.lastState)
        }

        @Test
        fun `publishViewportState can update snapshot without listener callback`() {
            val listener = RecordingViewportListener()
            val controller = SwingViewportController(listener)

            controller.updateVisualMetrics(historySize = 10, discardedCount = 0L, cellHeight = 20, visualOverflowPixels = 0)
            controller.scrollTo(offsetLines = 3.0, historySize = 10)
            controller.publishViewportState(
                historySize = 10,
                visibleRows = 5,
                renderRows = 6,
                viewportHeightPixels = 100,
                contentHeightPixels = 120,
                notifyListener = false,
            )

            assertEquals(
                TerminalViewportState(
                    historySize = 10,
                    scrollbackOffset = 3.0,
                    renderOffset = 3,
                    visibleRows = 5,
                    requestedRows = 6,
                    visualScrollOffsetPixels = 60.0,
                    visualScrollRangePixels = 200,
                    viewportHeightPixels = 100,
                    contentHeightPixels = 120,
                    cellHeightPixels = 20,
                ),
                controller.viewportStateSnapshot(),
            )
            assertEquals(0, listener.callCount)
        }

        @Test
        fun `publishViewportState can notify primitive listener without full snapshot`() {
            val listener = RecordingViewportListener()
            val controller = SwingViewportController(listener)
            controller.updateVisualMetrics(historySize = 10, discardedCount = 0L, cellHeight = 20, visualOverflowPixels = 0)
            controller.scrollTo(offsetLines = 2.5, historySize = 10)

            controller.publishViewportState(
                historySize = 10,
                visibleRows = 5,
                renderRows = 5,
                viewportHeightPixels = 100,
                contentHeightPixels = 120,
                notifyListener = false,
                notifyPrimitiveListener = true,
            )

            assertEquals(1, listener.callCount)
            assertEquals(2.5, listener.lastState?.scrollbackOffset)
            assertEquals(3, listener.lastState?.renderOffset)
        }
    }

    @Nested
    inner class ScrollState {
        @Test
        fun `resize anchoring preserves whole-row scroll offset`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 8.0, historySize = 100)
            val requestedOffset = controller.resizeRequestedOffset()
            controller.anchorAfterResize(
                newOffset = requestedOffset + 10,
                newHistorySize = 100,
            )

            assertEquals(18, controller.requestedOffset)
            assertEquals(18.0, controller.viewportOffsetForAssertion())
        }

        @Test
        fun `contentOriginY applies fractional smooth scroll translation`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 2.25, historySize = 10)

            assertEquals(
                -15.0,
                controller.contentOriginY(
                    cacheScrollbackOffset = 3,
                    cellHeight = 20,
                ),
            )
        }

        @Test
        fun `contentOriginY keeps exact row offsets unshifted`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 1.0, historySize = 10)

            assertEquals(
                0.0,
                controller.contentOriginY(
                    cacheScrollbackOffset = 1,
                    cellHeight = 20,
                ),
            )
        }

        @Test
        fun `visual metrics reject decorator overflow for fixed grid geometry`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            assertThrows(IllegalArgumentException::class.java) {
                controller.updateVisualMetrics(historySize = 0, discardedCount = 0L, cellHeight = 20, visualOverflowPixels = 12)
            }
        }

        @Test
        fun `clamp reports whether requested offset changed`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 10.0, historySize = 10)

            assertTrue(controller.clamp(historySize = 3, discardedCount = 0L, scrollOnOutput = true))
            assertEquals(3, controller.requestedOffset)
            assertFalse(controller.clamp(historySize = 3, discardedCount = 0L, scrollOnOutput = true))
        }

        @Test
        fun `reset returns viewport to live output`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            controller.scrollTo(offsetLines = 4.5, historySize = 10)
            controller.reset()
            controller.updateVisualMetrics(historySize = 10, discardedCount = 0L, cellHeight = 20, visualOverflowPixels = 0)
            controller.publishViewportState(
                historySize = 10,
                visibleRows = 4,
                renderRows = 4,
                viewportHeightPixels = 80,
                contentHeightPixels = 80,
                notifyListener = false,
            )

            assertEquals(
                TerminalViewportState(
                    historySize = 10,
                    scrollbackOffset = 0.0,
                    renderOffset = 0,
                    visibleRows = 4,
                    requestedRows = 4,
                    visualScrollOffsetPixels = 0.0,
                    visualScrollRangePixels = 200,
                    viewportHeightPixels = 80,
                    contentHeightPixels = 80,
                    cellHeightPixels = 20,
                ),
                controller.viewportStateSnapshot(),
            )
        }

        @Test
        fun `scrollTo rejects NaN offsets`() {
            val controller = SwingViewportController { _, _, _, _, _ -> }

            assertThrows(IllegalArgumentException::class.java) {
                controller.scrollTo(offsetLines = Double.NaN, historySize = 10)
            }
        }
    }

    private class RecordingViewportListener : io.github.ketraterm.ui.swing.api.TerminalViewportListener {
        var callCount = 0
        var lastState: TerminalViewportState? = null

        override fun viewportChanged(
            historySize: Int,
            scrollbackOffset: Double,
            renderOffset: Int,
            visibleRows: Int,
            requestedRows: Int,
        ) {
            callCount++
            lastState = TerminalViewportState(historySize, scrollbackOffset, renderOffset, visibleRows, requestedRows)
        }

        override fun viewportStateChanged(state: TerminalViewportState) {
            callCount++
            lastState = state
        }
    }

    private fun SwingViewportController.viewportOffsetForAssertion(): Double {
        publishViewportState(
            historySize = 100,
            visibleRows = 1,
            renderRows = 1,
            viewportHeightPixels = 20,
            contentHeightPixels = 20,
            notifyListener = false,
        )
        return viewportStateSnapshot().scrollbackOffset
    }
}

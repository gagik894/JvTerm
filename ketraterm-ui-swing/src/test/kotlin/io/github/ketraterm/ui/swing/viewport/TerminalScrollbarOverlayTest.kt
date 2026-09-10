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
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalScrollbarOverlayTest {
    @Test
    fun `thumb is painted inside the reserved right inset`() {
        val overlay = TerminalScrollbarOverlay()
        val settings = SwingSettings(padding = SwingPadding(0, 4, 8, 10))

        val thumb =
            overlay.thumbBounds(
                settings = settings,
                activeBuffer = TerminalRenderBufferKind.PRIMARY,
                componentWidth = 110,
                componentHeight = 108,
                state = viewportState(scrollbackOffset = 0.0),
            )

        assertNotNull(thumb)
        assertEquals(102, thumb!!.x)
        assertEquals(6, thumb.width)
        assertTrue(thumb.y > 0)
        assertTrue(thumb.y + thumb.height <= 100)
    }

    @Test
    fun `alternate screen uses small edge inset and hides thumb`() {
        val overlay = TerminalScrollbarOverlay()
        val settings = SwingSettings(padding = SwingPadding(0, 4, 8, 10))

        assertTrue(
            overlay.containsGutter(
                settings = settings,
                activeBuffer = TerminalRenderBufferKind.ALTERNATE,
                componentWidth = 110,
                componentHeight = 108,
                x = 109,
                y = 20,
            ),
        )
        assertNull(
            overlay.thumbBounds(
                settings = settings,
                activeBuffer = TerminalRenderBufferKind.ALTERNATE,
                componentWidth = 110,
                componentHeight = 108,
                state = viewportState(scrollbackOffset = 4.0),
            ),
        )
    }

    @Test
    fun `dragging maps bottom origin thumb movement to terminal scrollback offset`() {
        val overlay = TerminalScrollbarOverlay()
        val settings = SwingSettings(padding = SwingPadding(0, 4, 8, 10))
        var requestedOffset = -1
        var requestedAdjusting = false

        val handled =
            overlay.handlePressed(
                x = 105,
                y = 50,
                settings = settings,
                activeBuffer = TerminalRenderBufferKind.PRIMARY,
                componentWidth = 110,
                componentHeight = 108,
                state = viewportState(scrollbackOffset = 0.0),
            ) { offset, adjusting ->
                requestedOffset = offset
                requestedAdjusting = adjusting
            }

        assertTrue(handled)
        assertTrue(requestedOffset in 0..10)
        assertTrue(requestedAdjusting)

        overlay.handleReleased(
            y = 0,
            settings = settings,
            activeBuffer = TerminalRenderBufferKind.PRIMARY,
            componentHeight = 108,
            state = viewportState(scrollbackOffset = requestedOffset.toDouble()),
        ) { offset, adjusting ->
            requestedOffset = offset
            requestedAdjusting = adjusting
        }

        assertEquals(10, requestedOffset)
        assertFalse(requestedAdjusting)
    }

    private fun viewportState(scrollbackOffset: Double): TerminalViewportState =
        TerminalViewportState(
            historySize = 10,
            scrollbackOffset = scrollbackOffset,
            renderOffset = scrollbackOffset.toInt(),
            visibleRows = 5,
            requestedRows = 5,
            visualScrollOffsetPixels = scrollbackOffset * 20.0,
            visualScrollRangePixels = 200,
            viewportHeightPixels = 100,
            contentHeightPixels = 100,
            cellHeightPixels = 20,
        )
}

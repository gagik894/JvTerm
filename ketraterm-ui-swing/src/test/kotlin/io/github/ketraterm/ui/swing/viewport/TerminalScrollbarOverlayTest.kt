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

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.api.TerminalViewportState
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.*
import java.awt.image.BufferedImage

class TerminalScrollbarOverlayTest {
    @Test
    fun `thumb is painted inside the reserved right inset`() {
        val overlay = TerminalScrollbarOverlay()
        val settings = SwingSettings(padding = SwingPadding(0, 4, 8, 10))

        val thumb = Rectangle()
        assertTrue(
            overlay.copyThumbBounds(
                settings = settings,
                activeBuffer = TerminalRenderBufferKind.PRIMARY,
                componentWidth = 110,
                componentHeight = 108,
                state = viewportState(scrollbackOffset = 0.0),
                destination = thumb,
            ),
        )
        assertEquals(102, thumb.x)
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
        assertFalse(
            overlay.copyThumbBounds(
                settings = settings,
                activeBuffer = TerminalRenderBufferKind.ALTERNATE,
                componentWidth = 110,
                componentHeight = 108,
                state = viewportState(scrollbackOffset = 4.0),
                destination = Rectangle(),
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

    @Test
    fun `painting matches rounded thumb raster and restores caller graphics state`() {
        val overlay = TerminalScrollbarOverlay()
        val settings = SwingSettings(padding = SwingPadding(0, 4, 8, 10))
        val palette = TerminalColorPalette(defaultForeground = Color.RED.rgb)
        val state = viewportState(scrollbackOffset = 4.0)
        val actual = BufferedImage(110, 108, BufferedImage.TYPE_INT_ARGB)
        val expected = BufferedImage(110, 108, BufferedImage.TYPE_INT_ARGB)
        val graphics = actual.createGraphics()
        val reference = expected.createGraphics()
        try {
            graphics.color = Color.MAGENTA
            graphics.paint = GradientPaint(0f, 0f, Color.GREEN, 10f, 10f, Color.BLUE)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            val originalPaint = graphics.paint

            paintThumb(overlay, graphics, settings, palette, state)
            paintReferenceThumb(reference, Rectangle(102, 40, 6, 33), Color(255, 0, 0, 96))

            assertEquals(Color.MAGENTA, graphics.color)
            assertSame(originalPaint, graphics.paint)
            assertEquals(RenderingHints.VALUE_ANTIALIAS_OFF, graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING))
            assertArrayEquals(
                expected.getRGB(0, 0, 110, 108, null, 0, 110),
                actual.getRGB(0, 0, 110, 108, null, 0, 110),
            )
        } finally {
            graphics.dispose()
            reference.dispose()
        }
    }

    @Test
    fun `hover and palette changes update retained thumb colors`() {
        val overlay = TerminalScrollbarOverlay()
        val settings = SwingSettings(padding = SwingPadding(0, 4, 8, 10))
        val state = viewportState(scrollbackOffset = 4.0)
        val image = BufferedImage(110, 108, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            val redPalette = TerminalColorPalette(defaultForeground = Color.RED.rgb)
            paintThumb(overlay, graphics, settings, redPalette, state)
            assertEquals(0x60ff0000, image.getRGB(105, 50))

            overlay.handleMoved(105, 50, settings, TerminalRenderBufferKind.PRIMARY, 110, 108)
            paintThumb(overlay, graphics, settings, redPalette, state)
            assertEquals(0xa0ff0000.toInt(), image.getRGB(105, 50))

            val bluePalette = TerminalColorPalette(defaultForeground = Color.BLUE.rgb)
            paintThumb(overlay, graphics, settings, bluePalette, state)
            assertEquals(0xa00000ff.toInt(), image.getRGB(105, 50))

            overlay.handleExited()
            paintThumb(overlay, graphics, settings, bluePalette, state)
            assertEquals(0x600000ff, image.getRGB(105, 50))
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `short tracks and narrow insets keep thumb inside available geometry`() {
        val overlay = TerminalScrollbarOverlay()
        val destination = Rectangle()
        val state = viewportState(scrollbackOffset = 4.0)
        for (rightInset in 1..3) {
            val settings = SwingSettings(padding = SwingPadding(0, 4, 8, rightInset))
            for (trackHeight in 1..23) {
                assertTrue(
                    overlay.copyThumbBounds(
                        settings,
                        TerminalRenderBufferKind.PRIMARY,
                        110,
                        trackHeight + 8,
                        state,
                        destination,
                    ),
                )
                assertEquals(Rectangle(110 - rightInset, 0, rightInset, trackHeight), destination)
            }
        }
    }

    @Test
    fun `thumb without travel retains current offset when pressed and released`() {
        val overlay = TerminalScrollbarOverlay()
        val settings = SwingSettings(padding = SwingPadding(0, 4, 8, 1))
        val state = viewportState(scrollbackOffset = 4.0)
        var offset = -1
        assertTrue(
            overlay.handlePressed(109, 2, settings, TerminalRenderBufferKind.PRIMARY, 110, 13, state) { row, _ -> offset = row },
        )
        assertEquals(4, offset)
        assertTrue(
            overlay.handleReleased(100, settings, TerminalRenderBufferKind.PRIMARY, 13, state) { row, _ -> offset = row },
        )
        assertEquals(4, offset)
    }

    private fun paintThumb(
        overlay: TerminalScrollbarOverlay,
        graphics: Graphics2D,
        settings: SwingSettings,
        palette: TerminalColorPalette,
        state: TerminalViewportState,
    ) {
        overlay.paint(
            graphics,
            settings,
            TerminalRenderBufferKind.PRIMARY,
            palette,
            110,
            108,
            state.historySize,
            state.visualScrollOffsetPixels,
            state.visualScrollRangePixels,
            state.viewportHeightPixels,
        )
    }

    private fun paintReferenceThumb(
        graphics: Graphics2D,
        bounds: Rectangle,
        color: Color,
    ) {
        val previousColor = graphics.color
        val previousPaint = graphics.paint
        val previousAntialiasing = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = color
            graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, bounds.width, bounds.width)
        } finally {
            graphics.color = previousColor
            graphics.paint = previousPaint
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialiasing)
        }
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

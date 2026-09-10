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
package io.github.ketraterm.ui.swing.input

import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalMouseButton
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.protocol.MouseTrackingMode
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

class SwingTerminalMouseControllerTest {
    private val source = Canvas()

    @Nested
    inner class PressRouting {
        @Test
        fun `middle mouse press falls through to selection routing`() {
            val host = RecordingMouseHost()
            val controller = SwingTerminalMouseController(host)
            val event = mousePressed(button = MouseEvent.BUTTON2, modifiers = InputEvent.BUTTON2_DOWN_MASK)

            controller.mouseListener.mousePressed(event)

            assertEquals(1, host.selectionPressCount)
            assertFalse(event.isConsumed)
        }

        @Test
        fun `hyperlink mouse press wins over selection`() {
            val host = RecordingMouseHost(hyperlinkPressHandled = true)
            val controller = SwingTerminalMouseController(host)
            val event = mousePressed(button = MouseEvent.BUTTON1, modifiers = InputEvent.BUTTON1_DOWN_MASK)

            controller.mouseListener.mousePressed(event)

            assertEquals(1, host.hyperlinkPressCount)
            assertEquals(0, host.selectionPressCount)
        }

        @Test
        fun `mouse press requests focus in window even when mouse tracking is active`() {
            val host = RecordingMouseHost(mouseTrackingMode = MouseTrackingMode.NORMAL)
            val controller = SwingTerminalMouseController(host)
            val event = mousePressed(button = MouseEvent.BUTTON1, modifiers = InputEvent.BUTTON1_DOWN_MASK)

            controller.mouseListener.mousePressed(event)

            assertEquals(1, host.requestFocusInWindowCount)
            assertEquals(1, host.mouseReports.size)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `popup trigger opens context menu when mouse tracking is off`() {
            val host = RecordingMouseHost()
            val controller = SwingTerminalMouseController(host)
            val event = popupPressed()

            controller.mouseListener.mousePressed(event)

            assertEquals(1, host.contextMenuCount)
            assertFalse(host.lastContextMenuForcedByShift)
            assertEquals(0, host.mouseReports.size)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `plain popup trigger is sent to PTY when mouse tracking is active`() {
            val host = RecordingMouseHost(mouseTrackingMode = MouseTrackingMode.NORMAL)
            val controller = SwingTerminalMouseController(host)
            val event = popupPressed()

            controller.mouseListener.mousePressed(event)

            assertEquals(0, host.contextMenuCount)
            assertEquals(1, host.mouseReports.size)
            assertEquals(TerminalMouseButton.RIGHT, host.mouseReports.single().button)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `shift popup trigger forces context menu when mouse tracking is active`() {
            val host = RecordingMouseHost(mouseTrackingMode = MouseTrackingMode.NORMAL)
            val controller = SwingTerminalMouseController(host)
            val event = popupPressed(InputEvent.SHIFT_DOWN_MASK)

            controller.mouseListener.mousePressed(event)

            assertEquals(1, host.contextMenuCount)
            assertTrue(host.lastContextMenuForcedByShift)
            assertEquals(0, host.mouseReports.size)
            assertTrue(event.isConsumed)
        }
    }

    @Nested
    inner class SelectionRouting {
        @Test
        fun `mouse release and drag route to selection when mouse tracking is off`() {
            val host = RecordingMouseHost()
            val controller = SwingTerminalMouseController(host)

            controller.mouseListener.mouseReleased(mouseReleased())
            controller.mouseMotionListener.mouseDragged(mouseDragged())

            assertEquals(1, host.selectionReleaseCount)
            assertEquals(1, host.selectionDragCount)
        }
    }

    @Nested
    inner class WheelRouting {
        @Test
        fun `mouse wheel delegates even when no scrollback history exists`() {
            val host = RecordingMouseHost()
            val controller = SwingTerminalMouseController(host)
            val event = mouseWheel(rotation = 1.0)

            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(1, host.scrollCount)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `mouse wheel is not consumed when viewport reports no movement`() {
            val host = RecordingMouseHost(scrollResult = false)
            val controller = SwingTerminalMouseController(host)
            val event = mouseWheel(rotation = 1.0)

            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(1, host.scrollCount)
            assertFalse(event.isConsumed)
        }

        @Test
        fun `unit mouse wheel scrolls by configured scroll amount when history exists`() {
            val host = RecordingMouseHost()
            host.renderCache.updateFrom(FakeFrameReader(FakeFrame(historySize = 7, rows = 24)))
            val controller = SwingTerminalMouseController(host)
            val event = mouseWheel(rotation = 1.0)

            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(1, host.scrollCount)
            assertEquals(-3.0, host.lastScrollDelta)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `partial trackpad rotation enters shared smooth scroll path`() {
            val host = RecordingMouseHost()
            val controller = SwingTerminalMouseController(host)
            val event = mouseWheel(rotation = -0.1)

            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(1, host.scrollCount)
            assertEquals(0.3, host.lastScrollDelta, 1.0e-12)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `partial precision rotation is consumed without a false TUI wheel report`() {
            val host = RecordingMouseHost(mouseTrackingMode = MouseTrackingMode.NORMAL)
            val controller = SwingTerminalMouseController(host)
            val event = mouseWheel(rotation = 0.25)

            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(0, host.mouseReports.size)
            assertEquals(0, host.scrollCount)
            assertEquals(1, host.finishWheelAnimationCount)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `integer wheel rotation emits one TUI report per click`() {
            val host = RecordingMouseHost(mouseTrackingMode = MouseTrackingMode.NORMAL)
            val controller = SwingTerminalMouseController(host)
            val event = mouseWheel(rotation = -3.0)

            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(3, host.mouseReports.size)
            assertTrue(host.mouseReports.all { it.button == TerminalMouseButton.WHEEL_UP })
            assertEquals(0, host.scrollCount)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `scroll up in alternate screen buffer with mouse tracking off translates to UP keys`() {
            val encodedKeys = ArrayList<io.github.ketraterm.input.event.TerminalKeyEvent>()
            val fakeSession =
                object : TerminalInputEncoder {
                    override fun encodeKey(event: io.github.ketraterm.input.event.TerminalKeyEvent) {
                        encodedKeys += event
                    }

                    override fun encodePaste(event: io.github.ketraterm.input.event.TerminalPasteEvent) {}

                    override fun encodeFocus(event: io.github.ketraterm.input.event.TerminalFocusEvent) {}

                    override fun encodeMouse(event: TerminalMouseEvent) {}
                }

            val host = RecordingMouseHost(session = fakeSession)
            host.renderCache.updateFrom(
                FakeFrameReader(FakeFrame(historySize = 0, rows = 24, activeBuffer = TerminalRenderBufferKind.ALTERNATE)),
            )
            val controller = SwingTerminalMouseController(host)

            // rotation = -1.0 means scrolling UP (clicks = 1.0, delta = 3.0)
            val event = mouseWheel(rotation = -1.0)
            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(3, encodedKeys.size)
            assertTrue(encodedKeys.all { it.key == TerminalKey.UP })
            assertEquals(0, host.scrollCount)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `scroll down in alternate screen buffer with mouse tracking off translates to DOWN keys`() {
            val encodedKeys = ArrayList<io.github.ketraterm.input.event.TerminalKeyEvent>()
            val fakeSession =
                object : TerminalInputEncoder {
                    override fun encodeKey(event: io.github.ketraterm.input.event.TerminalKeyEvent) {
                        encodedKeys += event
                    }

                    override fun encodePaste(event: io.github.ketraterm.input.event.TerminalPasteEvent) {}

                    override fun encodeFocus(event: io.github.ketraterm.input.event.TerminalFocusEvent) {}

                    override fun encodeMouse(event: TerminalMouseEvent) {}
                }

            val host = RecordingMouseHost(session = fakeSession)
            host.renderCache.updateFrom(
                FakeFrameReader(FakeFrame(historySize = 0, rows = 24, activeBuffer = TerminalRenderBufferKind.ALTERNATE)),
            )
            val controller = SwingTerminalMouseController(host)

            // rotation = 1.0 means scrolling DOWN (clicks = -1.0, delta = -3.0)
            val event = mouseWheel(rotation = 1.0)
            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(3, encodedKeys.size)
            assertTrue(encodedKeys.all { it.key == TerminalKey.DOWN })
            assertEquals(0, host.scrollCount)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `scroll in alternate screen buffer does not crash and consumes event when session is null`() {
            val host = RecordingMouseHost(session = null)
            host.renderCache.updateFrom(
                FakeFrameReader(FakeFrame(historySize = 0, rows = 24, activeBuffer = TerminalRenderBufferKind.ALTERNATE)),
            )
            val controller = SwingTerminalMouseController(host)

            val event = mouseWheel(rotation = -1.0)
            controller.wheelListener.mouseWheelMoved(event)

            assertEquals(0, host.scrollCount)
            assertTrue(event.isConsumed)
        }
    }

    @Nested
    inner class HyperlinkHover {
        @Test
        fun `mouse moved routes to hyperlink hover when mouse tracking is off`() {
            val host = RecordingMouseHost()
            val controller = SwingTerminalMouseController(host)

            controller.mouseMotionListener.mouseMoved(mouseMoved())

            assertEquals(1, host.hyperlinkMoveCount)
            assertEquals(0, host.clearHoverCount)
        }

        @Test
        fun `mouse exit clears hyperlink hover`() {
            val host = RecordingMouseHost()
            val controller = SwingTerminalMouseController(host)

            controller.mouseListener.mouseExited(mouseExited())

            assertEquals(1, host.hyperlinkExitCount)
        }
    }

    private fun mousePressed(
        button: Int,
        modifiers: Int,
    ): MouseEvent =
        MouseEvent(
            source,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            20,
            30,
            1,
            false,
            button,
        )

    private fun popupPressed(modifiers: Int = 0): MouseEvent =
        MouseEvent(
            source,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            modifiers or InputEvent.BUTTON3_DOWN_MASK,
            20,
            30,
            1,
            true,
            MouseEvent.BUTTON3,
        )

    private fun mouseReleased(): MouseEvent =
        MouseEvent(
            source,
            MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK,
            20,
            30,
            1,
            false,
            MouseEvent.BUTTON1,
        )

    private fun mouseDragged(): MouseEvent =
        MouseEvent(
            source,
            MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK,
            25,
            35,
            0,
            false,
            MouseEvent.BUTTON1,
        )

    private fun mouseMoved(): MouseEvent =
        MouseEvent(
            source,
            MouseEvent.MOUSE_MOVED,
            System.currentTimeMillis(),
            0,
            25,
            35,
            0,
            false,
            MouseEvent.NOBUTTON,
        )

    private fun mouseExited(): MouseEvent =
        MouseEvent(
            source,
            MouseEvent.MOUSE_EXITED,
            System.currentTimeMillis(),
            0,
            25,
            35,
            0,
            false,
            MouseEvent.NOBUTTON,
        )

    private fun mouseWheel(rotation: Double): MouseWheelEvent =
        MouseWheelEvent(
            source,
            MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            0,
            25,
            35,
            25,
            35,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            3,
            rotation.toInt(),
            rotation,
        )

    private class RecordingMouseHost(
        override val settings: SwingSettings = SwingSettings(padding = SwingPadding(0, 0, 0, 0)),
        private val hyperlinkPressHandled: Boolean = false,
        private val scrollResult: Boolean = true,
        private val mouseTrackingMode: MouseTrackingMode = MouseTrackingMode.OFF,
        override val session: TerminalInputEncoder? = null,
    ) : SwingTerminalMouseHost {
        override val metrics =
            SwingMetrics(
                cellWidth = 10,
                cellHeight = 20,
                baseline = 15,
                underlineY = 16,
                strikethroughY = 10,
                overlineY = 0,
                cursorStrokeWidth = 2,
            )
        override val renderCache = TerminalRenderCache(80, 24)

        var scrollCount = 0
        var requestFocusInWindowCount = 0
        var hyperlinkPressCount = 0
        var hyperlinkMoveCount = 0
        var hyperlinkExitCount = 0
        var clearHoverCount = 0
        var selectionPressCount = 0
        var selectionReleaseCount = 0
        var selectionDragCount = 0
        var contextMenuCount = 0
        var lastContextMenuForcedByShift = false
        var lastScrollDelta = 0.0
        var finishWheelAnimationCount = 0
        val mouseReports = ArrayList<TerminalMouseEvent>()

        override fun mouseTrackingMode(): MouseTrackingMode = mouseTrackingMode

        override fun encodeMouse(event: TerminalMouseEvent) {
            mouseReports += event
        }

        override fun cellAt(
            x: Int,
            y: Int,
            cache: TerminalRenderCache,
        ): Long = 0L

        override fun terminalPixelYAt(
            y: Int,
            cache: TerminalRenderCache,
        ): Int = y

        override fun visibleGridRows(): Int = 24

        override fun scrollViewportByPreciseRows(deltaRows: Double): Boolean {
            scrollCount++
            lastScrollDelta = deltaRows
            return scrollResult
        }

        override fun finishViewportScroll() {
            finishWheelAnimationCount++
        }

        override fun requestFocusInWindow(): Boolean {
            requestFocusInWindowCount++
            return true
        }

        override fun handleContextMenuMouseEvent(
            event: MouseEvent,
            forcedByShift: Boolean,
        ): Boolean {
            contextMenuCount++
            lastContextMenuForcedByShift = forcedByShift
            return true
        }

        override fun handleHyperlinkMousePressed(event: MouseEvent): Boolean {
            hyperlinkPressCount++
            return hyperlinkPressHandled
        }

        override fun handleHyperlinkMouseMoved(event: MouseEvent) {
            hyperlinkMoveCount++
        }

        override fun handleHyperlinkMouseExited() {
            hyperlinkExitCount++
        }

        override fun clearHyperlinkHover() {
            clearHoverCount++
        }

        override fun handleSelectionMousePressed(event: MouseEvent) {
            selectionPressCount++
        }

        override fun handleSelectionMouseReleased(event: MouseEvent) {
            selectionReleaseCount++
        }

        override fun handleSelectionMouseDragged(event: MouseEvent) {
            selectionDragCount++
        }
    }

    private class FakeFrameReader(
        private val frame: TerminalRenderFrame,
    ) : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(frame)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(frame)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(frame)
        }
    }

    private class FakeFrame(
        override val historySize: Int,
        override val rows: Int,
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
    ) : TerminalRenderFrame {
        override val columns: Int = 80
        override val scrollbackOffset: Int = 0
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1L)
        override val discardedCount: Long = 0L

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
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }
}

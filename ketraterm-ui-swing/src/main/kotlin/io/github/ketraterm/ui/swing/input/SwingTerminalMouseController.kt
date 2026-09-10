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

import io.github.ketraterm.input.event.*
import io.github.ketraterm.protocol.MouseTrackingMode
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import java.awt.event.*
import javax.swing.SwingUtilities
import kotlin.math.min

/**
 * Swing mouse routing for terminal mouse protocol, links, selection, and wheel scrolling.
 */
internal class SwingTerminalMouseController(
    private val host: SwingTerminalMouseHost,
) {
    val wheelListener =
        MouseWheelListener { event ->
            handleMouseWheel(event)
        }

    val mouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                host.requestFocusInWindow()
                if (handleContextMenu(event)) return
                if (!host.renderCache.hasFrame) return
                if (host.handlePromptMarkerMousePressed(event)) return
                if (handleMouseTracking(event, TerminalMouseEventType.PRESS)) return
                if (host.handleHyperlinkMousePressed(event)) return
                host.handleSelectionMousePressed(event)
            }

            override fun mouseReleased(event: MouseEvent) {
                if (handleContextMenu(event)) return
                if (!host.renderCache.hasFrame) return
                if (handleMouseTracking(event, TerminalMouseEventType.RELEASE)) return
                host.handleSelectionMouseReleased(event)
            }

            override fun mouseExited(event: MouseEvent) {
                host.handlePromptMarkerMouseExited()
                host.handleHyperlinkMouseExited()
            }
        }

    val mouseMotionListener =
        object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                if (!host.renderCache.hasFrame) return
                if (handleMouseTracking(event, TerminalMouseEventType.MOTION)) return
                host.handleSelectionMouseDragged(event)
            }

            override fun mouseMoved(event: MouseEvent) {
                if (!host.renderCache.hasFrame) return
                if (host.handlePromptMarkerMouseMoved(event)) {
                    return
                }
                if (handleMouseTracking(event, TerminalMouseEventType.MOTION)) {
                    host.clearHyperlinkHover()
                    return
                }
                host.handleHyperlinkMouseMoved(event)
            }
        }

    private fun handleMouseWheel(event: MouseWheelEvent) {
        if (!host.renderCache.hasFrame) return
        if (isMouseTrackingIntercepted(event)) {
            host.finishViewportScroll()
            handleMouseTracking(event, TerminalMouseEventType.WHEEL)
            return
        }
        val delta = wheelScrollLines(event)
        if (delta == 0.0) {
            event.consume()
            return
        }

        if (host.renderCache.activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
            val key = if (delta > 0.0) TerminalKey.UP else TerminalKey.DOWN
            val count = kotlin.math.round(kotlin.math.abs(delta)).toInt()
            val session = host.session
            if (session != null && count > 0) {
                val keyEvent = TerminalKeyEvent.key(key)
                for (i in 0 until count) {
                    session.encodeKey(keyEvent)
                }
            }
            event.consume()
            return
        }

        if (host.scrollViewportByPreciseRows(delta)) {
            event.consume()
        }
    }

    fun isMouseTrackingIntercepted(event: MouseEvent): Boolean = !event.isShiftDown && host.mouseTrackingMode() != MouseTrackingMode.OFF

    private fun handleContextMenu(event: MouseEvent): Boolean {
        if (!event.isPopupTrigger) return false
        if (!event.isShiftDown && host.mouseTrackingMode() != MouseTrackingMode.OFF) return false
        val handled = host.handleContextMenuMouseEvent(event, forcedByShift = event.isShiftDown)
        if (!handled) return false
        event.consume()
        return true
    }

    private fun handleMouseTracking(
        event: MouseEvent,
        type: TerminalMouseEventType,
    ): Boolean {
        if (!isMouseTrackingIntercepted(event)) return false

        val wheelRotation = if (event is MouseWheelEvent) event.wheelRotation else 0
        if (event is MouseWheelEvent && wheelRotation == 0) {
            // High-resolution devices emit partial rotations whose integer
            // click count is zero. They belong to the application, but do not
            // represent a terminal wheel button report yet.
            event.consume()
            return true
        }

        val cell = host.cellAt(event.x, event.y, host.renderCache)
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)

        val button =
            if (event is MouseWheelEvent) {
                if (wheelRotation < 0) TerminalMouseButton.WHEEL_UP else TerminalMouseButton.WHEEL_DOWN
            } else {
                when {
                    SwingUtilities.isLeftMouseButton(event) -> TerminalMouseButton.LEFT
                    SwingUtilities.isMiddleMouseButton(event) -> TerminalMouseButton.MIDDLE
                    SwingUtilities.isRightMouseButton(event) -> TerminalMouseButton.RIGHT
                    else -> TerminalMouseButton.NONE
                }
            }

        var mods = TerminalModifiers.NONE
        if (event.isShiftDown) mods = mods or TerminalModifiers.SHIFT
        if (event.isAltDown) mods = mods or TerminalModifiers.ALT
        if (event.isControlDown) mods = mods or TerminalModifiers.CTRL
        if (event.isMetaDown) mods = mods or TerminalModifiers.SUPER

        val paddingLeft =
            SwingTerminalChrome.left(
                host.settings,
                host.renderCache.activeBuffer,
            )
        val gridWidth = host.renderCache.columns * host.metrics.cellWidth
        val gridHeight = host.renderCache.rows * host.metrics.cellHeight
        val visualPixelX = (event.x - paddingLeft).coerceIn(0, gridWidth - 1)
        val pixelX = column * host.metrics.cellWidth + visualPixelX % host.metrics.cellWidth
        val pixelY = host.terminalPixelYAt(event.y, host.renderCache).coerceIn(0, gridHeight - 1)

        val mouseEvent =
            TerminalMouseEvent(
                column = column,
                row = row,
                button = button,
                type = type,
                modifiers = mods,
                pixelX = pixelX,
                pixelY = pixelY,
            )
        val reportCount =
            if (event is MouseWheelEvent) {
                min(kotlin.math.abs(wheelRotation.toLong()), MAX_WHEEL_REPORTS_PER_EVENT.toLong()).toInt()
            } else {
                1
            }
        var report = 0
        while (report < reportCount) {
            host.encodeMouse(mouseEvent)
            report++
        }
        event.consume()
        return true
    }

    private fun wheelScrollLines(event: MouseWheelEvent): Double {
        val clicks = -event.preciseWheelRotation
        val units =
            when (event.scrollType) {
                MouseWheelEvent.WHEEL_UNIT_SCROLL -> event.scrollAmount
                MouseWheelEvent.WHEEL_BLOCK_SCROLL -> host.visibleGridRows()
                else -> 1
            }
        return (clicks * units).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
    }

    private companion object {
        private const val MAX_WHEEL_REPORTS_PER_EVENT = 64

        private fun unpackCellColumn(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }
}

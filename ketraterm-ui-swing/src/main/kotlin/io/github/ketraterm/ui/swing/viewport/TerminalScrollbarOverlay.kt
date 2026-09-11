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
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import kotlin.math.roundToInt

/**
 * EDT-owned overlay scrollbar painted inside the terminal's reserved right inset.
 */
internal class TerminalScrollbarOverlay {
    private val thumb = Rectangle()
    private var foregroundRgb = -1
    private var normalThumbColor = Color(0, 0, 0, THUMB_ALPHA)
    private var activeThumbColor = Color(0, 0, 0, HOVER_THUMB_ALPHA)
    private var dragThumbOffsetY: Int = 0
    var hovered: Boolean = false
        private set
    var dragging: Boolean = false
        private set

    fun paint(
        g: Graphics2D,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        palette: TerminalColorPalette,
        componentWidth: Int,
        componentHeight: Int,
        historySize: Int,
        visualScrollOffsetPixels: Double,
        visualScrollRangePixels: Int,
        viewportHeightPixels: Int,
    ) {
        if (!updateThumbBounds(
                settings,
                activeBuffer,
                componentWidth,
                componentHeight,
                historySize,
                visualScrollOffsetPixels,
                visualScrollRangePixels,
                viewportHeightPixels,
                thumb,
            )
        ) {
            return
        }
        val previousColor = g.color
        val previousPaint = g.paint
        val previousAntialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = thumbColor(palette)
            g.fillRoundRect(thumb.x, thumb.y, thumb.width, thumb.height, thumb.width, thumb.width)
        } finally {
            g.color = previousColor
            g.paint = previousPaint
            if (previousAntialiasing != null) {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialiasing)
            } else {
                val hints = g.renderingHints
                hints.remove(RenderingHints.KEY_ANTIALIASING)
                // The getter returns RenderingHints, but the setter accepts Map; the Kotlin property is read-only.
                @Suppress("UsePropertyAccessSyntax")
                g.setRenderingHints(hints)
            }
        }
    }

    fun handlePressed(
        x: Int,
        y: Int,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
        state: TerminalViewportState,
        scrollTo: (Int, Boolean) -> Unit,
    ): Boolean {
        if (!containsGutter(settings, activeBuffer, componentWidth, componentHeight, x, y)) return false
        if (!copyThumbBounds(settings, activeBuffer, componentWidth, componentHeight, state, thumb)) return true
        dragging = true
        dragThumbOffsetY =
            if (thumb.contains(x, y)) {
                y - thumb.y
            } else {
                thumb.height / 2
            }
        scrollTo(offsetAtThumbTop(y - dragThumbOffsetY, settings, activeBuffer, componentHeight, state), true)
        return true
    }

    fun handleDragged(
        y: Int,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentHeight: Int,
        state: TerminalViewportState,
        scrollTo: (Int, Boolean) -> Unit,
    ): Boolean {
        if (!dragging) return false
        scrollTo(offsetAtThumbTop(y - dragThumbOffsetY, settings, activeBuffer, componentHeight, state), true)
        return true
    }

    fun handleReleased(
        y: Int,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentHeight: Int,
        state: TerminalViewportState,
        scrollTo: (Int, Boolean) -> Unit,
    ): Boolean {
        if (!dragging) return false
        dragging = false
        scrollTo(offsetAtThumbTop(y - dragThumbOffsetY, settings, activeBuffer, componentHeight, state), false)
        return true
    }

    fun handleMoved(
        x: Int,
        y: Int,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
    ): Boolean {
        val nextHovered = containsGutter(settings, activeBuffer, componentWidth, componentHeight, x, y)
        val changed = hovered != nextHovered
        hovered = nextHovered
        return changed
    }

    fun handleExited(): Boolean {
        val changed = hovered || dragging
        hovered = false
        dragging = false
        return changed
    }

    fun containsGutter(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val rightInset = SwingTerminalChrome.right(settings, activeBuffer)
        if (rightInset <= 0 || componentWidth <= 0) return false
        val top = SwingTerminalChrome.top(settings, activeBuffer)
        val bottom = componentHeight - SwingTerminalChrome.bottom(settings, activeBuffer)
        return x in (componentWidth - rightInset) until componentWidth && y in top until bottom
    }

    /** Copies paint and input thumb geometry into caller-owned storage, or returns false when hidden. */
    fun copyThumbBounds(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
        state: TerminalViewportState,
        destination: Rectangle,
    ): Boolean =
        updateThumbBounds(
            settings,
            activeBuffer,
            componentWidth,
            componentHeight,
            state.historySize,
            state.visualScrollOffsetPixels,
            state.visualScrollRangePixels,
            state.viewportHeightPixels,
            destination,
        )

    private fun updateThumbBounds(
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentWidth: Int,
        componentHeight: Int,
        historySize: Int,
        visualScrollOffsetPixels: Double,
        visualScrollRangePixels: Int,
        viewportHeightPixels: Int,
        destination: Rectangle,
    ): Boolean {
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE ||
            componentWidth <= 0 ||
            historySize <= 0 ||
            visualScrollRangePixels <= 0 ||
            viewportHeightPixels <= 0
        ) {
            return false
        }
        val rightInset = SwingTerminalChrome.right(settings, activeBuffer)
        val trackTop = SwingTerminalChrome.top(settings, activeBuffer)
        val trackHeight = componentHeight - trackTop - SwingTerminalChrome.bottom(settings, activeBuffer)
        if (rightInset <= 0 || trackHeight <= 0) return false
        val thumbWidth = minOf(rightInset, MAX_THUMB_WIDTH, maxOf(MIN_THUMB_WIDTH, rightInset - THUMB_HORIZONTAL_PADDING * 2))
        val thumbHeight = thumbHeight(trackHeight, visualScrollRangePixels, viewportHeightPixels)
        val travel = trackHeight - thumbHeight
        val thumbTop =
            if (travel <= 0) {
                trackTop
            } else {
                val topOriginPixels = visualScrollRangePixels - visualScrollOffsetPixels.roundToInt().coerceIn(0, visualScrollRangePixels)
                trackTop + ((topOriginPixels.toLong() * travel.toLong()) / visualScrollRangePixels.toLong()).toInt()
            }
        val x = componentWidth - rightInset + (rightInset - thumbWidth) / 2
        destination.setBounds(x, thumbTop, thumbWidth, thumbHeight)
        return true
    }

    private fun offsetAtThumbTop(
        thumbTop: Int,
        settings: SwingSettings,
        activeBuffer: TerminalRenderBufferKind,
        componentHeight: Int,
        state: TerminalViewportState,
    ): Int {
        if (activeBuffer == TerminalRenderBufferKind.ALTERNATE ||
            state.historySize <= 0 ||
            SwingTerminalChrome.right(settings, activeBuffer) <= 0
        ) {
            return 0
        }
        val trackTop = SwingTerminalChrome.top(settings, activeBuffer)
        val trackHeight = componentHeight - trackTop - SwingTerminalChrome.bottom(settings, activeBuffer)
        val thumbHeight = thumbHeight(trackHeight, state.visualScrollRangePixels, state.viewportHeightPixels)
        if (thumbHeight == 0) return 0
        val travel = trackHeight - thumbHeight
        if (travel <= 0) return state.scrollbackOffset.roundToInt().coerceIn(0, state.historySize)

        val clampedTop = thumbTop.coerceIn(trackTop, trackTop + travel)
        val topOriginPixels =
            (((clampedTop - trackTop).toLong() * state.visualScrollRangePixels.toLong()) / travel.toLong()).toInt()
        val topRow = topOriginPixels / state.cellHeightPixels
        return (state.historySize - topRow).coerceIn(0, state.historySize)
    }

    private fun thumbHeight(
        trackHeight: Int,
        visualScrollRangePixels: Int,
        viewportHeightPixels: Int,
    ): Int {
        if (trackHeight <= 0 || visualScrollRangePixels <= 0 || viewportHeightPixels <= 0) return 0
        val proportionalHeight =
            (trackHeight.toLong() * viewportHeightPixels) / (visualScrollRangePixels.toLong() + viewportHeightPixels)
        return proportionalHeight.coerceIn(minOf(MIN_THUMB_HEIGHT, trackHeight).toLong(), trackHeight.toLong()).toInt()
    }

    private fun thumbColor(palette: TerminalColorPalette): Color {
        val rgb = palette.defaultForeground and 0x00ff_ffff
        if (foregroundRgb != rgb) {
            foregroundRgb = rgb
            normalThumbColor = Color(rgb or (THUMB_ALPHA shl 24), true)
            activeThumbColor = Color(rgb or (HOVER_THUMB_ALPHA shl 24), true)
        }
        return if (hovered || dragging) activeThumbColor else normalThumbColor
    }

    private companion object {
        private const val THUMB_HORIZONTAL_PADDING = 2
        private const val MIN_THUMB_WIDTH = 4
        private const val MAX_THUMB_WIDTH = 6
        private const val MIN_THUMB_HEIGHT = 24
        private const val THUMB_ALPHA = 96
        private const val HOVER_THUMB_ALPHA = 160
    }
}

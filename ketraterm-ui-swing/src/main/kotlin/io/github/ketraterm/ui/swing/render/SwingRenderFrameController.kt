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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import io.github.ketraterm.ui.swing.viewport.SwingRepaintPlanner
import io.github.ketraterm.ui.swing.viewport.TerminalRepaintSink
import java.awt.Insets

/**
 * EDT render-frame scheduler and repaint planner for the Swing terminal.
 */
internal class SwingRenderFrameController(
    private val host: SwingRenderFrameHost,
) {
    private val repaintPlanner = SwingRepaintPlanner()
    private val repaintPaddingScratch = Insets(0, 0, 0, 0)
    private val repaintSink =
        object : TerminalRepaintSink {
            override fun requestFullRepaint() {
                host.repaint()
            }

            override fun requestRegionRepaint(
                x: Int,
                y: Int,
                width: Int,
                height: Int,
            ) {
                host.repaintRegion(x, y, width, height)
            }
        }

    fun reset() {
        repaintPlanner.reset()
    }

    fun handlePublishedFrame() {
        val boundSession = host.session ?: return
        val blinkVisibilityChanged = host.resetCursorBlinkForFrame()
        host.refreshRenderCacheFromSession(boundSession)
        if (!host.renderCache.hasFrame) return
        val followUpRenderRequired =
            host.syncTerminalGridToActiveChrome() ||
                host.clampViewport(host.renderCache.historySize, host.renderCache.discardedCount) ||
                host.renderCache.scrollbackOffset != host.requestedViewportOffset()
        val shellIntegrationDecorationsChanged = host.refreshShellIntegrationDecorations(boundSession)
        if (followUpRenderRequired) host.requestRender(boundSession)
        host.refreshSearchForFrame()
        host.publishViewportState(host.renderCache.historySize)
        repaintFrame(forceFullRepaint = shellIntegrationDecorationsChanged)
        if (blinkVisibilityChanged) repaintBlinkState()
    }

    /** Plans damage for the installed frame and projection, including search commands between publications. */
    fun repaintFrame(forceFullRepaint: Boolean = false) {
        if (!host.renderCache.hasFrame) return
        repaintPlanner.requestFrameRepaint(
            cache = host.renderCache,
            metrics = host.metrics,
            componentWidth = host.componentWidth,
            componentHeight = host.componentHeight,
            padding = repaintPadding(),
            repaintSink = repaintSink,
            forceFullRepaint = forceFullRepaint,
            visualGeometry = host.visualGeometry,
            searchHighlights = host.searchHighlights,
        )
    }

    fun repaintBlinkState() {
        if (host.session == null || !host.renderCache.hasFrame) return
        if (host.cursorPresentationEnabled) {
            repaintPlanner.requestCursorBlinkRepaint(
                cache = host.renderCache,
                metrics = host.metrics,
                componentWidth = host.componentWidth,
                componentHeight = host.componentHeight,
                padding = repaintPadding(),
                repaintSink = repaintSink,
                visualGeometry = host.visualGeometry,
            )
        }
        repaintPlanner.requestBlinkingTextRepaint(
            cache = host.renderCache,
            metrics = host.metrics,
            componentWidth = host.componentWidth,
            componentHeight = host.componentHeight,
            padding = repaintPadding(),
            repaintSink = repaintSink,
            visualGeometry = host.visualGeometry,
        )
    }

    fun repaintCursorState() {
        if (host.session == null || !host.renderCache.hasFrame) return
        repaintPlanner.requestCursorRepaint(
            cache = host.renderCache,
            metrics = host.metrics,
            componentWidth = host.componentWidth,
            componentHeight = host.componentHeight,
            padding = repaintPadding(),
            repaintSink = repaintSink,
            visualGeometry = host.visualGeometry,
        )
    }

    private fun repaintPadding(): Insets {
        val settings = host.settings
        val activeBuffer = host.renderCache.activeBuffer
        repaintPaddingScratch.top = SwingTerminalChrome.top(settings, activeBuffer)
        repaintPaddingScratch.left =
            SwingTerminalChrome.left(
                settings,
                activeBuffer,
            )
        repaintPaddingScratch.bottom = SwingTerminalChrome.bottom(settings, activeBuffer)
        repaintPaddingScratch.right = SwingTerminalChrome.right(settings, activeBuffer)
        return repaintPaddingScratch
    }
}

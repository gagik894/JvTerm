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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.search.TerminalSearchModel
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SwingRenderFrameControllerTest {
    @Nested
    inner class RepaintRouting {
        @Test
        fun `blink repaint is ignored when no session is attached`() {
            val host = RecordingRenderFrameHost(session = null)
            val controller = SwingRenderFrameController(host)

            controller.repaintBlinkState()
            controller.repaintCursorState()

            assertEquals(0, host.fullRepaintCount)
            assertEquals(0, host.regionRepaintCount)
        }
    }

    @Nested
    inner class PublishedFrameHandling {
        @Test
        fun `removing a wrapped search result repaints its unchanged first row`() {
            val cells =
                arrayOf("abc", "def")
                    .map { text ->
                        Array(text.length) { TestCell(codeWord = text[it].code, flags = TerminalRenderCellFlags.CODEPOINT) }
                    }.toTypedArray()
            val frame =
                object : TestRenderFrame(cells) {
                    override var frameGeneration = 1L

                    override fun lineWrapped(row: Int): Boolean = row == 0

                    override fun lineGeneration(row: Int): Long = if (row == 0) 1L else frameGeneration
                }
            val session = createSession(frame)
            val host = RecordingRenderFrameHost(session)
            host.searchQuery = "cde"
            val controller = SwingRenderFrameController(host)
            try {
                controller.handlePublishedFrame()
                assertEquals(1, host.searchHighlights.segmentCountForRow(0))
                val firstRowGeneration = host.renderCache.lineGenerations[0]
                host.clearRepaints()
                cells[1][0] = TestCell(codeWord = 'x'.code, flags = TerminalRenderCellFlags.CODEPOINT)
                frame.frameGeneration++
                session.renderPublisher.updateAndPublish(frame)

                controller.handlePublishedFrame()

                assertEquals(firstRowGeneration, host.renderCache.lineGenerations[0])
                assertEquals(0, host.searchHighlights.segmentCountForRow(0))
                assertEquals(0, host.fullRepaintCount)
                assertEquals(listOf(Region(0, 0, 800, 40)), host.regions)
                host.clearRepaints()
                controller.handlePublishedFrame()
                assertTrue(host.regions.isEmpty())
            } finally {
                session.close()
            }
        }

        @Test
        fun `search commands between publications become the next repaint baseline`() {
            val session = createSession(TestRenderFrame.text("needle"))
            val host = RecordingRenderFrameHost(session)
            val controller = SwingRenderFrameController(host)
            try {
                controller.handlePublishedFrame()
                controller.handlePublishedFrame()
                host.clearRepaints()
                host.searchQuery = "needle"
                host.refreshSearchForFrame()
                controller.repaintFrame()
                assertEquals(listOf(Region(0, 0, 800, 20)), host.regions)

                host.clearRepaints()
                host.searchQuery = ""
                controller.handlePublishedFrame()

                assertEquals(0, host.fullRepaintCount)
                assertEquals(listOf(Region(0, 0, 800, 20)), host.regions)
            } finally {
                session.close()
            }
        }

        @Test
        fun resetFromHiddenPhaseMustRepaintUnchangedCursor() {
            val session = createSession(blinkFrame())
            val host = RecordingRenderFrameHost(session)
            val controller = SwingRenderFrameController(host)
            try {
                controller.handlePublishedFrame()
                host.clearRepaints()
                host.blinkVisible = false

                controller.handlePublishedFrame()

                assertTrue(host.blinkVisible)
                assertEquals(0, host.fullRepaintCount)
                assertEquals(listOf(Region(0, 0, 10, 20)), host.regions)
                host.clearRepaints()
                repeat(3) { controller.handlePublishedFrame() }
                assertEquals(0, host.fullRepaintCount)
                assertTrue(host.regions.isEmpty())
            } finally {
                session.close()
            }
        }

        @Test
        fun resetFromHiddenPhaseMustRepaintUnchangedTextWithoutCursorPresentation() {
            val session = createSession(blinkFrame(textBlinks = true))
            val host = RecordingRenderFrameHost(session, cursorPresentationEnabled = false)
            val controller = SwingRenderFrameController(host)
            try {
                controller.handlePublishedFrame()
                host.clearRepaints()
                host.blinkVisible = false

                controller.handlePublishedFrame()

                assertTrue(host.blinkVisible)
                assertEquals(0, host.fullRepaintCount)
                assertEquals(listOf(Region(0, 20, 800, 20)), host.regions)
            } finally {
                session.close()
            }
        }

        @Test
        fun unrelatedRowUpdateAlsoRepaintsUnchangedBlinkingContent() {
            val session = createSession(blinkFrame(textBlinks = true))
            val host = RecordingRenderFrameHost(session)
            val controller = SwingRenderFrameController(host)
            try {
                controller.handlePublishedFrame()
                host.clearRepaints()
                host.blinkVisible = false
                session.renderPublisher.updateAndPublish(blinkFrame(textBlinks = true, generation = 2))

                controller.handlePublishedFrame()

                assertTrue(host.blinkVisible)
                assertEquals(0, host.fullRepaintCount)
                assertEquals(setOf(Region(0, 0, 10, 20), Region(0, 20, 800, 20), Region(0, 40, 800, 20)), host.regions.toSet())
                assertTrue(host.repaintFrameGenerations.all { it == 2L }, "blink regions must use the refreshed frame")
            } finally {
                session.close()
            }
        }

        @Test
        fun visiblePhaseResetDoesNotRepaintUnchangedBlinkingContent() {
            val session = createSession(blinkFrame(textBlinks = true))
            val host = RecordingRenderFrameHost(session)
            val controller = SwingRenderFrameController(host)
            try {
                controller.handlePublishedFrame()
                host.clearRepaints()

                controller.handlePublishedFrame()

                assertTrue(host.blinkVisible)
                assertEquals(0, host.fullRepaintCount)
                assertTrue(host.regions.isEmpty())
            } finally {
                session.close()
            }
        }

        @Test
        fun hiddenPhaseResetDoesNotRepaintNonBlinkingContent() {
            val session = createSession(blinkFrame(cursorBlinks = false))
            val host = RecordingRenderFrameHost(session)
            val controller = SwingRenderFrameController(host)
            try {
                controller.handlePublishedFrame()
                host.clearRepaints()
                host.blinkVisible = false

                controller.handlePublishedFrame()

                assertTrue(host.blinkVisible)
                assertEquals(0, host.fullRepaintCount)
                assertTrue(host.regions.isEmpty())
            } finally {
                session.close()
            }
        }

        @Test
        fun `published frame refreshes session-backed state in order`() {
            val session = createSession()
            val host = RecordingRenderFrameHost(session = session)
            val controller = SwingRenderFrameController(host)

            try {
                controller.handlePublishedFrame()

                assertEquals(
                    listOf(
                        "resetCursorBlinkForFrame",
                        "refreshRenderCacheFromSession",
                        "syncTerminalGridToActiveChrome",
                        "refreshShellIntegrationDecorations",
                        "refreshSearchForFrame",
                        "publishViewportState",
                    ),
                    host.semanticCalls,
                )
                assertEquals(1, host.refreshCount)
                assertEquals(0, host.publishHistorySizes.single())
            } finally {
                session.close()
            }
        }

        @Test
        fun `published frame requests follow up when viewport clamp changes request`() {
            val session = createSession()
            val host = RecordingRenderFrameHost(session = session, clampViewportResult = true)
            val controller = SwingRenderFrameController(host)

            try {
                controller.handlePublishedFrame()

                assertEquals(1, host.refreshCount)
                assertEquals(1, host.renderRequestCount)
            } finally {
                session.close()
            }
        }

        @Test
        fun `published frame requests follow up when active chrome resizes grid`() {
            val session = createSession()
            val host = RecordingRenderFrameHost(session = session, syncGridToChromeResult = true)
            val controller = SwingRenderFrameController(host)

            try {
                controller.handlePublishedFrame()

                assertEquals(1, host.refreshCount)
                assertEquals(1, host.renderRequestCount)
            } finally {
                session.close()
            }
        }
    }

    private class RecordingRenderFrameHost(
        override val session: TerminalSession?,
        private val clampViewportResult: Boolean = false,
        private val syncGridToChromeResult: Boolean = false,
        override val cursorPresentationEnabled: Boolean = true,
    ) : SwingRenderFrameHost {
        override val renderCache = TerminalRenderCache(80, 24)
        override val settings = SwingSettings(padding = SwingPadding(), shellIntegrationDecorationGutterWidth = 0)
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
        override val visualGeometry = TerminalVisualViewportGeometry()
        override val searchHighlights = TerminalSearchViewportHighlights()
        private val searchModel = TerminalSearchModel()
        var searchQuery = ""
        override val componentWidth = 800
        override val componentHeight = 480
        var blinkVisible = true
        val regions = ArrayList<Region>()
        val repaintFrameGenerations = ArrayList<Long>()

        var fullRepaintCount = 0
        var regionRepaintCount = 0
        var refreshCount = 0
        var renderRequestCount = 0
        val semanticCalls = ArrayList<String>()
        val publishHistorySizes = ArrayList<Int>()

        override fun resetCursorBlinkForFrame(): Boolean {
            semanticCalls += "resetCursorBlinkForFrame"
            val changed = !blinkVisible
            blinkVisible = true
            return changed
        }

        override fun refreshRenderCacheFromSession(session: TerminalSession) {
            refreshCount++
            semanticCalls += "refreshRenderCacheFromSession"
            session.renderPublisher.readCurrent { published -> renderCache.updateFrom(published) }
            visualGeometry.updateLayout(metrics, renderCache.rows, componentHeight)
        }

        override fun requestRender(session: TerminalSession) {
            renderRequestCount++
        }

        override fun syncTerminalGridToActiveChrome(): Boolean {
            semanticCalls += "syncTerminalGridToActiveChrome"
            return syncGridToChromeResult
        }

        override fun clampViewport(
            historySize: Int,
            discardedCount: Long,
        ): Boolean = clampViewportResult

        override fun requestedViewportOffset(): Int = 0

        override fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean {
            semanticCalls += "refreshShellIntegrationDecorations"
            return false
        }

        override fun refreshSearchForFrame() {
            semanticCalls += "refreshSearchForFrame"
            searchModel.search(renderCache, searchQuery, ignoreCase = false).buildViewportHighlights(renderCache, searchHighlights)
        }

        override fun publishViewportState(historySize: Int) {
            semanticCalls += "publishViewportState"
            publishHistorySizes += historySize
        }

        override fun repaint() {
            fullRepaintCount++
        }

        override fun repaintRegion(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            regionRepaintCount++
            regions += Region(x, y, width, height)
            repaintFrameGenerations += renderCache.frameGeneration
        }

        fun clearRepaints() {
            fullRepaintCount = 0
            regionRepaintCount = 0
            regions.clear()
            repaintFrameGenerations.clear()
        }
    }

    private data class Region(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private fun blinkFrame(
        textBlinks: Boolean = false,
        cursorBlinks: Boolean = true,
        generation: Long = 1,
    ): TestRenderFrame =
        object : TestRenderFrame(
            cells =
                Array(3) { row ->
                    Array(4) {
                        TestCell(
                            codeWord = if (row == 2 && generation > 1) 'Z'.code else 'A'.code,
                            flags = TerminalRenderCellFlags.CODEPOINT,
                            attr = TerminalRenderAttrs.pack(blink = textBlinks && row == 1),
                        )
                    }
                },
            cursorValue = TerminalRenderCursor(0, 0, true, cursorBlinks, TerminalRenderCursorShape.BLOCK, 1),
        ) {
            override val frameGeneration: Long = generation

            override fun lineGeneration(row: Int): Long = if (row == 2) generation else 1
        }

    private fun createSession(frameReader: TerminalRenderFrameReader? = null): TerminalSession {
        val terminal = TerminalBuffers.create(width = 2, height = 1, maxHistory = 1)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(2, 1),
                renderReader = frameReader ?: terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        session.renderPublisher.updateAndPublish(frameReader ?: terminal as TerminalRenderFrameReader)
        return session
    }

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }

    private object NoOpParser : TerminalOutputParser {
        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() = Unit

        override fun reset() = Unit
    }

    private object NoOpInputEncoder : TerminalInputEncoder {
        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }
}

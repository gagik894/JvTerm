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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingSettingsProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.ComponentEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.RepaintManager
import javax.swing.SwingUtilities

/** Rendering, viewport, and EDT coalescing tests for Swing terminal scrollback. */
class SwingTerminalScrollbackTest {
    @Test
    fun `equal render requests schedule at most one pending EDT repaint`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = ScrollbackFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        session.renderPublisher.updateAndPublish(renderReader)
        val component = SwingTerminal()
        val oldManager = RepaintManager.currentManager(component)
        val repaintManager = CountingRepaintManager(component)

        try {
            RepaintManager.setCurrentManager(repaintManager)
            SwingUtilities.invokeAndWait {
                component.bind(session)
            }
            awaitRenderGenerationAfter(session, -1L)
            drainEdt()
            repaintManager.reset()
            val generationBeforeFlood = session.renderGeneration.value

            val edtBlocked = CountDownLatch(1)
            val releaseEdt = CountDownLatch(1)
            SwingUtilities.invokeLater {
                edtBlocked.countDown()
                assertTrue(releaseEdt.await(5, TimeUnit.SECONDS), "EDT block was not released")
            }
            assertTrue(edtBlocked.await(1, TimeUnit.SECONDS), "EDT block did not start")

            repeat(1_000) {
                session.requestRender(scrollbackOffset = 0)
            }
            awaitRenderGenerationAfter(session, generationBeforeFlood)
            assertEquals(0, repaintManager.count)

            releaseEdt.countDown()
            SwingUtilities.invokeAndWait {
                // Drain any pending render-generation collection runnable.
            }

            assertTrue(repaintManager.count <= 1)
        } finally {
            RepaintManager.setCurrentManager(oldManager)
            session.close()
        }
    }

    @Test
    fun `mouse wheel updates component scrollback viewport`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = ScrollbackFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        session.renderPublisher.updateAndPublish(renderReader)
        val component = scrollTestTerminal()

        SwingUtilities.invokeAndWait {
            component.setSize(30, 100)
            component.bind(session)
        }
        SwingUtilities.invokeAndWait {
            component.dispatchEvent(
                MouseWheelEvent(
                    component,
                    MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    0,
                    5,
                    5,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    -1,
                ),
            )
        }

        awaitViewportOffset(component, expectedOffset = 3.0)
        val state = component.viewportState()
        assertEquals(3.0, state.scrollbackOffset)
        assertEquals(3, state.renderOffset)
        assertEquals(3, renderReader.lastRequestedOffset)
        session.close()
    }

    @Test
    fun `precise trackpad fractions accumulate into an integer row destination`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = ScrollbackFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = scrollTestTerminal()

        SwingUtilities.invokeAndWait {
            component.setSize(30, 100)
            component.bind(session)
        }
        lateinit var event: MouseWheelEvent
        SwingUtilities.invokeAndWait {
            repeat(3) {
                event = preciseWheelEvent(component, preciseRotation = -0.1)
                component.dispatchEvent(event)
            }
            assertEquals(0.0, component.viewportState().scrollbackOffset)
        }

        SwingUtilities.invokeAndWait {
            event = preciseWheelEvent(component, preciseRotation = -0.1)
            component.dispatchEvent(event)
        }

        awaitViewportOffset(component, expectedOffset = 1.0)
        val state = component.viewportState()
        assertEquals(1.0, state.scrollbackOffset)
        assertEquals(1, state.renderOffset)
        assertTrue(event.isConsumed)
        session.close()
    }

    @Test
    fun `host scroll command requests absolute scrollback viewport`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = ScrollbackFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = scrollTestTerminal()

        SwingUtilities.invokeAndWait {
            component.setSize(30, 100)
            component.bind(session)
        }
        SwingUtilities.invokeAndWait {
            component.scrollToScrollbackOffset(4)
        }

        awaitRequestedOffset(renderReader, expectedOffset = 4)
        drainEdt()
        val state = component.viewportState()
        assertTrue(renderReader.requestedOffsets.contains(4), "reader never received absolute scrollback offset 4")
        assertEquals(5, state.historySize)
        assertEquals(4.0, state.scrollbackOffset)
        assertEquals(4, state.renderOffset)
        session.close()
    }

    @Test
    fun `host fractional scroll commands accumulate and finish on a row`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val listener = RecordingViewportListener()
        val renderReader = ScrollbackFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component =
            scrollTestTerminal(
                hostServices =
                    SwingHostServices(
                        viewportListener = listener,
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.setSize(30, 100)
            component.bind(session)
        }
        awaitRenderGenerationAfter(session, -1L)
        awaitViewportOffset(component, expectedOffset = 0.0)
        repeat(3) {
            component.scrollViewportBy(0.25)
        }
        drainEdt()
        assertEquals(0.0, component.viewportState().scrollbackOffset)

        component.scrollViewportBy(0.25)

        awaitViewportOffset(component, 1.0)
        val state = component.viewportState()
        assertEquals(1.0, state.scrollbackOffset)
        assertEquals(1, state.renderOffset)
        assertEquals(1.0, listener.lastScrollbackOffset.get())
        assertEquals(1, listener.lastRenderOffset.get())
        assertTrue(renderReader.requestedOffsets.contains(1))
        session.close()
    }

    @Test
    fun `fractional-height viewport requests both partial-row and animation overscan`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = ScrollbackFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = scrollTestTerminal()

        SwingUtilities.invokeAndWait {
            val tenRows = component.preferredGridSize(columns = 3, rows = 10)
            val elevenRows = component.preferredGridSize(columns = 3, rows = 11)
            val cellHeight = elevenRows.height - tenRows.height
            component.setSize(tenRows.width, tenRows.height + cellHeight - 1)
            component.bind(session)

            assertEquals(10, component.visibleGridSize().height)
            assertEquals(10, terminal.height)
            assertEquals(11, component.viewportState().requestedRows)
        }

        component.scrollViewportBy(1.0)
        awaitViewportOffset(component, expectedOffset = 1.0)

        assertTrue(
            renderReader.requestedRows.contains(12),
            "reader never received 11 visible render rows plus one animation overscan row",
        )
        assertEquals(11, component.viewportState().requestedRows)
        session.close()
    }

    @Test
    fun `height growth requests a frame covering the resized viewport`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = ScrollbackFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = scrollTestTerminal()

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(columns = 3, rows = 3)
            component.bind(session)
        }
        awaitRequestedRows(renderReader, expectedRows = 3)
        renderReader.requestedRows.clear()

        var resizedRows = -1
        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(columns = 3, rows = 8)
            component.dispatchEvent(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
            resizedRows = component.viewportState().requestedRows
        }

        awaitRequestedRows(renderReader, resizedRows)
        awaitPublishedRows(session, resizedRows)
        session.close()
    }

    @Test
    fun `scrollbar drag applies exact rows immediately and release is aligned`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = ScrollbackFrameReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        session.renderPublisher.updateAndPublish(renderReader)
        val component = scrollTestTerminal()

        SwingUtilities.invokeAndWait {
            component.setSize(30, 100)
            component.bind(session)
            component.scrollFromScrollbar(scrollbackOffset = 3, valueIsAdjusting = true)
            assertEquals(3.0, component.viewportState().scrollbackOffset)
        }

        component.scrollFromScrollbar(scrollbackOffset = 3, valueIsAdjusting = false)
        awaitViewportOffset(component, expectedOffset = 3.0)
        session.close()
    }

    @Test
    fun `independently scrolling components use separate sessions`() {
        fun newSession(): TerminalSession {
            val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
            val reader = ScrollbackFrameReader()
            return TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = reader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            ).also { it.renderPublisher.updateAndPublish(reader) }
        }
        val leftSession = newSession()
        val rightSession = newSession()
        val left = scrollTestTerminal()
        val right = scrollTestTerminal()

        SwingUtilities.invokeAndWait {
            left.setSize(30, 100)
            right.setSize(30, 100)
            left.bind(leftSession)
            right.bind(rightSession)
        }
        drainEdt()

        left.scrollToScrollbackOffset(4)
        drainEdt()

        assertEquals(4.0, left.viewportState().scrollbackOffset)
        assertEquals(0.0, right.viewportState().scrollbackOffset)

        right.scrollToScrollbackOffset(2)
        drainEdt()

        assertEquals(4.0, left.viewportState().scrollbackOffset)
        assertEquals(2.0, right.viewportState().scrollbackOffset)
        leftSession.close()
        rightSession.close()
    }

    @Test
    fun `command gutter guides do not create scrollable overflow without core history`() {
        val reader = DividerOverflowFrameReader()
        val shellIntegrationState = TerminalShellIntegrationState()
        shellIntegrationState.recordPromptStart(1)
        shellIntegrationState.recordPromptStart(2)
        val terminal = TerminalBuffers.create(width = 3, height = 3, maxHistory = 0)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 3),
                renderReader = reader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                shellIntegrationState = shellIntegrationState,
            )
        session.renderPublisher.updateAndPublish(reader)
        val component =
            scrollTestTerminal(
                settings =
                    SwingSettings(
                        padding = SwingPadding(0, 0, 0, 0),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(3, 3)
            component.bind(session)

            val liveState = component.viewportState()
            assertEquals(0, liveState.historySize)
            assertEquals(0, liveState.renderOffset)
            assertEquals(0, liveState.visualScrollRangePixels)
            assertEquals(0.0, liveState.visualScrollOffsetPixels)

            val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                component.paint(graphics)
            } finally {
                graphics.dispose()
            }
            val cellHeight = component.height / 3
            assertTrue(
                image.containsNonBackgroundPixel(yStart = cellHeight * 2, yEnd = component.height),
                "bottom prompt row was clipped out of the live visual viewport",
            )
        }

        component.scrollToScrollbackOffset(1)
        drainEdt()

        val scrolledState = component.viewportState()
        assertEquals(0.0, scrolledState.scrollbackOffset)
        assertEquals(0, scrolledState.renderOffset)
        assertEquals(0.0, scrolledState.visualScrollOffsetPixels)
        session.close()
    }

    @Test
    fun `toggling command gutter markers does not resize terminal geometry`() {
        val connector = RecordingConnector()
        val shellIntegrationState = TerminalShellIntegrationState()
        shellIntegrationState.recordPromptStart(1)
        shellIntegrationState.recordPromptStart(2)
        val terminal = TerminalBuffers.create(width = 3, height = 3, maxHistory = 0)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 3),
                renderReader = DividerOverflowFrameReader(),
                responseReader = terminal,
                connector = connector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                shellIntegrationState = shellIntegrationState,
            )
        val settingsProvider =
            MutableSettingsProvider(
                SwingSettings(
                    padding = SwingPadding(0, 0, 0, 0),
                    shellIntegrationPromptDotsVisible = false,
                ),
            )
        val component = SwingTerminal(settingsProvider = settingsProvider)

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(3, 3)
            component.bind(session)
        }
        drainEdt()

        assertEquals(1, connector.resizeCount.get())
        assertEquals(3, connector.lastColumns.get())
        assertEquals(3, connector.lastRows.get())

        settingsProvider.settings = settingsProvider.settings.copy(shellIntegrationPromptDotsVisible = true)
        component.reloadSettings()
        drainEdt()

        assertEquals(1, connector.resizeCount.get())
        assertEquals(3, connector.lastColumns.get())
        assertEquals(3, connector.lastRows.get())
        assertEquals(3, component.visibleGridSize().height)
        assertEquals(3, component.visibleGridSize().width)
        session.close()
    }

    @Test
    fun `alternate screen chrome resizes terminal grid to explicit alternate padding`() {
        val connector = RecordingConnector()
        val reader = ActiveBufferFrameReader()
        val terminal = TerminalBuffers.create(width = 3, height = 3, maxHistory = 0)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 3),
                renderReader = reader,
                responseReader = terminal,
                connector = connector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val settings =
            SwingSettings(
                padding = SwingPadding(0, 40, 8, 8),
                alternateScreenPadding = SwingPadding(0, 8, 8, 8),
                shellIntegrationDecorationGutterWidth = 32,
            )
        val component = SwingTerminal(settingsProvider = SwingSettingsProvider { settings })

        try {
            SwingUtilities.invokeAndWait {
                component.size = component.preferredGridSize(10, 3)
                component.bind(session)
            }
            drainEdt()

            val primaryColumns = terminal.width
            assertEquals(10, primaryColumns)

            reader.activeBuffer = TerminalRenderBufferKind.ALTERNATE
            session.requestRender(scrollbackOffset = 0)
            awaitVisibleGridColumnsGreaterThan(component, primaryColumns)

            lateinit var alternateVisibleSize: java.awt.Dimension
            SwingUtilities.invokeAndWait {
                alternateVisibleSize = component.visibleGridSize()
            }

            assertTrue(alternateVisibleSize.width > primaryColumns)
            assertEquals(alternateVisibleSize.width, terminal.width)
            assertEquals(alternateVisibleSize.width, connector.lastColumns.get())
            assertEquals(SwingPadding(0, 8, 8, 8), settings.alternateScreenPadding)
        } finally {
            session.close()
        }
    }

    @Test
    fun `alternate buffer transition clears an active primary scrollback viewport`() {
        val reader = ActiveBufferFrameReader().apply { historySize = 5 }
        val terminal = TerminalBuffers.create(width = 3, height = 3, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 3),
                renderReader = reader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        session.renderPublisher.updateAndPublish(reader)
        val component = scrollTestTerminal()

        try {
            SwingUtilities.invokeAndWait {
                component.setSize(30, 100)
                component.bind(session)
            }
            component.scrollToScrollbackOffset(3)
            awaitViewportOffset(component, expectedOffset = 3.0)

            reader.activeBuffer = TerminalRenderBufferKind.ALTERNATE
            reader.historySize = 0
            val alternateGeneration = session.renderGeneration.value
            session.requestRender(scrollbackOffset = 0)
            awaitRenderGenerationAfter(session, alternateGeneration)
            awaitViewportOffset(component, expectedOffset = 0.0)
            assertEquals(0, component.viewportState().renderOffset)

            reader.activeBuffer = TerminalRenderBufferKind.PRIMARY
            reader.historySize = 5
            val primaryGeneration = session.renderGeneration.value
            session.requestRender(scrollbackOffset = 0)
            awaitRenderGenerationAfter(session, primaryGeneration)
            awaitViewportOffset(component, expectedOffset = 0.0)
            assertEquals(0, component.viewportState().renderOffset)
        } finally {
            session.close()
        }
    }

    private class CountingRepaintManager(
        private val target: JComponent,
    ) : RepaintManager() {
        private val repaintCount = AtomicInteger()

        val count: Int
            get() = repaintCount.get()

        fun reset() {
            repaintCount.set(0)
        }

        override fun addDirtyRegion(
            component: JComponent,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ) {
            if (component === target) {
                repaintCount.incrementAndGet()
            }
            super.addDirtyRegion(component, x, y, w, h)
        }
    }

    private class RecordingViewportListener : TerminalViewportListener {
        val lastScrollbackOffset = AtomicReference(0.0)
        val lastRenderOffset = AtomicInteger()

        override fun viewportChanged(
            historySize: Int,
            scrollbackOffset: Double,
            renderOffset: Int,
            visibleRows: Int,
            requestedRows: Int,
        ) {
            lastScrollbackOffset.set(scrollbackOffset)
            lastRenderOffset.set(renderOffset)
        }
    }

    private fun drainEdt() {
        if (SwingUtilities.isEventDispatchThread()) return
        SwingUtilities.invokeAndWait {
        }
    }

    private fun awaitRenderGenerationAfter(
        session: TerminalSession,
        generation: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (session.renderGeneration.value <= generation && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(session.renderGeneration.value > generation, "render was not published")
    }

    private fun awaitViewportOffset(
        component: SwingTerminal,
        expectedOffset: Double,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            drainEdt()
            if (component.viewportState().scrollbackOffset == expectedOffset) return
            Thread.sleep(5)
        }
        assertEquals(expectedOffset, component.viewportState().scrollbackOffset)
    }

    private fun awaitVisibleGridColumnsGreaterThan(
        component: SwingTerminal,
        columns: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            drainEdt()
            if (component.visibleGridSize().width > columns) return
            Thread.sleep(5)
        }
        assertTrue(component.visibleGridSize().width > columns, "visible grid was not resized")
    }

    private fun awaitRequestedRows(
        reader: ScrollbackFrameReader,
        expectedRows: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (expectedRows !in reader.requestedRows && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(expectedRows in reader.requestedRows, "render request did not cover $expectedRows rows")
    }

    private fun awaitRequestedOffset(
        reader: ScrollbackFrameReader,
        expectedOffset: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (expectedOffset !in reader.requestedOffsets && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(expectedOffset in reader.requestedOffsets, "render request did not use offset $expectedOffset")
    }

    private fun awaitPublishedRows(
        session: TerminalSession,
        expectedRows: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        var publishedRows = -1
        while (System.nanoTime() < deadline) {
            session.renderPublisher.readCurrent { publishedRows = it.rows }
            if (publishedRows == expectedRows) return
            Thread.onSpinWait()
        }
        assertEquals(expectedRows, publishedRows)
    }

    private fun preciseWheelEvent(
        component: SwingTerminal,
        preciseRotation: Double,
    ): MouseWheelEvent =
        MouseWheelEvent(
            component,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            0,
            5,
            5,
            0,
            0,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            3,
            0,
            preciseRotation,
        )

    private fun scrollTestTerminal(
        hostServices: SwingHostServices = SwingHostServices(),
        settings: SwingSettings = SwingSettings(padding = SwingPadding(0, 0, 0, 0)),
    ): SwingTerminal =
        SwingTerminal(
            settingsProvider = SwingSettingsProvider { settings },
            hostServices = hostServices,
        )

    private fun BufferedImage.containsNonBackgroundPixel(
        yStart: Int,
        yEnd: Int,
    ): Boolean {
        val safeStart = yStart.coerceIn(0, height)
        val safeEnd = yEnd.coerceIn(safeStart, height)
        var y = safeStart
        while (y < safeEnd) {
            var x = 0
            while (x < width) {
                if (getRGB(x, y) != BLACK) return true
                x++
            }
            y++
        }
        return false
    }

    private class ScrollbackFrameReader : TerminalRenderFrameReader {
        private val generation = AtomicLong()

        @Volatile
        var lastRequestedOffset: Int = -1
            private set

        @Volatile
        var lastRequestedRows: Int = -1
            private set
        val requestedOffsets = CopyOnWriteArrayList<Int>()
        val requestedRows = CopyOnWriteArrayList<Int>()

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            requestedOffsets += scrollbackOffset
            lastRequestedRows = 0
            requestedRows += 0
            consumer.accept(
                ScrollbackFrame(
                    scrollbackOffset.coerceIn(0, 5),
                    rows = 1,
                    frameGeneration = generation.incrementAndGet(),
                ),
            )
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            requestedOffsets += scrollbackOffset
            lastRequestedRows = viewportRows
            requestedRows += viewportRows
            consumer.accept(
                ScrollbackFrame(
                    scrollbackOffset.coerceIn(0, 5),
                    rows = viewportRows.coerceAtLeast(1),
                    frameGeneration = generation.incrementAndGet(),
                ),
            )
        }
    }

    private class ScrollbackFrame(
        override val scrollbackOffset: Int,
        override val rows: Int,
        override val frameGeneration: Long,
    ) : TerminalRenderFrame {
        override val columns: Int = 3
        override val historySize: Int = 5
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = scrollbackOffset == 0,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

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

    private class DividerOverflowFrameReader : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(DividerOverflowFrame)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(DividerOverflowFrame)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(DividerOverflowFrame)
        }
    }

    private object DividerOverflowFrame : TerminalRenderFrame {
        private val lines = arrayOf("one", "two", "end")
        override val columns: Int = 3
        override val rows: Int = 3
        override val historySize: Int = 0
        override val scrollbackOffset: Int = 0
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val palette: TerminalColorPalette =
            TerminalColorPalette(
                defaultForeground = WHITE,
                defaultBackground = BLACK,
            )
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

        override fun lineId(row: Int): Long = (row + 1).toLong()

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
            val line = lines[row]
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = line[column].code
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private class ActiveBufferFrameReader : TerminalRenderFrameReader {
        @Volatile
        var activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY

        @Volatile
        var historySize: Int = 0

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(ActiveBufferFrame(activeBuffer, historySize, scrollbackOffset = 0))
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(ActiveBufferFrame(activeBuffer, historySize, scrollbackOffset.coerceIn(0, historySize)))
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(ActiveBufferFrame(activeBuffer, historySize, scrollbackOffset.coerceIn(0, historySize)))
        }
    }

    private class ActiveBufferFrame(
        override val activeBuffer: TerminalRenderBufferKind,
        override val historySize: Int,
        override val scrollbackOffset: Int,
    ) : TerminalRenderFrame {
        override val columns: Int = 3
        override val rows: Int = 3
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

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

    private class RecordingConnector : TerminalConnector {
        val resizeCount = AtomicInteger()
        val lastColumns = AtomicInteger()
        val lastRows = AtomicInteger()

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            resizeCount.incrementAndGet()
            lastColumns.set(columns)
            lastRows.set(rows)
        }

        override fun close() = Unit
    }

    private class MutableSettingsProvider(
        var settings: SwingSettings,
    ) : SwingSettingsProvider {
        override fun currentSettings(): SwingSettings = settings
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

    private companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}

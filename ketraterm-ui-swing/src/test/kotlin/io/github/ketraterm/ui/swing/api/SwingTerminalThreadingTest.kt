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
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.*
import java.awt.event.ComponentEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

class SwingTerminalThreadingTest {
    @ParameterizedTest
    @ValueSource(strings = ["select", "bind", "unbind"])
    fun `currentSelection snapshots state after queued EDT changes`(change: String) {
        val session = testSession()
        session.renderPublisher.updateAndPublish(session.terminal as TerminalRenderFrameReader)
        val component = SwingTerminal(settingsProvider = { SwingSettings(columns = 3, rows = 1, cursorBlinkMillis = 0) })
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        val snapshotQueuedOrReturned = CountDownLatch(1)
        val result = AtomicReference<CellSelection?>()
        val failure = AtomicReference<Throwable?>()
        val worker =
            thread(start = false, name = "selection-snapshot-reader") {
                try {
                    result.set(component.currentSelection())
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    snapshotQueuedOrReturned.countDown()
                }
            }
        val eventQueue =
            object : EventQueue() {
                override fun postEvent(event: AWTEvent) {
                    super.postEvent(event)
                    if (Thread.currentThread() === worker) snapshotQueuedOrReturned.countDown()
                }

                fun restore() = pop()
            }
        edtCall {
            Toolkit.getDefaultToolkit().systemEventQueue.push(eventQueue)
        }
        try {
            edtCall {
                if (change != "bind") component.bind(session)
                if (change == "unbind") assertTrue(component.selectAll())
            }
            SwingUtilities.invokeLater {
                try {
                    edtBlocked.countDown()
                    check(releaseEdt.await(5, TimeUnit.SECONDS)) { "EDT blocker was not released" }
                    when (change) {
                        "select" -> assertTrue(component.selectAll())
                        "bind" -> {
                            component.bind(session)
                            assertTrue(component.selectAll())
                        }
                        "unbind" -> component.unbind()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            assertTrue(edtBlocked.await(5, TimeUnit.SECONDS))
            worker.start()
            // Wait for dispatch or an incorrect early return, without racing the EDT mutation.
            assertTrue(snapshotQueuedOrReturned.await(5, TimeUnit.SECONDS))
            releaseEdt.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive, "Selection snapshot did not complete")
            drainEdt()
            failure.get()?.let { throw it }
            assertEquals(if (change == "unbind") null else CellSelection(0, 0, 3, 0), result.get())
        } finally {
            releaseEdt.countDown()
            edtCall {
                component.dispose()
                eventQueue.restore()
            }
            worker.join(5_000)
            session.close()
        }
    }

    @Test
    fun `dispose cancels the component coroutine scope`() {
        val component = SwingTerminal()

        SwingUtilities.invokeAndWait {
            assertTrue(component.isCoroutineScopeActive)
            component.dispose()
            assertFalse(component.isCoroutineScopeActive)
        }
    }

    @Test
    fun `routine frame consumption never reads the session on the EDT`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val checkingReader = EdtCheckingRenderReader(terminal as TerminalRenderFrameReader)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = checkingReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        val component = SwingTerminal()

        component.bind(session)
        awaitRenderAfter(session, -1L)
        drainEdt()

        assertFalse(checkingReader.readOnEdt.get())
        session.close()
    }

    @Test
    fun `publication from a previously bound session is ignored`() {
        val first = testSession()
        val second = testSession()
        repeat(3) {
            first.terminal.writeCodepoint('x'.code)
            first.terminal.carriageReturn()
            first.terminal.newLine()
        }
        val component = SwingTerminal()
        edtCall {
            component.size = component.preferredGridSize(3, 1)
        }

        component.bind(first)
        awaitRenderAfter(first, -1L)
        awaitHistorySize(component) { it > 0 }

        component.bind(second)
        awaitRenderAfter(second, -1L)
        awaitHistorySize(component) { it == 0 }

        val previousFirstGeneration = first.renderGeneration.value
        first.requestRender(scrollbackOffset = 0)
        awaitRenderAfter(first, previousFirstGeneration)
        drainEdt()
        assertEquals(0, component.viewportState().historySize)

        first.close()
        second.close()
    }

    @Test
    fun `bind and unbind may be called from a background thread`() {
        val session = testSession()
        val component = SwingTerminal()

        runOffEdt {
            component.bind(session)
        }
        drainEdt()
        assertTrue(component.hasActiveRenderBinding)

        runOffEdt {
            component.unbind()
        }
        drainEdt()

        assertFalse(component.hasActiveRenderBinding)
        session.close()
    }

    @Test
    fun `off EDT lifecycle work uses injected UI dispatcher`() {
        val session = testSession()
        val dispatcher = RecordingDispatcher()
        val component =
            SwingTerminal(
                hostServices =
                    SwingHostServices(
                        uiDispatcher = dispatcher,
                    ),
            )
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        SwingUtilities.invokeLater {
            edtBlocked.countDown()
            releaseEdt.await(2, TimeUnit.SECONDS)
        }
        assertTrue(edtBlocked.await(1, TimeUnit.SECONDS), "EDT blocker did not start")

        try {
            runOffEdt {
                component.bind(session)
                component.reloadSettings()
                component.unbind()
            }
            assertEquals(3, dispatcher.dispatchCount.get())
        } finally {
            releaseEdt.countDown()
        }
        drainEdt()

        assertFalse(component.hasActiveRenderBinding)
        session.close()
    }

    @Test
    fun `bind called off EDT does not wait for EDT execution`() {
        val session = testSession()
        val component = SwingTerminal()
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        val bindReturned = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()

        SwingUtilities.invokeLater {
            try {
                edtBlocked.countDown()
                if (!releaseEdt.await(1, TimeUnit.SECONDS)) {
                    failure.set(AssertionError("EDT blocker was not released"))
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        assertTrue(edtBlocked.await(1, TimeUnit.SECONDS), "EDT blocker did not start")

        val worker =
            thread(start = true) {
                try {
                    component.bind(session)
                    bindReturned.set(true)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

        worker.join(500)
        assertTrue(bindReturned.get(), "bind should post to EDT and return without waiting")

        releaseEdt.countDown()
        worker.join(1_000)
        failure.get()?.let { throw it }
        drainEdt()
        assertTrue(component.hasActiveRenderBinding)
        session.close()
    }

    @Test
    fun `reloadSettings called off EDT rebuilds component state on EDT`() {
        val reloadCalledOnEdt = AtomicBoolean(false)
        val calls = AtomicInteger()
        val component =
            SwingTerminal(settingsProvider = {
                if (calls.incrementAndGet() > 1) {
                    reloadCalledOnEdt.set(SwingUtilities.isEventDispatchThread())
                }
                SwingSettings(
                    font = Font(Font.MONOSPACED, Font.PLAIN, 18),
                    columns = 100,
                    rows = 30,
                )
            })

        runOffEdt {
            component.reloadSettings()
        }

        val preferredSize = edtCall { component.preferredSize }
        assertAll(
            { assertTrue(reloadCalledOnEdt.get()) },
            { assertTrue(preferredSize.width > 0) },
            { assertTrue(preferredSize.height > 0) },
        )
    }

    @Test
    fun `visibleGridSize called off EDT reads cached grid size without waiting for EDT`() {
        val component = SwingTerminal()
        val expected =
            edtCall {
                component.size = Dimension(160, 80)
                component.visibleGridSize()
            }
        val visibleFromBackground = AtomicReference<Dimension>()
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        SwingUtilities.invokeLater {
            try {
                edtBlocked.countDown()
                if (!releaseEdt.await(1, TimeUnit.SECONDS)) {
                    failure.set(AssertionError("EDT blocker was not released"))
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        assertTrue(edtBlocked.await(1, TimeUnit.SECONDS), "EDT blocker did not start")

        val worker =
            thread(start = true) {
                try {
                    visibleFromBackground.set(component.visibleGridSize())
                    returned.countDown()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

        val completedWhileEdtBlocked = returned.await(500, TimeUnit.MILLISECONDS)
        releaseEdt.countDown()
        worker.join(1_000)
        failure.get()?.let { throw it }
        assertTrue(completedWhileEdtBlocked, "visibleGridSize should not wait for the EDT")
        assertEquals(expected, visibleFromBackground.get())
    }

    @Test
    fun `bind resizes session to current visible grid when component has bounds`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
        val component = SwingTerminal()

        val expected =
            edtCall {
                component.size = Dimension(160, 80)
                component.visibleGridSize()
            }

        component.bind(session)
        drainEdt()

        assertAll(
            { assertEquals(expected.width, connector.lastColumns.get()) },
            { assertEquals(expected.height, connector.lastRows.get()) },
            { assertEquals(1, connector.resizeCount.get()) },
        )
        session.close()
    }

    @Test
    fun `bind applies ambiguous width setting to session core`() {
        val session = testSession()
        val component =
            SwingTerminal(settingsProvider = {
                SwingSettings(treatAmbiguousAsWide = true)
            })

        component.bind(session)
        drainEdt()

        assertTrue(session.terminal.getModeSnapshot().treatAmbiguousAsWide)
        session.close()
    }

    @Test
    fun `reloadSettings updates ambiguous width setting on bound session`() {
        val session = testSession()
        var ambiguousAsWide = false
        val component =
            SwingTerminal(settingsProvider = {
                SwingSettings(treatAmbiguousAsWide = ambiguousAsWide)
            })

        component.bind(session)
        drainEdt()
        assertFalse(session.terminal.getModeSnapshot().treatAmbiguousAsWide)

        ambiguousAsWide = true
        component.reloadSettings()
        drainEdt()

        assertTrue(session.terminal.getModeSnapshot().treatAmbiguousAsWide)
        session.close()
    }

    @Test
    fun `component resize updates session only when visible grid changes`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
        val component = SwingTerminal()

        edtCall {
            component.size = Dimension(160, 80)
        }
        component.bind(session)
        drainEdt()
        connector.reset()

        val expected =
            edtCall {
                component.size = Dimension(320, 160)
                component.dispatchEvent(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
                component.visibleGridSize()
            }

        assertAll(
            { assertEquals(expected.width, connector.lastColumns.get()) },
            { assertEquals(expected.height, connector.lastRows.get()) },
            { assertEquals(1, connector.resizeCount.get()) },
        )

        edtCall {
            component.dispatchEvent(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
        }

        assertEquals(1, connector.resizeCount.get())
        session.close()
    }

    @Test
    fun `unrelated and unchanged settings preserve application palette and cursor`() {
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5),
                connector = NoOpConnector,
            )
        var settings = SwingSettings()
        val component = SwingTerminal(settingsProvider = { settings })
        try {
            edtCall {
                component.bind(session)
                // Apply application overrides through the same synchronization boundary as live output.
                val output = "\u001B[6 q\u001B]4;1;rgb:12/34/56\u0007".toByteArray(Charsets.US_ASCII)
                session.onBytes(output, 0, output.size)

                component.reloadSettings()
                settings = settings.copy(visualBellEnabled = !settings.visualBellEnabled)
                component.reloadSettings()

                session.readRenderFrame { frame ->
                    assertEquals(TerminalRenderCursorShape.BAR, frame.cursor.shape)
                    assertEquals(0xFF123456.toInt(), frame.palette.indexedColor(1))
                }
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `changed theme and cursor preferences replace application overrides`() {
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5),
                connector = NoOpConnector,
            )
        var settings = SwingSettings()
        val component = SwingTerminal(settingsProvider = { settings })
        try {
            edtCall {
                component.bind(session)
                val output = "\u001B[6 q\u001B]4;1;rgb:12/34/56\u0007".toByteArray(Charsets.US_ASCII)
                session.onBytes(output, 0, output.size)
                session.readRenderFrame { frame ->
                    assertEquals(TerminalRenderCursorShape.BAR, frame.cursor.shape)
                    assertEquals(0xFF123456.toInt(), frame.palette.indexedColor(1))
                }
                settings = settings.copy(palette = TerminalTheme.NORD.createPalette(), cursorShape = TerminalRenderCursorShape.UNDERLINE)

                component.reloadSettings()

                session.readRenderFrame { frame ->
                    assertEquals(settings.cursorShape, frame.cursor.shape)
                    assertEquals(settings.palette, frame.palette)
                }
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `font size changes resize the grid but paint preferences do not`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
        var settings = SwingSettings(font = Font(Font.MONOSPACED, Font.PLAIN, 14))
        val component = SwingTerminal(settingsProvider = { settings })
        try {
            edtCall {
                component.size = Dimension(320, 160)
                component.bind(session)
            }
            drainEdt()
            connector.reset()
            edtCall {
                val originalGrid = component.visibleGridSize()
                settings = settings.copy(selectionBackground = 0xFF123456.toInt())
                component.reloadSettings()
                assertEquals(originalGrid, component.visibleGridSize())
                assertEquals(0, connector.resizeCount.get())

                settings = settings.copy(font = settings.font.deriveFont(28f))
                component.reloadSettings()
                assertTrue(component.visibleGridSize().width < originalGrid.width)
                assertTrue(component.visibleGridSize().height < originalGrid.height)
                assertEquals(1, connector.resizeCount.get())
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    private fun testSession(connector: TerminalConnector = NoOpConnector): TerminalSession {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        return TerminalSession(
            terminal = terminal,
            renderPublisher = TerminalRenderPublisher(3, 1),
            renderReader = terminal as TerminalRenderFrameReader,
            responseReader = terminal,
            connector = connector,
            parser = NoOpParser,
            inputEncoder = NoOpInputEncoder,
        )
    }

    private fun runOffEdt(action: () -> Unit) {
        assertFalse(SwingUtilities.isEventDispatchThread())
        val failure = AtomicReference<Throwable?>()
        val worker =
            thread(start = true) {
                try {
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    action()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        worker.join()
        failure.get()?.let { throw it }
    }

    private fun <T> edtCall(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()

        val result = AtomicReference<T>()
        SwingUtilities.invokeAndWait {
            result.set(action())
        }
        return result.get()
    }

    private fun drainEdt() {
        edtCall { }
    }

    private fun awaitRenderAfter(
        session: TerminalSession,
        generation: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (session.renderGeneration.value <= generation && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(session.renderGeneration.value > generation, "render was not published")
    }

    private fun awaitHistorySize(
        component: SwingTerminal,
        predicate: (Int) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var historySize = component.viewportState().historySize
        while (System.nanoTime() < deadline) {
            drainEdt()
            historySize = component.viewportState().historySize
            if (predicate(historySize)) return
            Thread.sleep(5)
        }
        assertTrue(predicate(historySize), "unexpected Swing history size: $historySize")
    }

    private class EdtCheckingRenderReader(
        private val delegate: TerminalRenderFrameReader,
    ) : TerminalRenderFrameReader {
        val readOnEdt = AtomicBoolean(false)

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            recordThread()
            delegate.readRenderFrame(consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            recordThread()
            delegate.readRenderFrame(scrollbackOffset, consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            recordThread()
            delegate.readRenderFrame(scrollbackOffset, viewportRows, consumer)
        }

        private fun recordThread() {
            if (SwingUtilities.isEventDispatchThread()) readOnEdt.set(true)
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

    private class RecordingDispatcher : TerminalUiDispatcher {
        val dispatchCount = AtomicInteger()

        override fun dispatch(runnable: Runnable) {
            dispatchCount.incrementAndGet()
            SwingUtilities.invokeLater(runnable)
        }
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
            lastColumns.set(columns)
            lastRows.set(rows)
            resizeCount.incrementAndGet()
        }

        override fun close() = Unit

        fun reset() {
            resizeCount.set(0)
            lastColumns.set(0)
            lastRows.set(0)
        }
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

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
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.TerminalHyperlinkHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Cursor
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton

class TerminalHyperlinkControllerTest {
    @Test
    fun `stationary hover follows replaced spans and retains modifiers while detection is pending`() {
        val cache = TerminalRenderCache(8, 2)
        val host = FakeHyperlinkHost(cache, null, SwingHostServices(), mapOf(-1 to { true }, -2 to { true }))
        val controller = TerminalHyperlinkController(host)
        controller.handleMouseMoved(MouseEvent(JButton(), MouseEvent.MOUSE_MOVED, 0L, InputEvent.CTRL_DOWN_MASK, 15, 5, 0, false))
        assertEquals(0, controller.hoveredHyperlinkId)

        cache.hyperlinkIds.fill(-1, 1, 11)
        cache.lineWrapped[0] = true
        controller.refreshHyperlinkHover()
        assertEquals(-1, controller.hoveredHyperlinkId)
        assertEquals(0, controller.hoveredHyperlinkStartRow)
        assertEquals(1, controller.hoveredHyperlinkStartColumn)
        assertEquals(1, controller.hoveredHyperlinkEndRow)
        assertEquals(3, controller.hoveredHyperlinkEndColumn)
        assertTrue(controller.hyperlinkActivationHover)
        host.repaintSpans.clear()
        controller.refreshHyperlinkHover()
        assertTrue(host.repaintSpans.isEmpty(), "Unchanged hover must not schedule extra painting")

        cache.hyperlinkIds.fill(0)
        cache.hyperlinkIds.fill(-2, 0, 4)
        cache.lineWrapped[0] = false
        controller.refreshHyperlinkHover()
        assertEquals(-2, controller.hoveredHyperlinkId)
        assertEquals(0, controller.hoveredHyperlinkStartColumn)
        assertEquals(0, controller.hoveredHyperlinkEndRow)
        assertEquals(4, controller.hoveredHyperlinkEndColumn)

        cache.hyperlinkIds.fill(0)
        cache.hyperlinkIds[cache.rowOffset(1)] = -2
        controller.refreshHyperlinkHover()
        assertEquals(0, controller.hoveredHyperlinkId, "A link moved away from the pointer must not stay hovered")
        assertEquals(Cursor.DEFAULT_CURSOR, host.cursor.type)
        controller.updateHyperlinkActivationHover(false)
        cache.hyperlinkIds[1] = -1
        controller.refreshHyperlinkHover()
        assertEquals(-1, controller.hoveredHyperlinkId)
        assertEquals(false, controller.hyperlinkActivationHover)

        controller.handleMouseExited()
        controller.refreshHyperlinkHover()
        assertEquals(0, controller.hoveredHyperlinkId, "Publication after mouse exit must not revive hover")
        controller.handleMouseMoved(MouseEvent(JButton(), MouseEvent.MOUSE_MOVED, 0L, 0, 15, 5, 0, false))
        controller.clearHyperlinkHover()
        controller.refreshHyperlinkHover()
        assertEquals(0, controller.hoveredHyperlinkId, "Lifecycle reset must forget the old pointer")
    }

    @Test
    fun `OSC8 hover survives progress rewrites and clears when its cells are erased`() {
        Osc8PipelineFixture(20, 2).use { fixture ->
            fixture.accept("\u001b]8;;https://example.com\u001b\\docs\u001b]8;;\u001b\\\r\n0%")
            fixture.refresh()
            fixture.hover(1, 0, activationHover = true)
            val id = fixture.controller.hoveredHyperlinkId
            assertTrue(id > 0)
            fixture.host.repaintSpans.clear()
            repeat(3) { progress ->
                fixture.accept("\r\u001b[2K$progress%")
                fixture.refresh()
                fixture.controller.refreshHyperlinkHover()
                fixture.assertHoverSpan(id, 0, 0, 0, 4)
                assertTrue(fixture.controller.hyperlinkActivationHover)
            }
            assertTrue(fixture.host.repaintSpans.isEmpty())
            fixture.accept("\u001b[1;1H\u001b[2K")
            fixture.refresh()
            fixture.controller.refreshHyperlinkHover()
            fixture.assertNoHover()
        }
    }

    private data class RepaintSpan(
        val startRow: Int,
        val startColumn: Int,
        val endRow: Int,
        val endColumn: Int,
    )

    private class FakeHyperlinkHost(
        override val renderCache: TerminalRenderCache,
        private val session: TerminalSession?,
        private val hostServices: SwingHostServices,
        private val discoveredActions: Map<Int, () -> Boolean> = emptyMap(),
    ) : TerminalHyperlinkHost {
        override var cursor: Cursor = Cursor.getDefaultCursor()
        var repaints = 0
        val repaintSpans = mutableListOf<RepaintSpan>()

        override fun cellAt(
            x: Int,
            y: Int,
        ): Long {
            val col = x / 10
            val row = y / 20
            return (col.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }

        override fun repaintHyperlinkSpan(
            startRow: Int,
            startColumn: Int,
            endRow: Int,
            endColumn: Int,
        ) {
            repaintSpans += RepaintSpan(startRow, startColumn, endRow, endColumn)
        }

        override fun hyperlinkIdAt(
            row: Int,
            column: Int,
        ): Int = renderCache.hyperlinkIds[renderCache.rowOffset(row) + column]

        override fun isHyperlinkResolvable(hyperlinkId: Int): Boolean =
            if (hyperlinkId > 0) {
                session?.hyperlinkUri(hyperlinkId) != null
            } else {
                discoveredActions.containsKey(hyperlinkId)
            }

        override fun openHyperlink(hyperlinkId: Int): Boolean {
            if (hyperlinkId < 0) return discoveredActions[hyperlinkId]?.invoke() == true
            val uri = session?.hyperlinkUri(hyperlinkId) ?: return false
            return hostServices.hyperlinkHandler.openHyperlink(uri)
        }
    }

    private class FakeFrameReader : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) = Unit

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) = Unit

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) = Unit
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

    private class Osc8PipelineFixture(
        width: Int,
        height: Int,
        maxHistory: Int = 16,
        hostPolicy: HostPolicy = HostPolicy(),
    ) : AutoCloseable {
        val terminal = TerminalBuffers.create(width = width, height = height, maxHistory = maxHistory)
        val session = TerminalSession.create(terminal = terminal, connector = NoOpConnector, hostPolicy = hostPolicy)
        val cache = TerminalRenderCache(width, height)
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)
        private val component = JButton()

        fun accept(text: String) {
            val bytes = text.encodeToByteArray()
            session.onBytes(bytes, 0, bytes.size)
        }

        fun refresh(
            scrollbackOffset: Int = 0,
            viewportRows: Int = 0,
        ) {
            cache.updateFrom(session, scrollbackOffset, viewportRows)
        }

        fun hover(
            column: Int,
            row: Int,
            activationHover: Boolean = false,
        ) {
            val modifiers = if (activationHover) InputEvent.CTRL_DOWN_MASK else 0
            controller.handleMouseMoved(
                MouseEvent(
                    component,
                    MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(),
                    modifiers,
                    column * CELL_WIDTH + CELL_WIDTH / 2,
                    row * CELL_HEIGHT + CELL_HEIGHT / 2,
                    0,
                    false,
                ),
            )
        }

        fun assertHoverSpan(
            hyperlinkId: Int,
            startRow: Int,
            startColumn: Int,
            endRow: Int,
            endColumn: Int,
        ) {
            assertEquals(hyperlinkId, controller.hoveredHyperlinkId)
            assertEquals(startRow, controller.hoveredHyperlinkStartRow)
            assertEquals(startColumn, controller.hoveredHyperlinkStartColumn)
            assertEquals(endRow, controller.hoveredHyperlinkEndRow)
            assertEquals(endColumn, controller.hoveredHyperlinkEndColumn)
        }

        fun assertNoHover() {
            assertEquals(0, controller.hoveredHyperlinkId)
            assertEquals(Cursor.getDefaultCursor(), host.cursor)
        }

        override fun close() {
            session.close()
        }
    }

    @Test
    fun `hover over cell without hyperlink keeps default cursor`() {
        val cache = TerminalRenderCache(10, 10)
        val host = FakeHyperlinkHost(cache, null, SwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(0, controller.hoveredHyperlinkId)
        assertEquals(Cursor.getDefaultCursor(), host.cursor)
    }

    @Test
    fun `hover over cell with resolved hyperlink changes cursor to hand`() {
        val cache =
            TerminalRenderCache(10, 10).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
            }
        val terminal = TerminalBuffers.create(width = 10, height = 10, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(10, 10),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(5, controller.hoveredHyperlinkId)
        assertEquals(1, controller.hoveredHyperlinkStartRow)
        assertEquals(1, controller.hoveredHyperlinkStartColumn)
        assertEquals(1, controller.hoveredHyperlinkEndRow)
        assertEquals(2, controller.hoveredHyperlinkEndColumn)
        assertEquals(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR), host.cursor)
    }

    @Test
    fun `mouseExited clears hover state and resets cursor`() {
        val cache =
            TerminalRenderCache(10, 10).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
            }
        val terminal = TerminalBuffers.create(width = 10, height = 10, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(10, 10),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val moveEvent = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(moveEvent)
        assertEquals(5, controller.hoveredHyperlinkId)

        controller.handleMouseExited()
        assertEquals(0, controller.hoveredHyperlinkId)
        assertEquals(Cursor.getDefaultCursor(), host.cursor)
    }

    @Test
    fun `hover over repeated same id tracks only the contiguous span under the pointer`() {
        val cache =
            TerminalRenderCache(6, 2).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
                hyperlinkIds[rowOffset(1) + 2] = 5
                hyperlinkIds[rowOffset(1) + 4] = 5
            }
        val terminal = TerminalBuffers.create(width = 6, height = 2, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(6, 2),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(5, controller.hoveredHyperlinkId)
        assertEquals(1, controller.hoveredHyperlinkStartRow)
        assertEquals(1, controller.hoveredHyperlinkStartColumn)
        assertEquals(1, controller.hoveredHyperlinkEndRow)
        assertEquals(3, controller.hoveredHyperlinkEndColumn)
    }

    @Test
    fun `hover over soft-wrapped link tracks the full wrapped contiguous span`() {
        val cache =
            TerminalRenderCache(3, 2).apply {
                hyperlinkIds[rowOffset(0) + 1] = 5
                hyperlinkIds[rowOffset(0) + 2] = 5
                hyperlinkIds[rowOffset(1)] = 5
                hyperlinkIds[rowOffset(1) + 1] = 5
                lineWrapped[0] = true
            }
        val terminal = TerminalBuffers.create(width = 3, height = 2, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 2),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val event = MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 5, 25, 0, false)
        controller.handleMouseMoved(event)

        assertEquals(5, controller.hoveredHyperlinkId)
        assertEquals(0, controller.hoveredHyperlinkStartRow)
        assertEquals(1, controller.hoveredHyperlinkStartColumn)
        assertEquals(1, controller.hoveredHyperlinkEndRow)
        assertEquals(2, controller.hoveredHyperlinkEndColumn)
    }

    @Test
    fun `hover state repaints only old and new hyperlink spans`() {
        val cache =
            TerminalRenderCache(6, 2).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
                hyperlinkIds[rowOffset(1) + 2] = 5
                hyperlinkIds[rowOffset(1) + 4] = 5
            }
        val terminal = TerminalBuffers.create(width = 6, height = 2, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(6, 2),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val host = FakeHyperlinkHost(cache, session, SwingHostServices())
        val controller = TerminalHyperlinkController(host)
        val button = JButton()

        controller.handleMouseMoved(MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 15, 25, 0, false))
        assertEquals(listOf(RepaintSpan(1, 1, 1, 3)), host.repaintSpans)
        assertEquals(0, host.repaints)

        controller.updateHyperlinkActivationHover(true)
        assertEquals(listOf(RepaintSpan(1, 1, 1, 3), RepaintSpan(1, 1, 1, 3)), host.repaintSpans)
        assertEquals(0, host.repaints)

        controller.handleMouseMoved(MouseEvent(button, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 45, 25, 0, false))
        assertEquals(
            listOf(
                RepaintSpan(1, 1, 1, 3),
                RepaintSpan(1, 1, 1, 3),
                RepaintSpan(1, 1, 1, 3),
                RepaintSpan(1, 4, 1, 5),
            ),
            host.repaintSpans,
        )
        assertEquals(0, host.repaints)
    }

    @Test
    fun `real OSC8 repeated anonymous links produce independent UI hover spans`() {
        Osc8PipelineFixture(width = 8, height = 2).use { f ->
            f.accept(osc8("https://example.com/a", "AA") + " " + osc8("https://example.com/a", "BB"))
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 2)

            f.hover(column = 3, row = 0)
            f.assertHoverSpan(hyperlinkId = 2, startRow = 0, startColumn = 3, endRow = 0, endColumn = 5)
        }
    }

    @Test
    fun `real OSC8 span includes wide cells and grapheme clusters`() {
        Osc8PipelineFixture(width = 8, height = 2).use { f ->
            f.accept(osc8("https://example.com/unicode", "\u4E2De\u0301Z"))
            f.refresh()

            assertEquals("e\u0301", f.cache.clusterText(row = 0, column = 2))
            f.hover(column = 1, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 4)
        }
    }

    @Test
    fun `real OSC8 span excludes erased and partially overwritten cells`() {
        Osc8PipelineFixture(width = 6, height = 2).use { f ->
            f.accept(osc8("https://example.com/edit", "ABCD") + "\u001B[1;3H\u001B[K\u001B[1;2Hx")
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 1)

            f.hover(column = 1, row = 0)
            f.assertNoHover()
        }
    }

    @Test
    fun `real OSC8 links in scrollback resolve through render cache viewport`() {
        Osc8PipelineFixture(width = 8, height = 2, maxHistory = 8).use { f ->
            f.accept(osc8("https://example.com/history", "SC") + "\r\nplain\r\nbottom")
            f.refresh(scrollbackOffset = 1)

            assertEquals(1, f.cache.historySize)
            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 2)
        }
    }

    @Test
    fun `real OSC8 alternate buffer links are resolved from active alternate frame`() {
        Osc8PipelineFixture(width = 6, height = 2).use { f ->
            f.accept(osc8("https://example.com/primary", "P") + "\u001B[?1049h" + osc8("https://example.com/alt", "A"))
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 2, startRow = 0, startColumn = 0, endRow = 0, endColumn = 1)
        }
    }

    @Test
    fun `real OSC8 wrapped link remains one span after resize reflow`() {
        Osc8PipelineFixture(width = 6, height = 2, maxHistory = 8).use { f ->
            f.accept(osc8("https://example.com/reflow", "abcdefg"))
            f.session.resize(columns = 3, rows = 3)
            f.refresh()

            f.hover(column = 1, row = 1)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 2, endColumn = 1)
        }
    }

    @Test
    fun `real OSC8 bidi row keeps logical hover span bounds`() {
        Osc8PipelineFixture(width = 6, height = 2).use { f ->
            f.accept(osc8("https://example.com/bidi", "\u05D0\u05D1"))
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 2)
        }
    }

    @Test
    fun `real OSC8 evicted hyperlink id is not hoverable even when cells still carry the id`() {
        Osc8PipelineFixture(width = 4, height = 2, hostPolicy = HostPolicy(maxHyperlinkEntries = 1)).use { f ->
            f.accept(osc8("https://example.com/old", "A") + osc8("https://example.com/new", "B"))
            f.refresh()

            f.hover(column = 0, row = 0)
            f.assertNoHover()

            f.hover(column = 1, row = 0)
            f.assertHoverSpan(hyperlinkId = 2, startRow = 0, startColumn = 1, endRow = 0, endColumn = 2)
        }
    }

    @Test
    fun `real OSC8 link crossing viewport boundary is clamped to visible cache rows`() {
        Osc8PipelineFixture(width = 3, height = 2, maxHistory = 4).use { f ->
            f.accept(osc8("https://example.com/boundary", "abcdef"))
            f.refresh(scrollbackOffset = 0, viewportRows = 1)

            f.hover(column = 0, row = 0)
            f.assertHoverSpan(hyperlinkId = 1, startRow = 0, startColumn = 0, endRow = 0, endColumn = 3)
        }
    }

    @Test
    fun `ctrl click on resolved hyperlink invokes hyperlink handler`() {
        val cache =
            TerminalRenderCache(10, 10).apply {
                hyperlinkIds[rowOffset(1) + 1] = 5
            }
        val terminal = TerminalBuffers.create(width = 10, height = 10, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(10, 10),
                renderReader = FakeFrameReader(),
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
                hyperlinkResolver = { id -> if (id == 5) "https://example.com" else null },
            )
        val openedUri = AtomicReference<String?>()
        val handler =
            TerminalHyperlinkHandler { uri ->
                openedUri.set(uri)
                true
            }
        val host = FakeHyperlinkHost(cache, session, SwingHostServices(hyperlinkHandler = handler))
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val clickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK or InputEvent.CTRL_DOWN_MASK,
                15,
                25,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        val consumed = controller.handleMousePressed(clickEvent)

        assertTrue(consumed)
        assertEquals("https://example.com", openedUri.get())
    }

    @Test
    fun `ctrl click on discovered hyperlink invokes discovered action`() {
        val cache =
            TerminalRenderCache(10, 10).apply {
                hyperlinkIds[rowOffset(1) + 1] = -1
            }
        val opened = AtomicBoolean(false)
        val host =
            FakeHyperlinkHost(
                renderCache = cache,
                session = null,
                hostServices = SwingHostServices(),
                discoveredActions =
                    mapOf(
                        -1 to {
                            opened.set(true)
                            true
                        },
                    ),
            )
        val controller = TerminalHyperlinkController(host)

        val button = JButton()
        val clickEvent =
            MouseEvent(
                button,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK or InputEvent.CTRL_DOWN_MASK,
                15,
                25,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        val consumed = controller.handleMousePressed(clickEvent)

        assertTrue(consumed)
        assertTrue(opened.get())
    }

    private companion object {
        private const val CELL_WIDTH = 10
        private const val CELL_HEIGHT = 20

        private fun osc8(
            uri: String,
            text: String,
            id: String? = null,
        ): String = osc8Open(uri, id) + text + OSC8_CLOSE

        private fun osc8Open(
            uri: String,
            id: String?,
        ): String {
            val params = if (id == null) "" else "id=$id"
            return "\u001B]8;$params;$uri\u0007"
        }

        private const val OSC8_CLOSE = "\u001B]8;;\u0007"
    }
}

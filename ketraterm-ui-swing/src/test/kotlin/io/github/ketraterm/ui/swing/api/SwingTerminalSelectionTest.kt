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
import io.github.ketraterm.session.TerminalHyperlinkResolver
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.render.TestCell
import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.settings.TerminalHyperlinkHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Insets
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

class SwingTerminalSelectionTest {
    @Test
    fun `rtl pointer activates the hyperlink under its visual cell`() {
        val opened = AtomicReference<String?>()
        val frame =
            TestRenderFrame(
                arrayOf(
                    Array(3) { column ->
                        TestCell(codeWord = 0x05D0 + column, flags = TerminalRenderCellFlags.CODEPOINT, hyperlinkId = column + 1)
                    },
                ),
            )
        val session = testSession(frame, hyperlinkResolver = TerminalHyperlinkResolver { "https://example.com/$it" })
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0), shellIntegrationDecorationGutterWidth = 0) },
                hostServices =
                    SwingHostServices(
                        hyperlinkHandler =
                            TerminalHyperlinkHandler {
                                opened.set(it)
                                true
                            },
                    ),
            )
        try {
            SwingUtilities.invokeAndWait {
                component.setSize(100, 40)
                component.bind(session)
                session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
                component.mouseListeners.forEach { it.mousePressed(mousePressedWithCtrl(component, 1, 1)) }
            }
            assertEquals("https://example.com/3", opened.get())
        } finally {
            SwingUtilities.invokeAndWait { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `rtl mouse reports map both cell and pixel coordinates back to the logical grid`() {
        val input = RecordingInputEncoder()
        val frame = TestRenderFrame.text("\u05D0\u05D1\u05D2")
        val session = testSession(frame, inputEncoder = input)
        val settings = SwingSettings(padding = Insets(0, 0, 0, 0), shellIntegrationDecorationGutterWidth = 0)
        val component = SwingTerminal(settingsProvider = { settings })
        session.start(columns = 3, rows = 1)
        session.terminal.setMouseTrackingMode(io.github.ketraterm.protocol.MouseTrackingMode.NORMAL)
        try {
            SwingUtilities.invokeAndWait {
                component.setSize(100, 40)
                component.bind(session)
                session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
                component.mouseListeners.forEach { it.mousePressed(mousePressed(component, 1, 1, 1)) }
                val event = requireNotNull(input.lastMouseEvent.get())
                assertEquals(2, event.column)
                assertEquals(0, event.row)
                val metrics =
                    io.github.ketraterm.ui.swing.settings.SwingMetrics
                        .from(component.getFontMetrics(settings.font))
                assertEquals(2 * metrics.cellWidth + 1, event.pixelX)
                assertEquals(1, event.pixelY)
            }
        } finally {
            SwingUtilities.invokeAndWait { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `single click clears selection without selecting the clicked cell`() {
        val frame = TestRenderFrame.text("hello")
        val session = testSession(frame = frame)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1)) }
        }

        assertNull(component.currentSelection())
        session.close()
    }

    @Test
    fun `drag after single click creates selection`() {
        val frame = TestRenderFrame.text("hello")
        val session = testSession(frame = frame)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1)) }
            component.mouseMotionListeners.forEach { it.mouseDragged(mouseDragged(component, x = 299, y = 8)) }
        }

        assertEquals(CellSelection(0, 0, 5, 0), component.currentSelection())
        session.close()
    }

    @Test
    fun `unrelated settings changes preserve the current text selection`() {
        val frame = TestRenderFrame.text("hello")
        val session = testSession(frame = frame)
        var settings = SwingSettings(padding = Insets(0, 0, 0, 0))
        val component = SwingTerminal(settingsProvider = { settings })
        try {
            SwingUtilities.invokeAndWait {
                component.setSize(300, 80)
                component.bind(session)
                session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
                component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1)) }
                component.mouseMotionListeners.forEach { it.mouseDragged(mouseDragged(component, x = 299, y = 8)) }
                component.mouseListeners.forEach { it.mouseReleased(mouseReleased(component, x = 299, y = 8)) }
                val selected = component.currentSelection()
                assertNotNull(selected)

                settings = settings.copy(visualBellEnabled = false, scrollOnOutput = !settings.scrollOnOutput)
                component.reloadSettings()

                assertEquals(selected, component.currentSelection())
            }
        } finally {
            SwingUtilities.invokeAndWait { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `drag above viewport autoscrolls into scrollback`() {
        val renderReader = ScrollbackFrameReader()
        val session =
            testSession(
                frame = ScrollbackFrame(scrollbackOffset = 0, rows = 1),
                renderReader = renderReader,
            )
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(60, 20)
            component.bind(session)
        }
        SwingUtilities.invokeAndWait {
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1)) }
            component.mouseMotionListeners.forEach { it.mouseDragged(mouseDragged(component, x = 8, y = -10)) }
        }
        awaitRequestedOffset(renderReader, expectedOffset = 1)
        SwingUtilities.invokeAndWait {
            component.mouseListeners.forEach { it.mouseReleased(mouseReleased(component, x = 8, y = -10)) }
        }
        assertEquals(1, renderReader.lastRequestedOffset)
        session.close()
    }

    private fun awaitRequestedOffset(
        renderReader: ScrollbackFrameReader,
        expectedOffset: Int,
    ) {
        repeat(200) {
            SwingUtilities.invokeAndWait {}
            if (renderReader.lastRequestedOffset == expectedOffset) return
            Thread.sleep(5)
        }
        assertEquals(expectedOffset, renderReader.lastRequestedOffset)
    }

    @Test
    fun `alt drag creates rectangular block selection`() {
        val frame = TestRenderFrame.text("hello world")
        val session = testSession(frame = frame)
        val component = SwingTerminal(settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressedWithAlt(component, x = 8, y = 8)) }
            component.mouseMotionListeners.forEach { it.mouseDragged(mouseDraggedWithAlt(component, x = 80, y = 8)) }
        }

        val selection = component.currentSelection()
        assertNotNull(selection)
        assertTrue(selection!!.isBlock, "selection should be block selection")
        session.close()
    }

    @Test
    fun `selection snaps to enclose full wide characters`() {
        val cells =
            arrayOf(
                arrayOf(
                    // 'A' at col 0
                    TestCell(codeWord = 0x41, flags = TerminalRenderCellFlags.CODEPOINT),
                    // '中' (wide leading) at col 1
                    TestCell(
                        codeWord = 0x4E2D,
                        flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                    ),
                    // (wide trailing) at col 2
                    TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                    TestCell(codeWord = 0x42, flags = TerminalRenderCellFlags.CODEPOINT), // 'B' at col 3
                ),
            )
        val frame = TestRenderFrame(cells)
        val cache =
            io.github.ketraterm.ui.swing.render
                .renderCache(frame)

        // Case 1: Selecting starting on wide trailing cell (col 2)
        val selection1 = CellSelection(anchorColumn = 2, anchorRow = 0, caretColumn = 3, caretRow = 0)
        val range1 = selection1.packedColumnRange(row = 0, columns = 4, cache = cache)
        assertEquals(1, CellSelection.rangeStart(range1))
        assertEquals(3, CellSelection.rangeEnd(range1))

        // Case 2: Selecting ending on wide leading cell (col 1)
        val selection2 = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 2, caretRow = 0)
        val range2 = selection2.packedColumnRange(row = 0, columns = 4, cache = cache)
        assertEquals(0, CellSelection.rangeStart(range2))
        assertEquals(3, CellSelection.rangeEnd(range2))
    }

    @Test
    fun `clipboard copy extracts fully snapped wide characters`() {
        val cells =
            arrayOf(
                arrayOf(
                    TestCell(codeWord = 0x41, flags = TerminalRenderCellFlags.CODEPOINT), // 'A'
                    // '中'
                    TestCell(
                        codeWord = 0x4E2D,
                        flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                        cluster = "中",
                    ),
                    TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                    TestCell(codeWord = 0x42, flags = TerminalRenderCellFlags.CODEPOINT), // 'B'
                ),
            )
        val frame = TestRenderFrame(cells)
        val cache =
            io.github.ketraterm.ui.swing.render
                .renderCache(frame)

        // Selecting only trailing half should extract the whole Chinese char
        val selection = CellSelection(anchorColumn = 2, anchorRow = 0, caretColumn = 3, caretRow = 0)
        val text = TerminalSelectionTextExtractor().selectedText(cache, selection)
        assertEquals("中", text)
    }

    @Test
    fun `copySelectionToClipboard copies selected text to clipboard`() {
        val clipboard = RecordingClipboard()
        val frame = TestRenderFrame.text("hello world")
        val session = testSession(frame = frame)
        val component =
            SwingTerminal(
                settingsProvider = {
                    SwingSettings(padding = Insets(0, 0, 0, 0))
                },
                hostServices =
                    SwingHostServices(
                        clipboardHandler = clipboard,
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 2)) }
            assertTrue(component.copySelectionToClipboard())
        }

        assertEquals("hello", clipboard.copied.get())
        session.close()
    }

    @Test
    fun `copySelectionToClipboard ignores selected empty cells after row content`() {
        val clipboard = RecordingClipboard()
        val frame =
            TestRenderFrame(
                arrayOf(
                    arrayOf(
                        TestCell(codeWord = 'h'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                        TestCell(codeWord = 'i'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                        TestCell(),
                        TestCell(),
                        TestCell(),
                    ),
                ),
            )
        val session = testSession(frame = frame)
        val component =
            SwingTerminal(
                settingsProvider = {
                    SwingSettings(padding = Insets(0, 0, 0, 0))
                },
                hostServices =
                    SwingHostServices(
                        clipboardHandler = clipboard,
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1)) }
            component.mouseMotionListeners.forEach { it.mouseDragged(mouseDragged(component, x = 299, y = 8)) }
            assertTrue(component.copySelectionToClipboard())
        }

        assertEquals("hi", clipboard.copied.get())
        session.close()
    }

    @Test
    fun `copySelectionToClipboard replaces stale clipboard content for an empty-cell selection`() {
        val clipboard = RecordingClipboard()
        clipboard.copyText("stale")
        val frame = TestRenderFrame(arrayOf(Array(5) { TestCell() }))
        val session = testSession(frame = frame)
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) },
                hostServices = SwingHostServices(clipboardHandler = clipboard),
            )

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1)) }
            component.mouseMotionListeners.forEach { it.mouseDragged(mouseDragged(component, x = 299, y = 8)) }
            assertTrue(component.copySelectionToClipboard())
        }

        assertEquals("", clipboard.copied.get())
        session.close()
    }

    @Test
    fun `pasteClipboardText reads clipboard and sends paste event through session`() {
        val clipboard = RecordingClipboard(readValue = "pasted text")
        val input = RecordingInputEncoder()
        val frame = TestRenderFrame.text("ready")
        val session = testSession(frame = frame, inputEncoder = input)
        val component =
            SwingTerminal(
                settingsProvider = {
                    SwingSettings(smartSuggestionsEnabled = true, padding = Insets(0, 0, 0, 0))
                },
                hostServices =
                    SwingHostServices(
                        clipboardHandler = clipboard,
                    ),
            )

        session.start(columns = 5, rows = 1)
        val visibleDuringInvalidation = ArrayList<Boolean>()
        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            component.addShellSuggestionInvalidationListener {
                visibleDuringInvalidation += component.currentShellSuggestionState().visible
            }
            component.showShellSuggestions(
                SwingShellSuggestionRequest("git s", 5, 5, 0),
                listOf(SwingShellSuggestion("status", 4, 5, "spec", "SUBCOMMAND")),
            )
            assertTrue(component.pasteClipboardText())
        }

        assertEquals("pasted text", input.pasteText.get())
        assertEquals(listOf(false), visibleDuringInvalidation)
        session.close()
    }

    @Test
    fun `middle click is not bound to paste by reusable SwingTerminal`() {
        val clipboard = RecordingClipboard(readValue = "middle click pasted text")
        val input = RecordingInputEncoder()
        val frame = TestRenderFrame.text("ready")
        val session = testSession(frame = frame, inputEncoder = input)
        val component =
            SwingTerminal(
                settingsProvider = {
                    SwingSettings(padding = Insets(0, 0, 0, 0))
                },
                hostServices =
                    SwingHostServices(
                        clipboardHandler = clipboard,
                    ),
            )

        session.start(columns = 5, rows = 1)
        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            component.mouseListeners.forEach {
                it.mousePressed(mousePressedMiddle(component, x = 8, y = 8, clickCount = 1))
            }
        }

        assertNull(input.pasteText.get())
        session.close()
    }

    @Test
    fun `middle click when mouse tracking is active is encoded and does not paste`() {
        val clipboard = RecordingClipboard(readValue = "middle click pasted text")
        val input = RecordingInputEncoder()
        val frame = TestRenderFrame.text("ready")
        val session = testSession(frame = frame, inputEncoder = input)
        val component =
            SwingTerminal(
                settingsProvider = {
                    SwingSettings(padding = Insets(0, 0, 0, 0))
                },
                hostServices =
                    SwingHostServices(
                        clipboardHandler = clipboard,
                    ),
            )

        session.start(columns = 5, rows = 1)
        session.terminal.setMouseTrackingMode(io.github.ketraterm.protocol.MouseTrackingMode.NORMAL)

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            component.mouseListeners.forEach {
                it.mousePressed(mousePressedMiddle(component, x = 8, y = 8, clickCount = 1))
            }
        }

        assertNull(input.pasteText.get())
        val mouseEvent = input.lastMouseEvent.get()
        assertNotNull(mouseEvent)
        assertEquals(io.github.ketraterm.input.event.TerminalMouseButton.MIDDLE, mouseEvent!!.button)
        assertEquals(io.github.ketraterm.input.event.TerminalMouseEventType.PRESS, mouseEvent.type)

        session.close()
    }

    @Test
    fun `ctrl click on hyperlink opens resolved uri without changing selection`() {
        val opened = AtomicReference<String?>()
        val frame =
            TestRenderFrame(
                arrayOf(
                    arrayOf(
                        TestCell(
                            codeWord = 'h'.code,
                            flags = TerminalRenderCellFlags.CODEPOINT,
                            hyperlinkId = 7,
                        ),
                    ),
                ),
            )
        val session =
            testSession(
                frame = frame,
                hyperlinkResolver =
                    TerminalHyperlinkResolver { id ->
                        if (id == 7) "https://example.com" else null
                    },
            )
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        hyperlinkHandler =
                            TerminalHyperlinkHandler { uri ->
                                opened.set(uri)
                                true
                            },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.setSize(80, 40)
            component.bind(session)
            session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach {
                it.mousePressed(mousePressedWithCtrl(component, x = 8, y = 8))
            }
        }

        assertEquals("https://example.com", opened.get())
        assertNull(component.currentSelection())
        session.close()
    }

    @Test
    fun `plain click on hyperlink keeps normal selection behavior`() {
        val opened = AtomicInteger(0)
        val frame =
            TestRenderFrame(
                arrayOf(
                    arrayOf(
                        TestCell(
                            codeWord = 'h'.code,
                            flags = TerminalRenderCellFlags.CODEPOINT,
                            hyperlinkId = 7,
                        ),
                    ),
                ),
            )
        val session =
            testSession(
                frame = frame,
                hyperlinkResolver = TerminalHyperlinkResolver { "https://example.com" },
            )
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(padding = Insets(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        hyperlinkHandler =
                            TerminalHyperlinkHandler {
                                opened.incrementAndGet()
                                true
                            },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.setSize(80, 40)
            component.bind(session)
            session.renderPublisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach {
                it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1))
            }
            component.mouseMotionListeners.forEach {
                it.mouseDragged(mouseDragged(component, x = 20, y = 8))
            }
        }

        assertEquals(0, opened.get())
        assertEquals(CellSelection(0, 0, 1, 0), component.currentSelection())
        session.close()
    }

    private fun testSession(
        frame: TerminalRenderFrame,
        inputEncoder: TerminalInputEncoder = NoOpInputEncoder,
        renderReader: TerminalRenderFrameReader = StaticFrameReader(frame),
        hyperlinkResolver: TerminalHyperlinkResolver = TerminalHyperlinkResolver.NONE,
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = frame.columns, height = frame.rows, maxHistory = 5)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(frame.columns, frame.rows),
                renderReader = renderReader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = inputEncoder,
                hyperlinkResolver = hyperlinkResolver,
            )
        session.renderPublisher.updateAndPublish(renderReader)
        return session
    }

    private fun mousePressed(
        component: SwingTerminal,
        x: Int,
        y: Int,
        clickCount: Int,
    ): MouseEvent =
        MouseEvent(
            component,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK,
            x,
            y,
            clickCount,
            false,
            MouseEvent.BUTTON1,
        )

    private fun mousePressedMiddle(
        component: SwingTerminal,
        x: Int,
        y: Int,
        clickCount: Int,
    ): MouseEvent =
        MouseEvent(
            component,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            InputEvent.BUTTON2_DOWN_MASK,
            x,
            y,
            clickCount,
            false,
            MouseEvent.BUTTON2,
        )

    private fun mouseDragged(
        component: SwingTerminal,
        x: Int,
        y: Int,
    ): MouseEvent =
        MouseEvent(
            component,
            MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK,
            x,
            y,
            0,
            false,
            MouseEvent.BUTTON1,
        )

    private fun mouseReleased(
        component: SwingTerminal,
        x: Int,
        y: Int,
    ): MouseEvent =
        MouseEvent(
            component,
            MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK,
            x,
            y,
            1,
            false,
            MouseEvent.BUTTON1,
        )

    private fun mousePressedWithAlt(
        component: SwingTerminal,
        x: Int,
        y: Int,
    ): MouseEvent =
        MouseEvent(
            component,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK or InputEvent.ALT_DOWN_MASK,
            x,
            y,
            1,
            false,
            MouseEvent.BUTTON1,
        )

    private fun mousePressedWithCtrl(
        component: SwingTerminal,
        x: Int,
        y: Int,
    ): MouseEvent =
        MouseEvent(
            component,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK or InputEvent.CTRL_DOWN_MASK,
            x,
            y,
            1,
            false,
            MouseEvent.BUTTON1,
        )

    private fun mouseDraggedWithAlt(
        component: SwingTerminal,
        x: Int,
        y: Int,
    ): MouseEvent =
        MouseEvent(
            component,
            MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK or InputEvent.ALT_DOWN_MASK,
            x,
            y,
            0,
            false,
            MouseEvent.BUTTON1,
        )

    private class StaticFrameReader(
        private val frame: TerminalRenderFrame,
    ) : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(frame)
        }
    }

    private class ScrollbackFrameReader : TerminalRenderFrameReader {
        @Volatile
        var lastRequestedOffset: Int = -1
            private set

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            consumer.accept(ScrollbackFrame(scrollbackOffset = scrollbackOffset.coerceIn(0, 5), rows = 1))
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            lastRequestedOffset = scrollbackOffset
            consumer.accept(
                ScrollbackFrame(
                    scrollbackOffset = scrollbackOffset.coerceIn(0, 5),
                    rows = viewportRows.coerceAtLeast(1),
                ),
            )
        }
    }

    private class ScrollbackFrame(
        override val scrollbackOffset: Int,
        override val rows: Int,
    ) : TerminalRenderFrame {
        override val columns: Int = 3
        override val historySize: Int = 5
        override val frameGeneration: Long = scrollbackOffset.toLong() + 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = scrollbackOffset == 0,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = frameGeneration,
            )

        override fun lineGeneration(row: Int): Long = frameGeneration

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
                codeWords[codeOffset + column] = 'A'.code + column
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private class RecordingClipboard(
        private val readValue: String? = null,
    ) : TerminalClipboardHandler {
        val copied = AtomicReference<String?>()

        override fun copyText(text: String) {
            copied.set(text)
        }

        override fun readText(): String? = readValue
    }

    private class RecordingInputEncoder : TerminalInputEncoder {
        val pasteText = AtomicReference<String?>()
        val lastMouseEvent = AtomicReference<TerminalMouseEvent?>()
        var keyCount: Int = 0
            private set

        override fun encodeKey(event: TerminalKeyEvent) {
            keyCount++
        }

        override fun encodePaste(event: TerminalPasteEvent) {
            pasteText.set(event.text)
        }

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) {
            lastMouseEvent.set(event)
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

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
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.RepaintManager
import javax.swing.SwingUtilities

class SwingTerminalCursorBlinkTest {
    @Test
    fun `focus reset repaints blinking text even without a terminal cursor`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 1)
        val reader = TestRenderFrame.text("ABC", attrs = LongArray(3) { TerminalRenderAttrs.pack(blink = true) })
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = reader,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoder = NoOpInputEncoder,
            )
        session.renderPublisher.updateAndPublish(reader)
        try {
            SwingUtilities.invokeAndWait {
                val component =
                    SwingTerminal(settingsProvider = {
                        SwingSettings(cursorBlinkMillis = 0, padding = SwingPadding(), shellIntegrationDecorationGutterWidth = 0)
                    })
                val previousManager = RepaintManager.currentManager(component)
                val rowRepaints = ArrayList<Int>()
                val manager =
                    object : RepaintManager() {
                        override fun addDirtyRegion(
                            target: JComponent,
                            x: Int,
                            y: Int,
                            width: Int,
                            height: Int,
                        ) {
                            if (target === component && x == 0 && y == 0 && width == component.width && height > 0) {
                                rowRepaints += height
                            }
                        }
                    }
                try {
                    component.size = component.preferredGridSize(3, 1)
                    component.bind(session)
                    component.cursorBlinkVisible = false
                    RepaintManager.setCurrentManager(manager)

                    val event = FocusEvent(component, FocusEvent.FOCUS_GAINED)
                    for (listener in component.focusListeners) listener.focusGained(event)

                    assertTrue(component.cursorBlinkVisible)
                    assertTrue(rowRepaints.isNotEmpty(), "focus reset must invalidate the hidden text row")
                    assertFalse(component.cursorTimer.isRunning)
                } finally {
                    RepaintManager.setCurrentManager(previousManager)
                    component.dispose()
                }
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `cursor presentation follows terminal focus`() {
        val component = SwingTerminal()

        SwingUtilities.invokeAndWait {
            component.cursorBlinkVisible = true
        }
        assertFalse(component.cursorPresentationEnabled)

        SwingUtilities.invokeAndWait {
            val focusEvent = FocusEvent(component, FocusEvent.FOCUS_GAINED)
            for (listener in component.focusListeners) {
                listener.focusGained(focusEvent)
            }
        }
        assertTrue(component.cursorPresentationEnabled)

        SwingUtilities.invokeAndWait {
            val focusEvent = FocusEvent(component, FocusEvent.FOCUS_LOST)
            for (listener in component.focusListeners) {
                listener.focusLost(focusEvent)
            }
        }
        assertFalse(component.cursorPresentationEnabled)
    }

    @Test
    fun `zero cursor blink setting keeps timer stopped and cursor visible`() {
        val component =
            SwingTerminal(
                settingsProvider = {
                    SwingSettings(cursorBlinkMillis = 0)
                },
            )
        val frame = JFrame()

        try {
            SwingUtilities.invokeAndWait {
                component.cursorBlinkVisible = false
                frame.add(component)
                frame.pack()
            }

            assertFalse(component.cursorTimer.isRunning)
            assertTrue(component.cursorBlinkVisible)
        } finally {
            SwingUtilities.invokeAndWait {
                frame.dispose()
            }
        }
    }

    @Test
    fun `key events and frame updates reset cursor blinking timer`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val renderReader = SimpleFrameReader()
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
        val component = SwingTerminal()

        val frame = JFrame()
        try {
            SwingUtilities.invokeAndWait {
                frame.add(component)
                frame.pack()
                component.bind(session)
            }

            // Verify cursor timer is started when component is added
            assertTrue(component.cursorTimer.isRunning)

            // Prevent timer from firing during the test by setting a very large delay
            SwingUtilities.invokeAndWait {
                component.cursorTimer.delay = 100_000
                component.cursorTimer.restart()
            }

            // 1. Manually set cursorBlinkVisible to false and verify, then dispatch key event and verify, then set back to false and verify
            SwingUtilities.invokeAndWait {
                component.cursorBlinkVisible = false
                assertFalse(component.cursorBlinkVisible)

                val keyEvent =
                    KeyEvent(
                        component,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        KeyEvent.VK_A,
                        'A',
                    )
                for (listener in component.keyListeners) {
                    listener.keyPressed(keyEvent)
                }
                assertTrue(component.cursorBlinkVisible)

                component.cursorBlinkVisible = false
                assertFalse(component.cursorBlinkVisible)
            }

            val previousGeneration = session.renderGeneration.value
            session.requestRender(scrollbackOffset = 0)
            awaitRenderPublication(session, previousGeneration)
            SwingUtilities.invokeAndWait {
                // Drain the StateFlow collection on the EDT.
            }

            // Verify cursorBlinkVisible resets back to true on frame updates
            assertTrue(component.cursorBlinkVisible)
        } finally {
            SwingUtilities.invokeAndWait {
                frame.dispose()
            }
            session.close()
        }
    }

    private fun awaitRenderPublication(
        session: TerminalSession,
        previousGeneration: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (session.renderGeneration.value <= previousGeneration && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(session.renderGeneration.value > previousGeneration, "render was not published")
    }

    private class SimpleFrameReader : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(SimpleFrame())
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            consumer.accept(SimpleFrame())
        }
    }

    private class SimpleFrame : TerminalRenderFrame {
        override val columns: Int = 3
        override val rows: Int = 1
        override val historySize: Int = 0
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val scrollbackOffset: Int = 0
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = true,
                blinking = true,
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

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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.*
import io.github.ketraterm.input.policy.EnterNewLineModePolicy
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.testkit.MockConnector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class TerminalSessionHeadlessTest {
    @Test
    fun `testkit mock connector is used by headless session tests`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        assertEquals(1, connector.startCount)
        assertEquals(listOf(10 to 3), connector.resizeCalls)
        session.close()
    }

    @Test
    fun `host printable bytes mutate headless core state`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("hello".ascii())

        assertEquals("hello", session.terminal.getLineAsString(0))
        assertEquals("", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `chunked host control sequence is parsed across connector callbacks`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("\u001B[".ascii())
        connector.feedFromHost("5".ascii())
        connector.feedFromHost("n".ascii())

        assertEquals("\u001B[0n", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `headless session applies host modes before encoding paste and focus input`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("\u001B[?2004;1004h".ascii())
        session.encodePaste(TerminalPasteEvent("text"))
        session.encodeFocus(TerminalFocusEvent(focused = true))
        session.encodeFocus(TerminalFocusEvent(focused = false))

        assertEquals("\u001B[200~text\u001B[201~\u001B[I\u001B[O", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `headless session applies host mouse modes before encoding mouse input`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("\u001B[?1000;1006h".ascii())
        session.encodeMouse(
            TerminalMouseEvent(
                column = 4,
                row = 2,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.PRESS,
            ),
        )

        assertEquals("\u001B[<0;5;3M", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `paste policy update preserves existing non paste input policy`() {
        val connector = MockConnector()
        val session =
            createStartedSession(
                connector,
                inputPolicy =
                    TerminalInputPolicy(
                        enterNewLineModePolicy = EnterNewLineModePolicy.SEND_CR,
                    ),
            )

        connector.feedFromHost("\u001B[20h".ascii())
        session.setPasteSanitizationPolicy(PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF)
        session.encodePaste(TerminalPasteEvent("A\u0001B"))
        session.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER))

        assertEquals("AB\r", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `local close stops host bytes and UI input from mutating the headless session`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.close()
        session.onBytes("A\u001B[5n".ascii(), 0, 5)
        session.encodeKey(TerminalKeyEvent.codepoint('b'.code))

        assertEquals("", session.terminal.getLineAsString(0))
        assertEquals("", connector.writtenBytes.asciiText())
        assertEquals(1, connector.closeCount)
    }

    @Test
    fun `remote close stops host bytes and UI input without local connector close`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.simulateClosed(0)
        session.onBytes("A\u001B[5n".ascii(), 0, 5)
        session.encodeKey(TerminalKeyEvent.codepoint('b'.code))

        assertEquals("", session.terminal.getLineAsString(0))
        assertEquals("", connector.writtenBytes.asciiText())
        assertEquals(0, connector.closeCount)
    }

    @Test
    fun `invalid resize is rejected before connector resize`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        assertThrows(IllegalArgumentException::class.java) {
            session.resize(columns = 0, rows = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            session.resizeViewport(columns = 10, rows = 0)
        }

        assertEquals(10, session.terminal.width)
        assertEquals(3, session.terminal.height)
        assertEquals(listOf(10 to 3), connector.resizeCalls)
        session.close()
    }

    private fun createStartedSession(
        connector: MockConnector,
        columns: Int = 10,
        rows: Int = 3,
        inputPolicy: TerminalInputPolicy = TerminalInputPolicy(),
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = columns, height = rows)
        val session = TerminalSession.create(terminal, connector, inputPolicy = inputPolicy)
        session.start(columns, rows)
        return session
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun ByteArray.asciiText(): String = toString(StandardCharsets.US_ASCII)
}

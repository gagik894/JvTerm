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

import io.github.ketraterm.session.TerminalSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class SwingTerminalInputControllerTest {
    private val source = Canvas()

    @Nested
    inner class FocusRouting {
        @Test
        fun `focus events update focus and cursor repaint state`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)

            controller.focusListener.focusGained(FocusEvent(source, FocusEvent.FOCUS_GAINED))
            controller.focusListener.focusLost(FocusEvent(source, FocusEvent.FOCUS_LOST))

            assertFalse(host.focused)
            assertEquals(listOf(false), host.cursorBlinkResets)
            assertEquals(2, host.cursorRepaints)
            assertEquals(1, host.hideShellSuggestionsCount)
        }
    }

    @Nested
    inner class HostShortcuts {
        @Test
        fun `host handled key press is consumed before terminal input handling`() {
            val host = RecordingInputHost(hostKeyHandled = true)
            val controller = SwingTerminalInputController(host)
            val event =
                keyPressed(
                    keyCode = KeyEvent.VK_F,
                    modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
                )

            controller.keyListener.keyPressed(event)

            assertEquals(1, host.hostKeyPressCount)
            assertEquals(1, host.shellSuggestionKeyPressCount)
            assertTrue(host.hyperlinkHoverUpdates.isEmpty())
            assertTrue(host.cursorBlinkResets.isEmpty())
            assertTrue(event.isConsumed)
        }

        @Test
        fun `host-owned repeat cannot become a suggestion action before release`() {
            val host = RecordingInputHost(hostKeyHandled = true)
            val controller = SwingTerminalInputController(host)
            val press = keyPressed(KeyEvent.VK_DOWN, 0)
            val repeat = keyPressed(KeyEvent.VK_DOWN, 0)
            val release = keyReleased(KeyEvent.VK_DOWN, 0)

            controller.keyListener.keyPressed(press)
            host.shellSuggestionKeyHandled = true
            controller.keyListener.keyPressed(repeat)
            controller.keyListener.keyReleased(release)

            assertEquals(1, host.shellSuggestionKeyPressCount)
            assertEquals(1, host.hostKeyPressCount)
            assertTrue(press.isConsumed)
            assertTrue(repeat.isConsumed)
            assertTrue(release.isConsumed)
        }
    }

    @Nested
    inner class ShellSuggestionShortcuts {
        @Test
        fun `visible suggestion popup handles navigation before terminal input`() {
            val host = RecordingInputHost(shellSuggestionKeyHandled = true)
            val controller = SwingTerminalInputController(host)
            val event = keyPressed(keyCode = KeyEvent.VK_DOWN, modifiers = 0)

            controller.keyListener.keyPressed(event)

            assertEquals(1, host.shellSuggestionKeyPressCount)
            assertTrue(event.isConsumed)
            assertEquals(0, host.invalidationCount)
        }

        @Test
        fun `suggestion action takes precedence over a matching host shortcut`() {
            val host = RecordingInputHost(hostKeyHandled = true, shellSuggestionKeyHandled = true)
            val controller = SwingTerminalInputController(host)

            controller.keyListener.keyPressed(keyPressed(KeyEvent.VK_ENTER, 0))

            assertEquals(1, host.shellSuggestionKeyPressCount)
            assertEquals(0, host.hostKeyPressCount)
            assertEquals(0, host.invalidationCount)
        }

        @Test
        fun `claimed navigation key redispatches repeated presses to suggestions`() {
            val host = RecordingInputHost(shellSuggestionKeyHandled = true)
            val controller = SwingTerminalInputController(host)
            val press = keyPressed(KeyEvent.VK_DOWN, 0)
            val repeat = keyPressed(KeyEvent.VK_DOWN, 0)
            val release = keyReleased(KeyEvent.VK_DOWN, 0)

            controller.keyListener.keyPressed(press)
            controller.keyListener.keyPressed(repeat)
            controller.keyListener.keyReleased(release)

            assertEquals(2, host.shellSuggestionKeyPressCount)
            assertTrue(press.isConsumed)
            assertTrue(repeat.isConsumed)
            assertTrue(release.isConsumed)
            assertEquals(0, host.hostKeyPressCount)
            assertEquals(0, host.invalidationCount)
        }

        @Test
        fun `claimed acceptance key owns repeat typed and release events after popup closes`() {
            val host = RecordingInputHost(shellSuggestionKeyHandled = true)
            val controller = SwingTerminalInputController(host)
            val press = keyPressed(KeyEvent.VK_ENTER, 0)
            val firstRepeat = keyPressed(KeyEvent.VK_ENTER, 0)
            val firstTyped = keyTyped('\n')
            val secondRepeat = keyPressed(KeyEvent.VK_ENTER, 0)
            val secondTyped = keyTyped('\n')
            val release = keyReleased(KeyEvent.VK_ENTER, 0)

            controller.keyListener.keyPressed(press)
            host.shellSuggestionKeyHandled = false
            controller.keyListener.keyPressed(firstRepeat)
            controller.keyListener.keyTyped(firstTyped)
            controller.keyListener.keyPressed(secondRepeat)
            controller.keyListener.keyTyped(secondTyped)
            controller.keyListener.keyReleased(release)

            assertEquals(3, host.shellSuggestionKeyPressCount)
            assertTrue(press.isConsumed)
            assertTrue(firstRepeat.isConsumed)
            assertTrue(firstTyped.isConsumed)
            assertTrue(secondRepeat.isConsumed)
            assertTrue(secondTyped.isConsumed)
            assertTrue(release.isConsumed)
            assertEquals(0, host.hostKeyPressCount)
            assertEquals(0, host.invalidationCount)
        }
    }

    @Nested
    inner class SuggestionInvalidation {
        @Test
        fun `printable typing invalidates suggestions`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)

            controller.keyListener.keyTyped(keyTyped('x'))

            assertEquals(1, host.invalidationCount)
        }

        @Test
        fun `edit and cursor keys invalidate suggestions`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)

            controller.keyListener.keyPressed(keyPressed(KeyEvent.VK_BACK_SPACE, 0))
            controller.keyListener.keyPressed(keyPressed(KeyEvent.VK_DELETE, 0))
            controller.keyListener.keyPressed(keyPressed(KeyEvent.VK_LEFT, 0))
            controller.keyListener.keyPressed(keyPressed(KeyEvent.VK_RIGHT, 0))

            assertEquals(4, host.invalidationCount)
        }

        @Test
        fun `key release never invalidates suggestions`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)

            controller.keyListener.keyReleased(keyReleased(KeyEvent.VK_LEFT, 0))

            assertEquals(0, host.invalidationCount)
        }
    }

    @Nested
    inner class HyperlinkHover {
        @Test
        fun `key press and release publish control activation state`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)
            val releaseEvent = keyReleased(keyCode = KeyEvent.VK_CONTROL, modifiers = 0)

            controller.keyListener.keyPressed(keyPressed(keyCode = KeyEvent.VK_CONTROL, modifiers = InputEvent.CTRL_DOWN_MASK))
            controller.keyListener.keyReleased(releaseEvent)

            assertEquals(listOf(true, false), host.hyperlinkHoverUpdates)
            assertTrue(releaseEvent.isConsumed)
        }
    }

    private fun keyPressed(
        keyCode: Int,
        modifiers: Int,
    ): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private fun keyReleased(
        keyCode: Int,
        modifiers: Int,
    ): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_RELEASED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private fun keyTyped(character: Char): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_UNDEFINED,
            character,
        )

    private class RecordingInputHost(
        private val hostKeyHandled: Boolean = false,
        shellSuggestionKeyHandled: Boolean = false,
    ) : SwingTerminalInputHost {
        override val session: TerminalSession? = null
        val hyperlinkHoverUpdates = ArrayList<Boolean>()
        val cursorBlinkResets = ArrayList<Boolean>()
        var focused = false
        var cursorRepaints = 0
        var hostKeyPressCount = 0
        var shellSuggestionKeyPressCount = 0
        var shellSuggestionKeyHandled = shellSuggestionKeyHandled
        var invalidationCount = 0

        override fun updateHyperlinkActivationHover(active: Boolean) {
            hyperlinkHoverUpdates += active
        }

        override fun resetCursorBlink(forceRepaint: Boolean) {
            cursorBlinkResets += forceRepaint
        }

        override fun setTerminalFocused(focused: Boolean) {
            this.focused = focused
        }

        override fun repaintCursorState() {
            cursorRepaints++
        }

        override fun handleHostKeyPressed(event: KeyEvent): Boolean {
            hostKeyPressCount++
            return hostKeyHandled
        }

        override fun handleShellSuggestionKeyPressed(event: KeyEvent): Boolean {
            shellSuggestionKeyPressCount++
            if (shellSuggestionKeyHandled) event.consume()
            return shellSuggestionKeyHandled
        }

        override fun invalidateShellSuggestions() {
            invalidationCount++
        }

        var hideShellSuggestionsCount = 0

        override fun hideShellSuggestions() {
            hideShellSuggestionsCount++
        }
    }
}

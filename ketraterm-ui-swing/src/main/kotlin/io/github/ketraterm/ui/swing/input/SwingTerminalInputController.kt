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

import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

/**
 * Swing keyboard/focus routing for terminal input.
 */
internal class SwingTerminalInputController(
    private val host: SwingTerminalInputHost,
) {
    private val keyMapper = SwingKeyMapper()
    private val claimedKeyLifecycle = ClaimedSwingKeyLifecycle()

    val keyListener =
        object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                when (claimedKeyLifecycle.repeatedPressOwner(event)) {
                    ClaimedSwingKeyOwner.SUGGESTION -> {
                        host.handleShellSuggestionKeyPressed(event)
                        event.consume()
                        return
                    }
                    ClaimedSwingKeyOwner.HOST -> {
                        event.consume()
                        return
                    }
                    null -> Unit
                }

                if (host.handleShellSuggestionKeyPressed(event)) {
                    claimedKeyLifecycle.claim(event, ClaimedSwingKeyOwner.SUGGESTION)
                    event.consume()
                    return
                }

                if (host.handleHostKeyPressed(event)) {
                    claimedKeyLifecycle.claim(event, ClaimedSwingKeyOwner.HOST)
                    event.consume()
                    return
                }

                host.updateHyperlinkActivationHover(event.isControlDown)
                host.resetCursorBlink(forceRepaint = true)

                val keyEvent = keyMapper.keyPressed(event) ?: return
                host.invalidateShellSuggestions()
                host.session?.encodeKey(keyEvent)
                event.consume()
            }

            override fun keyReleased(event: KeyEvent) {
                host.updateHyperlinkActivationHover(event.isControlDown)
                if (claimedKeyLifecycle.release(event)) {
                    event.consume()
                    return
                }
                val keyEvent = keyMapper.keyReleased(event) ?: return
                host.session?.encodeKey(keyEvent)
                event.consume()
            }

            override fun keyTyped(event: KeyEvent) {
                host.resetCursorBlink(forceRepaint = true)
                if (claimedKeyLifecycle.ownsTypedEvent()) {
                    event.consume()
                    return
                }
                val keyEvent = keyMapper.keyTyped(event) ?: return
                host.invalidateShellSuggestions()
                host.session?.encodeKey(keyEvent)
                event.consume()
            }
        }

    val focusListener =
        object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                host.setTerminalFocused(true)
                host.resetCursorBlink(forceRepaint = false)
                host.repaintCursorState()
            }

            override fun focusLost(event: FocusEvent) {
                claimedKeyLifecycle.clear()
                host.setTerminalFocused(false)
                host.repaintCursorState()
                host.hideShellSuggestions()
            }
        }
}

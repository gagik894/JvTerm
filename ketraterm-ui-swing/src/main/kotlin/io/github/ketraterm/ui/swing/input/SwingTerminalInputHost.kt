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

/**
 * Narrow adapter for Swing keyboard/focus terminal input routing.
 */
internal interface SwingTerminalInputHost {
    val session: TerminalSession?

    fun updateHyperlinkActivationHover(active: Boolean)

    /** Restarts the shared blink phase and repaints cursor/text regions whose visibility changed. */
    fun resetCursorBlink()

    fun setTerminalFocused(focused: Boolean)

    fun repaintCursorState()

    fun handleHostKeyPressed(event: java.awt.event.KeyEvent): Boolean

    fun handleShellSuggestionKeyPressed(event: java.awt.event.KeyEvent): Boolean

    /** Cancels and hides suggestions before shell-bound input changes command state. */
    fun invalidateShellSuggestions()

    /**
     * Hides the shell suggestion popup.
     */
    fun hideShellSuggestions()
}

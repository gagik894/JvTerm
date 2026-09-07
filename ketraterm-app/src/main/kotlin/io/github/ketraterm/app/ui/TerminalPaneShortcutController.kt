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
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import io.github.ketraterm.ui.swing.host.SwingTerminalHostShortcutMap
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Standalone-host shortcut bindings for one terminal pane.
 *
 * The reusable `SwingTerminal` exposes terminal operations but does not decide
 * which keyboard or mouse gestures invoke application commands. This controller
 * keeps those bindings in the standalone host while leaving terminal keystrokes
 * to the reusable input encoder.
 */
internal class TerminalPaneShortcutController(
    private val pane: TerminalPane,
    private val settings: KetraTermSettings,
    private val actionRegistry: TerminalPaneActionRegistry = TerminalPaneActionRegistry,
    private val shortcutMap: SwingTerminalHostShortcutMap = SwingTerminalHostShortcutMap.platformDefault(),
) {
    private val mouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.isConsumed) return
                if (!SwingUtilities.isMiddleMouseButton(event)) return
                if (!settings.pasteOnMiddleClick) return
                if (!pane.terminal.pasteClipboardText()) return
                event.consume()
            }
        }

    init {
        installKeyBindings()
        pane.terminal.addMouseListener(mouseListener)
    }

    /**
     * Removes listeners installed by this controller.
     */
    fun dispose() {
        pane.terminal.removeMouseListener(mouseListener)
    }

    private fun installKeyBindings() {
        val inputMap = pane.terminal.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = pane.terminal.actionMap
        shortcutMap.forEachShortcut { actionId, shortcut ->
            val bindingKey = "ketraterm.${actionId.name}"
            inputMap.put(shortcut.keyStroke(), bindingKey)
            actionMap.put(bindingKey, TerminalPaneSwingAction(actionId))
        }
    }

    private inner class TerminalPaneSwingAction(
        private val actionId: SwingTerminalHostAction,
    ) : AbstractAction() {
        override fun isEnabled(): Boolean = actionRegistry.isEnabled(actionId, pane)

        override fun actionPerformed(event: ActionEvent) {
            actionRegistry.perform(actionId, pane)
        }
    }

    /**
     * Handles a key press before terminal input encoding.
     *
     * @param event Swing key event from the terminal component.
     * @return `true` when the key was a host shortcut and must not be sent to
     * the shell.
     */
    fun handleKeyPressed(event: KeyEvent): Boolean {
        val action = shortcutMap.actionFor(event.keyCode, event.modifiersEx) ?: return false
        if (!actionRegistry.isEnabled(action, pane)) return false
        actionRegistry.perform(action, pane)
        return true
    }
}

/**
 * Standalone terminal-pane action execution.
 */
internal object TerminalPaneActionRegistry {
    /**
     * Returns whether [action] can currently be executed for [target].
     *
     * @param action terminal host action.
     * @param target active terminal pane.
     * @return `true` when the action should claim its shortcut.
     */
    fun isEnabled(
        action: SwingTerminalHostAction,
        target: TerminalPaneActionTarget,
    ): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> target.hasSelection()
            SwingTerminalHostAction.REQUEST_SUGGESTIONS -> target.suggestionsEnabled()
            SwingTerminalHostAction.PASTE_CLIPBOARD,
            SwingTerminalHostAction.OPEN_SEARCH,
            SwingTerminalHostAction.SELECT_ALL,
            SwingTerminalHostAction.CLEAR_SCREEN,
            SwingTerminalHostAction.SCROLL_PAGE_UP,
            SwingTerminalHostAction.SCROLL_PAGE_DOWN,
            -> true
        }

    /**
     * Executes [action] for [target].
     *
     * @param action terminal host action.
     * @param target target terminal pane.
     * @return `true` when the action performed useful work.
     */
    fun perform(
        action: SwingTerminalHostAction,
        target: TerminalPaneActionTarget,
    ): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> target.copySelectionToClipboard()
            SwingTerminalHostAction.PASTE_CLIPBOARD -> target.pasteClipboardText()
            SwingTerminalHostAction.SELECT_ALL -> target.selectAll()
            SwingTerminalHostAction.CLEAR_SCREEN -> target.clearScreen()
            SwingTerminalHostAction.REQUEST_SUGGESTIONS -> {
                if (target.suggestionsEnabled()) {
                    target.requestShellSuggestions()
                    true
                } else {
                    false
                }
            }
            SwingTerminalHostAction.OPEN_SEARCH -> {
                target.openSearch()
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_UP -> {
                target.scrollPageUp()
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_DOWN -> {
                target.scrollPageDown()
                true
            }
        }
}

/**
 * Minimal standalone terminal-pane action target.
 */
internal interface TerminalPaneActionTarget {
    /** Whether the master feature is available for this pane. */
    fun suggestionsEnabled(): Boolean

    /**
     * Returns whether terminal text is currently selected.
     */
    fun hasSelection(): Boolean

    /**
     * Copies the current terminal selection to the host clipboard.
     */
    fun copySelectionToClipboard(): Boolean

    /**
     * Pastes host clipboard text into the terminal.
     */
    fun pasteClipboardText(): Boolean

    /**
     * Selects all retained terminal text.
     */
    fun selectAll(): Boolean

    /**
     * Requests a foreground-program screen clear/redraw.
     */
    fun clearScreen(): Boolean

    /**
     * Requests suggestions for the active shell command line.
     */
    fun requestShellSuggestions()

    /**
     * Opens host-owned search chrome.
     */
    fun openSearch()

    /**
     * Scrolls one terminal page away from the live viewport.
     */
    fun scrollPageUp()

    /**
     * Scrolls one terminal page toward the live viewport.
     */
    fun scrollPageDown()
}

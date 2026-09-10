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
package io.github.ketraterm.intellij.ui

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.KeymapManager
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAction
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionKeymap
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/** Resolves shell-suggestion actions from IntelliJ's active user keymap. */
internal object KetraTermShellSuggestionKeymap : SwingShellSuggestionKeymap {
    override fun actionFor(event: KeyEvent): SwingShellSuggestionAction? {
        SwingShellSuggestionKeymap.STANDARD.actionFor(event)?.let { return it }
        val modifiers = event.modifiersEx and RELEVANT_MODIFIERS
        val keyStroke = KeyStroke.getKeyStroke(event.keyCode, modifiers)
        val actionIds = KeymapManager.getInstance().activeKeymap.getActionIds(keyStroke)
        return actionFor(actionIds)
    }

    /** Resolves the first supported IntelliJ action from keymap lookup order. */
    internal fun actionFor(actionIds: Array<String>): SwingShellSuggestionAction? {
        for (actionId in actionIds) {
            when (actionId) {
                IdeActions.ACTION_LOOKUP_DOWN -> return SwingShellSuggestionAction.SELECT_NEXT
                IdeActions.ACTION_LOOKUP_UP -> return SwingShellSuggestionAction.SELECT_PREVIOUS
                IdeActions.ACTION_EDITOR_ESCAPE -> return SwingShellSuggestionAction.DISMISS
            }
        }
        return null
    }

    private const val RELEVANT_MODIFIERS =
        InputEvent.SHIFT_DOWN_MASK or
            InputEvent.CTRL_DOWN_MASK or
            InputEvent.META_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or
            InputEvent.ALT_GRAPH_DOWN_MASK
}

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
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class KetraTermShellSuggestionKeymapTest {
    @Test
    fun `lookup navigation follows IntelliJ actions`() {
        assertEquals(
            SwingShellSuggestionAction.SELECT_NEXT,
            KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_LOOKUP_DOWN)),
        )
        assertEquals(
            SwingShellSuggestionAction.SELECT_PREVIOUS,
            KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_LOOKUP_UP)),
        )
    }

    @Test
    fun `unmodified navigation keys always control the visible popup`() {
        val source = Canvas()

        assertEquals(
            SwingShellSuggestionAction.SELECT_NEXT,
            action(source, KeyEvent.VK_DOWN),
        )
        assertEquals(
            SwingShellSuggestionAction.SELECT_PREVIOUS,
            action(source, KeyEvent.VK_UP),
        )
        assertEquals(
            SwingShellSuggestionAction.SELECT_FIRST,
            action(source, KeyEvent.VK_HOME),
        )
        assertEquals(
            SwingShellSuggestionAction.SELECT_LAST,
            action(source, KeyEvent.VK_END),
        )
    }

    @Test
    fun `IntelliJ lookup acceptance actions do not claim terminal submission`() {
        assertNull(KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM)))
        assertNull(KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM_REPLACE)))
    }

    @Test
    fun `enter accepts only a selected popup item while tab retains select or accept semantics`() {
        val source = Canvas()

        assertEquals(
            SwingShellSuggestionAction.ACCEPT_SELECTED,
            KetraTermShellSuggestionKeymap.actionFor(
                KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED),
            ),
        )
        assertEquals(
            SwingShellSuggestionAction.ACCEPT,
            KetraTermShellSuggestionKeymap.actionFor(
                KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED),
            ),
        )
        assertNull(
            KetraTermShellSuggestionKeymap.actionFor(
                KeyEvent(
                    source,
                    KeyEvent.KEY_PRESSED,
                    0L,
                    InputEvent.CTRL_DOWN_MASK,
                    KeyEvent.VK_ENTER,
                    KeyEvent.CHAR_UNDEFINED,
                ),
            ),
        )
    }

    @Test
    fun `editor escape dismisses and unrelated actions remain unclaimed`() {
        assertEquals(
            SwingShellSuggestionAction.DISMISS,
            KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_EDITOR_ESCAPE)),
        )
        assertNull(KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_EDITOR_COPY)))
    }

    private fun action(
        source: Canvas,
        keyCode: Int,
        modifiers: Int = 0,
    ): SwingShellSuggestionAction? =
        KetraTermShellSuggestionKeymap.actionFor(
            KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED),
        )
}

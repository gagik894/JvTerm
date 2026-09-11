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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.model.Line
import io.github.ketraterm.core.model.UnderlineStyle
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRenderGenerationTest {
    @Test
    fun `printing changes frame and line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val state = stateOf(buffer)
        val line = lineAt(state, 0)
        val oldFrame = state.frameGeneration
        val oldLine = line.renderGeneration

        buffer.writeCodepoint('A'.code)

        assertAll(
            { assertNotEquals(oldFrame, state.frameGeneration) },
            { assertNotEquals(oldLine, line.renderGeneration) },
        )
    }

    @Test
    fun `cursor movement changes cursor generation without dirtying line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val state = stateOf(buffer)
        buffer.writeCodepoint('A'.code)
        val line = lineAt(state, 0)
        val oldFrame = state.frameGeneration
        val oldCursor = state.cursorGeneration
        val oldLine = line.renderGeneration

        buffer.positionCursor(0, 1)

        assertAll(
            { assertNotEquals(oldFrame, state.frameGeneration) },
            { assertNotEquals(oldCursor, state.cursorGeneration) },
            { assertEquals(oldLine, line.renderGeneration) },
        )
    }

    @Test
    fun `SGR alone does not change render generations but later print does`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val state = stateOf(buffer)
        val line = lineAt(state, 0)
        val oldFrame = state.frameGeneration
        val oldLine = line.renderGeneration

        buffer.setPenAttributes(
            fg = 1,
            bg = 0,
            bold = true,
            faint = false,
            italic = false,
            underlineStyle = UnderlineStyle.SINGLE,
        )

        assertAll(
            { assertEquals(oldFrame, state.frameGeneration) },
            { assertEquals(oldLine, line.renderGeneration) },
        )

        buffer.writeCodepoint('B'.code)

        assertAll(
            { assertNotEquals(oldFrame, state.frameGeneration) },
            { assertNotEquals(oldLine, line.renderGeneration) },
        )
    }

    @Test
    fun `erase changes the affected line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val state = stateOf(buffer)
        buffer.writeText("AB")
        val line = lineAt(state, 0)
        val oldFrame = state.frameGeneration
        val oldLine = line.renderGeneration

        buffer.positionCursor(0, 0)
        buffer.eraseCurrentLine()

        assertAll(
            { assertNotEquals(oldFrame, state.frameGeneration) },
            { assertNotEquals(oldLine, line.renderGeneration) },
        )
    }

    @Test
    fun `scroll changes structure generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val state = stateOf(buffer)
        val oldStructure = state.structureGeneration

        buffer.positionCursor(0, 1)
        buffer.newLine()

        assertNotEquals(oldStructure, state.structureGeneration)
    }

    @Test
    fun `resize changes structure generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val state = stateOf(buffer)
        val oldStructure = state.structureGeneration

        buffer.resize(5, 3)

        assertNotEquals(oldStructure, state.structureGeneration)
    }

    @Test
    fun `alternate screen switch changes structure generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2)
        val state = stateOf(buffer)
        val oldStructure = state.structureGeneration

        buffer.enterAltBuffer()

        assertNotEquals(oldStructure, state.structureGeneration)
    }

    private fun stateOf(api: TerminalBuffer): TerminalState {
        val componentsField = api.javaClass.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(api)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    private fun lineAt(
        state: TerminalState,
        row: Int,
    ): Line = state.ring[state.resolveRingIndex(row)]
}

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

import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DefaultTerminalBufferResizeTest {
    private fun stateOf(buffer: DefaultTerminalBuffer): TerminalState {
        val componentsField = DefaultTerminalBuffer::class.java.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(buffer)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    @ParameterizedTest
    @CsvSource("8, 3", "8, 4", "10, 3")
    fun `resize returns the active alternate viewport while retaining primary history`(
        newWidth: Int,
        newHeight: Int,
    ) {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3, maxHistory = 16)
        val sourceRows = (0..6).map { "row$it" }
        for ((row, text) in sourceRows.withIndex()) {
            if (row > 0) {
                buffer.carriageReturn()
                buffer.newLine()
            }
            for (character in text) buffer.writeCodepoint(character.code)
        }
        assertEquals(4, buffer.historySize)
        buffer.enterAltBuffer()

        val result = buffer.resize(newWidth, newHeight, oldScrollbackOffset = 2)

        assertEquals(0 to 0, result)
        assertEquals(newWidth, buffer.width)
        assertEquals(newHeight, buffer.height)
        assertEquals(0, buffer.historySize)
        buffer.exitAltBuffer()
        assertEquals(newWidth, buffer.width)
        assertEquals(newHeight, buffer.height)
        assertEquals(4, buffer.historySize)
        assertEquals(sourceRows.joinToString("\n"), buffer.getAllAsString().trimEnd())
    }

    @Test
    fun `resizeWhileAltActive_clampsPrimaryScrollRegionBeforeExit`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 5, initialHeight = 6, maxHistory = 4)
        buffer.setScrollRegion(top = 3, bottom = 6)
        buffer.enterAltBuffer()

        buffer.resize(newWidth = 5, newHeight = 3)

        val state = stateOf(buffer)
        assertAll(
            { assertTrue(state.primaryBuffer.scrollTop in 0 until 3) },
            { assertTrue(state.primaryBuffer.scrollBottom in 0 until 3) },
            { assertTrue(state.primaryBuffer.scrollTop <= state.primaryBuffer.scrollBottom) },
        )
    }

    @Test
    fun `resizeWhileAltActive_thenInsertLinesOnPrimary_doesNotIndexPastRing`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 5, initialHeight = 6, maxHistory = 4)
        buffer.setScrollRegion(top = 3, bottom = 6)
        buffer.enterAltBuffer()
        buffer.resize(newWidth = 5, newHeight = 3)
        buffer.exitAltBuffer()
        buffer.positionCursor(col = 0, row = 2)

        assertDoesNotThrow {
            buffer.insertLines(1)
        }
    }

    @Test
    fun `resize_resetsOrClampsBothBuffersMarginsPerChosenPolicy`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 7, initialHeight = 6, maxHistory = 4)
        buffer.setScrollRegion(top = 2, bottom = 5)
        buffer.enterAltBuffer()
        buffer.setScrollRegion(top = 3, bottom = 6)

        buffer.resize(newWidth = 7, newHeight = 3)

        val state = stateOf(buffer)
        assertAll(
            { assertTrue(state.primaryBuffer.scrollTop in 0 until 3) },
            { assertTrue(state.primaryBuffer.scrollBottom in 0 until 3) },
            { assertTrue(state.primaryBuffer.scrollTop <= state.primaryBuffer.scrollBottom) },
            { assertTrue(state.altBuffer.scrollTop in 0 until 3) },
            { assertTrue(state.altBuffer.scrollBottom in 0 until 3) },
            { assertTrue(state.altBuffer.scrollTop <= state.altBuffer.scrollBottom) },
        )
    }

    @Test
    fun `resize while scrolled into top anchored history preserves viewport anchor`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 4, maxHistory = 8)
        val state = stateOf(buffer)
        val rows = arrayOf("AAA", "BBB", "CCC", "DDD")
        for (row in rows.indices) {
            val line = state.primaryBuffer.ring[row]
            for (column in rows[row].indices) {
                line.setCell(column, rows[row][column].code, state.pen.currentAttr)
            }
        }
        buffer.setScrollRegion(top = 1, bottom = 3)
        buffer.positionCursor(col = 0, row = 2)
        buffer.newLine()

        val anchoredLine = state.ring[state.resolveScrollbackRingIndex(viewportRow = 0, scrollbackOffset = 1)]
        val anchoredLineId = anchoredLine.lineId
        assertEquals("AAA", anchoredLine.toTextTrimmed())

        val (newOffset, newHistorySize) = buffer.resize(newWidth = 4, newHeight = 4, oldScrollbackOffset = 1)

        val reanchoredLine = state.ring[state.resolveScrollbackRingIndex(viewportRow = 0, scrollbackOffset = newOffset)]
        assertAll(
            { assertEquals(1, newOffset) },
            { assertEquals(1, newHistorySize) },
            { assertEquals(anchoredLineId, reanchoredLine.lineId) },
            { assertEquals("AAA", reanchoredLine.toTextTrimmed()) },
            { assertEquals("DDD", state.ring[state.resolveRingIndex(3)].toTextTrimmed()) },
        )
    }
}

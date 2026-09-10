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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CellSelectionTest {
    @Test
    fun `block bounds normalize horizontal and vertical directions independently`() {
        val selection = CellSelection(anchorColumn = 5, anchorRow = 0, caretColumn = 2, caretRow = 2, isBlock = true)

        assertEquals(2, selection.startColumn)
        assertEquals(5, selection.endColumn)
        for (row in 0..2) {
            assertEquals(CellSelection.packRange(2, 5), selection.packedColumnRange(row, 10))
        }
        assertEquals(CellSelection.NO_RANGE, selection.packedColumnRange(3, 10))
    }

    @Test
    fun `zero width block is empty even when its rows differ`() {
        val selection = CellSelection(anchorColumn = 2, anchorRow = 0, caretColumn = 2, caretRow = 2, isBlock = true)

        assertTrue(selection.isEmpty)
        for (row in 0..2) {
            assertEquals(CellSelection.NO_RANGE, selection.packedColumnRange(row, 10))
        }
    }

    @Test
    fun `single row selection exposes half-open range`() {
        val selection = CellSelection(anchorColumn = 2, anchorRow = 0, caretColumn = 5, caretRow = 0)

        val range = selection.packedColumnRange(row = 0, columns = 10)

        assertEquals(2, CellSelection.rangeStart(range))
        assertEquals(5, CellSelection.rangeEnd(range))
    }

    @Test
    fun `backward multi row selection normalizes ranges`() {
        val selection = CellSelection(anchorColumn = 7, anchorRow = 2, caretColumn = 2, caretRow = 0)

        val first = selection.packedColumnRange(row = 0, columns = 10)
        val middle = selection.packedColumnRange(row = 1, columns = 10)
        val last = selection.packedColumnRange(row = 2, columns = 10)

        assertEquals(2, CellSelection.rangeStart(first))
        assertEquals(10, CellSelection.rangeEnd(first))
        assertEquals(0, CellSelection.rangeStart(middle))
        assertEquals(10, CellSelection.rangeEnd(middle))
        assertEquals(0, CellSelection.rangeStart(last))
        assertEquals(7, CellSelection.rangeEnd(last))
    }
}

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
package io.github.ketraterm.ui.swing.suggestion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SwingShellSuggestionViewSnapshotTest {
    @Test
    fun `publishes local selection with absolute viewport metadata`() {
        val suggestions = List(3) { suggestion("candidate-$it") }

        val snapshot =
            SwingShellSuggestionViewSnapshot.create(
                visibleSuggestions = suggestions,
                selectedIndex = 1,
                viewportStartIndex = 4,
                totalSuggestionCount = 12,
            )

        assertEquals(5, snapshot.absoluteSelectedIndex)
        assertSame(snapshot.visibleSuggestions[1], snapshot.selectedSuggestion)
        assertTrue(snapshot.hasSuggestionsBefore)
        assertTrue(snapshot.hasSuggestionsAfter)
    }

    @Test
    fun `defensively copies the visible viewport`() {
        val mutable = mutableListOf(suggestion("first"))
        val snapshot = SwingShellSuggestionViewSnapshot.create(mutable, 0, 0, 1)

        mutable.clear()

        assertEquals(listOf("first"), snapshot.visibleSuggestions.map { it.displayText })
    }

    @Test
    fun `empty snapshot has no overflow or selection`() {
        val snapshot = SwingShellSuggestionViewSnapshot.EMPTY

        assertEquals(-1, snapshot.absoluteSelectedIndex)
        assertEquals(null, snapshot.selectedSuggestion)
        assertFalse(snapshot.hasSuggestionsBefore)
        assertFalse(snapshot.hasSuggestionsAfter)
    }

    @Test
    fun `rejects malformed viewport state`() {
        val suggestion = suggestion("candidate")

        assertThrows(IllegalArgumentException::class.java) {
            SwingShellSuggestionViewSnapshot.create(List(9) { suggestion }, 0, 0, 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SwingShellSuggestionViewSnapshot.create(listOf(suggestion), 1, 0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SwingShellSuggestionViewSnapshot.create(listOf(suggestion), 0, 4, 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SwingShellSuggestionViewSnapshot.create(emptyList(), -1, 1, 1)
        }
    }

    private fun suggestion(displayText: String): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = displayText,
            replacementStartOffset = 0,
            replacementEndOffset = 0,
            source = "spec",
            kind = "COMMAND",
        )
}

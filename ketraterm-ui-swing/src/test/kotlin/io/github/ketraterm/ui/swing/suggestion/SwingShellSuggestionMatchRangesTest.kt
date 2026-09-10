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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwingShellSuggestionMatchRangesTest {
    @Test
    fun `factory owns an immutable validated copy`() {
        val offsets = intArrayOf(0, 1, 5, 8)
        val ranges = SwingShellSuggestionMatchRanges.fromPackedOffsets("buildRelease", offsets)
        offsets[0] = 4

        assertEquals(2, ranges.rangeCount)
        assertContentEquals(intArrayOf(0, 1, 5, 8), ranges.copyPackedOffsets())
    }

    @Test
    fun `factory rejects incomplete overlapping and out of bounds ranges`() {
        assertFailsWith<IllegalArgumentException> {
            SwingShellSuggestionMatchRanges.fromPackedOffsets("status", intArrayOf(0))
        }
        assertFailsWith<IllegalArgumentException> {
            SwingShellSuggestionMatchRanges.fromPackedOffsets("status", intArrayOf(0, 3, 2, 4))
        }
        assertFailsWith<IllegalArgumentException> {
            SwingShellSuggestionMatchRanges.fromPackedOffsets("status", intArrayOf(0, 7))
        }
    }

    @Test
    fun `factory rejects surrogate splitting offsets`() {
        assertFailsWith<IllegalArgumentException> {
            SwingShellSuggestionMatchRanges.fromPackedOffsets("a😀b", intArrayOf(1, 2))
        }
    }

    @Test
    fun `suggestion rejects ranges owned by another display string`() {
        val ranges = SwingShellSuggestionMatchRanges.fromPackedOffsets("status", intArrayOf(0, 6))

        assertFailsWith<IllegalArgumentException> {
            SwingShellSuggestion(
                replacementText = "s",
                replacementStartOffset = 0,
                replacementEndOffset = 0,
                source = "test",
                kind = "COMMAND",
                displayText = "s",
                matchedRanges = ranges,
            )
        }
    }
}

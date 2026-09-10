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
package io.github.ketraterm.completion.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalCompletionMatchRangesTest {
    @Test
    fun `factory copies caller storage and exposes stable indexed ranges`() {
        val offsets = intArrayOf(0, 1, 5, 8)
        val ranges = TerminalCompletionMatchRanges.fromPackedOffsets("buildRelease", offsets)
        offsets[0] = 4

        assertEquals(2, ranges.rangeCount)
        assertEquals(0, ranges.startOffset(0))
        assertEquals(1, ranges.endOffset(0))
        assertEquals(5, ranges.startOffset(1))
        assertEquals(8, ranges.endOffset(1))
        assertContentEquals(intArrayOf(0, 1, 5, 8), ranges.copyPackedOffsets())
    }

    @Test
    fun `factory rejects malformed or noncanonical ranges`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionMatchRanges.fromPackedOffsets("status", intArrayOf(0))
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionMatchRanges.fromPackedOffsets("status", intArrayOf(0, 3, 2, 4))
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionMatchRanges.fromPackedOffsets("status", intArrayOf(2, 2))
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionMatchRanges.fromPackedOffsets("status", intArrayOf(0, 7))
        }
    }

    @Test
    fun `factory rejects offsets that split surrogate pairs`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionMatchRanges.fromPackedOffsets("a😀b", intArrayOf(1, 2))
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionMatchRanges.fromPackedOffsets("a😀b", intArrayOf(2, 3))
        }
    }

    @Test
    fun `candidate rejects ranges created for a different display string`() {
        val ranges = TerminalCompletionMatchRanges.fromPackedOffsets("status", intArrayOf(0, 6))

        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionCandidate(
                replacementText = "s",
                replacementStartOffset = 0,
                replacementEndOffset = 0,
                source = "test",
                kind = TerminalCompletionCandidateKind.COMMAND,
                displayText = "s",
                matchedRanges = ranges,
            )
        }
    }
}

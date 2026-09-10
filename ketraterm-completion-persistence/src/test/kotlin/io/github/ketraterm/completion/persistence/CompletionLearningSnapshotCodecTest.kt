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
package io.github.ketraterm.completion.persistence

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import kotlin.test.*

class CompletionLearningSnapshotCodecTest {
    @Test
    fun `current file name and header use the split schema version`() {
        assertEquals("command-completion-learning-v3.tsv", TerminalCompletionLearningCoordinator.currentFileName())
        assertEquals(listOf(HEADER), CompletionLearningSnapshotCodec.encode(TerminalCompletionLearningSnapshot.EMPTY))
    }

    @Test
    fun `round trips opaque evidence and replay text with unicode`() {
        val snapshot = snapshot("git commit -m 'Բարև աշխարհ'")

        assertEquals(snapshot, CompletionLearningSnapshotCodec.decode(CompletionLearningSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `credential commands encode only opaque ranking rows`() {
        val snapshot =
            snapshot(
                "curl -u alice:s3cr3t https://example.test",
                "mysql -p hunter2",
            )

        val encoded = CompletionLearningSnapshotCodec.encode(snapshot)
        val decoded = requireNotNull(CompletionLearningSnapshotCodec.decode(encoded))

        assertEquals(2, decoded.rankingStats.size)
        assertTrue(decoded.replayCommands.isEmpty())
        assertTrue(encoded.drop(1).all { it.startsWith("R\t") })
        assertFalse(encoded.any { "s3cr3t" in it || "hunter2" in it })
    }

    @Test
    fun `malformed rows reject the complete snapshot`() {
        val validLines = CompletionLearningSnapshotCodec.encode(snapshot("git status"))
        val ranking = validLines.first { it.startsWith("R\t") }
        val replay = validLines.first { it.startsWith("H\t") }
        val malformedCount =
            ranking
                .split('\t')
                .toMutableList()
                .also { it[4] = "invalid" }
                .joinToString("\t")
        val malformedReplay =
            replay
                .split('\t')
                .toMutableList()
                .also { it[1] = OTHER_DIGEST }
                .joinToString("\t")

        for (invalidRow in listOf(malformedCount, malformedReplay, "R\ttoo-short", "H\ttoo-short", "X\tunknown")) {
            assertNull(CompletionLearningSnapshotCodec.decode(listOf(HEADER, invalidRow)))
        }
        assertNull(CompletionLearningSnapshotCodec.decode(listOf(HEADER, replay, ranking)))
    }

    @Test
    fun `malformed UTF-8 replay text rejects the complete snapshot`() {
        val invalidUtf8Replay = "H\t$OTHER_DIGEST\twyg\t\t"

        assertNull(CompletionLearningSnapshotCodec.decode(listOf(HEADER, invalidUtf8Replay)))
    }

    @Test
    fun `legacy and unknown schemas are rejected`() {
        assertNull(CompletionLearningSnapshotCodec.decode(listOf("KetraTerm_COMMAND_COMPLETION_STATS\t2")))
        assertNull(CompletionLearningSnapshotCodec.decode(listOf("KetraTerm_COMMAND_COMPLETION_LEARNING\t999")))
    }

    @Test
    fun `header-only file decodes to an empty snapshot`() {
        assertEquals(CompletionLearningSnapshotCodec.decode(listOf(HEADER)), TerminalCompletionLearningSnapshot.EMPTY)
    }

    private fun snapshot(vararg commands: String): TerminalCompletionLearningSnapshot {
        val learning = TerminalCompletionLearningStore()
        for ((index, command) in commands.withIndex()) {
            learning.recordCommandResult(command, true, "bash", "file:///repo", index + 1L)
        }
        return learning.snapshot()
    }

    private companion object {
        private const val HEADER = "KetraTerm_COMMAND_COMPLETION_LEARNING\t3"
        private const val OTHER_DIGEST = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}

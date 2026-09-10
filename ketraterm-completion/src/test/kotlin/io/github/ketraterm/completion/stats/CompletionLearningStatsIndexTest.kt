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
package io.github.ketraterm.completion.stats

import io.github.ketraterm.completion.internal.terminalCompletionRankingIdentity
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import kotlin.test.*

class CompletionLearningStatsIndexTest {
    @Test
    fun `one aggregate publishes opaque evidence and optional replay projections`() {
        val index = CompletionLearningStatsIndex(capacity = 8)
        val safeIdentity = terminalCompletionRankingIdentity("git status")
        val privateIdentity = terminalCompletionRankingIdentity("curl -u alice:s3cr3t example.com")

        index.recordCommandResult(safeIdentity, "git status", true, null, null, 10)
        index.recordCommandResult(privateIdentity, null, false, null, null, 20)

        val snapshot = index.snapshot()
        assertEquals(listOf(safeIdentity, privateIdentity), snapshot.rankingStats.map { it.identityDigest })
        assertEquals(listOf("git status"), snapshot.replayCommands.map { it.commandLine })
        assertNull(snapshot.replayCommands.find { it.identityDigest == privateIdentity })
    }

    @Test
    fun `case-sensitive identities remain independent`() {
        val index = CompletionLearningStatsIndex(capacity = 8)
        val upper = terminalCompletionRankingIdentity("cat Foo")
        val lower = terminalCompletionRankingIdentity("cat foo")

        index.recordCommandResult(upper, "cat Foo", true, null, null, 10)
        index.recordCommandResult(lower, "cat foo", false, null, null, 20)

        val byIdentity = index.snapshot().rankingStats.associateBy { it.identityDigest }
        assertEquals(1, byIdentity.getValue(upper).successCount)
        assertEquals(1, byIdentity.getValue(lower).useCount)
        assertEquals(0, byIdentity.getValue(lower).successCount)
        assertEquals(1, byIdentity.getValue(lower).failureCount)
    }

    @Test
    fun `records execution and feedback counters without replaying unsuccessful commands`() {
        val index = CompletionLearningStatsIndex(capacity = 3)

        fun record(
            command: String,
            successful: Boolean,
            time: Long,
        ) = index.recordCommandResult(terminalCompletionRankingIdentity(command), command, successful, null, null, time)

        record("one", true, 1)
        record("two", false, 2)
        index.recordSuggestionFeedback(
            terminalCompletionRankingIdentity("two"),
            TerminalCompletionFeedbackKind.ACCEPTED,
            null,
            null,
            3,
        )
        index.recordSuggestionFeedback(
            terminalCompletionRankingIdentity("two"),
            TerminalCompletionFeedbackKind.DISMISSED,
            null,
            null,
            4,
        )
        record("three", true, 5)

        val snapshot = index.snapshot()
        assertEquals(listOf("three", "one"), snapshot.replayCommands.map { it.commandLine })
        val two = snapshot.rankingStats.single { it.identityDigest == terminalCompletionRankingIdentity("two") }
        assertEquals(1, two.useCount)
        assertEquals(0, two.successCount)
        assertEquals(1, two.failureCount)
        assertEquals(1, two.acceptedCount)
        assertEquals(1, two.dismissedCount)
        assertEquals(4L, two.lastUsedEpochMillis)
    }

    @Test
    fun `merge sums evidence and attaches replay only to retained rows`() {
        val index = CompletionLearningStatsIndex(capacity = 1)

        index.mergeSnapshot(
            learningSnapshot(
                commandLearning("git status", workingDirectoryUri = "file:///repo", successCount = 1, lastUsedEpochMillis = 1),
                commandLearning("git status", workingDirectoryUri = "file:///repo/", successCount = 2, lastUsedEpochMillis = 2),
                commandLearning("old", successCount = 1, lastUsedEpochMillis = 0),
            ),
        )

        val snapshot = index.snapshot()
        assertEquals(1, snapshot.rankingStats.size)
        assertEquals(3, snapshot.rankingStats.single().successCount)
        assertEquals("file:///repo/", snapshot.rankingStats.single().workingDirectoryUri)
        assertEquals(listOf("git status"), snapshot.replayCommands.map { it.commandLine })
    }

    @Test
    fun `non-successful live and merged evidence retain no replay projection`() {
        val index = CompletionLearningStatsIndex(capacity = 8)
        val failedCommand = "git failing-safe-command"

        index.recordCommandResult(
            terminalCompletionRankingIdentity(failedCommand),
            failedCommand,
            false,
            null,
            null,
            1,
        )
        index.mergeSnapshot(
            learningSnapshot(
                commandLearning("git dismissed-safe-command", dismissedCount = 1),
            ),
        )

        val snapshot = index.snapshot()
        assertEquals(2, snapshot.rankingStats.size)
        assertTrue(snapshot.replayCommands.isEmpty())
    }

    @Test
    fun `positive rows survive newer negative churn at capacity`() {
        val index = CompletionLearningStatsIndex(capacity = 2)
        for ((offset, command) in listOf("kept-one", "kept-two").withIndex()) {
            index.recordCommandResult(terminalCompletionRankingIdentity(command), command, true, null, null, offset + 1L)
        }

        repeat(100) { offset ->
            val command = "negative-$offset"
            index.recordCommandResult(
                terminalCompletionRankingIdentity(command),
                command,
                false,
                null,
                null,
                1_000L + offset,
            )
        }

        val snapshot = index.snapshot()
        assertEquals(listOf("kept-two", "kept-one"), snapshot.replayCommands.map { it.commandLine })
        assertTrue(snapshot.rankingStats.all { it.successCount == 1 })
    }

    @Test
    fun `snapshot reuses retained validated replay objects`() {
        val index = CompletionLearningStatsIndex(capacity = 2)
        val command = "git status"
        val identity = terminalCompletionRankingIdentity(command)
        index.recordCommandResult(identity, command, true, null, null, 1L)
        val replay = index.snapshot().replayCommands.single()

        index.recordCommandResult(identity, command, true, null, null, 2L)

        assertSame(replay, index.snapshot().replayCommands.single())
    }
}

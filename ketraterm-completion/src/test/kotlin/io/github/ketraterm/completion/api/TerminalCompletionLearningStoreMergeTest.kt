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

import io.github.ketraterm.completion.internal.terminalCompletionRankingIdentity
import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalCompletionLearningStoreMergeTest {
    @Test
    fun `clear removes ranking evidence and replay commands`() {
        val store = TerminalCompletionLearningStore()
        store.recordCommandResult("git status", true, "bash", "file:///repo", 1L)
        store.recordSuggestionFeedback(
            commandLine = "gradle test",
            feedback = TerminalCompletionFeedbackKind.DISMISSED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 2L,
        )

        store.clear()

        assertSame(TerminalCompletionLearningSnapshot.EMPTY, store.snapshot())
    }

    @Test
    fun `merge snapshot canonicalizes context without collapsing command case`() {
        val store = TerminalCompletionLearningStore()
        store.mergeSnapshot(
            learningSnapshot(
                rows = listOf(commandStats("Git Status", "file:///repo", 3, 1, 100)),
            ),
        )

        store.mergeSnapshot(
            learningSnapshot(
                rows = listOf(commandStats("git status", "file:///repo/", 4, 2, 200)),
            ),
        )

        val snapshot = store.snapshot()
        assertEquals(listOf("git status", "Git Status"), snapshot.replayCommands.map { it.commandLine })
        assertEquals(listOf("file:///repo/", "file:///repo/"), snapshot.rankingStats.map { it.workingDirectoryUri })
        assertEquals(listOf(4, 3), snapshot.rankingStats.map { it.useCount })
        assertEquals(listOf(2, 1), snapshot.rankingStats.map { it.successCount })
        assertEquals(listOf(200L, 100L), snapshot.rankingStats.map { it.lastUsedEpochMillis })
    }

    @Test
    fun `merge drops replay projections without a successful execution`() {
        val store = TerminalCompletionLearningStore()
        val acceptedCommand = "git accepted"
        val acceptedIdentity = terminalCompletionRankingIdentity(acceptedCommand)

        store.mergeSnapshot(
            TerminalCompletionLearningSnapshot(
                rankingStats =
                    listOf(
                        TerminalCompletionRankingStats(acceptedIdentity, acceptedCount = 1),
                        learningSnapshot(commandLearning("git failed", useCount = 1, failureCount = 1)).rankingStats.single(),
                        learningSnapshot(commandLearning("git dismissed", dismissedCount = 1)).rankingStats.single(),
                    ),
                replayCommands = listOf(TerminalCommandReplay(acceptedIdentity, acceptedCommand)),
            ),
        )

        assertEquals(3, store.snapshot().rankingStats.size)
        assertTrue(store.snapshot().replayCommands.isEmpty())
    }

    private fun commandStats(
        commandLine: String,
        directory: String,
        useCount: Int,
        successCount: Int,
        timestamp: Long,
    ) = commandLearning(
        commandLine = commandLine,
        profileId = "bash",
        workingDirectoryUri = directory,
        useCount = useCount,
        successCount = successCount,
        lastUsedEpochMillis = timestamp,
    )
}

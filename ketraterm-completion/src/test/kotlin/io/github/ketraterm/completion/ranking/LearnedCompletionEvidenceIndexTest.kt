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
package io.github.ketraterm.completion.ranking

import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.CompletionLearningIndexCache
import io.github.ketraterm.completion.internal.terminalCompletionRankingIdentity
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearnedCompletionEvidenceIndexTest {
    @Test
    fun `empty evidence has a zero adjustment`() {
        val index = CompletionLearningIndexCache().indexesFor(TerminalCompletionLearningSnapshot.EMPTY, TerminalShellSyntax.POSIX).evidence

        assertEquals(
            0,
            index.adjustment(
                ResolvedCompletionOutcome(groupKey = listOf("git", "status"), exactCommandLine = "git status"),
                CompletionLearningContextKey.of(null, null),
                NOW,
            ),
        )
    }

    @Test
    fun `exact evidence prefers directory context over profile context`() {
        val snapshot =
            learningSnapshot(
                commandLearning(commandLine = "git status", profileId = "profile", dismissedCount = 100),
                commandLearning(commandLine = "git status", workingDirectoryUri = "file:///repo", acceptedCount = 10),
            )
        val index = CompletionLearningIndexCache().indexesFor(snapshot, TerminalShellSyntax.POSIX).evidence

        val adjustment =
            index.exactAdjustment(
                terminalCompletionRankingIdentity("git status"),
                CompletionLearningContextKey.of("profile", "file:///repo/"),
                NOW,
            )

        assertTrue(adjustment > 0)
    }

    @Test
    fun `exact evidence keeps case-distinct command arguments separate`() {
        val snapshot =
            learningSnapshot(
                commandLearning(commandLine = "cat Foo", acceptedCount = 10),
                commandLearning(commandLine = "cat foo", dismissedCount = 10),
            )
        val index = CompletionLearningIndexCache().indexesFor(snapshot, TerminalShellSyntax.POSIX).evidence

        val upperAdjustment =
            index.exactAdjustment(
                terminalCompletionRankingIdentity("cat Foo"),
                CompletionLearningContextKey.of(null, null),
                NOW,
            )
        val lowerAdjustment =
            index.exactAdjustment(
                terminalCompletionRankingIdentity("cat foo"),
                CompletionLearningContextKey.of(null, null),
                NOW,
            )

        assertTrue(upperAdjustment > 0)
        assertTrue(lowerAdjustment < 0)
    }

    private companion object {
        private const val NOW = 2_000_000_000_000L
    }
}

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
package io.github.ketraterm.completion.engine

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Deterministic replay gate for representative interactive completion choices. */
class CompletionRankingReplayTest {
    @Test
    fun `representative learned choices retain perfect top one replay`() =
        runBlocking {
            val cases =
                listOf(
                    replayCase(
                        commandLine = "git switch fe",
                        expectedCommand = "git switch feature/terminal",
                        domain = TerminalCompletionValueDomain.GIT_BRANCH,
                        alternatives = listOf("feature/aaa", "feature/terminal"),
                        learnedCommand = "git switch feature/terminal",
                    ),
                    replayCase(
                        commandLine = "./gradlew :app:",
                        expectedCommand = "./gradlew :app:test",
                        domain = TerminalCompletionValueDomain.NONE,
                        alternatives = listOf(":app:check", ":app:test"),
                        learnedCommand = "./gradlew :app:test",
                    ),
                    replayCase(
                        commandLine = "cd I",
                        expectedCommand = "cd IdeaProjects/",
                        domain = TerminalCompletionValueDomain.NONE,
                        alternatives = listOf("IdeaSnapshots/", "IdeaProjects/"),
                        learnedCommand = "cd IdeaProjects/",
                        kind = TerminalCompletionCandidateKind.PATH,
                    ),
                )

            val ranks = cases.map(ReplayCase::acceptedRank)
            val metrics = ReplayMetrics.fromRanks(ranks)

            assertEquals(listOf(1, 1, 1), ranks)
            assertEquals(1.0, metrics.topOneRate)
            assertEquals(1.0, metrics.topThreeRate)
            assertEquals(1.0, metrics.meanReciprocalRank)
        }

    private suspend fun replayCase(
        commandLine: String,
        expectedCommand: String,
        domain: TerminalCompletionValueDomain,
        alternatives: List<String>,
        learnedCommand: String,
        kind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.ARGUMENT,
    ): ReplayCase {
        val activeStart = commandLine.indexOfLast { it == ' ' } + 1
        val source =
            TerminalCompletionSource { _, _, _ ->
                alternatives.mapIndexed { index, replacement ->
                    TerminalCompletionCandidate(
                        replacementText = replacement,
                        replacementStartOffset = activeStart,
                        replacementEndOffset = commandLine.length,
                        source = "replay-provider",
                        kind = kind,
                        score = alternatives.size - index,
                        valueDomain = domain,
                    )
                }
            }
        val snapshot =
            learningSnapshot(
                rows =
                    listOf(
                        commandLearning(
                            commandLine = learnedCommand,
                            profileId = PROFILE,
                            workingDirectoryUri = WORKING_DIRECTORY,
                            useCount = 12,
                            successCount = 12,
                            acceptedCount = 6,
                            lastUsedEpochMillis = NOW,
                        ),
                    ),
            )
        val request =
            TerminalCompletionRequest(
                commandLine = commandLine,
                cursorOffset = commandLine.length,
                profileId = PROFILE,
                workingDirectoryUri = WORKING_DIRECTORY,
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )
        val engine =
            MergedCompletionEngine(
                sources = listOf(TerminalCompletionSourceEntry(source, priority = 10)),
                learningStore = TerminalCompletionLearningStore().apply { mergeSnapshot(snapshot) },
                clockEpochMillis = { NOW },
            )
        return ReplayCase(engine.complete(request), request, expectedCommand)
    }

    private data class ReplayCase(
        val candidates: List<TerminalCompletionCandidate>,
        val request: TerminalCompletionRequest,
        val expectedCommand: String,
    ) {
        fun acceptedRank(): Int =
            candidates.indexOfFirst { candidate ->
                request.commandLine.replaceRange(
                    candidate.replacementStartOffset,
                    candidate.replacementEndOffset,
                    candidate.replacementText,
                ) == expectedCommand
            } + 1
    }

    private data class ReplayMetrics(
        val topOneRate: Double,
        val topThreeRate: Double,
        val meanReciprocalRank: Double,
    ) {
        companion object {
            fun fromRanks(ranks: List<Int>): ReplayMetrics {
                require(ranks.isNotEmpty())
                require(ranks.all { it > 0 })
                return ReplayMetrics(
                    topOneRate = ranks.count { it == 1 }.toDouble() / ranks.size,
                    topThreeRate = ranks.count { it <= 3 }.toDouble() / ranks.size,
                    meanReciprocalRank = ranks.sumOf { 1.0 / it } / ranks.size,
                )
            }
        }
    }

    private companion object {
        private const val PROFILE = "replay"
        private const val WORKING_DIRECTORY = "file:///replay-project"
        private const val NOW = 2_000_000_000_000L
    }
}

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
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalEvidenceFusionCompletionEngineTest {
    @Test
    fun `learned and path outcomes fuse across quoting and trailing separator`() =
        runBlocking {
            val engine =
                engine(
                    sources =
                        listOf(
                            entry(source(candidate("cd build", 0, 3, "learned", TerminalCompletionCandidateKind.PATH)), 8),
                            entry(source(candidate("\"build/\"", 3, 3, "path", TerminalCompletionCandidateKind.PATH)), 12),
                        ),
                )

            val candidates = engine.complete(request("cd "))

            assertEquals(1, candidates.size)
            assertEquals("\"build/\"", candidates.single().replacementText)
            assertEquals("path", candidates.single().source)
        }

    @Test
    fun `full-command learned path variants use the projected path token when fusing`() =
        runBlocking {
            val engine =
                engine(
                    sources =
                        listOf(
                            entry(
                                source(candidate("cd IdeaProjects/KetraTerm", 0, 2, "learned", TerminalCompletionCandidateKind.PATH)),
                                8,
                            ),
                            entry(
                                source(
                                    candidate(
                                        "cd IdeaProjects/KetraTerm/",
                                        0,
                                        2,
                                        "learned",
                                        TerminalCompletionCandidateKind.PATH,
                                    ),
                                ),
                                8,
                            ),
                        ),
                )

            val candidates = engine.complete(request("cd"))

            assertEquals(1, candidates.size)
            assertEquals("cd IdeaProjects/KetraTerm", candidates.single().replacementText)
        }

    @Test
    fun `learning store produces one learned directory candidate without a provider source`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "cd IdeaProjects/KetraTerm/",
                                profileId = "profile",
                                workingDirectoryUri = "file:///home",
                                successCount = 4,
                            ),
                        ),
                )
            val learningStore = TerminalCompletionLearningStore().apply { mergeSnapshot(snapshot) }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = emptyList(),
                    commandSpecs = TerminalCommandSpecs.defaults(),
                    learningStore = learningStore,
                )

            val candidates = engine.complete(request("cd ", workingDirectoryUri = "file:///home"))

            assertEquals(1, candidates.size)
            assertEquals("IdeaProjects/KetraTerm/", candidates.single().replacementText)
            assertEquals("learned", candidates.single().source)
            assertEquals(TerminalCompletionCandidateKind.PATH, candidates.single().kind)
        }

    @Test
    fun `same provider contributes only its best local rank to one outcome`() =
        runBlocking {
            val duplicateSource =
                source(
                    candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100),
                    candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 10),
                )
            val duplicateScore = engine(listOf(entry(duplicateSource, 12))).complete(request("cd ")).single().score
            val singleScore =
                engine(
                    listOf(entry(source(candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100)), 12)),
                ).complete(request("cd ")).single().score

            assertEquals(singleScore, duplicateScore)
        }

    @Test
    fun `accepted exact outcome promotes matching path candidate`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "cd build/",
                                profileId = "profile",
                                workingDirectoryUri = "file:///repo",
                                acceptedCount = 8,
                                lastUsedEpochMillis = NOW,
                            ),
                        ),
                )
            val engine =
                engine(
                    sources =
                        listOf(
                            entry(
                                source(
                                    candidate("cache/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100),
                                    candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 1),
                                ),
                                12,
                            ),
                        ),
                    snapshot = snapshot,
                    commandSpecs = TerminalCommandSpecs.defaults(),
                )

            val candidates = engine.complete(request("cd ", workingDirectoryUri = "file:///repo"))

            assertEquals("build/", candidates.first().replacementText)
        }

    @Test
    fun `successful directory history promotes the equivalent path outcome`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "cd build",
                                profileId = "profile",
                                workingDirectoryUri = "file:///repo",
                                useCount = 12,
                                successCount = 12,
                                lastUsedEpochMillis = NOW,
                            ),
                        ),
                )
            val candidates =
                engine(
                    sources =
                        listOf(
                            entry(
                                source(
                                    candidate("cache/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 100),
                                    candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH, score = 1),
                                ),
                                12,
                            ),
                        ),
                    snapshot = snapshot,
                    commandSpecs = TerminalCommandSpecs.defaults(),
                ).complete(request("cd "))

            assertEquals("build/", candidates.first().replacementText)
        }

    @Test
    fun `Gradle task supported by history and statistics outranks an unused sibling`() =
        runBlocking {
            val commandLine = "./gradlew :app:"
            val task =
                candidate(
                    ":app:test",
                    10,
                    commandLine.length,
                    "gradle-tasks",
                    TerminalCompletionCandidateKind.SUBCOMMAND,
                )
            val sibling = task.copy(replacementText = ":app:check", displayText = ":app:check", score = 100)
            val remembered =
                candidate(
                    "./gradlew :app:test",
                    0,
                    commandLine.length,
                    "learned",
                    TerminalCompletionCandidateKind.SUBCOMMAND,
                )
            val statistical = remembered.copy(source = "command-stats")
            val candidates =
                engine(
                    listOf(
                        entry(source(sibling, task), 15),
                        entry(source(remembered), 8),
                        entry(source(statistical), 6),
                    ),
                ).complete(request(commandLine))

            assertEquals("./gradlew :app:test", candidates.first().let { commandLineAfter(it, commandLine) })
            assertEquals(2, candidates.size)
        }

    @Test
    fun `learned command replay does not cross working directories`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "tool deploy",
                                profileId = "profile",
                                workingDirectoryUri = "file:///other",
                                acceptedCount = 100,
                                successCount = 100,
                                lastUsedEpochMillis = NOW,
                            ),
                        ),
                )
            val engine = engine(sources = emptyList(), snapshot = snapshot)

            val candidates = engine.complete(request("tool d", workingDirectoryUri = "file:///repo"))

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `explicit dismissal lowers one exact outcome`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "git switch main",
                                dismissedCount = 12,
                                lastUsedEpochMillis = NOW,
                            ),
                        ),
                )
            val engine =
                engine(
                    sources =
                        listOf(
                            entry(
                                source(
                                    candidate(
                                        "main",
                                        11,
                                        13,
                                        "branches",
                                        TerminalCompletionCandidateKind.ARGUMENT,
                                        score = 100,
                                        domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                    ),
                                    candidate(
                                        "maint",
                                        11,
                                        13,
                                        "branches",
                                        TerminalCompletionCandidateKind.ARGUMENT,
                                        score = 1,
                                        domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                    ),
                                ),
                                15,
                            ),
                        ),
                    snapshot = snapshot,
                )

            val candidates = engine.complete(request("git switch ma"))

            assertEquals("maint", candidates.first().replacementText)
        }

    @Test
    fun `nonzero exits contribute usage without a ranking penalty`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "git switch main",
                                useCount = 10,
                                successCount = 10,
                                lastUsedEpochMillis = NOW,
                                replay = false,
                            ),
                            commandLearning(
                                commandLine = "git switch maint",
                                useCount = 10,
                                failureCount = 10,
                                lastUsedEpochMillis = NOW,
                                replay = false,
                            ),
                        ),
                )
            val candidates =
                engine(
                    sources =
                        listOf(
                            entry(
                                source(
                                    branchCandidate("maint", score = 100),
                                    branchCandidate("main", score = 1),
                                ),
                                15,
                            ),
                        ),
                    snapshot = snapshot,
                ).complete(request("git switch ma"))

            assertEquals("maint", candidates.first().replacementText)
        }

    @Test
    fun `one learned event cannot overwhelm a stronger semantic context`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "cd remembered",
                                acceptedCount = 1,
                                lastUsedEpochMillis = NOW,
                            ),
                        ),
                )
            val candidates =
                engine(
                    sources =
                        listOf(
                            entry(source(candidate("src/", 3, 3, "path", TerminalCompletionCandidateKind.PATH)), 12),
                        ),
                    snapshot = snapshot,
                    commandSpecs = TerminalCommandSpecs.defaults(),
                ).complete(request("cd "))

            assertEquals("src/", candidates.first().replacementText)
        }

    @Test
    fun `recent evidence receives a larger boost than old evidence`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(commandLine = "git switch main", useCount = 1, lastUsedEpochMillis = NOW),
                            commandLearning(
                                commandLine = "git switch maint",
                                useCount = 1,
                                lastUsedEpochMillis =
                                    NOW - FORTY_DAYS,
                            ),
                        ),
                )
            val engine =
                engine(
                    sources =
                        listOf(
                            entry(
                                source(
                                    candidate(
                                        "maint",
                                        11,
                                        13,
                                        "branches",
                                        TerminalCompletionCandidateKind.ARGUMENT,
                                        score = 100,
                                        domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                    ),
                                    candidate(
                                        "main",
                                        11,
                                        13,
                                        "branches",
                                        TerminalCompletionCandidateKind.ARGUMENT,
                                        score = 1,
                                        domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                    ),
                                ),
                                0,
                            ),
                        ),
                    snapshot = snapshot,
                )

            assertEquals("main", engine.complete(request("git switch ma")).first().replacementText)
        }

    @Test
    fun `case sensitive path values remain separate outcomes`() =
        runBlocking {
            val engine =
                engine(
                    listOf(
                        entry(source(candidate("Build/", 3, 3, "learned", TerminalCompletionCandidateKind.PATH)), 0),
                        entry(source(candidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH)), 0),
                    ),
                )

            val candidates = engine.complete(request("cd "))

            assertEquals(2, candidates.size)
            assertTrue(candidates.map { it.replacementText }.containsAll(listOf("Build/", "build/")))
        }

    @Test
    fun `trailing separators are not normalized for non-path values`() =
        runBlocking {
            val candidates =
                engine(
                    listOf(
                        entry(source(branchCandidate("feature", replacementEndOffset = 11)), 15),
                        entry(source(branchCandidate("feature/", replacementEndOffset = 11)), 15),
                    ),
                ).complete(request("git switch "))

            assertEquals(2, candidates.size)
        }

    @Test
    fun `different arguments and reordered options remain distinct outcomes`() =
        runBlocking {
            val commandLine = "t"
            val candidates =
                engine(
                    listOf(
                        entry(
                            source(
                                candidate("tool --alpha --beta one", 0, 1, "one", TerminalCompletionCandidateKind.COMMAND),
                                candidate("tool --alpha --beta two", 0, 1, "two", TerminalCompletionCandidateKind.COMMAND),
                                candidate("tool --beta --alpha one", 0, 1, "reordered", TerminalCompletionCandidateKind.COMMAND),
                            ),
                            0,
                        ),
                    ),
                ).complete(request(commandLine))

            assertEquals(3, candidates.size)
        }

    @Test
    fun `equivalent commands merge even when contributors replace different ranges`() =
        runBlocking {
            val commandLine = "git st"
            val candidates =
                engine(
                    listOf(
                        entry(
                            source(candidate("git status", 0, commandLine.length, "learned", TerminalCompletionCandidateKind.SUBCOMMAND)),
                            8,
                        ),
                        entry(
                            source(candidate("status", 4, commandLine.length, "spec", TerminalCompletionCandidateKind.SUBCOMMAND)),
                            0,
                        ),
                    ),
                ).complete(request(commandLine))

            assertEquals(1, candidates.size)
            assertEquals("git status", commandLineAfter(candidates.single(), commandLine))
        }

    @Test
    fun `outcomes before an operator are grouped by their own command segment`() =
        runBlocking {
            val commandLine = "git st && echo later"
            val request =
                TerminalCompletionRequest(
                    commandLine = commandLine,
                    cursorOffset = "git st".length,
                    shellCapabilities = TerminalShellCapabilities.POSIX,
                )
            val candidates =
                engine(
                    listOf(
                        entry(
                            source(
                                candidate("status", 4, 6, "status", TerminalCompletionCandidateKind.SUBCOMMAND),
                                candidate("log", 4, 6, "log", TerminalCompletionCandidateKind.SUBCOMMAND),
                            ),
                            0,
                        ),
                    ),
                ).complete(request)

            assertEquals(2, candidates.size)
            assertEquals(setOf("status", "log"), candidates.mapTo(mutableSetOf()) { it.replacementText })
        }

    @Test
    fun `invalid replacement ranges are not published`() =
        runBlocking {
            val malformed = candidate("status", 20, 30, "first", TerminalCompletionCandidateKind.SUBCOMMAND)
            val candidates =
                engine(
                    listOf(
                        entry(source(malformed), 0),
                        entry(source(malformed.copy(source = "second")), 0),
                        entry(source(malformed.copy(replacementStartOffset = 21, source = "third")), 0),
                    ),
                ).complete(request("git s"))

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `multiline projected commands use fallback grouping`() =
        runBlocking {
            val commandLine = "git s"
            val candidates =
                engine(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    "git status\ngit log",
                                    0,
                                    commandLine.length,
                                    "whole-line",
                                    TerminalCompletionCandidateKind.SUBCOMMAND,
                                ),
                            ),
                            0,
                        ),
                        entry(
                            source(
                                candidate(
                                    "status\ngit log",
                                    4,
                                    commandLine.length,
                                    "token",
                                    TerminalCompletionCandidateKind.SUBCOMMAND,
                                ),
                            ),
                            0,
                        ),
                    ),
                ).complete(request(commandLine))

            assertEquals(2, candidates.size)
            assertTrue(candidates.map { it.source }.containsAll(listOf("whole-line", "token")))
        }

    @Test
    fun `assignment-only projected commands use fallback grouping`() =
        runBlocking {
            val commandLine = "FOO=F"
            val candidates =
                engine(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    "FOO=bar",
                                    0,
                                    commandLine.length,
                                    "whole-line",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            0,
                        ),
                        entry(
                            source(
                                candidate(
                                    "bar",
                                    4,
                                    commandLine.length,
                                    "token",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            0,
                        ),
                    ),
                ).complete(request(commandLine))

            assertEquals(2, candidates.size)
            assertTrue(candidates.map { it.source }.containsAll(listOf("whole-line", "token")))
        }

    @Test
    fun `maximum counters future timestamps and extreme priorities remain bounded`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "git switch main",
                                useCount = Int.MAX_VALUE,
                                successCount = Int.MAX_VALUE,
                                acceptedCount = Int.MAX_VALUE,
                                dismissedCount = Int.MAX_VALUE,
                                lastUsedEpochMillis = Long.MAX_VALUE,
                            ),
                        ),
                )
            val candidates =
                engine(
                    sources =
                        listOf(
                            entry(source(branchCandidate("main", score = Int.MAX_VALUE)), Int.MAX_VALUE),
                            entry(source(branchCandidate("maint", score = Int.MIN_VALUE)), Int.MIN_VALUE),
                        ),
                    snapshot = snapshot,
                ).complete(request("git switch ma"))

            assertEquals(2, candidates.size)
            assertEquals(candidates.map { it.score }.sortedDescending(), candidates.map { it.score })
        }

    private fun engine(
        sources: List<TerminalCompletionSourceEntry>,
        snapshot: TerminalCompletionLearningSnapshot = TerminalCompletionLearningSnapshot.EMPTY,
        commandSpecs: List<TerminalCommandSpec> = emptyList(),
    ): TerminalCompletionEngine =
        MergedCompletionEngine(
            sources = sources,
            commandSpecs = commandSpecs,
            learningStore = TerminalCompletionLearningStore().apply { mergeSnapshot(snapshot) },
            clockEpochMillis = { NOW },
        )

    private fun entry(
        source: TerminalCompletionSource,
        priority: Int,
    ) = TerminalCompletionSourceEntry(source, priority)

    private fun source(vararg candidates: TerminalCompletionCandidate): TerminalCompletionSource =
        TerminalCompletionSource { _, _, _ -> candidates.toList() }

    private fun candidate(
        replacement: String,
        start: Int,
        end: Int,
        source: String,
        kind: TerminalCompletionCandidateKind,
        score: Int = 0,
        domain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
    ) = TerminalCompletionCandidate(
        replacementText = replacement,
        replacementStartOffset = start,
        replacementEndOffset = end,
        source = source,
        kind = kind,
        score = score,
        valueDomain = domain,
    )

    private fun branchCandidate(
        replacement: String,
        score: Int = 0,
        replacementEndOffset: Int = 13,
    ): TerminalCompletionCandidate =
        candidate(
            replacement = replacement,
            start = 11,
            end = replacementEndOffset,
            source = "branches",
            kind = TerminalCompletionCandidateKind.ARGUMENT,
            score = score,
            domain = TerminalCompletionValueDomain.GIT_BRANCH,
        )

    private fun commandLineAfter(
        candidate: TerminalCompletionCandidate,
        commandLine: String,
    ): String =
        commandLine.substring(0, candidate.replacementStartOffset) +
            candidate.replacementText +
            commandLine.substring(candidate.replacementEndOffset)

    private fun request(
        commandLine: String,
        workingDirectoryUri: String = "file:///repo",
    ) = TerminalCompletionRequest(
        commandLine = commandLine,
        cursorOffset = commandLine.length,
        profileId = "profile",
        workingDirectoryUri = workingDirectoryUri,
        shellCapabilities = TerminalShellCapabilities.POSIX,
    )

    private companion object {
        private const val NOW = 2_000_000_000_000L
        private const val FORTY_DAYS = 40L * 24L * 60L * 60L * 1_000L
    }
}

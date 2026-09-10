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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.resolveCompletionContext
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.testing.TestCommandLearning
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TerminalCompletionLearningStoreTest {
    @Test
    fun `complete snapshot identity remains stable until mutation`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()

            val before = source.snapshot()
            assertSame(before, source.snapshot())

            source.recordCommandResult("git status", true, null, null, 1)

            val after = source.snapshot()
            assertNotSame(before, after)
            assertSame(after, source.snapshot())
        }

    @Test
    fun `derived learning indexes are cached for a stable snapshot and syntax`() {
        val source = TerminalCompletionLearningStore()
        source.recordCommandResult("git status", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 1)

        val first = source.indexesFor(TerminalShellSyntax.POSIX)
        val second = source.indexesFor(TerminalShellSyntax.POSIX)

        assertSame(first, second)
        assertNotSame(first, source.indexesFor(TerminalShellSyntax.POWERSHELL))

        source.recordCommandResult("git log", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 2)

        assertNotSame(first, source.indexesFor(TerminalShellSyntax.POSIX))
    }

    @Test
    fun `successful command result supplies token-local learned history evidence`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                commandLine = "tool status",
                successful = true,
                profileId = "pwsh",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 1_000,
            )

            val candidates =
                learnedHistory(source).complete(request("tool s", profileId = "pwsh", workingDirectoryUri = "file:///repo"))

            assertEquals(listOf("status"), candidates.map { it.replacementText })
            assertEquals("learned", candidates.single().source)
            assertEquals(TerminalCompletionCandidateKind.ARGUMENT, candidates.single().kind)
            assertEquals(5, candidates.single().replacementStartOffset)
            assertEquals(6, candidates.single().replacementEndOffset)
        }

    @Test
    fun `credential commands update opaque ranking without entering plaintext indexes`() {
        val source = TerminalCompletionLearningStore()
        val sensitiveCommands =
            listOf(
                "curl -u alice:s3cr3t https://example.test",
                "mysql -p hunter2",
                "docker login -u alice -p hunter2",
                "redis-cli -a hunter2",
                "sshpass -p hunter2 ssh host",
            )

        sensitiveCommands.forEachIndexed { index, command ->
            assertTrue(
                source.recordCommandResult(
                    commandLine = command,
                    successful = true,
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    usedAtEpochMillis = index + 1L,
                ),
            )
        }

        val snapshot = source.snapshot()
        assertEquals(sensitiveCommands.size, snapshot.rankingStats.size)
        assertTrue(snapshot.rankingStats.all { it.useCount == 1 && it.successCount == 1 })
        assertTrue(snapshot.replayCommands.isEmpty())
        assertFalse("s3cr3t" in snapshot.toString())
        assertFalse("hunter2" in snapshot.toString())

        val indexes = source.indexesFor(TerminalShellSyntax.POSIX)
        val historyLine = TerminalCommandLineTokenizer.parse("curl ", 5, TerminalShellSyntax.POSIX)
        assertTrue(indexes.history.matching(historyLine, CompletionLearningContextKey.of(null, null)).isEmpty())

        val observedRequest = request("curl ", shellCapabilities = TerminalShellCapabilities.POSIX)
        val observedCandidates = mutableListOf<TerminalCompletionCandidate>()
        indexes.observed.appendCandidates(
            observedRequest,
            observedRequest.resolveCompletionContext(emptyList()),
            observedCandidates,
        )
        assertTrue(observedCandidates.isEmpty())
    }

    @Test
    fun `controls and oversized commands update only ranking while malformed text is ignored`() {
        val source = TerminalCompletionLearningStore()
        val commands =
            listOf(
                "git\u0000status",
                "git\u007fstatus",
                "x".repeat(4_097),
                "界".repeat(2_731),
            )

        assertFalse(source.recordCommandResult("git\uD800status", true, null, null, 1L))
        assertFalse(source.recordCommandResult("git\uDC00status", true, null, null, 1L))

        commands.forEachIndexed { index, command ->
            assertTrue(source.recordCommandResult(command, true, null, null, index + 1L))
        }

        val snapshot = source.snapshot()
        assertEquals(commands.size, snapshot.rankingStats.size)
        assertTrue(snapshot.rankingStats.all { it.successCount == 1 })
        assertTrue(snapshot.replayCommands.isEmpty())
    }

    @Test
    fun `exact identity and replay preserve trailing whitespace`() {
        val source = TerminalCompletionLearningStore()
        source.recordCommandResult("git status", true, null, null, 1L)
        source.recordCommandResult("git status ", true, null, null, 2L)

        val snapshot = source.snapshot()
        assertEquals(2, snapshot.rankingStats.size)
        assertEquals(listOf("git status ", "git status"), snapshot.replayCommands.map { it.commandLine })
    }

    @Test
    fun `records case-distinct exact commands independently`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()

            source.recordCommandResult(
                "Git Status",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = null,
                usedAtEpochMillis = 10,
            )
            source.recordCommandResult(
                "git status",
                successful = false,
                profileId = "bash",
                workingDirectoryUri = null,
                usedAtEpochMillis = 20,
            )

            assertEquals(
                learningSnapshot(
                    commandLearning(
                        commandLine = "Git Status",
                        profileId = "bash",
                        workingDirectoryUri = null,
                        useCount = 1,
                        successCount = 1,
                        acceptedCount = 0,
                        dismissedCount = 0,
                        lastUsedEpochMillis = 10,
                    ),
                    commandLearning(
                        commandLine = "git status",
                        profileId = "bash",
                        workingDirectoryUri = null,
                        useCount = 1,
                        successCount = 0,
                        failureCount = 1,
                        acceptedCount = 0,
                        dismissedCount = 0,
                        lastUsedEpochMillis = 20,
                        replay = false,
                    ),
                ),
                source.snapshot(),
            )
        }

    @Test
    fun `accepted feedback affects ranking but only later success creates replay`() {
        val source = TerminalCompletionLearningStore()

        source.recordSuggestionFeedback(
            commandLine = "git status",
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            feedbackAtEpochMillis = 10,
        )

        val accepted = source.snapshot()
        assertTrue(accepted.replayCommands.isEmpty())
        assertEquals(1, accepted.rankingStats.single().acceptedCount)

        source.recordCommandResult(
            commandLine = "git status",
            successful = true,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
            usedAtEpochMillis = 20,
        )

        val executed = source.snapshot()
        assertEquals(listOf("git status"), executed.replayCommands.map { it.commandLine })
        assertEquals(1, executed.rankingStats.single().acceptedCount)
        assertEquals(1, executed.rankingStats.single().useCount)
        assertEquals(1, executed.rankingStats.single().successCount)
    }

    @Test
    fun `accepted feedback boosts candidate above dismissed candidate`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "tool status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )
            source.recordCommandResult(
                "tool switch main",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )

            repeat(4) {
                source.recordSuggestionFeedback(
                    commandLine = "tool switch main",
                    feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                    profileId = null,
                    workingDirectoryUri = null,
                    feedbackAtEpochMillis = 200 + it.toLong(),
                )
            }
            repeat(4) {
                source.recordSuggestionFeedback(
                    commandLine = "tool status",
                    feedback = TerminalCompletionFeedbackKind.DISMISSED,
                    profileId = null,
                    workingDirectoryUri = null,
                    feedbackAtEpochMillis = 200 + it.toLong(),
                )
            }

            val candidates = learnedHistory(source).complete(request("tool s"))

            assertEquals(listOf("switch main", "status"), candidates.map { it.replacementText })
            assertTrue(candidates[0].score > candidates[1].score)
        }

    @Test
    fun `exact command ranking caps learned counter contribution`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.mergeSnapshot(
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "tool alpha",
                                useCount = 50,
                                successCount = 50,
                                acceptedCount = 50,
                                lastUsedEpochMillis = 60_000,
                            ),
                            commandLearning(
                                commandLine = "tool beta",
                                useCount = 500,
                                successCount = 500,
                                acceptedCount = 500,
                                lastUsedEpochMillis = 60_000,
                            ),
                        ),
                ),
            )

            val candidates = learnedHistory(source).complete(request("tool "))

            assertEquals(listOf("alpha", "beta"), candidates.map { it.replacementText })
        }

    @Test
    fun `learned history requires exact canonical profile and directory context`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "tool scoped",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 100,
            )
            source.recordCommandResult(
                "tool unknown",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )

            assertEquals(
                listOf("scoped"),
                learnedHistory(source)
                    .complete(request("tool ", profileId = "bash", workingDirectoryUri = "file:///repo/"))
                    .map { it.replacementText },
            )
            assertTrue(
                learnedHistory(source)
                    .complete(request("tool ", profileId = "pwsh", workingDirectoryUri = "file:///repo"))
                    .isEmpty(),
            )
            assertTrue(
                learnedHistory(source)
                    .complete(request("tool ", profileId = "bash", workingDirectoryUri = "file:///other"))
                    .isEmpty(),
            )
            assertTrue(learnedHistory(source).complete(request("tool ", profileId = "bash")).isEmpty())
            assertEquals(
                listOf("unknown"),
                learnedHistory(source).complete(request("tool ")).map { it.replacementText },
            )
        }

    @Test
    fun `exact command prefix is not suggested`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )

            assertTrue(learnedHistory(source).complete(request("git status")).isEmpty())
        }

    @Test
    fun `does not replace a chained command segment with whole-line history`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )

            val candidates =
                learnedHistory(source).complete(
                    request(
                        "echo ready && git s",
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    ),
                )

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `failure only and dismissed only rows retain no replay plaintext`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.recordCommandResult(
                "git status",
                successful = false,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )
            source.recordSuggestionFeedback(
                commandLine = "git switch main",
                feedback = TerminalCompletionFeedbackKind.DISMISSED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = 200,
            )

            assertEquals(2, source.snapshot().rankingStats.size)
            assertTrue(source.snapshot().replayCommands.isEmpty())
            assertTrue(learnedHistory(source).complete(request("git s")).isEmpty())
        }

    @Test
    fun `blank multiline and negative timestamp events are ignored`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            val before = source.snapshot()

            source.recordCommandResult(
                "   ",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )
            source.recordCommandResult(
                "git status\ngit log",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 100,
            )
            source.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = -1,
            )
            source.mergeSnapshot(TerminalCompletionLearningSnapshot.EMPTY)

            assertTrue(source.snapshot().rankingStats.isEmpty())
            assertTrue(source.snapshot().replayCommands.isEmpty())
            assertSame(before, source.snapshot())
        }

    @Test
    fun `capacity keeps most relevant recent records`() =
        runBlocking {
            val source = TerminalCompletionLearningStore(capacity = 2)

            source.recordCommandResult(
                "one",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1,
            )
            source.recordCommandResult(
                "two",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 2,
            )
            source.recordCommandResult(
                "three",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 3,
            )

            assertEquals(listOf("three", "two"), source.snapshot().replayCommands.map { it.commandLine })
            val retained = source.snapshot()

            val changed =
                source.recordCommandResult(
                    "obsolete",
                    successful = true,
                    profileId = null,
                    workingDirectoryUri = null,
                    usedAtEpochMillis = 0,
                )

            assertFalse(changed)
            assertSame(retained, source.snapshot())
        }

    @Test
    fun `merge snapshot preserves command case while canonicalizing context`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()

            source.mergeSnapshot(
                learningSnapshot(
                    rows =
                        listOf(
                            stats(
                                "Git Status",
                                profileId = "bash",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 10,
                            ),
                            stats(
                                "git status",
                                profileId = "bash",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 20,
                            ),
                            stats(
                                "git status",
                                profileId = "pwsh",
                                workingDirectoryUri = "file:///repo",
                                lastUsedEpochMillis = 5,
                            ),
                        ),
                ),
            )

            assertEquals(
                learningSnapshot(
                    stats("git status", profileId = "bash", workingDirectoryUri = "file:///repo/", lastUsedEpochMillis = 20),
                    stats("Git Status", profileId = "bash", workingDirectoryUri = "file:///repo/", lastUsedEpochMillis = 10),
                    stats("git status", profileId = "pwsh", workingDirectoryUri = "file:///repo/", lastUsedEpochMillis = 5),
                ),
                source.snapshot(),
            )
        }

    @Test
    fun `constructor creates bounded command stats store`() =
        runBlocking {
            val source = TerminalCompletionLearningStore(capacity = 1)

            source.recordCommandResult(
                "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1,
            )

            assertEquals(listOf("git status"), source.snapshot().replayCommands.map { it.commandLine })
        }

    @Test
    fun `recorded counters saturate at integer maximum`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            source.mergeSnapshot(
                learningSnapshot(
                    rows =
                        listOf(
                            commandLearning(
                                commandLine = "git status",
                                useCount = Int.MAX_VALUE,
                                successCount = Int.MAX_VALUE,
                                acceptedCount = Int.MAX_VALUE,
                                lastUsedEpochMillis = 10,
                            ),
                        ),
                ),
            )

            source.recordCommandResult(
                commandLine = "git status",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 20,
            )
            source.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = 30,
            )

            val stats = source.snapshot().rankingStats.single()
            assertEquals(Int.MAX_VALUE, stats.useCount)
            assertEquals(Int.MAX_VALUE, stats.successCount)
            assertEquals(Int.MAX_VALUE, stats.acceptedCount)
        }

    private fun request(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            shellCapabilities = shellCapabilities,
        )

    private fun learnedHistory(source: TerminalCompletionLearningStore): TerminalCompletionEngine =
        TerminalCompletionEngines.fromSources(
            sources = emptyList(),
            commandSpecs = listOf(TerminalCommandSpec(name = "tool")),
            learningStore = source,
        )

    private fun stats(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        lastUsedEpochMillis: Long,
    ): TestCommandLearning =
        commandLearning(
            commandLine = commandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = 1,
            successCount = 1,
            acceptedCount = 0,
            dismissedCount = 0,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )

    @Test
    fun `concurrent synchronized mutations do not lose commands`() {
        val source = TerminalCompletionLearningStore(capacity = 128)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writers =
                listOf("left", "right").map { prefix ->
                    executor.submit {
                        start.await()
                        repeat(32) { index ->
                            source.recordCommandResult(
                                "$prefix-$index",
                                successful = true,
                                profileId = null,
                                workingDirectoryUri = null,
                                usedAtEpochMillis = index.toLong(),
                            )
                        }
                    }
                }

            start.countDown()
            writers.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals(64, source.snapshot().rankingStats.size)
        } finally {
            executor.shutdownNow()
        }
    }
}

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
package io.github.ketraterm.benchmark

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures pure static completion through the full POSIX segment-aware engine path. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
open class TerminalCompletionBenchmark {
    private val commandSpecs = TerminalCommandSpecs.defaults()
    private val engine =
        TerminalCompletionEngines.fromSources(
            sources = emptyList(),
            commandSpecs = commandSpecs,
        )

    @Param("64", "512", "4096", "32768")
    var commandLineLength: Int = 0

    private lateinit var chainedRequest: TerminalCompletionRequest
    private lateinit var unclosedQuoteRequest: TerminalCompletionRequest
    private lateinit var fusionRequest: TerminalCompletionRequest
    private lateinit var realisticSources: List<TerminalCompletionSourceEntry>
    private lateinit var learnedSnapshot: TerminalCompletionLearningSnapshot
    private lateinit var coldStartFusionEngine: TerminalCompletionEngine
    private lateinit var learnedFusionEngine: TerminalCompletionEngine
    private lateinit var indexedLearnedHistoryEngine: TerminalCompletionEngine
    private lateinit var duplicateFusionEngine: TerminalCompletionEngine
    private lateinit var hostileFusionEngine: TerminalCompletionEngine
    private lateinit var fuzzyPathEngine: TerminalCompletionEngine
    private lateinit var fuzzyPathRequest: TerminalCompletionRequest

    @Setup(Level.Trial)
    open fun setUp() {
        chainedRequest = requestFor("git status && ", "git sw")
        unclosedQuoteRequest = requestFor("git status && cd ", "\"Idea Pro")
        fusionRequest =
            TerminalCompletionRequest(
                commandLine = "git switch ma",
                cursorOffset = "git switch ma".length,
                profileId = "benchmark",
                workingDirectoryUri = "file:///repo",
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )
        realisticSources = List(8) { sourceIndex -> sourceEntry(sourceIndex, 32, duplicateMain = true) }
        coldStartFusionEngine = TerminalCompletionEngines.fromSources(realisticSources, commandSpecs)
        val learnedSeed = TerminalCompletionLearningStore(capacity = 2_048)
        repeat(2_048) { index ->
            val commandLine = if (index == 0) "git switch main" else "git switch branch-$index"
            val eventTime = 2_000_000_000_000L - index
            val executionCount = (index % 20) + 1
            val successCount = if (index == 0) executionCount else minOf(executionCount, index % 17)
            repeat(executionCount) { eventIndex ->
                learnedSeed.recordCommandResult(
                    commandLine = commandLine,
                    successful = eventIndex < successCount,
                    profileId = "benchmark",
                    workingDirectoryUri = "file:///repo",
                    usedAtEpochMillis = eventTime,
                )
            }
            val acceptedCount = if (index == 0) 4 else index % 7
            repeat(acceptedCount) {
                learnedSeed.recordSuggestionFeedback(
                    commandLine = commandLine,
                    feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                    profileId = "benchmark",
                    workingDirectoryUri = "file:///repo",
                    feedbackAtEpochMillis = eventTime,
                )
            }
        }
        learnedSnapshot = learnedSeed.snapshot()
        learnedFusionEngine =
            TerminalCompletionEngines.fromSources(
                realisticSources,
                commandSpecs,
                learningStore = TerminalCompletionLearningStore().apply { mergeSnapshot(learnedSnapshot) },
            )
        val persistedStatsSource = TerminalCompletionLearningStore(capacity = 2_048)
        persistedStatsSource.mergeSnapshot(learnedSnapshot)
        indexedLearnedHistoryEngine =
            TerminalCompletionEngines.fromSources(
                sources = emptyList(),
                commandSpecs = commandSpecs,
                learningStore = persistedStatsSource,
            )
        runBlocking { indexedLearnedHistoryEngine.completions(fusionRequest).last() }
        duplicateFusionEngine = TerminalCompletionEngines.fromSources(List(8) { sourceEntry(it, 32, duplicateMain = true) }, commandSpecs)
        hostileFusionEngine = TerminalCompletionEngines.fromSources(List(10) { sourceEntry(it, 256, duplicateMain = false) }, commandSpecs)
        val fuzzyPathMatches =
            List(FUZZY_PATH_MATCH_COUNT) { index ->
                TerminalFuzzyPathEntry(
                    path = "src/module-${index and 63}/GenF327Match$index.kt",
                    isDirectory = false,
                )
            }
        fuzzyPathEngine =
            TerminalCompletionEngines.fromSources(
                sources =
                    listOf(
                        TerminalCompletionSourceEntry(
                            TerminalCompletionSources.fuzzyPath(
                                sourceId = "benchmark-project-path",
                                entriesProvider = { _, _ -> fuzzyPathMatches },
                            ),
                        ),
                    ),
                commandSpecs = commandSpecs,
            )
        fuzzyPathRequest =
            TerminalCompletionRequest(
                commandLine = "git add GenF327",
                cursorOffset = "git add GenF327".length,
                profileId = "benchmark",
                workingDirectoryUri = "file:///repo",
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )
    }

    @Benchmark
    open fun completeChainedCommand(blackhole: Blackhole) {
        blackhole.consume(runBlocking { engine.completions(chainedRequest).last() })
    }

    @Benchmark
    open fun completeUnclosedQuote(blackhole: Blackhole) {
        blackhole.consume(runBlocking { engine.completions(unclosedQuoteRequest).last() })
    }

    @Benchmark
    open fun completeColdStartFusion(blackhole: Blackhole) {
        blackhole.consume(runBlocking { coldStartFusionEngine.completions(fusionRequest).last() })
    }

    @Benchmark
    open fun completeLearnedFusion(blackhole: Blackhole) {
        blackhole.consume(runBlocking { learnedFusionEngine.completions(fusionRequest).last() })
    }

    /** Measures cold construction of the full 2,048-row learned view and its indexes, then one ranked completion. */
    @Benchmark
    open fun buildLearnedIndexAndComplete(blackhole: Blackhole) {
        val coldEngine =
            TerminalCompletionEngines.fromSources(
                sources = realisticSources,
                commandSpecs = commandSpecs,
                learningStore = TerminalCompletionLearningStore().apply { mergeSnapshot(learnedSnapshot) },
            )
        blackhole.consume(runBlocking { coldEngine.completions(fusionRequest).last() })
    }

    /** Measures hot indexed lookup across a full 2,048-row learned snapshot. */
    @Benchmark
    open fun completeIndexedLearnedHistory(blackhole: Blackhole) {
        blackhole.consume(runBlocking { indexedLearnedHistoryEngine.completions(fusionRequest).last() })
    }

    @Benchmark
    open fun completeDuplicateHeavyFusion(blackhole: Blackhole) {
        blackhole.consume(runBlocking { duplicateFusionEngine.completions(fusionRequest).last() })
    }

    @Benchmark
    open fun completeHostileFusion(blackhole: Blackhole) {
        blackhole.consume(runBlocking { hostileFusionEngine.completions(fusionRequest).last() })
    }

    /** Measures allocation and throughput while scanning a realistic immutable project-path snapshot. */
    @Benchmark
    open fun completeFuzzyProjectPath(blackhole: Blackhole) {
        blackhole.consume(runBlocking { fuzzyPathEngine.completions(fuzzyPathRequest).last() })
    }

    private fun sourceEntry(
        sourceIndex: Int,
        count: Int,
        duplicateMain: Boolean,
    ): TerminalCompletionSourceEntry =
        TerminalCompletionSourceEntry(
            source =
                TerminalCompletionSource { _, _, _ ->
                    List(count) { candidateIndex ->
                        val value = if (duplicateMain && candidateIndex == 0) "main" else "match-$sourceIndex-$candidateIndex"
                        TerminalCompletionCandidate(
                            replacementText = value,
                            replacementStartOffset = 11,
                            replacementEndOffset = 13,
                            source = "benchmark-$sourceIndex",
                            kind = TerminalCompletionCandidateKind.ARGUMENT,
                            score = count - candidateIndex,
                            valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        )
                    }
                },
            priority = sourceIndex.coerceAtMost(15),
        )

    private fun requestFor(
        prefix: String,
        suffix: String,
    ): TerminalCompletionRequest {
        val paddingUnit = "echo x && "
        val paddingLength = (commandLineLength - prefix.length - suffix.length).coerceAtLeast(0)
        val padding =
            buildString(paddingLength) {
                while (length + paddingUnit.length <= paddingLength) append(paddingUnit)
                while (length < paddingLength) append(' ')
            }
        val commandLine = prefix + padding + suffix
        return TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )
    }

    private companion object {
        private const val FUZZY_PATH_MATCH_COUNT = 8_192
    }
}

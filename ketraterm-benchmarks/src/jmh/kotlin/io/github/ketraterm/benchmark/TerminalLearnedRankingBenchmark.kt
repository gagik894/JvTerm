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
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Measures learned-ranking lookup and bounded multi-provider merge paths. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
open class TerminalLearnedRankingBenchmark {
    private lateinit var learnedEngine: TerminalCompletionEngine
    private lateinit var hostileMergeEngine: TerminalCompletionEngine
    private lateinit var learnedRequest: TerminalCompletionRequest
    private lateinit var hostileMergeRequest: TerminalCompletionRequest
    private lateinit var statsSource: TerminalCompletionLearningStore
    private lateinit var mutationStatsSource: TerminalCompletionLearningStore
    private lateinit var mutationEngine: TerminalCompletionEngine
    private var mutationEpochMillis = STATS_CAPACITY.toLong()

    @Setup
    open fun setUp() {
        mutationEpochMillis = STATS_CAPACITY.toLong()
        val commandSpecs = TerminalCommandSpecs.defaults()
        val fullSnapshot = fullLearnedSnapshot()
        statsSource = TerminalCompletionLearningStore(capacity = STATS_CAPACITY)
        statsSource.mergeSnapshot(fullSnapshot)
        learnedEngine =
            TerminalCompletionEngines.fromSources(
                sources = emptyList(),
                commandSpecs = commandSpecs,
                learningStore = statsSource,
            )

        mutationStatsSource = TerminalCompletionLearningStore(capacity = STATS_CAPACITY)
        mutationStatsSource.mergeSnapshot(fullSnapshot)
        mutationEngine =
            TerminalCompletionEngines.fromSources(
                sources = emptyList(),
                commandSpecs = commandSpecs,
                learningStore = mutationStatsSource,
            )
        learnedRequest =
            TerminalCompletionRequest(
                commandLine = "git s",
                cursorOffset = 5,
                profileId = TARGET_PROFILE,
                workingDirectoryUri = TARGET_DIRECTORY,
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )

        hostileMergeEngine =
            TerminalCompletionEngines.fromSources(
                sources =
                    List(PROVIDER_COUNT) { providerIndex ->
                        TerminalCompletionSourceEntry(
                            source = fixedProvider(providerIndex),
                            priority = providerIndex,
                        )
                    },
                commandSpecs = commandSpecs,
            )
        hostileMergeRequest =
            TerminalCompletionRequest(
                commandLine = "unknown value",
                cursorOffset = "unknown value".length,
            )
    }

    @Benchmark
    open fun completeWithFullLearnedSnapshots(blackhole: Blackhole) {
        blackhole.consume(runBlocking { learnedEngine.completions(learnedRequest).last() })
    }

    @Benchmark
    open fun mergeHostileProviderResultsIntoTopEight(blackhole: Blackhole) {
        blackhole.consume(runBlocking { hostileMergeEngine.completions(hostileMergeRequest).last() })
    }

    @Benchmark
    open fun readPublishedLearnedSnapshots(blackhole: Blackhole) {
        blackhole.consume(statsSource.snapshot())
    }

    /** Measures one mutation followed by the first request that compiles its new full learned view. */
    @Benchmark
    open fun mutateThenCompleteAgainstFullLearnedSnapshot(blackhole: Blackhole) {
        mutationEpochMillis++
        mutationStatsSource.recordCommandResult(
            commandLine = "git status",
            successful = true,
            profileId = TARGET_PROFILE,
            workingDirectoryUri = TARGET_DIRECTORY,
            usedAtEpochMillis = mutationEpochMillis,
        )
        blackhole.consume(runBlocking { mutationEngine.completions(learnedRequest).last() })
    }

    private fun fullLearnedSnapshot(): TerminalCompletionLearningSnapshot {
        val learningStore = TerminalCompletionLearningStore(capacity = STATS_CAPACITY)
        var index = 0
        while (index < STATS_CAPACITY - 1) {
            learningStore.recordCommandResult(
                commandLine = "tool-$index command",
                successful = true,
                profileId = "profile-$index",
                workingDirectoryUri = "file:///workspace/$index",
                usedAtEpochMillis = index.toLong(),
            )
            index++
        }
        repeat(8) {
            learningStore.recordCommandResult(
                commandLine = "git status",
                successful = true,
                profileId = TARGET_PROFILE,
                workingDirectoryUri = TARGET_DIRECTORY,
                usedAtEpochMillis = STATS_CAPACITY.toLong(),
            )
            learningStore.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = TARGET_PROFILE,
                workingDirectoryUri = TARGET_DIRECTORY,
                feedbackAtEpochMillis = STATS_CAPACITY.toLong(),
            )
        }
        return learningStore.snapshot()
    }

    private fun fixedProvider(providerIndex: Int): TerminalCompletionSource {
        val candidates =
            List(CANDIDATES_PER_PROVIDER) { candidateIndex ->
                TerminalCompletionCandidate(
                    replacementText = "value-$providerIndex-$candidateIndex",
                    replacementStartOffset = 8,
                    replacementEndOffset = 13,
                    displayText = "candidate-${CANDIDATES_PER_PROVIDER - candidateIndex}",
                    source = "provider-$providerIndex",
                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                    score = (candidateIndex * 37) % 101,
                )
            }
        return TerminalCompletionSource { _, _, _ -> candidates }
    }

    private companion object {
        private const val STATS_CAPACITY = 2_048
        private const val PROVIDER_COUNT = 4
        private const val CANDIDATES_PER_PROVIDER = 256
        private const val TARGET_PROFILE = "benchmark-profile"
        private const val TARGET_DIRECTORY = "file:///benchmark"
    }
}

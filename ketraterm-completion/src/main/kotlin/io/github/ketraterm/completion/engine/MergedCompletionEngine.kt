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
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.TerminalCompletionContextResolver
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.internal.boundedTo
import io.github.ketraterm.completion.internal.hasValidReplacementRangeFor
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.ranking.CompletionSourceCandidates
import io.github.ketraterm.completion.ranking.GlobalCompletionRanker
import io.github.ketraterm.completion.source.appendLearnedHistoryCandidates
import io.github.ketraterm.completion.spec.PathCommandSpecCandidateProjector
import io.github.ketraterm.completion.spec.SpecCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** Coordinates bounded source collection and delegates deterministic fusion to [GlobalCompletionRanker]. */
internal class MergedCompletionEngine(
    sources: List<TerminalCompletionSourceEntry>,
    private val commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val learningStore: TerminalCompletionLearningStore? = null,
    private val clockEpochMillis: () -> Long = System::currentTimeMillis,
    private val sourceFailureHandler: TerminalCompletionSourceFailureHandler =
        TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER,
) : TerminalCompletionEngine {
    private val specSource = commandSpecs.takeIf { it.isNotEmpty() }?.let(::SpecCompletionSource)
    private val sources = sources.toList()
    private val hostSourceIndexOffset = if (specSource == null) 0 else 1
    private val learnedSourceIndex = hostSourceIndexOffset + sources.size
    private val ranker = GlobalCompletionRanker()
    private val pathCommandSpecCandidateProjector = PathCommandSpecCandidateProjector(this.commandSpecs)

    override fun completions(request: TerminalCompletionRequest): Flow<List<TerminalCompletionCandidate>> =
        channelFlow {
            if (specSource == null && sources.isEmpty() && learningStore == null) {
                send(emptyList())
                return@channelFlow
            }

            val commandLineContext =
                TerminalCommandLineTokenizer.parse(
                    request.commandLine,
                    request.cursorOffset,
                    request.shellCapabilities.syntax,
                )
            val completionContext =
                TerminalCompletionContextResolver.resolve(
                    commandLine = request.commandLine,
                    lineContext = commandLineContext,
                    commandSpecs = commandSpecs,
                )
            if (completionContext.activePosition == TerminalCompletionActivePosition.OPERATOR) {
                send(emptyList())
                return@channelFlow
            }
            val learningIndexes = learningStore?.indexesFor(request.shellCapabilities.syntax)
            val nowEpochMillis = clockEpochMillis().coerceAtLeast(0L)
            val rankingState =
                ranker.createRequestState(
                    request = request,
                    context = completionContext,
                    resultLimit = REQUEST_CANDIDATE_LIMIT,
                    learnedIndex = learningIndexes?.evidence,
                    nowEpochMillis = nowEpochMillis,
                )
            var lastPublished: List<TerminalCompletionCandidate>? = null

            learningIndexes
                ?.takeUnless { completionContext.commandLineContext.precededByOperator }
                ?.let { indexes ->
                    val learnedCandidates =
                        ArrayList<TerminalCompletionCandidate>()
                            .apply {
                                appendLearnedHistoryCandidates(
                                    request = request,
                                    lineContext = completionContext.commandLineContext,
                                    completionContext = completionContext,
                                    index = indexes.history,
                                    nowEpochMillis = nowEpochMillis,
                                    destination = this,
                                )
                                indexes.observed.appendCandidates(
                                    request = request,
                                    context = completionContext,
                                    destination = this,
                                )
                                sortWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
                            }.let { candidates ->
                                pathCommandSpecCandidateProjector
                                    .project(request, completionContext, candidates)
                                    .filter { it.hasValidReplacementRangeFor(request) }
                                    .boundedTo(SOURCE_CANDIDATE_LIMIT)
                            }
                    if (learnedCandidates.isNotEmpty()) {
                        rankingState.ingest(
                            CompletionSourceCandidates(
                                sourceIndex = learnedSourceIndex,
                                priority = LEARNED_PRIORITY,
                                isFallback = true,
                                candidates = learnedCandidates,
                            ),
                        )
                    }
                }

            specSource?.let { source ->
                val candidates = completeSource(source, request, completionContext)
                if (candidates.isNotEmpty()) {
                    rankingState.ingest(
                        CompletionSourceCandidates(
                            sourceIndex = 0,
                            priority = STATIC_SPECIFICATION_PRIORITY,
                            candidates = candidates,
                        ),
                    )
                }
            }

            // Immediately emit the initial synchronous ranking from specs and learned data.
            val initialRanked = rankingState.rankedCandidates()
            if (initialRanked.isNotEmpty() || sources.isEmpty()) {
                lastPublished = initialRanked
                send(initialRanked)
            }

            if (sources.isEmpty()) {
                return@channelFlow
            }

            val completions = Channel<SourceCompletion>(sources.size)
            try {
                supervisorScope {
                    for (localSourceIndex in sources.indices) {
                        val entry = sources[localSourceIndex]
                        val sourceIndex = localSourceIndex + hostSourceIndexOffset
                        launch {
                            val candidates =
                                try {
                                    completeSource(
                                        source = entry.source,
                                        request = request,
                                        context = completionContext,
                                    )
                                } catch (cancellation: CancellationException) {
                                    if (!coroutineContext.isActive) throw cancellation
                                    emptyList()
                                } catch (failure: Exception) {
                                    reportSourceFailure(sourceIndex, entry, failure)
                                    emptyList()
                                }
                            completions.send(
                                SourceCompletion(
                                    sourceIndex = sourceIndex,
                                    priority = entry.priority,
                                    candidates = candidates,
                                ),
                            )
                        }
                    }

                    repeat(sources.size) {
                        val completed = completions.receive()
                        if (completed.candidates.isNotEmpty()) {
                            rankingState.ingest(
                                CompletionSourceCandidates(
                                    sourceIndex = completed.sourceIndex,
                                    priority = completed.priority,
                                    candidates = completed.candidates,
                                ),
                            )
                        }
                        val ranked = rankingState.rankedCandidates()
                        if (ranked != lastPublished) {
                            lastPublished = ranked
                            send(ranked)
                        }
                    }
                }
            } finally {
                completions.cancel()
            }
        }

    private suspend fun completeSource(
        source: TerminalCompletionSource,
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
    ): List<TerminalCompletionCandidate> {
        val candidates = source.complete(request, context, SOURCE_CANDIDATE_LIMIT)
        return pathCommandSpecCandidateProjector
            .project(request, context, candidates)
            .filter { it.hasValidReplacementRangeFor(request) }
            .boundedTo(SOURCE_CANDIDATE_LIMIT)
    }

    private fun reportSourceFailure(
        sourceIndex: Int,
        source: TerminalCompletionSourceEntry,
        failure: Throwable,
    ) {
        try {
            sourceFailureHandler.sourceFailed(sourceIndex, source, failure)
        } catch (diagnosticFailure: RuntimeException) {
            diagnosticFailure.addSuppressed(failure)
            TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER.sourceFailed(sourceIndex, source, diagnosticFailure)
        }
    }

    private data class SourceCompletion(
        val sourceIndex: Int,
        val priority: Int,
        val candidates: List<TerminalCompletionCandidate>,
    )

    private companion object {
        private const val STATIC_SPECIFICATION_PRIORITY = 0
        private const val LEARNED_PRIORITY = 8
        private const val SOURCE_CANDIDATE_LIMIT = 256
        private const val REQUEST_CANDIDATE_LIMIT = 256
    }
}

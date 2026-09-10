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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/** One source's bounded candidate surplus plus stable composition metadata. */
internal data class CompletionSourceCandidates(
    val sourceIndex: Int,
    val priority: Int,
    val isFallback: Boolean = false,
    val candidates: List<TerminalCompletionCandidate>,
)

/**
 * Deterministic global evidence-fusion ranker for one merged completion engine.
 *
 * This component groups projected outcomes, chooses edit and presentation
 * representatives, and orders the fused results. Outcome resolution, context
 * relevance, numeric policy, and learned evidence indexing are delegated to
 * their owning collaborators.
 */
internal class GlobalCompletionRanker {
    private val outcomeResolver = TerminalCompletionOutcomeKeyResolver()

    fun createRequestState(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        resultLimit: Int,
        nowEpochMillis: Long,
        learnedIndex: LearnedCompletionEvidenceIndex? = null,
    ): RequestCompletionRankingState {
        require(resultLimit > 0) { "resultLimit must be > 0, was $resultLimit" }
        return RequestCompletionRankingState(
            request = request,
            context = context,
            outcomeResolver = outcomeResolver,
            learnedIndex = learnedIndex,
            learningContext = CompletionLearningContextKey.from(request),
            now = nowEpochMillis.coerceAtLeast(0L),
            resultLimit = resultLimit,
        )
    }

    class RequestCompletionRankingState internal constructor(
        private val request: TerminalCompletionRequest,
        private val context: TerminalCompletionContext,
        private val outcomeResolver: TerminalCompletionOutcomeKeyResolver,
        private val learnedIndex: LearnedCompletionEvidenceIndex?,
        private val learningContext: CompletionLearningContextKey,
        private val now: Long,
        private val resultLimit: Int,
    ) {
        private val outcomes = HashMap<Any, RankedOutcome>()
        private var publishedCandidates: List<TerminalCompletionCandidate> = emptyList()
        private var publicationDirty = false

        fun ingest(sourceResult: CompletionSourceCandidates) {
            val locallyRanked = sourceResult.candidates.sortedLocally()
            val bestByOutcome = LinkedHashMap<Any, RankedContribution>()
            for (candidateIndex in locallyRanked.indices) {
                val candidate = locallyRanked[candidateIndex]
                val resolved = outcomeResolver.resolve(request, candidate, context)
                val key: Any = resolved?.groupKey ?: FallbackOutcomeKey(candidate)
                val contribution =
                    RankedContribution(
                        candidate = candidate,
                        sourceIndex = sourceResult.sourceIndex,
                        candidateIndex = candidateIndex,
                        localRank = candidateIndex + 1,
                        sourcePrior = sourceResult.priority.coerceIn(MIN_SOURCE_PRIOR, MAX_SOURCE_PRIOR),
                        isFallback = sourceResult.isFallback,
                        contextAdjustment =
                            semanticAdjustment(context, candidate) +
                                if (sourceResult.isFallback) fallbackContextAdjustment(candidate) else 0,
                        exactLearningAdjustment = learnedIndex?.adjustment(resolved, learningContext, now) ?: 0,
                    )
                val existing = bestByOutcome[key]
                if (existing == null || EDIT_REPRESENTATIVE_ORDER.compare(contribution, existing) < 0) {
                    bestByOutcome[key] = contribution
                }
            }
            for ((key, contribution) in bestByOutcome) {
                val outcome = outcomes.getOrPut(key, ::RankedOutcome)
                outcome.aggregate.add(contribution)
                outcome.fused = outcome.aggregate.finish()
                publicationDirty = true
            }
        }

        fun rankedCandidates(): List<TerminalCompletionCandidate> {
            if (!publicationDirty) return publishedCandidates
            val allFused = ArrayList<FusedCandidate>(outcomes.size)
            for (outcome in outcomes.values) {
                val fused = outcome.fused
                if (fused != null) allFused += fused
            }
            allFused.sortWith(FUSED_ORDER)
            val limit = minOf(resultLimit, allFused.size)
            val candidates = ArrayList<TerminalCompletionCandidate>(limit)
            for (i in 0 until limit) {
                candidates += allFused[i].toPublicCandidate()
            }
            if (candidates != publishedCandidates) {
                publishedCandidates = candidates
            }
            publicationDirty = false
            return publishedCandidates
        }
    }

    private class RankedOutcome {
        val aggregate = MutableOutcomeAggregate()
        var fused: FusedCandidate? = null
    }

    private class MutableOutcomeAggregate {
        private val contributions = ArrayList<RankedContribution>(2)

        fun add(contribution: RankedContribution) {
            contributions += contribution
        }

        fun finish(): FusedCandidate {
            val editRepresentative = contributions.minWith(EDIT_REPRESENTATIVE_ORDER)
            var presentationRepresentative = editRepresentative
            var reciprocalRankScore = 0L
            var sourcePriorScore = 0L
            var strongestContext = Int.MIN_VALUE
            for (contribution in contributions) {
                reciprocalRankScore += reciprocalRank(contribution.localRank)
                sourcePriorScore += contribution.sourcePrior
                strongestContext = maxOf(strongestContext, contribution.contextAdjustment)
                if (contribution.hasSameEditAs(editRepresentative) &&
                    PRESENTATION_REPRESENTATIVE_ORDER.compare(contribution, presentationRepresentative) < 0
                ) {
                    presentationRepresentative = contribution
                }
            }
            val score =
                CompletionScoreComponents(
                    reciprocalRank = reciprocalRankScore,
                    sourcePrior = sourcePriorScore,
                    semanticContext = strongestContext,
                    exactLearning = editRepresentative.exactLearningAdjustment,
                )
            return FusedCandidate(
                rankingCandidate = editRepresentative.candidate,
                presentationCandidate = presentationRepresentative.candidate,
                score = score.total,
                strongestContext = strongestContext,
                bestLocalRank = contributions.minOf { it.localRank },
                sourceIndex = editRepresentative.sourceIndex,
                candidateIndex = editRepresentative.candidateIndex,
            )
        }
    }

    /** Auditable score composition; each ranking policy contributes exactly once. */
    private data class CompletionScoreComponents(
        val reciprocalRank: Long,
        val sourcePrior: Long,
        val semanticContext: Int,
        val exactLearning: Int,
    ) {
        val total: Long
            get() = reciprocalRank + sourcePrior + semanticContext + exactLearning
    }

    private data class RankedContribution(
        val candidate: TerminalCompletionCandidate,
        val sourceIndex: Int,
        val candidateIndex: Int,
        val localRank: Int,
        val sourcePrior: Int,
        val isFallback: Boolean,
        val contextAdjustment: Int,
        val exactLearningAdjustment: Int,
    ) {
        val replacementLength: Int = candidate.replacementText.length

        fun hasSameEditAs(other: RankedContribution): Boolean =
            candidate.replacementText == other.candidate.replacementText &&
                candidate.replacementStartOffset == other.candidate.replacementStartOffset &&
                candidate.replacementEndOffset == other.candidate.replacementEndOffset
    }

    private data class FallbackOutcomeKey(
        val replacementText: String,
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
    ) {
        constructor(candidate: TerminalCompletionCandidate) : this(
            candidate.replacementText,
            candidate.replacementStartOffset,
            candidate.replacementEndOffset,
        )
    }

    private data class FusedCandidate(
        val rankingCandidate: TerminalCompletionCandidate,
        val presentationCandidate: TerminalCompletionCandidate,
        val score: Long,
        val strongestContext: Int,
        val bestLocalRank: Int,
        val sourceIndex: Int,
        val candidateIndex: Int,
    ) {
        fun toPublicCandidate(): TerminalCompletionCandidate =
            presentationCandidate.copy(score = score.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
    }

    private companion object {
        private fun reciprocalRank(localRank: Int): Long = RECIPROCAL_RANK_SCALE / (RECIPROCAL_RANK_OFFSET + localRank)

        private fun semanticAdjustment(
            context: TerminalCompletionContext,
            candidate: TerminalCompletionCandidate,
        ): Int {
            val pos = context.activePosition
            val kind = candidate.kind

            return when (pos) {
                TerminalCompletionActivePosition.OPERATOR -> 0
                TerminalCompletionActivePosition.COMMAND,
                TerminalCompletionActivePosition.SUBCOMMAND,
                TerminalCompletionActivePosition.OPTION_NAME,
                -> STATIC_CONTEXT_BOOST_TABLE[pos.ordinal][kind.ordinal]

                TerminalCompletionActivePosition.OPTION_VALUE ->
                    when (kind) {
                        TerminalCompletionCandidateKind.ARGUMENT ->
                            if (candidate.matchesExpectedDomain(context)) DOMAIN_CONTEXT_BOOST else STRONG_CONTEXT_BOOST
                        TerminalCompletionCandidateKind.PATH ->
                            if (context.expectedPathKind == TerminalPathArgumentKind.NONE) PATH_CONTEXT_PENALTY else STRONG_CONTEXT_BOOST
                        else -> 0
                    }

                TerminalCompletionActivePosition.POSITIONAL_ARGUMENT ->
                    when (kind) {
                        TerminalCompletionCandidateKind.ARGUMENT ->
                            if (candidate.matchesExpectedDomain(context)) DOMAIN_CONTEXT_BOOST else MEDIUM_CONTEXT_BOOST
                        TerminalCompletionCandidateKind.PATH ->
                            if (context.expectedPathKind == TerminalPathArgumentKind.NONE) 0 else STRONG_CONTEXT_BOOST
                        TerminalCompletionCandidateKind.SUBCOMMAND -> PATH_CONTEXT_PENALTY
                        else -> 0
                    }
            }
        }

        private fun TerminalCompletionCandidate.matchesExpectedDomain(context: TerminalCompletionContext): Boolean =
            context.expectedValueDomain != TerminalCompletionValueDomain.NONE && valueDomain == context.expectedValueDomain

        private fun fallbackContextAdjustment(candidate: TerminalCompletionCandidate): Int =
            if (candidate.kind == TerminalCompletionCandidateKind.PATH) {
                FALLBACK_PATH_CONTEXT_PENALTY
            } else {
                FALLBACK_CONTEXT_PENALTY
            }

        private val STATIC_CONTEXT_BOOST_TABLE =
            Array(TerminalCompletionActivePosition.entries.size) {
                IntArray(TerminalCompletionCandidateKind.entries.size) { 0 }
            }.apply {
                this[TerminalCompletionActivePosition.COMMAND.ordinal][TerminalCompletionCandidateKind.COMMAND.ordinal] =
                    STRONG_CONTEXT_BOOST
                this[TerminalCompletionActivePosition.COMMAND.ordinal][TerminalCompletionCandidateKind.PATH.ordinal] = WEAK_CONTEXT_BOOST

                this[TerminalCompletionActivePosition.SUBCOMMAND.ordinal][TerminalCompletionCandidateKind.SUBCOMMAND.ordinal] =
                    STRONG_CONTEXT_BOOST
                this[TerminalCompletionActivePosition.SUBCOMMAND.ordinal][TerminalCompletionCandidateKind.PATH.ordinal] =
                    PATH_CONTEXT_PENALTY

                this[TerminalCompletionActivePosition.OPTION_NAME.ordinal][TerminalCompletionCandidateKind.OPTION.ordinal] =
                    STRONG_CONTEXT_BOOST
                this[TerminalCompletionActivePosition.OPTION_NAME.ordinal][TerminalCompletionCandidateKind.PATH.ordinal] =
                    PATH_CONTEXT_PENALTY
            }

        private val EDIT_REPRESENTATIVE_ORDER =
            compareByDescending<RankedContribution> { it.contextAdjustment }
                .thenBy { it.isFallback }
                .thenBy { it.replacementLength }
                .thenByDescending { it.sourcePrior }
                .thenBy { it.localRank }
                .thenBy { it.sourceIndex }
                .thenBy { it.candidateIndex }

        private val PRESENTATION_REPRESENTATIVE_ORDER =
            compareByDescending<RankedContribution> { it.contextAdjustment }
                .thenBy { it.isFallback }
                .thenByDescending { it.sourcePrior }
                .thenBy { it.localRank }
                .thenBy { it.sourceIndex }
                .thenBy { it.candidateIndex }

        private val FUSED_ORDER =
            compareByDescending<FusedCandidate> { it.score }
                .thenByDescending { it.strongestContext }
                .thenBy { it.bestLocalRank }
                .thenBy { it.rankingCandidate.displayText }
                .thenBy { it.rankingCandidate.replacementText }
                .thenBy { it.sourceIndex }
                .thenBy { it.candidateIndex }

        private fun List<TerminalCompletionCandidate>.sortedLocally(): List<TerminalCompletionCandidate> {
            if (size <= 1) return this
            var isSorted = true
            for (i in 0 until size - 1) {
                if (TERMINAL_COMPLETION_CANDIDATE_ORDER.compare(this[i], this[i + 1]) > 0) {
                    isSorted = false
                    break
                }
            }
            return if (isSorted) this else sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
        }

        private const val MIN_SOURCE_PRIOR = -20
        private const val MAX_SOURCE_PRIOR = 20
        private const val RECIPROCAL_RANK_SCALE = 10_000L
        private const val RECIPROCAL_RANK_OFFSET = 60L
        private const val DOMAIN_CONTEXT_BOOST = 320
        private const val STRONG_CONTEXT_BOOST = 160
        private const val MEDIUM_CONTEXT_BOOST = 80
        private const val WEAK_CONTEXT_BOOST = 40
        private const val PATH_CONTEXT_PENALTY = -80
        private const val FALLBACK_CONTEXT_PENALTY = -40
        private const val FALLBACK_PATH_CONTEXT_PENALTY = -80
    }
}

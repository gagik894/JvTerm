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

import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.terminalCompletionRankingIdentity
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats

/** Immutable direct-lookup view of exact command learning in one snapshot. */
internal class LearnedCompletionEvidenceIndex private constructor(
    private val exactEvidence: Map<ExactEvidenceKey, LearnedEvidenceCounts>,
) {
    fun adjustment(
        outcome: ResolvedCompletionOutcome?,
        requestContext: CompletionLearningContextKey,
        nowEpochMillis: Long,
    ): Int {
        if (outcome == null || exactEvidence.isEmpty()) return 0
        return exactAdjustment(
            identityDigest = terminalCompletionRankingIdentity(outcome.exactCommandLine),
            requestContext = requestContext,
            nowEpochMillis = nowEpochMillis,
        )
    }

    fun exactAdjustment(
        identityDigest: String,
        requestContext: CompletionLearningContextKey,
        nowEpochMillis: Long,
    ): Int {
        val match =
            requestContext.mostSpecific { context ->
                exactEvidence[ExactEvidenceKey(identityDigest, context)]
            } ?: return 0
        return LearnedEvidenceScoring.exact(
            counts = match.value,
            contextBoost = match.context.boost(profile = 20, directory = 30),
            nowEpochMillis = nowEpochMillis,
        )
    }

    companion object {
        fun build(rows: List<TerminalCompletionRankingStats>): LearnedCompletionEvidenceIndex {
            val exactEvidence = HashMap<ExactEvidenceKey, LearnedEvidenceCounts>()
            for (row in rows) {
                val context = CompletionLearningContextKey.of(row.profileId, row.workingDirectoryUri)
                exactEvidence.getOrPut(ExactEvidenceKey(row.identityDigest, context), ::LearnedEvidenceCounts).add(row)
            }
            return LearnedCompletionEvidenceIndex(exactEvidence)
        }
    }
}

private data class ExactEvidenceKey(
    val identityDigest: String,
    val context: CompletionLearningContextKey,
)

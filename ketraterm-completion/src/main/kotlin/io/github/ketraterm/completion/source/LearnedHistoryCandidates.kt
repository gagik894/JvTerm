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

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats
import io.github.ketraterm.completion.ranking.LearnedEvidenceScoring

/** Appends fallback candidates from positive exact-command learning. */
internal fun appendLearnedHistoryCandidates(
    request: TerminalCompletionRequest,
    lineContext: TerminalCommandLineContext,
    completionContext: TerminalCompletionContext,
    index: LearnedHistoryCandidateIndex,
    nowEpochMillis: Long,
    destination: MutableList<TerminalCompletionCandidate>,
) {
    val requestContext = CompletionLearningContextKey.from(request)
    for (indexed in index.matching(lineContext, requestContext)) {
        val entry = indexed.stats
        projectLearnedCommandCandidate(
            request = request,
            requestLine = lineContext,
            completionContext = completionContext,
            learnedCommand = indexed.replay.commandLine,
            source = SOURCE_LEARNED,
            score = entry.localScore(nowEpochMillis),
            detailPrefix = "learned",
            learnedLine = indexed.lineContext,
        )?.let(destination::add)
    }
}

private fun TerminalCompletionRankingStats.localScore(nowEpochMillis: Long): Int {
    val score =
        BASE_SCORE.toLong() +
            counterScore(useCount, USE_COUNT_SCORE) +
            counterScore(acceptedCount, ACCEPTED_COUNT_SCORE) +
            counterScore(dismissedCount, -DISMISSED_COUNT_PENALTY) +
            LearnedEvidenceScoring.recencyBoost(nowEpochMillis, lastUsedEpochMillis)
    return score.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

private fun counterScore(
    count: Int,
    scorePerUnit: Int,
): Long = minOf(count, MAX_COUNTER_SCORE_UNITS).toLong() * scorePerUnit.toLong()

private const val SOURCE_LEARNED = "learned"
private const val BASE_SCORE = 620
private const val USE_COUNT_SCORE = 18
private const val ACCEPTED_COUNT_SCORE = 24
private const val DISMISSED_COUNT_PENALTY = 30
private const val MAX_COUNTER_SCORE_UNITS = 50

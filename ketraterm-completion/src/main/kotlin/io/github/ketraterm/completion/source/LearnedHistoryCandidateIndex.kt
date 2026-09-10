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

import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.ParsedLearnedStatsRow
import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats
import java.util.*

/** Immutable exact-host-context and command-prefix index for positive learned command rows. */
internal class LearnedHistoryCandidateIndex private constructor(
    private val buckets: Map<HistoryBucketKey, List<IndexedLearnedCommand>>,
) {
    /** Returns rows whose exact host context, prior tokens, and active-token prefix match the request. */
    fun matching(
        requestLine: TerminalCommandLineContext,
        requestContext: CompletionLearningContextKey,
    ): List<IndexedLearnedCommand> {
        val priorTokens = requestLine.tokens.subList(0, requestLine.activeTokenIndex).map { it.text.lowercase(Locale.ROOT) }
        val key = HistoryBucketKey(requestContext, priorTokens)
        val bucket = buckets[key] ?: return emptyList()
        val prefix = requestLine.activePrefix.lowercase(Locale.ROOT)
        val start = bucket.lowerBound(prefix)
        if (start == bucket.size) return emptyList()
        val matches = ArrayList<IndexedLearnedCommand>()
        var index = start
        while (index < bucket.size && bucket[index].normalizedActiveToken.startsWith(prefix)) {
            matches += bucket[index]
            index++
        }
        return matches
    }

    private fun List<IndexedLearnedCommand>.lowerBound(prefix: String): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].normalizedActiveToken < prefix) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        /** Builds one bounded index from rows parsed by the shared learning compiler. */
        fun build(rows: List<ParsedLearnedStatsRow>): LearnedHistoryCandidateIndex {
            val mutableBuckets = HashMap<HistoryBucketKey, MutableList<IndexedLearnedCommand>>()
            for (snapshotRank in rows.indices) {
                val parsedRow = rows[snapshotRank]
                val stats = parsedRow.stats
                if (!stats.hasSuccessfulExecution()) continue
                val line = parsedRow.lineContext
                val learningContext = CompletionLearningContextKey.of(stats.profileId, stats.workingDirectoryUri)
                val normalizedTokens = line.tokens.map { it.text.lowercase(Locale.ROOT) }
                val indexedTokenCount = minOf(line.tokens.size, MAX_INDEXED_TOKEN_POSITIONS)
                for (activeIndex in 0 until indexedTokenCount) {
                    val key = HistoryBucketKey(learningContext, normalizedTokens.subList(0, activeIndex))
                    mutableBuckets
                        .getOrPut(key, ::ArrayList)
                        .add(
                            IndexedLearnedCommand(
                                stats = stats,
                                replay = parsedRow.replay,
                                lineContext = line,
                                normalizedActiveToken = normalizedTokens[activeIndex],
                                snapshotRank = snapshotRank,
                            ),
                        )
                }
            }
            val order =
                compareBy<IndexedLearnedCommand> { it.normalizedActiveToken }
                    .thenBy { it.snapshotRank }
            return LearnedHistoryCandidateIndex(
                mutableBuckets.mapValues { (_, rows) -> rows.sortedWith(order) },
            )
        }

        private const val MAX_INDEXED_TOKEN_POSITIONS = 64
    }
}

private data class HistoryBucketKey(
    val context: CompletionLearningContextKey,
    val priorTokens: List<String>,
)

/** Pre-tokenized positive command row retained by [LearnedHistoryCandidateIndex]. */
internal data class IndexedLearnedCommand(
    val stats: TerminalCompletionRankingStats,
    val replay: TerminalCommandReplay,
    val lineContext: TerminalCommandLineContext,
    val normalizedActiveToken: String,
    val snapshotRank: Int,
)

private fun TerminalCompletionRankingStats.hasSuccessfulExecution(): Boolean = successCount > 0

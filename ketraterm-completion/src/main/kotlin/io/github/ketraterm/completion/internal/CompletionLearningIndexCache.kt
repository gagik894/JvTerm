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
package io.github.ketraterm.completion.internal

import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats
import io.github.ketraterm.completion.ranking.LearnedCompletionEvidenceIndex
import io.github.ketraterm.completion.source.LearnedHistoryCandidateIndex
import io.github.ketraterm.completion.source.LearnedObservedTokenIndex

/** Compiles and shares derived learning indexes for one current snapshot. */
internal class CompletionLearningIndexCache {
    private val lock = Any()
    private var cachedSnapshot: TerminalCompletionLearningSnapshot? = null
    private val indexesBySyntax = arrayOfNulls<CompletionLearningIndexes>(TerminalShellSyntax.entries.size)

    fun indexesFor(
        snapshot: TerminalCompletionLearningSnapshot,
        shellSyntax: TerminalShellSyntax,
    ): CompletionLearningIndexes =
        synchronized(lock) {
            if (cachedSnapshot !== snapshot) {
                cachedSnapshot = snapshot
                indexesBySyntax.fill(null)
            }
            val syntaxIndex = shellSyntax.ordinal
            indexesBySyntax[syntaxIndex]
                ?: buildIndexes(snapshot, shellSyntax).also { indexesBySyntax[syntaxIndex] = it }
        }

    private fun buildIndexes(
        snapshot: TerminalCompletionLearningSnapshot,
        shellSyntax: TerminalShellSyntax,
    ): CompletionLearningIndexes {
        val rankingByKey = HashMap<LearningRowKey, TerminalCompletionRankingStats>(snapshot.rankingStats.size)
        for (stats in snapshot.rankingStats) rankingByKey[stats.rowKey()] = stats
        val parsedRows = ArrayList<ParsedLearnedStatsRow>(snapshot.replayCommands.size)
        for (replay in snapshot.replayCommands) {
            val stats = rankingByKey[replay.rowKey()] ?: continue
            val line = TerminalCommandLineTokenizer.parse(replay.commandLine, replay.commandLine.length, shellSyntax)
            parsedRows += ParsedLearnedStatsRow(stats, replay, line)
        }
        return CompletionLearningIndexes(
            evidence = LearnedCompletionEvidenceIndex.build(snapshot.rankingStats),
            history = LearnedHistoryCandidateIndex.build(parsedRows),
            observed = LearnedObservedTokenIndex.build(parsedRows),
        )
    }
}

/** All derived learning data for one shell syntax. */
internal class CompletionLearningIndexes(
    val evidence: LearnedCompletionEvidenceIndex,
    val history: LearnedHistoryCandidateIndex,
    val observed: LearnedObservedTokenIndex,
)

/** One learned row tokenized for every derived index. */
internal class ParsedLearnedStatsRow(
    val stats: TerminalCompletionRankingStats,
    val replay: TerminalCommandReplay,
    val lineContext: TerminalCommandLineContext,
)

private data class LearningRowKey(
    val identityDigest: String,
    val context: CompletionLearningContextKey,
)

private fun TerminalCompletionRankingStats.rowKey(): LearningRowKey =
    LearningRowKey(identityDigest, CompletionLearningContextKey.of(profileId, workingDirectoryUri))

private fun TerminalCommandReplay.rowKey(): LearningRowKey =
    LearningRowKey(identityDigest, CompletionLearningContextKey.of(profileId, workingDirectoryUri))

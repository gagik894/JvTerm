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
package io.github.ketraterm.completion.stats

import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement
import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats

/** One bounded aggregate index with an optional validated replay projection. */
internal class CompletionLearningStatsIndex(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val rowsByKey = HashMap<CompletionLearningStatsKey, RetainedCompletionLearning>(capacity)
    private val orderedRows = ArrayList<RetainedCompletionLearning>(capacity)

    fun mergeSnapshot(snapshot: TerminalCompletionLearningSnapshot) {
        for (stats in snapshot.rankingStats) mergeRankingStats(stats)
        rebuildOrder()
        for (replay in snapshot.replayCommands) attachReplay(replay)
    }

    fun snapshot(): TerminalCompletionLearningSnapshot {
        val rankingStats = ArrayList<TerminalCompletionRankingStats>(orderedRows.size)
        val replayCommands = ArrayList<TerminalCommandReplay>()
        for (row in orderedRows) {
            rankingStats += row.rankingStats
            row.replay?.let(replayCommands::add)
        }
        return TerminalCompletionLearningSnapshot(rankingStats, replayCommands)
    }

    fun clear() {
        rowsByKey.clear()
        orderedRows.clear()
    }

    fun recordCommandResult(
        identityDigest: String,
        replayCommandLine: String?,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ): Boolean =
        mutate(identityDigest, replayCommandLine.takeIf { successful }, profileId, workingDirectoryUri) { previous ->
            previous.copy(
                useCount = saturatedCompletionCounterIncrement(previous.useCount),
                successCount =
                    if (successful) {
                        saturatedCompletionCounterIncrement(previous.successCount)
                    } else {
                        previous.successCount
                    },
                failureCount =
                    if (successful) {
                        previous.failureCount
                    } else {
                        saturatedCompletionCounterIncrement(previous.failureCount)
                    },
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, usedAtEpochMillis),
            )
        }

    fun recordSuggestionFeedback(
        identityDigest: String,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
    ): Boolean =
        mutate(
            identityDigest,
            null,
            profileId,
            workingDirectoryUri,
        ) { previous ->
            previous.copy(
                acceptedCount = incrementAccepted(previous.acceptedCount, feedback),
                dismissedCount = incrementDismissed(previous.dismissedCount, feedback),
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
            )
        }

    private fun mutate(
        identityDigest: String,
        replayCommandLine: String?,
        profileId: String?,
        workingDirectoryUri: String?,
        update: (TerminalCompletionRankingStats) -> TerminalCompletionRankingStats,
    ): Boolean {
        val context = CompletionLearningContextKey.of(profileId, workingDirectoryUri)
        val key = CompletionLearningStatsKey(identityDigest, context)
        val current = rowsByKey[key]
        if (current != null) {
            val updatedStats = update(current.rankingStats)
            val updatedReplay = current.replay ?: replay(identityDigest, replayCommandLine, context)
            if (updatedStats == current.rankingStats && updatedReplay == current.replay) return false
            check(orderedRows.remove(current)) { "indexed completion-learning row is missing from sorted storage" }
            val updated = RetainedCompletionLearning(updatedStats, updatedReplay)
            rowsByKey[key] = updated
            insertOrdered(updated)
            return true
        }

        val createdStats =
            update(
                TerminalCompletionRankingStats(
                    identityDigest = identityDigest,
                    profileId = context.profileId,
                    workingDirectoryUri = context.workingDirectoryUri,
                ),
            )
        val created = RetainedCompletionLearning(createdStats, replay(identityDigest, replayCommandLine, context))
        rowsByKey[key] = created
        insertOrdered(created)
        if (orderedRows.size <= capacity) return true

        val evicted = orderedRows.removeAt(orderedRows.lastIndex)
        rowsByKey.remove(evicted.key())
        return evicted !== created
    }

    private fun mergeRankingStats(record: TerminalCompletionRankingStats) {
        val incoming = canonicalizeContext(record)
        val key = incoming.key()
        val current = rowsByKey[key]
        rowsByKey[key] =
            if (current == null) {
                RetainedCompletionLearning(incoming)
            } else {
                current.copy(rankingStats = mergeStats(current.rankingStats, incoming))
            }
    }

    private fun attachReplay(replay: TerminalCommandReplay) {
        val context = CompletionLearningContextKey.of(replay.profileId, replay.workingDirectoryUri)
        val key = CompletionLearningStatsKey(replay.identityDigest, context)
        val current = rowsByKey[key] ?: return
        if (!current.rankingStats.hasSuccessfulExecution()) return
        if (current.replay != null) return
        val retainedReplay =
            if (replay.profileId == current.rankingStats.profileId &&
                replay.workingDirectoryUri == current.rankingStats.workingDirectoryUri
            ) {
                replay
            } else {
                TerminalCommandReplay(
                    identityDigest = replay.identityDigest,
                    commandLine = replay.commandLine,
                    profileId = current.rankingStats.profileId,
                    workingDirectoryUri = current.rankingStats.workingDirectoryUri,
                )
            }
        val updated = current.copy(replay = retainedReplay)
        rowsByKey[key] = updated
        val index = orderedRows.indexOf(current)
        check(index >= 0) { "indexed completion-learning row is missing from sorted storage" }
        orderedRows[index] = updated
    }

    private fun rebuildOrder() {
        orderedRows.clear()
        orderedRows.addAll(rowsByKey.values)
        orderedRows.sortWith(RETAINED_ORDER)
        if (orderedRows.size > capacity) orderedRows.subList(capacity, orderedRows.size).clear()
        rowsByKey.clear()
        for (row in orderedRows) rowsByKey[row.key()] = row
    }

    private fun insertOrdered(row: RetainedCompletionLearning) {
        var low = 0
        var high = orderedRows.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (RETAINED_ORDER.compare(orderedRows[middle], row) <= 0) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        orderedRows.add(low, row)
    }

    private data class CompletionLearningStatsKey(
        val identityDigest: String,
        val context: CompletionLearningContextKey,
    )

    private data class RetainedCompletionLearning(
        val rankingStats: TerminalCompletionRankingStats,
        val replay: TerminalCommandReplay? = null,
    )

    private fun replay(
        identityDigest: String,
        commandLine: String?,
        context: CompletionLearningContextKey,
    ): TerminalCommandReplay? =
        commandLine?.let {
            TerminalCommandReplay(
                identityDigest = identityDigest,
                commandLine = it,
                profileId = context.profileId,
                workingDirectoryUri = context.workingDirectoryUri,
            )
        }

    private fun RetainedCompletionLearning.key(): CompletionLearningStatsKey = rankingStats.key()

    private fun TerminalCompletionRankingStats.key(): CompletionLearningStatsKey =
        CompletionLearningStatsKey(
            identityDigest = identityDigest,
            context = CompletionLearningContextKey.of(profileId, workingDirectoryUri),
        )

    private fun canonicalizeContext(record: TerminalCompletionRankingStats): TerminalCompletionRankingStats {
        val context = CompletionLearningContextKey.of(record.profileId, record.workingDirectoryUri)
        return record.copy(
            profileId = context.profileId,
            workingDirectoryUri = context.workingDirectoryUri,
        )
    }

    private fun mergeStats(
        current: TerminalCompletionRankingStats,
        incoming: TerminalCompletionRankingStats,
    ): TerminalCompletionRankingStats {
        val newest = if (incoming.lastUsedEpochMillis >= current.lastUsedEpochMillis) incoming else current
        val context = CompletionLearningContextKey.of(current.profileId, current.workingDirectoryUri)
        return newest.copy(
            profileId = context.profileId,
            workingDirectoryUri = context.workingDirectoryUri,
            useCount = saturatedCounterSum(current.useCount, incoming.useCount),
            successCount = saturatedCounterSum(current.successCount, incoming.successCount),
            failureCount = saturatedCounterSum(current.failureCount, incoming.failureCount),
            acceptedCount = saturatedCounterSum(current.acceptedCount, incoming.acceptedCount),
            dismissedCount = saturatedCounterSum(current.dismissedCount, incoming.dismissedCount),
            lastUsedEpochMillis = maxOf(current.lastUsedEpochMillis, incoming.lastUsedEpochMillis),
        )
    }

    private companion object {
        private val RANKING_STATS_ORDER =
            compareByDescending<TerminalCompletionRankingStats> { it.hasSuccessfulExecution() }
                .thenByDescending { it.lastUsedEpochMillis }
                .thenByDescending { it.acceptedCount }
                .thenByDescending { it.successCount }
                .thenBy { it.dismissedCount }
                .thenBy { it.identityDigest }
                .thenBy { it.profileId.orEmpty() }
                .thenBy { it.workingDirectoryUri.orEmpty() }
        private val RETAINED_ORDER =
            Comparator<RetainedCompletionLearning> { left, right ->
                RANKING_STATS_ORDER.compare(left.rankingStats, right.rankingStats)
            }
    }
}

private fun TerminalCompletionRankingStats.hasSuccessfulExecution(): Boolean = successCount > 0

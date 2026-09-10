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

import io.github.ketraterm.completion.model.TerminalCompletionRankingStats

/** Saturating aggregate for exact command learning. */
internal class LearnedEvidenceCounts(
    var useCount: Long = 0,
    var acceptedCount: Long = 0,
    var dismissedCount: Long = 0,
    var lastUsedEpochMillis: Long = 0,
) {
    fun add(row: TerminalCompletionRankingStats) {
        useCount = saturatedAdd(useCount, row.useCount.toLong())
        acceptedCount = saturatedAdd(acceptedCount, row.acceptedCount.toLong())
        dismissedCount = saturatedAdd(dismissedCount, row.dismissedCount.toLong())
        lastUsedEpochMillis = maxOf(lastUsedEpochMillis, row.lastUsedEpochMillis)
    }

    private companion object {
        private fun saturatedAdd(
            left: Long,
            right: Long,
        ): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}

/** Documented numeric frecency policy for exact command learning. */
internal object LearnedEvidenceScoring {
    fun exact(
        counts: LearnedEvidenceCounts,
        contextBoost: Int,
        nowEpochMillis: Long,
    ): Int {
        var score = minOf(counts.useCount, EXACT_MAX_USE_COUNT) * EXACT_USE_SCORE
        score += (counts.acceptedCount - counts.dismissedCount).coerceIn(-20L, 20L) * 10L
        if (counts.useCount > 0 || counts.acceptedCount > 0) {
            score += contextBoost
            score += recencyBoost(nowEpochMillis, counts.lastUsedEpochMillis)
        }
        return score.coerceIn(EXACT_MIN_ADJUSTMENT, EXACT_MAX_ADJUSTMENT).toInt()
    }

    fun recencyBoost(
        nowEpochMillis: Long,
        lastUsedEpochMillis: Long,
    ): Int {
        if (nowEpochMillis <= 0L || lastUsedEpochMillis <= 0L) return 0
        val age = (nowEpochMillis - lastUsedEpochMillis).coerceAtLeast(0L)
        return when {
            age <= ONE_HOUR_MILLIS -> 60
            age <= ONE_DAY_MILLIS -> 40
            age <= SEVEN_DAYS_MILLIS -> 20
            age <= THIRTY_DAYS_MILLIS -> 10
            else -> 0
        }
    }

    private const val EXACT_MAX_USE_COUNT = 30L
    private const val EXACT_USE_SCORE = 5L
    private const val EXACT_MIN_ADJUSTMENT = -200L
    private const val EXACT_MAX_ADJUSTMENT = 500L
    private const val ONE_HOUR_MILLIS = 60L * 60L * 1_000L
    private const val ONE_DAY_MILLIS = 24L * ONE_HOUR_MILLIS
    private const val SEVEN_DAYS_MILLIS = 7L * ONE_DAY_MILLIS
    private const val THIRTY_DAYS_MILLIS = 30L * ONE_DAY_MILLIS
}

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
package io.github.ketraterm.completion.model

/**
 * Immutable snapshot of split completion learning.
 *
 * [rankingStats] contain no command text. Each [replayCommands] entry is an
 * optional, positive, separately policy-approved projection of one matching evidence row.
 *
 * @property rankingStats opaque exact-command evidence used by ranking.
 * @property replayCommands plaintext commands used by history and observed-token suggestions.
 */
class TerminalCompletionLearningSnapshot
    @JvmOverloads
    constructor(
        rankingStats: List<TerminalCompletionRankingStats> = emptyList(),
        replayCommands: List<TerminalCommandReplay> = emptyList(),
    ) {
        val rankingStats: List<TerminalCompletionRankingStats> = immutableListCopy(rankingStats)
        val replayCommands: List<TerminalCommandReplay> = immutableListCopy(replayCommands)

        /** Returns a snapshot with the supplied lane replacements. */
        fun copy(
            rankingStats: List<TerminalCompletionRankingStats> = this.rankingStats,
            replayCommands: List<TerminalCommandReplay> = this.replayCommands,
        ): TerminalCompletionLearningSnapshot = TerminalCompletionLearningSnapshot(rankingStats, replayCommands)

        /** Returns [rankingStats] for destructuring. */
        operator fun component1(): List<TerminalCompletionRankingStats> = rankingStats

        /** Returns [replayCommands] for destructuring. */
        operator fun component2(): List<TerminalCommandReplay> = replayCommands

        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is TerminalCompletionLearningSnapshot &&
                        rankingStats == other.rankingStats &&
                        replayCommands == other.replayCommands
                )

        override fun hashCode(): Int = 31 * rankingStats.hashCode() + replayCommands.hashCode()

        override fun toString(): String = "TerminalCompletionLearningSnapshot(rankingStats=$rankingStats, replayCommands=$replayCommands)"

        companion object {
            /** Shared empty learning snapshot. */
            @JvmField
            val EMPTY = TerminalCompletionLearningSnapshot()
        }
    }

private fun <T> immutableListCopy(values: List<T>): List<T> =
    if (values.isEmpty()) {
        emptyList()
    } else {
        java.util.List.copyOf(values)
    }

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
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.TERMINAL_COMMAND_OPTION_TERMINATOR
import io.github.ketraterm.completion.commandline.firstCommandTokenIndex
import io.github.ketraterm.completion.commandline.isTerminalOptionToken
import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.ParsedLearnedStatsRow
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats
import io.github.ketraterm.completion.stats.saturatedCounterSum

/** Immutable exact-host-context observed-token transitions derived from successful command rows. */
internal class LearnedObservedTokenIndex private constructor(
    private val buckets: Map<ObservedBucketKey, List<Entry>>,
) {
    /** Appends matching transitions for executable families without a static command specification. */
    fun appendCandidates(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        destination: MutableList<TerminalCompletionCandidate>,
    ) {
        val observedContext = context.observedContext() ?: return
        val bucket = buckets[ObservedBucketKey(CompletionLearningContextKey.from(request), observedContext)] ?: return
        val prefix = normalizeTerminalCommandToken(context.activePrefix)
        var index = bucket.lowerBound(prefix)
        while (index < bucket.size) {
            val entry = bucket[index]
            if (!entry.normalizedToken.startsWith(prefix)) break
            if (entry.normalizedToken != prefix) {
                entry.toCandidate(request, context)?.let(destination::add)
            }
            index++
        }
    }

    private fun TerminalCompletionContext.observedContext(): List<String>? {
        if (command != null) return null
        val line = commandLineContext
        val commandIndex = commandTokenIndex
        if (commandIndex >= line.tokens.size || line.activeTokenIndex <= commandIndex) return null
        val executable = normalizeTerminalCommandToken(line.tokens[commandIndex].text)
        if (executable.isBlank()) return null

        val context = ArrayList<String>(line.activeTokenIndex - commandIndex)
        for (index in commandIndex until line.activeTokenIndex) {
            val token = normalizeTerminalCommandToken(line.tokens[index].text)
            if (token.isBlank()) return null
            context += token
        }
        return context
    }

    private fun List<Entry>.lowerBound(prefix: String): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].normalizedToken < prefix) low = middle + 1 else high = middle
        }
        return low
    }

    private fun Entry.toCandidate(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
    ): TerminalCompletionCandidate? {
        val replacement =
            ShellReplacementText.encode(
                value = token,
                activeTokenQuote = context.activeTokenQuote,
                policy = request.shellCapabilities.quoting,
            ) ?: return null
        return TerminalCompletionCandidate(
            replacementText = replacement,
            replacementStartOffset = context.replacementStartOffset,
            replacementEndOffset = context.replacementEndOffset,
            displayText = token,
            detail = DETAIL,
            source = SOURCE_ID,
            kind =
                if (normalizedToken.isTerminalOptionToken()) {
                    TerminalCompletionCandidateKind.OPTION
                } else {
                    TerminalCompletionCandidateKind.ARGUMENT
                },
            score = score(),
        )
    }

    private fun Entry.score(): Int = BASE_SCORE + minOf(successCount, MAX_SUCCESS_SCORE_UNITS) * SUCCESS_SCORE

    companion object {
        /** Builds one deterministic bounded index from rows parsed by the shared learning compiler. */
        fun build(rows: List<ParsedLearnedStatsRow>): LearnedObservedTokenIndex {
            val aggregates = HashMap<TransitionKey, MutableEntry>()
            for (row in rows) {
                if (row.stats.successCount <= 0) continue
                addTransitions(row, aggregates)
            }

            val retained =
                aggregates.values
                    .sortedWith(RETENTION_ORDER)
                    .take(MAX_ENTRY_COUNT)
            val mutableBuckets = HashMap<ObservedBucketKey, MutableList<Entry>>()
            for (aggregate in retained) {
                mutableBuckets
                    .getOrPut(aggregate.bucketKey, ::ArrayList)
                    .add(aggregate.freeze())
            }
            return LearnedObservedTokenIndex(
                mutableBuckets.mapValues { (_, entries) ->
                    entries.sortedWith(compareBy(Entry::normalizedToken).thenBy(Entry::token))
                },
            )
        }

        private fun addTransitions(
            row: ParsedLearnedStatsRow,
            aggregates: MutableMap<TransitionKey, MutableEntry>,
        ) {
            if (row.lineContext.precededByOperator) return
            val tokens = row.lineContext.tokens
            var tokenIndex = tokens.firstCommandTokenIndex()
            if (tokenIndex >= tokens.size) return
            val executable = normalizeTerminalCommandToken(tokens[tokenIndex].text)
            if (executable.isBlank()) return

            val learningContext = CompletionLearningContextKey.of(row.stats.profileId, row.stats.workingDirectoryUri)
            val context = ArrayList<String>()
            context += executable
            tokenIndex++
            var observedFirstArgument = false
            var encounteredOption = false
            while (tokenIndex < tokens.size) {
                val token = tokens[tokenIndex].text
                val normalizedToken = normalizeTerminalCommandToken(token)
                when {
                    normalizedToken.isBlank() -> Unit
                    normalizedToken in COMMAND_OPERATORS || normalizedToken == TERMINAL_COMMAND_OPTION_TERMINATOR -> return
                    normalizedToken.isTerminalOptionToken() -> {
                        token.observedOptionToken()?.let { retain(context, it, learningContext, row.stats, aggregates) }
                        context += normalizedToken
                        encounteredOption = true
                    }
                    !observedFirstArgument && !encounteredOption -> {
                        retain(context, token, learningContext, row.stats, aggregates)
                        context += normalizedToken
                        observedFirstArgument = true
                    }
                    else -> return
                }
                tokenIndex++
            }
        }

        private fun retain(
            context: List<String>,
            token: String,
            learningContext: CompletionLearningContextKey,
            stats: TerminalCompletionRankingStats,
            aggregates: MutableMap<TransitionKey, MutableEntry>,
        ) {
            if (token.isBlank()) return
            val key = TransitionKey(ObservedBucketKey(learningContext, context.toList()), token)
            val aggregate =
                aggregates.getOrPut(key) {
                    MutableEntry(
                        bucketKey = key.bucketKey,
                        token = key.token,
                        normalizedToken = normalizeTerminalCommandToken(key.token),
                    )
                }
            aggregate.successCount = saturatedCounterSum(aggregate.successCount, stats.successCount)
        }

        private fun String.observedOptionToken(): String? =
            when {
                startsWith("--") -> substringBefore('=')
                length == SHORT_OPTION_LENGTH -> this
                else -> null
            }

        private val RETENTION_ORDER =
            compareByDescending<MutableEntry> { it.successCount }
                .thenBy { it.bucketKey.observedContext.joinToString(CONTEXT_SEPARATOR) }
                .thenBy { it.normalizedToken }
                .thenBy { it.token }
                .thenBy { it.bucketKey.learningContext.profileId }
                .thenBy { it.bucketKey.learningContext.workingDirectoryUri }

        private const val SOURCE_ID = "observed"
        private const val DETAIL = "learned from successful commands"
        private const val BASE_SCORE = 760
        private const val SUCCESS_SCORE = 30
        private const val MAX_SUCCESS_SCORE_UNITS = 20
        private const val MAX_ENTRY_COUNT = 2048
        private const val CONTEXT_SEPARATOR = "\u0000"
        private const val SHORT_OPTION_LENGTH = 2
        private val COMMAND_OPERATORS = setOf("&&", "||", "|", "|&", ";", "&")
    }

    private data class ObservedBucketKey(
        val learningContext: CompletionLearningContextKey,
        val observedContext: List<String>,
    )

    private data class TransitionKey(
        val bucketKey: ObservedBucketKey,
        val token: String,
    )

    private class MutableEntry(
        val bucketKey: ObservedBucketKey,
        val token: String,
        val normalizedToken: String,
        var successCount: Int = 0,
    ) {
        fun freeze(): Entry =
            Entry(
                token = token,
                normalizedToken = normalizedToken,
                successCount = successCount,
            )
    }

    private data class Entry(
        val token: String,
        val normalizedToken: String,
        val successCount: Int,
    )
}

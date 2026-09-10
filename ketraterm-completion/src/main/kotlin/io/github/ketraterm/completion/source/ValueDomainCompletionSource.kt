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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.internal.BoundedCompletionCandidateCollector
import io.github.ketraterm.completion.matching.CompletionMatcher
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

/**
 * Dynamic-domain source backed by a bounded suspending host loader.
 *
 * The source invokes [valuesProvider] only for a matching resolved context and
 * does not retain returned values. The loader owns any host I/O and must
 * cooperate with coroutine cancellation.
 *
 * @property domain command-spec value domain served by this source.
 * @property sourceId stable candidate-source identifier used by feedback ranking.
 * @property valuesProvider suspending bounded value loader.
 * @throws IllegalArgumentException if [domain] is
 * [TerminalCompletionValueDomain.NONE] or [sourceId] is blank.
 */
internal class ValueDomainCompletionSource(
    private val domain: TerminalCompletionValueDomain,
    private val sourceId: String,
    private val valuesProvider:
        suspend (TerminalCompletionRequest, TerminalCompletionContext) -> List<TerminalCompletionDomainValue>,
    private val allowedCommandNames: Set<String>,
) : TerminalCompletionSource {
    init {
        require(domain != TerminalCompletionValueDomain.NONE) { "domain must not be NONE" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(allowedCommandNames.none(String::isBlank)) { "allowedCommandNames must not contain blank values" }
    }

    /**
     * Returns candidates using the engine's already-resolved command context.
     *
     * @param request immutable completion request.
     * @param context resolved context corresponding to [request].
     * @param limit maximum candidates this source may return.
     * @return candidates bounded by [limit], or an empty list when the cursor
     * does not expect [domain].
     */
    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        require(limit > 0) { "limit must be > 0, was $limit" }
        if (context.expectedValueDomain != domain ||
            (allowedCommandNames.isNotEmpty() && context.currentCommand?.name !in allowedCommandNames)
        ) {
            return emptyList()
        }

        val values = valuesProvider(request, context)
        return projectValueDomainCandidates(request, context, domain, sourceId, values, limit)
    }
}

/** Projects already-loaded dynamic values through the shared matching and quoting policy. */
internal fun projectValueDomainCandidates(
    request: TerminalCompletionRequest,
    context: TerminalCompletionContext,
    domain: TerminalCompletionValueDomain,
    sourceId: String,
    values: List<TerminalCompletionDomainValue>,
    limit: Int,
): List<TerminalCompletionCandidate> {
    if (context.expectedValueDomain != domain || values.isEmpty()) return emptyList()
    val prefix = context.activePrefix
    val candidates = BoundedCompletionCandidateCollector(limit)
    for (index in values.indices) {
        val value = values[index]
        if (prefix.isNotEmpty() && value.value.equals(prefix, ignoreCase = true)) continue
        val match = CompletionMatcher.match(value.value, prefix) ?: continue
        if (!ShellReplacementText.canEncode(value.value, context.activeTokenQuote, request.shellCapabilities.quoting)) {
            continue
        }
        val candidateScore =
            match.sourceScore(
                baseScore = VALUE_DOMAIN_BASE_SCORE + value.scoreAdjustment,
                query = prefix,
                orderIndex = index,
            )
        if (!candidates.shouldMaterialize(candidateScore)) continue
        val displayMatchRanges =
            if (value.displayText == value.value) {
                match.matchedRanges
            } else {
                CompletionMatcher.match(value.displayText, prefix)?.matchedRanges ?: TerminalCompletionMatchRanges.EMPTY
            }
        val replacement =
            ShellReplacementText.encode(
                value = value.value,
                activeTokenQuote = context.activeTokenQuote,
                policy = request.shellCapabilities.quoting,
            ) ?: continue
        candidates.offer(
            TerminalCompletionCandidate(
                replacementText = replacement,
                replacementStartOffset = context.replacementStartOffset,
                replacementEndOffset = context.replacementEndOffset,
                displayText = value.displayText,
                detail = value.detail,
                source = sourceId,
                kind = TerminalCompletionCandidateKind.ARGUMENT,
                score = candidateScore,
                valueDomain = domain,
                matchedRanges = displayMatchRanges,
            ),
        )
    }
    return candidates.finish()
}

private const val VALUE_DOMAIN_BASE_SCORE = 260

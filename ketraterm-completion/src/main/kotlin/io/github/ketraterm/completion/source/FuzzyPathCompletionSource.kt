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

/**
 * Materializes already-matched host paths using terminal-safe semantics.
 *
 * The host supplies bounded entries that are already relative to the request
 * working directory and relevance-ordered for the active prefix. This source
 * never indexes files or repeats the host's fuzzy match.
 * It normally complements direct directory completion by requiring a non-empty
 * prefix, while small context-specific providers may opt into empty-prefix matching.
 */
internal class FuzzyPathCompletionSource(
    private val sourceId: String,
    private val entriesProvider:
        suspend (TerminalCompletionRequest, TerminalCompletionContext) -> List<TerminalFuzzyPathEntry>,
    private val requiresNonEmptyPrefix: Boolean,
    private val allowedCommandNames: Set<String>,
) : TerminalCompletionSource {
    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        require(limit > 0) { "limit must be > 0, was $limit" }
        if (allowedCommandNames.isNotEmpty() && context.currentCommand?.name !in allowedCommandNames) {
            return emptyList()
        }
        val prefix = context.activePrefix
        if (!allowsPathCompletion(context.activePosition, context.expectedPathKind, prefix) ||
            (requiresNonEmptyPrefix && prefix.isEmpty())
        ) {
            return emptyList()
        }

        val pathSeparator = if (prefix.contains('\\')) '\\' else '/'
        val candidates = BoundedCompletionCandidateCollector(limit)
        var orderIndex = 0
        for ((path, isDirectory, detail) in entriesProvider(request, context)) {
            if (!context.expectedPathKind.acceptsPathEntry(isDirectory)) continue
            if (!context.expectedHiddenPathPolicy.acceptsPath(path, prefix)) continue
            val rawPath = if (pathSeparator == '\\') path.replace('/', '\\') else path
            val rawReplacement = if (isDirectory) rawPath + pathSeparator else rawPath
            if (!ShellReplacementText.canEncode(rawReplacement, context.activeTokenQuote, request.shellCapabilities.quoting)) {
                continue
            }
            val candidateScore = FUZZY_PATH_BASE_SCORE - orderIndex++
            if (!candidates.shouldMaterialize(candidateScore)) continue
            val replacementText =
                ShellReplacementText.encode(
                    value = rawReplacement,
                    activeTokenQuote = context.activeTokenQuote,
                    policy = request.shellCapabilities.quoting,
                ) ?: continue
            candidates.offer(
                TerminalCompletionCandidate(
                    replacementText = replacementText,
                    replacementStartOffset = context.replacementStartOffset,
                    replacementEndOffset = context.replacementEndOffset,
                    displayText = path + if (isDirectory) "/" else "",
                    detail = detail ?: if (isDirectory) "project directory" else "project file",
                    source = sourceId,
                    kind = TerminalCompletionCandidateKind.PATH,
                    score = candidateScore,
                ),
            )
        }
        return candidates.finish()
    }

    private companion object {
        private const val FUZZY_PATH_BASE_SCORE = 200
    }
}

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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.source.*

/** Factories for dependency-free, host-composable completion sources. */
object TerminalCompletionSources {
    /**
     * Creates a path autocomplete source backed by a host-provided file system lister.
     *
     * @param fileSystemProvider host-implemented directory lister.
     * @return path completion source.
     */
    @JvmStatic
    fun path(fileSystemProvider: TerminalFileSystemProvider): TerminalCompletionSource = PathCompletionSource(fileSystemProvider)

    /**
     * Creates a source backed by a query-aware host fuzzy-path provider.
     *
     * The immutable request and resolved semantic context let IDE hosts query
     * their indexes asynchronously using the decoded active path prefix. The
     * provider owns the only fuzzy match and an independent host-query budget;
     * it must return ready results in relevance order without blocking the
     * completion thread. The engine's final candidate limit is applied only
     * after shared terminal path and quoting rules.
     *
     * @param sourceId stable candidate-source id used by ranking feedback.
     * @param entriesProvider ready query-aware path provider scoped by the
     * immutable completion request.
     * @param requiresNonEmptyPrefix whether this source waits for explicit path text.
     * @param allowedCommandNames optional canonical command/subcommand restriction.
     * @return context-aware fuzzy path completion source.
     * @throws IllegalArgumentException if [sourceId] is blank.
     */
    @JvmStatic
    @JvmOverloads
    fun fuzzyPath(
        sourceId: String,
        entriesProvider: TerminalFuzzyPathProvider,
        requiresNonEmptyPrefix: Boolean = true,
        allowedCommandNames: Set<String> = emptySet(),
    ): TerminalCompletionSource {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(allowedCommandNames.none(String::isBlank)) { "allowedCommandNames must not contain blank values" }
        return FuzzyPathCompletionSource(
            sourceId = sourceId,
            entriesProvider = entriesProvider::entries,
            requiresNonEmptyPrefix = requiresNonEmptyPrefix,
            allowedCommandNames = allowedCommandNames.toSet(),
        )
    }

    /**
     * Creates a source for host-indexed Gradle tasks.
     *
     * The source understands Gradle's canonical `:project:task` notation and
     * scopes short task names after `-p` or `--project-dir`. [tasksProvider]
     * receives the resolved semantic context and returns a snapshot bounded by
     * its own host-model input or visit budget. The shared source matches that
     * snapshot before applying the final candidate limit. The loader may read a
     * host model but must never start Gradle from a completion request.
     *
     * @param sourceId stable candidate-source id used by ranking feedback.
     * @param tasksProvider suspending bounded Gradle-task snapshot loader.
     * @return context-aware Gradle task completion source.
     * @throws IllegalArgumentException if [sourceId] is blank.
     */
    @JvmStatic
    fun gradleTask(
        sourceId: String,
        tasksProvider: suspend (TerminalCompletionRequest, TerminalCompletionContext) -> List<TerminalGradleTask>,
    ): TerminalCompletionSource =
        GradleTaskCompletionSource(
            sourceId = sourceId,
            tasksProvider = tasksProvider,
        )

    /**
     * Creates a source for one host-owned dynamic value domain.
     *
     * [valuesProvider] is called only when the resolved context expects [domain].
     * It may perform bounded suspending host work and must cooperate with
     * cancellation. The provider receives the resolved semantic context and
     * owns an independent input or visit budget. The shared source matches the
     * complete snapshot before applying its final candidate limit.
     *
     * @param domain command-spec value domain served by this source.
     * @param sourceId stable candidate-source id used by ranking feedback.
     * @param valuesProvider suspending bounded value snapshot loader.
     * @param allowedCommandNames optional canonical command/subcommand names to
     * which this source is restricted. An empty set permits every matching
     * value-domain position.
     * @return context-aware dynamic value completion source.
     * @throws IllegalArgumentException if [domain] is
     * [TerminalCompletionValueDomain.NONE] or [sourceId] is blank.
     */
    @JvmStatic
    @JvmOverloads
    fun valueDomain(
        domain: TerminalCompletionValueDomain,
        sourceId: String,
        valuesProvider: suspend (TerminalCompletionRequest, TerminalCompletionContext) -> List<TerminalCompletionDomainValue>,
        allowedCommandNames: Set<String> = emptySet(),
    ): TerminalCompletionSource =
        ValueDomainCompletionSource(
            domain = domain,
            sourceId = sourceId,
            valuesProvider = valuesProvider,
            allowedCommandNames = allowedCommandNames.toSet(),
        )

    /**
     * Projects one already-loaded dynamic-value group into completion candidates.
     *
     * This is intended for aggregate host sources that load several provider
     * groups in one host operation. It applies the same prefix matching, shell
     * quoting, replacement ranges, and local scoring as [valueDomain] without
     * allocating nested source adapters per request.
     *
     * @param request immutable completion request supplied to the aggregate source.
     * @param context engine-resolved context supplied to the aggregate source.
     * @param domain dynamic value domain represented by [values].
     * @param sourceId stable provider id attached to projected candidates.
     * @param values bounded values already loaded by the aggregate source.
     * @param limit maximum candidates returned for this group.
     * @return projected candidates, or an empty list for an ineligible context.
     */
    @JvmStatic
    fun valueDomainCandidates(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        domain: TerminalCompletionValueDomain,
        sourceId: String,
        values: List<TerminalCompletionDomainValue>,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        require(domain != TerminalCompletionValueDomain.NONE) { "domain must not be NONE" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(limit > 0) { "limit must be > 0, was $limit" }
        return projectValueDomainCandidates(request, context, domain, sourceId, values, limit)
    }
}

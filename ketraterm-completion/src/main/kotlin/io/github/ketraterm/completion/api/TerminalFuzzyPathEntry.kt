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

/**
 * One host-indexed path available to fuzzy path completion.
 *
 * [path] is a shell-facing lexical path relative to the request's current
 * directory, or an absolute path when a host cannot safely relativize it. It
 * always uses forward slashes; the shared source adapts the replacement to the
 * active shell token's separator and quoting style.
 *
 * @property path non-empty lexical path without a trailing separator.
 * @property isDirectory whether this entry represents a directory.
 * @property detail optional host-owned concise description shown beside the path.
 * @throws IllegalArgumentException if [path] is blank or separator-terminated,
 * or [detail] contains a line break.
 */
data class TerminalFuzzyPathEntry(
    val path: String,
    val isDirectory: Boolean,
    val detail: String? = null,
) {
    init {
        require(path.isNotBlank()) { "path must not be blank" }
        require(!path.endsWith('/')) { "path must not end with '/', was $path" }
        require(!path.endsWith('\\')) { "path must not end with '\\', was $path" }
        require(detail.isNullOrBlank() || !detail.contains('\n')) { "detail must be a single line" }
    }
}

/**
 * Supplies host-bounded fuzzy matches for one immutable completion request and
 * its resolved terminal context.
 *
 * Entries must already match [TerminalCompletionContext.activePrefix] and be
 * ordered from most to least relevant. The provider owns an independent host
 * query, input, visit, or time budget; it never receives the engine's final
 * candidate limit. The shared completion source deliberately does not run a
 * second fuzzy matcher. It applies terminal path eligibility, hidden-path and
 * quoting rules before enforcing that final limit.
 *
 * Implementations may perform bounded suspending host queries and must
 * cooperate with cancellation. Blocking I/O must be moved to an appropriate
 * dispatcher by the host.
 */
fun interface TerminalFuzzyPathProvider {
    /**
     * Returns host-bounded path entries matching the context's active prefix in
     * descending relevance.
     *
     * @param request immutable completion request whose working-directory URI
     * scopes host-relative paths.
     * @param context semantic completion context resolved once by the engine.
     * @return immutable path entries within the provider's independent host
     * query budget, or an empty list when no match is available.
     */
    suspend fun entries(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
    ): List<TerminalFuzzyPathEntry>
}

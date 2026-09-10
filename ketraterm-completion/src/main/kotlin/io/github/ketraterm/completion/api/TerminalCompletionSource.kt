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
 * Suspending completion source contract for one provider such as static
 * command specs, path completion, or IDE context.
 */
fun interface TerminalCompletionSource {
    /**
     * Returns candidates produced by this source for [request] and [context].
     *
     * Implementations may suspend for bounded host I/O. Cancellation must stop
     * obsolete work promptly. Sources do not launch child coroutines; the
     * merged engine owns parallel collection.
     *
     * @param request command-line completion context.
     * @param context parsed semantic context shared by the merged engine.
     * @param limit maximum candidates this source may return.
     * @return ordered candidates from this source.
     */
    suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate>
}

/**
 * Source registration consumed by merged completion engines.
 *
 * @property source completion source to query.
 * @property priority small cold-start prior added to this source's reciprocal-rank
 * contribution. The merged engine clamps values to `[-20, 20]`.
 */
data class TerminalCompletionSourceEntry
    @JvmOverloads
    constructor(
        val source: TerminalCompletionSource,
        val priority: Int = 0,
    )

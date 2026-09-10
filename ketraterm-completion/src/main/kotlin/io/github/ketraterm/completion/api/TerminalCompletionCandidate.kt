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

import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

/**
 * Completion candidate category used by hosts for grouping, ranking display,
 * and source-specific styling.
 */
enum class TerminalCompletionCandidateKind {
    /** Top-level executable or shell command candidate. */
    COMMAND,

    /** Command-specific subcommand candidate. */
    SUBCOMMAND,

    /** Command option or flag candidate. */
    OPTION,

    /** Positional argument candidate. */
    ARGUMENT,

    /** File-system path candidate. */
    PATH,
}

/**
 * Immutable completion candidate returned by [TerminalCompletionEngine].
 *
 * @property replacementText text to insert into [replacementStartOffset],
 * exclusive [replacementEndOffset].
 * @property replacementStartOffset inclusive UTF-16 replacement start offset in
 * the original request command line.
 * @property replacementEndOffset exclusive UTF-16 replacement end offset in the
 * original request command line.
 * @property source compact source label, such as `spec`, `learned`, or `path`.
 * @property kind semantic candidate category.
 * @property displayText primary text shown in suggestion UI.
 * @property detail optional secondary text explaining the candidate.
 * @property score source-local relevance before merging and the final fused
 * ranking score after the merged engine returns the candidate; larger values
 * are better in either scope.
 * @property valueDomain dynamic value domain for argument candidates supplied by
 * host-owned providers.
 * @property matchedRanges immutable ordered UTF-16 intervals relative to
 * [displayText] used by UI components to highlight matching fragments.
 * @throws IllegalArgumentException if text/source fields are empty, replacement
 * offsets do not form a nonnegative ordered range, or [matchedRanges] do not
 * address [displayText].
 */
data class TerminalCompletionCandidate
    @JvmOverloads
    constructor(
        val replacementText: String,
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
        val source: String,
        val kind: TerminalCompletionCandidateKind,
        val displayText: String = replacementText,
        val detail: String = "",
        val score: Int = 0,
        val valueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
        val matchedRanges: TerminalCompletionMatchRanges = TerminalCompletionMatchRanges.EMPTY,
    ) {
        init {
            require(replacementText.isNotEmpty()) { "replacementText must not be empty" }
            require(displayText.isNotEmpty()) { "displayText must not be empty" }
            require(source.isNotBlank()) { "source must not be blank" }
            require(replacementStartOffset >= 0) {
                "replacementStartOffset must be >= 0, was $replacementStartOffset"
            }
            require(replacementEndOffset >= replacementStartOffset) {
                "replacementEndOffset must be >= replacementStartOffset, was $replacementEndOffset"
            }
            matchedRanges.requireValidFor(displayText)
        }
    }

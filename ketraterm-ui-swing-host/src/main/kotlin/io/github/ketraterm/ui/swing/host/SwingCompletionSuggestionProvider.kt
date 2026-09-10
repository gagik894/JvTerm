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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.*

/**
 * Host-neutral adapter from the pure completion engine to Swing suggestions.
 *
 * The adapter reads [contextProvider] for every request so hosts can publish
 * current profile and working-directory state without rebuilding the engine.
 *
 * @param engine pure progressive completion engine. This adapter does not select
 * a dispatcher; the owning suggestion caller controls its coroutine context.
 * @param contextProvider supplier for current host-owned request metadata.
 */
class SwingCompletionSuggestionProvider(
    private val engine: TerminalCompletionEngine,
    private val contextProvider: () -> SwingCompletionContext = { SwingCompletionContext.EMPTY },
) : SwingShellSuggestionProvider {
    /**
     * Returns progressive candidate snapshots adapted to the reusable Swing popup contract.
     *
     * @param request visible command text, cursor, and popup anchor supplied by Swing.
     * @return cold ordered Swing suggestion snapshots, or one empty snapshot when conversion is invalid.
     */
    override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> {
        val requestContext = contextProvider()
        val completionRequest =
            try {
                TerminalCompletionRequest(
                    commandLine = request.commandText,
                    cursorOffset = request.cursorOffset,
                    workingDirectoryUri = requestContext.workingDirectoryUri,
                    profileId = requestContext.profileId,
                    shellCapabilities = requestContext.shellCapabilities,
                )
            } catch (_: IllegalArgumentException) {
                return flowOf(emptyList())
            }
        return engine
            .completions(completionRequest)
            .map { candidates -> candidates.map { it.toSwingSuggestion(requestContext) } }
    }

    private companion object {
        private fun TerminalCompletionCandidate.toSwingSuggestion(requestContext: SwingCompletionContext): SwingShellSuggestion =
            SwingShellSuggestion(
                replacementText = replacementText,
                replacementStartOffset = replacementStartOffset,
                replacementEndOffset = replacementEndOffset,
                source = source,
                sourceDisplayText = source.toDisplayText(),
                kind = kind.name,
                displayText = displayText,
                detail = detail,
                accentRole = SwingShellSuggestionAccentRole.from(kind.name, source),
                interactionContext = requestContext,
                matchedRanges =
                    SwingShellSuggestionMatchRanges.fromPackedOffsets(
                        displayText,
                        matchedRanges.copyPackedOffsets(),
                    ),
            )

        private fun String.toDisplayText(): String {
            val normalized = trim().boundedSourceLabel().lowercase(Locale.ROOT).boundedSourceLabel()
            return SOURCE_DISPLAY_TEXT[normalized]
                ?: run {
                    normalized
                        .removePrefix("intellij-")
                        .humanizeSourceIdentifier()
                        .replaceFirstChar { character -> character.titlecase(Locale.ROOT) }
                        .boundedSourceLabel()
                }
        }

        private fun String.humanizeSourceIdentifier(): String {
            val result = StringBuilder(length.coerceAtMost(MAXIMUM_SOURCE_LABEL_CODE_UNITS))
            var separatorPending = false
            for (character in this) {
                if (character == '-' || character == '_' || character.isWhitespace()) {
                    separatorPending = result.isNotEmpty()
                } else {
                    if (separatorPending) result.append(' ')
                    result.append(character)
                    separatorPending = false
                }
            }
            return result.toString().ifEmpty { "other" }
        }

        private fun String.boundedSourceLabel(): String {
            if (length <= MAXIMUM_SOURCE_LABEL_CODE_UNITS) return this
            var retainedLength = MAXIMUM_SOURCE_LABEL_CODE_UNITS - ELLIPSIS.length
            if (
                Character.isHighSurrogate(this[retainedLength - 1]) && Character.isLowSurrogate(this[retainedLength])
            ) {
                retainedLength--
            }
            return substring(0, retainedLength).trimEnd() + ELLIPSIS
        }

        private const val MAXIMUM_SOURCE_LABEL_CODE_UNITS = 128
        private const val ELLIPSIS = "…"
        private val SOURCE_DISPLAY_TEXT =
            mapOf(
                "spec" to "Built-in",
                "learned" to "Learned",
                "observed" to "Learned",
                "path" to "Path",
                "intellij-project-file" to "Project",
                "intellij-gradle-task" to "Gradle",
                "intellij-git-branch" to "Git",
                "intellij-git-remote-branch" to "Git",
                "intellij-git-tag" to "Git",
                "intellij-git-commit" to "Git",
                "intellij-git-status-path" to "Git",
            )
    }
}

/**
 * Immutable host metadata attached to a Swing completion request.
 *
 * @property profileId stable host profile id, or `null` when unknown.
 * @property workingDirectoryUri current authoritative working-directory URI.
 * @property shellCapabilities explicit shell lexical and replacement policy.
 */
data class SwingCompletionContext
    @JvmOverloads
    constructor(
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ) {
        companion object {
            /** Empty context for hosts without profile or directory metadata. */
            @JvmField
            val EMPTY: SwingCompletionContext = SwingCompletionContext()
        }
    }

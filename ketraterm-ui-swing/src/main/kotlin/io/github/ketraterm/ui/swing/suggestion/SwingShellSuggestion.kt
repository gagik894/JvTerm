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
package io.github.ketraterm.ui.swing.suggestion

import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.regex.Pattern

/**
 * Host-provided shell suggestion shown by the reusable Swing terminal popup.
 *
 * The Swing layer only presents and selects suggestions. The host/provider
 * owns choosing the replacement text, range, source, and semantic kind; Swing
 * validates that contract before applying the default command-line edit.
 *
 * @property replacementText text the provider intends to insert or use for
 * command-line replacement after acceptance.
 * @property displayText primary text shown in the popup.
 * @property detail secondary contextual text shown with [displayText], such as
 * flags, path context, or a short description.
 * @property source stable provider identifier used for feedback and learning,
 * such as `history`, `path`, or `intellij-git-branch`.
 * @property sourceDisplayText compact user-facing source label. This is kept
 * separate from [source] so renderers never need to infer presentation from a
 * provider identifier.
 * @property kind compact semantic candidate kind label supplied by the host.
 * @property accentRole stable visual category supplied by the host or derived
 * from [kind] for generic providers.
 * @property interactionContext opaque host-owned request context preserved by
 * Swing and returned unchanged with acceptance or dismissal feedback.
 * @property replacementStartOffset inclusive UTF-16 start offset in the request
 * command text.
 * @property replacementEndOffset exclusive UTF-16 end offset in the request
 * command text.
 * @property matchedRanges immutable ordered UTF-16 intervals relative to
 * [displayText] used to visually highlight matching fragments.
 * @throws IllegalArgumentException if text/source/kind fields are invalid,
 * replacement offsets are unordered, or [matchedRanges] do not address
 * [displayText].
 */
data class SwingShellSuggestion
    @JvmOverloads
    constructor(
        val replacementText: String,
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
        val source: String,
        val kind: String,
        val displayText: String = replacementText,
        val detail: String = "",
        val accentRole: SwingShellSuggestionAccentRole = SwingShellSuggestionAccentRole.from(kind, source),
        val matchedRanges: SwingShellSuggestionMatchRanges = SwingShellSuggestionMatchRanges.EMPTY,
        val sourceDisplayText: String = source,
        val interactionContext: Any? = null,
    ) {
        init {
            require(replacementText.isNotEmpty()) { "replacementText must not be empty" }
            require(displayText.isNotEmpty()) { "displayText must not be empty" }
            require(source.isNotBlank()) { "source must not be blank" }
            require(sourceDisplayText.isNotBlank()) { "sourceDisplayText must not be blank" }
            require(kind.isNotBlank()) { "kind must not be blank" }
            require(replacementStartOffset >= 0) {
                "replacementStartOffset must be >= 0, was $replacementStartOffset"
            }
            require(replacementEndOffset >= replacementStartOffset) {
                "replacementEndOffset must be >= replacementStartOffset, was " +
                    "$replacementEndOffset < $replacementStartOffset"
            }
            matchedRanges.requireValidFor(displayText)
        }
    }

/**
 * Stable presentation category for one shell suggestion.
 *
 * Hosts should provide this from their semantic completion vocabulary rather
 * than making the popup infer visual meaning from source-label text.
 */
enum class SwingShellSuggestionAccentRole {
    /** Command or subcommand completion. */
    COMMAND,

    /** Filesystem or project-path completion. */
    PATH,

    /** Command option-name completion. */
    OPTION,

    /** Completion derived from learned command history. */
    HISTORY,

    /** A value that does not belong to another presentation category. */
    OTHER,
    ;

    companion object {
        /**
         * Derives a conservative visual category for generic providers that do
         * not expose a richer semantic completion vocabulary.
         */
        fun from(
            kind: String,
            source: String,
        ): SwingShellSuggestionAccentRole =
            when {
                source.equals("learned", ignoreCase = true) ||
                    source.equals("observed", ignoreCase = true) -> HISTORY
                kind.equals("PATH", ignoreCase = true) -> PATH
                kind.equals("OPTION", ignoreCase = true) -> OPTION
                kind.equals("COMMAND", ignoreCase = true) || kind.equals("SUBCOMMAND", ignoreCase = true) -> COMMAND
                else -> OTHER
            }
    }
}

/**
 * Validated command-line replacement plan for one shell suggestion.
 *
 * The plan is derived from the suggestion's replacement range and the request
 * cursor. Hosts can use [commandTextAfterReplacement] for learning/feedback
 * while the default Swing acceptance handler uses the delete counts to emit
 * terminal editing keys before pasting [replacementText].
 *
 * @property startOffset inclusive UTF-16 start offset replaced in the request
 * command text.
 * @property endOffset exclusive UTF-16 end offset replaced in the request
 * command text.
 * @property replacementText text pasted after the deletion keys are emitted.
 * @property deleteBeforeCursorCount number of Backspace key events needed
 * before paste.
 * @property deleteAfterCursorCount number of Delete key events needed before
 * any Backspace key events.
 */
data class SwingShellSuggestionReplacement(
    val startOffset: Int,
    val endOffset: Int,
    val replacementText: String,
    val deleteBeforeCursorCount: Int,
    val deleteAfterCursorCount: Int,
) {
    init {
        require(startOffset >= 0) { "startOffset must be >= 0, was $startOffset" }
        require(endOffset >= startOffset) { "endOffset must be >= startOffset, was $endOffset < $startOffset" }
        require(replacementText.isNotEmpty()) { "replacementText must not be empty" }
        require(deleteBeforeCursorCount >= 0) {
            "deleteBeforeCursorCount must be >= 0, was $deleteBeforeCursorCount"
        }
        require(deleteAfterCursorCount >= 0) {
            "deleteAfterCursorCount must be >= 0, was $deleteAfterCursorCount"
        }
    }
}

/**
 * Returns the validated replacement plan for this suggestion and request.
 *
 * Replacement ranges are validated against the command text and request cursor.
 * Malformed UTF-16 offsets and offsets inside an extended grapheme cluster
 * return `null` instead of producing partial user-perceived characters.
 *
 * @param request command-line request that produced this suggestion.
 * @return validated replacement plan, or `null` when the request/range is invalid.
 */
fun SwingShellSuggestion.replacementFor(request: SwingShellSuggestionRequest): SwingShellSuggestionReplacement? {
    if (!request.commandText.isUtf16Boundary(request.cursorOffset)) return null
    if (replacementStartOffset > request.cursorOffset) return null
    if (request.cursorOffset > replacementEndOffset) return null
    if (replacementEndOffset > request.commandText.length) return null
    if (!request.commandText.isUtf16Boundary(replacementStartOffset)) return null
    if (!request.commandText.isUtf16Boundary(replacementEndOffset)) return null
    val deletionCounts =
        request.commandText.graphemeDeletionCounts(
            startOffset = replacementStartOffset,
            cursorOffset = request.cursorOffset,
            endOffset = replacementEndOffset,
        ) ?: return null
    return SwingShellSuggestionReplacement(
        startOffset = replacementStartOffset,
        endOffset = replacementEndOffset,
        replacementText = replacementText,
        deleteBeforeCursorCount = deletionCounts.beforeCursor,
        deleteAfterCursorCount = deletionCounts.afterCursor,
    )
}

/**
 * Returns the command text that would result from accepting this suggestion.
 *
 * This is the host-learning companion to [replacementFor]: it uses the same
 * UTF-16 and grapheme-cluster rules as the default acceptance handler.
 *
 * @param request command-line request that produced this suggestion.
 * @return resulting command text, or `null` when the request/range is invalid.
 */
fun SwingShellSuggestion.commandTextAfterReplacement(request: SwingShellSuggestionRequest): String? {
    val replacement = replacementFor(request) ?: return null
    return request.commandText.replaceRange(
        replacement.startOffset,
        replacement.endOffset,
        replacement.replacementText,
    )
}

/**
 * Snapshot of the currently visible shell suggestion popup state.
 *
 * @property visible whether the popup is currently visible.
 * @property count number of retained suggestions.
 * @property selectedIndex selected suggestion index, or `-1` when none.
 * @property anchorColumn terminal-grid column used as the popup anchor.
 * @property anchorRow terminal-grid row used as the popup anchor.
 * @property selectedSuggestion selected suggestion, or `null` when no
 * suggestion is selected.
 */
data class SwingShellSuggestionState(
    val visible: Boolean,
    val count: Int,
    val selectedIndex: Int,
    val anchorColumn: Int,
    val anchorRow: Int,
    val selectedSuggestion: SwingShellSuggestion?,
) {
    companion object {
        /**
         * Empty state used when the popup is hidden.
         */
        @JvmField
        val EMPTY: SwingShellSuggestionState =
            SwingShellSuggestionState(
                visible = false,
                count = 0,
                selectedIndex = -1,
                anchorColumn = 0,
                anchorRow = 0,
                selectedSuggestion = null,
            )
    }
}

/**
 * Immutable command-line context used to request shell suggestions.
 *
 * @property commandText visible command-line text known to the provider.
 * @property cursorOffset UTF-16 cursor offset within [commandText].
 * @property anchorColumn terminal-grid column used as the popup anchor.
 * @property anchorRow terminal-grid row used as the popup anchor.
 */
data class SwingShellSuggestionRequest(
    val commandText: String,
    val cursorOffset: Int,
    val anchorColumn: Int,
    val anchorRow: Int,
) {
    init {
        require(cursorOffset in 0..commandText.length) {
            "cursorOffset must be in 0..${commandText.length}, was $cursorOffset"
        }
        require(anchorColumn >= 0) { "anchorColumn must be >= 0, was $anchorColumn" }
        require(anchorRow >= 0) { "anchorRow must be >= 0, was $anchorRow" }
    }

    companion object {
        /**
         * Empty request used only while the popup is hidden.
         */
        internal val EMPTY: SwingShellSuggestionRequest =
            SwingShellSuggestionRequest(
                commandText = "",
                cursorOffset = 0,
                anchorColumn = 0,
                anchorRow = 0,
            )
    }
}

/**
 * Host/provider result accepted by the user.
 *
 * @property suggestion accepted suggestion.
 * @property index global rank of [suggestion] in the current ranked snapshot.
 * @property request command-line context that produced the accepted suggestion.
 */
data class SwingShellSuggestionAcceptance(
    val suggestion: SwingShellSuggestion,
    val index: Int,
    val request: SwingShellSuggestionRequest,
)

/**
 * Feedback kind emitted by the reusable suggestion popup.
 */
enum class SwingShellSuggestionFeedbackKind {
    /**
     * The user accepted the selected suggestion.
     */
    ACCEPTED,

    /**
     * The user explicitly dismissed the selected suggestion.
     */
    DISMISSED,
}

/**
 * User feedback event for the selected shell suggestion.
 *
 * @property kind feedback category.
 * @property suggestion suggestion that was accepted or explicitly dismissed.
 * @property index index of [suggestion] in the displayed list.
 * @property request command-line context that produced the suggestion.
 */
data class SwingShellSuggestionFeedback(
    val kind: SwingShellSuggestionFeedbackKind,
    val suggestion: SwingShellSuggestion,
    val index: Int,
    val request: SwingShellSuggestionRequest,
)

/**
 * Host provider for progressive shell-suggestion snapshots.
 */
fun interface SwingShellSuggestionProvider {
    /**
     * Returns a cold stream of suggestions for [request].
     *
     * The reusable Swing terminal invokes this on [kotlinx.coroutines.Dispatchers.Default],
     * outside the Swing Event Dispatch Thread. Implementations must cooperate
     * with cancellation when a newer request replaces the current one.
     *
     * @param request command-line context.
     * @return cold stream of ordered suggestion snapshots, best first.
     */
    fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>>

    companion object {
        /**
         * Provider that returns no suggestions.
         */
        @JvmField
        val NONE: SwingShellSuggestionProvider = SwingShellSuggestionProvider { flowOf(emptyList()) }
    }
}

/**
 * Listener notified immediately before command-affecting terminal input is submitted.
 *
 * Listeners are registered on one [io.github.ketraterm.ui.swing.api.SwingTerminal]
 * and invoked synchronously on the Swing Event Dispatch Thread after its active
 * provider collection is cancelled and its popup is hidden.
 */
fun interface SwingShellSuggestionInvalidationListener {
    /** Called on the Swing Event Dispatch Thread before command-affecting input is submitted. */
    fun onShellSuggestionsInvalidated()
}

/**
 * Listener for automatic shell-suggestion eligibility changes.
 *
 * Eligibility is false while the terminal is outside its live viewport or the
 * current Swing settings disable automatic suggestions. Callbacks are invoked
 * synchronously on the Swing Event Dispatch Thread after ineligible component
 * work has been cancelled and hidden.
 */
fun interface SwingShellSuggestionEligibilityListener {
    /**
     * Reports the latest automatic-suggestion eligibility.
     *
     * @param eligible `true` only when automatic suggestions are enabled and
     * the terminal is displaying its live viewport.
     */
    fun onAutomaticShellSuggestionEligibilityChanged(eligible: Boolean)
}

/**
 * Host callback invoked for suggestion acceptance and explicit dismissal.
 */
fun interface SwingShellSuggestionFeedbackHandler {
    /**
     * Handles one user feedback event.
     *
     * @param feedback selected suggestion and feedback kind.
     */
    fun onSuggestionFeedback(feedback: SwingShellSuggestionFeedback)

    companion object {
        /**
         * Feedback handler that ignores all popup feedback.
         */
        @JvmField
        val NONE: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler { }
    }
}

/**
 * Host callback invoked when the user accepts a shell suggestion.
 */
fun interface SwingShellSuggestionHandler {
    /**
     * Handles an accepted suggestion.
     *
     * @param acceptance accepted suggestion and the request that produced it.
     */
    fun onSuggestionAccepted(acceptance: SwingShellSuggestionAcceptance)

    companion object {
        /**
         * Handler that ignores accepted suggestions.
         */
        @JvmField
        val NONE: SwingShellSuggestionHandler = SwingShellSuggestionHandler { }

        /**
         * Creates a standard command-line replacement suggestion handler.
         *
         * The default handler is Unicode-aware: it computes grapheme-cluster
         * counts for generated Delete and Backspace events before pasting the
         * accepted suggestion replacement.
         *
         * @param session active input encoder used to submit the replacement event.
         * @return standard replacement suggestion handler.
         */
        @JvmStatic
        fun createDefault(session: TerminalInputEncoder): SwingShellSuggestionHandler =
            SwingShellSuggestionHandler { acceptance ->
                val request = acceptance.request
                val replacement = acceptance.suggestion.replacementFor(request) ?: return@SwingShellSuggestionHandler

                session.encodeTextReplacement(
                    TerminalTextReplacementEvent(
                        deleteAfterCursorCount = replacement.deleteAfterCursorCount,
                        deleteBeforeCursorCount = replacement.deleteBeforeCursorCount,
                        replacementText = replacement.replacementText,
                    ),
                )
            }
    }
}

private data class GraphemeDeletionCounts(
    val beforeCursor: Int,
    val afterCursor: Int,
)

private fun String.graphemeDeletionCounts(
    startOffset: Int,
    cursorOffset: Int,
    endOffset: Int,
): GraphemeDeletionCounts? {
    var beforeCursor = 0
    var afterCursor = 0
    var startIsBoundary = startOffset == 0
    var cursorIsBoundary = cursorOffset == 0
    var endIsBoundary = endOffset == 0
    val matcher = EXTENDED_GRAPHEME_CLUSTER.matcher(this)
    while (matcher.find()) {
        val clusterStart = matcher.start()
        val clusterEnd = matcher.end()
        if (clusterStart == startOffset || clusterEnd == startOffset) startIsBoundary = true
        if (clusterStart == cursorOffset || clusterEnd == cursorOffset) cursorIsBoundary = true
        if (clusterStart == endOffset || clusterEnd == endOffset) endIsBoundary = true

        when {
            clusterStart >= startOffset && clusterEnd <= cursorOffset -> beforeCursor++
            clusterStart >= cursorOffset && clusterEnd <= endOffset -> afterCursor++
        }
        if (clusterEnd >= endOffset) break
    }
    if (!startIsBoundary || !cursorIsBoundary || !endIsBoundary) return null
    return GraphemeDeletionCounts(beforeCursor, afterCursor)
}

private fun String.isUtf16Boundary(offset: Int): Boolean {
    if (offset !in 0..length) return false
    val afterHighSurrogate = offset > 0 && Character.isHighSurrogate(this[offset - 1])
    val beforeLowSurrogate = offset < length && Character.isLowSurrogate(this[offset])
    return !afterHighSurrogate && !beforeLowSurrogate
}

private val EXTENDED_GRAPHEME_CLUSTER: Pattern = Pattern.compile("\\X")

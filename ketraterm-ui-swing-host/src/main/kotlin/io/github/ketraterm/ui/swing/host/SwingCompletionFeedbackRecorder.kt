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

import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.commandTextAfterReplacement

/**
 * Shared adapter from Swing suggestion feedback to completion statistics.
 *
 * Hosts inject the exact-feedback sink. This keeps Swing-to-completion
 * vocabulary mapping identical while leaving learning, privacy, persistence,
 * and thread ownership in the product host.
 *
 * @param recordSuggestionFeedback host-owned exact-feedback sink.
 * @param clockEpochMillis host wall-clock supplier.
 */
class SwingCompletionFeedbackRecorder(
    private val recordSuggestionFeedback: (
        commandLine: String,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
    ) -> Unit,
    private val clockEpochMillis: () -> Long = System::currentTimeMillis,
) {
    /** Creates a handler that records feedback against each suggestion's immutable request context. */
    fun createHandler(): SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler(::record)

    /**
     * Validates and records one accepted or dismissed suggestion.
     *
     * @param feedback Swing popup feedback event.
     */
    fun record(feedback: SwingShellSuggestionFeedback) {
        val context = feedback.suggestion.interactionContext as? SwingCompletionContext ?: return
        val commandLine =
            feedback.suggestion
                .commandTextAfterReplacement(feedback.request)
                ?.takeUnless(String::isBlank)
                ?: return
        val feedbackAtEpochMillis = clockEpochMillis()
        recordSuggestionFeedback(
            commandLine,
            feedback.kind.toCompletionKind(),
            context.profileId,
            context.workingDirectoryUri,
            feedbackAtEpochMillis,
        )
    }

    private companion object {
        private fun SwingShellSuggestionFeedbackKind.toCompletionKind(): TerminalCompletionFeedbackKind =
            when (this) {
                SwingShellSuggestionFeedbackKind.ACCEPTED -> TerminalCompletionFeedbackKind.ACCEPTED
                SwingShellSuggestionFeedbackKind.DISMISSED -> TerminalCompletionFeedbackKind.DISMISSED
            }
    }
}

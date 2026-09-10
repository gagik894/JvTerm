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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingCompletionFeedbackRecorderTest {
    @Test
    fun `accepted range suggestion records opaque ranking evidence and publishes snapshot`() {
        val source = TerminalCompletionLearningStore()
        val published = ArrayList<TerminalCompletionLearningSnapshot>()
        val recorder = recorder(source, afterMutation = published::add, clockEpochMillis = { 1_000L })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "git status",
                    replacementStartOffset = 0,
                    replacementEndOffset = 5,
                ),
        )

        val snapshot = source.snapshot()
        val ranking = snapshot.rankingStats.single()
        assertTrue(snapshot.replayCommands.isEmpty())
        assertEquals("bash", ranking.profileId)
        assertEquals("file:///repo/", ranking.workingDirectoryUri)
        assertEquals(1, ranking.acceptedCount)
        assertEquals(1_000L, ranking.lastUsedEpochMillis)
        assertEquals(snapshot, published.single())
    }

    @Test
    fun `dismissed token suggestion records only opaque evidence`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            val recorder = recorder(source, clockEpochMillis = { 2_000L })

            recorder.record(
                feedback =
                    feedback(
                        kind = SwingShellSuggestionFeedbackKind.DISMISSED,
                        commandText = "git s",
                        replacementText = "status",
                        replacementStartOffset = 4,
                        replacementEndOffset = 5,
                    ),
            )

            val snapshot = source.snapshot()
            val ranking = snapshot.rankingStats.single()
            assertTrue(snapshot.replayCommands.isEmpty())
            assertEquals("bash", ranking.profileId)
            assertEquals("file:///repo/", ranking.workingDirectoryUri)
            assertEquals(1, ranking.dismissedCount)
            assertEquals(2_000L, ranking.lastUsedEpochMillis)
            assertTrue(
                TerminalCompletionEngines
                    .fromSources(
                        sources = emptyList(),
                        commandSpecs = emptyList(),
                        learningStore = source,
                    ).completions(completionRequest("git s"))
                    .last()
                    .isEmpty(),
            )
        }

    @Test
    fun `unknown suggestion kind still records exact command feedback`() {
        val source = TerminalCompletionLearningStore()
        val recorder = recorder(source, clockEpochMillis = { 1_500L })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "git status",
                    replacementStartOffset = 0,
                    replacementEndOffset = 5,
                    suggestionKindName = "custom",
                ),
        )

        assertEquals(
            1,
            source
                .snapshot()
                .rankingStats
                .single()
                .acceptedCount,
        )
    }

    @Test
    fun `invalid replacement range is ignored and does not publish`() {
        val source = TerminalCompletionLearningStore()
        var publishCount = 0
        val recorder = recorder(source, afterMutation = { publishCount++ })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "git status",
                    replacementStartOffset = 0,
                    replacementEndOffset = 99,
                ),
        )

        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, source.snapshot())
        assertEquals(0, publishCount)
    }

    @Test
    fun `explicit Unicode range records acceptance without replay text`() {
        val source = TerminalCompletionLearningStore()
        val published = ArrayList<TerminalCompletionLearningSnapshot>()
        val recorder = recorder(source, afterMutation = published::add, clockEpochMillis = { 2_500L })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "echo \uD83D\uDE02",
                    replacementText = "ok",
                    replacementStartOffset = 5,
                    replacementEndOffset = "echo \uD83D\uDE02".length,
                    suggestionKind = TerminalCompletionCandidateKind.ARGUMENT,
                ),
        )

        assertTrue(source.snapshot().replayCommands.isEmpty())
        assertEquals(
            1,
            source
                .snapshot()
                .rankingStats
                .single()
                .acceptedCount,
        )
        assertTrue(published.single().replayCommands.isEmpty())
    }

    @Test
    fun `explicit Unicode range with malformed cursor is ignored`() {
        val source = TerminalCompletionLearningStore()
        var publishCount = 0
        val recorder = recorder(source, afterMutation = { publishCount++ })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "echo \uD83D\uDE02",
                    replacementText = "ok",
                    replacementStartOffset = 5,
                    replacementEndOffset = "echo \uD83D\uDE02".length,
                    cursorOffset = 6,
                    suggestionKind = TerminalCompletionCandidateKind.ARGUMENT,
                ),
        )

        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, source.snapshot())
        assertEquals(0, publishCount)
    }

    @Test
    fun `created handler records the suggestion request context instead of latest host context`() =
        runBlocking {
            val source = TerminalCompletionLearningStore()
            val recorder = recorder(source, clockEpochMillis = { 3_000L })
            var workingDirectoryUri = "file:///first"
            val provider =
                SwingCompletionSuggestionProvider(
                    engine =
                        TerminalCompletionEngine {
                            flowOf(
                                listOf(
                                    TerminalCompletionCandidate(
                                        replacementText = "npm test",
                                        replacementStartOffset = 0,
                                        replacementEndOffset = 5,
                                        source = "spec",
                                        kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                                    ),
                                ),
                            )
                        },
                    contextProvider = { context(workingDirectoryUri) },
                )
            val request = SwingShellSuggestionRequest("npm t", 5, 5, 0)
            val suggestion = provider.suggestions(request).last().single()

            workingDirectoryUri = "file:///second"
            recorder.createHandler().onSuggestionFeedback(
                SwingShellSuggestionFeedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    suggestion = suggestion,
                    index = 0,
                    request = request,
                ),
            )

            assertEquals(
                "file:///first/",
                source
                    .snapshot()
                    .rankingStats
                    .single()
                    .workingDirectoryUri,
            )
        }

    @Test
    fun `queued feedback keeps the event timestamp`() {
        val source = TerminalCompletionLearningStore()
        val queued = ArrayList<() -> Unit>()
        var currentTime = 1_000L
        val recorder =
            SwingCompletionFeedbackRecorder(
                recordSuggestionFeedback = { commandLine, feedback, profileId, workingDirectoryUri, timestamp ->
                    queued += {
                        source.recordSuggestionFeedback(commandLine, feedback, profileId, workingDirectoryUri, timestamp)
                    }
                },
                clockEpochMillis = { currentTime },
            )

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "status",
                    replacementStartOffset = 4,
                    replacementEndOffset = 5,
                    suggestionKind = TerminalCompletionCandidateKind.ARGUMENT,
                    interactionContext = SwingCompletionContext(profileId = "bash"),
                ),
        )
        currentTime = 2_000L
        queued.single().invoke()

        val snapshot = source.snapshot()
        assertEquals(1_000L, snapshot.rankingStats.single().lastUsedEpochMillis)
    }

    private fun recorder(
        source: TerminalCompletionLearningStore,
        afterMutation: ((TerminalCompletionLearningSnapshot) -> Unit)? = null,
        clockEpochMillis: () -> Long = System::currentTimeMillis,
    ): SwingCompletionFeedbackRecorder =
        SwingCompletionFeedbackRecorder(
            recordSuggestionFeedback = { commandLine, feedback, profileId, workingDirectoryUri, timestamp ->
                if (source.recordSuggestionFeedback(commandLine, feedback, profileId, workingDirectoryUri, timestamp)) {
                    afterMutation?.invoke(source.snapshot())
                }
            },
            clockEpochMillis = clockEpochMillis,
        )

    private fun context(workingDirectoryUri: String = "file:///repo"): SwingCompletionContext =
        SwingCompletionContext(
            profileId = "bash",
            workingDirectoryUri = workingDirectoryUri,
        )

    private fun feedback(
        kind: SwingShellSuggestionFeedbackKind,
        commandText: String,
        replacementText: String,
        replacementStartOffset: Int,
        replacementEndOffset: Int,
        cursorOffset: Int = commandText.length,
        source: String = "spec",
        suggestionKind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
        suggestionKindName: String = suggestionKind.name,
        interactionContext: Any? = context(),
    ): SwingShellSuggestionFeedback =
        SwingShellSuggestionFeedback(
            kind = kind,
            suggestion =
                SwingShellSuggestion(
                    replacementText = replacementText,
                    replacementStartOffset = replacementStartOffset,
                    replacementEndOffset = replacementEndOffset,
                    source = source,
                    kind = suggestionKindName,
                    interactionContext = interactionContext,
                ),
            index = 0,
            request =
                SwingShellSuggestionRequest(
                    commandText = commandText,
                    cursorOffset = cursorOffset,
                    anchorColumn = cursorOffset,
                    anchorRow = 0,
                ),
        )

    private fun completionRequest(commandLine: String): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )
}

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
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAccentRole
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SwingCompletionSuggestionProviderTest {
    @Test
    fun `forwards live host context and adapts candidates`() =
        runBlocking {
            lateinit var captured: TerminalCompletionRequest
            val requestContext =
                SwingCompletionContext(
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    shellCapabilities = TerminalShellCapabilities.POSIX,
                )
            val provider =
                SwingCompletionSuggestionProvider(
                    engine =
                        TerminalCompletionEngine { request ->
                            captured = request
                            flowOf(
                                listOf(
                                    TerminalCompletionCandidate(
                                        replacementText = "status",
                                        replacementStartOffset = 4,
                                        replacementEndOffset = 7,
                                        source = "spec",
                                        kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                                        displayText = "status",
                                        detail = "show status",
                                        matchedRanges =
                                            TerminalCompletionMatchRanges.fromPackedOffsets(
                                                "status",
                                                intArrayOf(0, 2),
                                            ),
                                    ),
                                ),
                            )
                        },
                    contextProvider = { requestContext },
                )

            val suggestions = provider.suggestions(request("git ste", cursorOffset = 6)).last()

            assertEquals("bash", captured.profileId)
            assertEquals("file:///repo", captured.workingDirectoryUri)
            assertEquals(TerminalShellCapabilities.POSIX, captured.shellCapabilities)
            assertEquals("status", suggestions.single().replacementText)
            assertEquals(4, suggestions.single().replacementStartOffset)
            assertEquals(7, suggestions.single().replacementEndOffset)
            assertEquals("show status", suggestions.single().detail)
            assertEquals("SUBCOMMAND", suggestions.single().kind)
            assertEquals("Built-in", suggestions.single().sourceDisplayText)
            assertSame(requestContext, suggestions.single().interactionContext)
            assertContentEquals(intArrayOf(0, 2), suggestions.single().matchedRanges.copyPackedOffsets())
        }

    @Test
    fun `maps provider identifiers once into bounded renderer-neutral labels`() =
        runBlocking {
            val sources =
                listOf(
                    "spec",
                    "learned",
                    "observed",
                    "intellij-git-branch",
                    "intellij-gradle-task",
                    "intellij-project-file",
                    "path",
                    "intellij-git-status-path",
                    "intellij-custom_source",
                    "legitimate-provider",
                    "pathology",
                    "custom-${"x".repeat(500)}",
                )
            val provider =
                SwingCompletionSuggestionProvider(
                    TerminalCompletionEngine {
                        flowOf(
                            sources.map { source ->
                                TerminalCompletionCandidate(
                                    replacementText = source,
                                    replacementStartOffset = 0,
                                    replacementEndOffset = 0,
                                    source = source,
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                )
                            },
                        )
                    },
                )

            val suggestions = provider.suggestions(request("x", cursorOffset = 1)).last()
            val labels = suggestions.map { it.sourceDisplayText }

            assertEquals(
                listOf(
                    "Built-in",
                    "Learned",
                    "Learned",
                    "Git",
                    "Gradle",
                    "Project",
                    "Path",
                    "Git",
                    "Custom source",
                    "Legitimate provider",
                    "Pathology",
                ),
                labels.dropLast(1),
            )
            assertTrue(labels.last().startsWith("Custom "))
            assertTrue(labels.last().endsWith("…"))
            assertTrue(labels.last().length <= 128)
            assertEquals(SwingShellSuggestionAccentRole.HISTORY, suggestions[1].accentRole)
            assertEquals(SwingShellSuggestionAccentRole.HISTORY, suggestions[2].accentRole)
        }

    @Test
    fun `invalid UTF-16 cursor is rejected before engine invocation`() =
        runBlocking {
            var invoked = false
            val provider =
                SwingCompletionSuggestionProvider(
                    TerminalCompletionEngine {
                        invoked = true
                        flowOf(emptyList())
                    },
                )

            assertTrue(provider.suggestions(request("😀", cursorOffset = 1)).last().isEmpty())
            assertEquals(false, invoked)
        }

    private fun request(
        commandText: String,
        cursorOffset: Int,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
            anchorColumn = 0,
            anchorRow = 0,
        )
}

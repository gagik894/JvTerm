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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingCompletionBindingTest {
    @Test
    fun `empty binding rejects requests and feedback and installs no observation`() =
        runBlocking {
            Fixture().use { fixture ->
                val listeners = fixture.terminal.focusListeners.size
                SwingUtilities.invokeAndWait {
                    fixture.binding.update(null, true)
                    fixture.binding.feedbackHandler.onSuggestionFeedback(feedback())
                    assertFalse(fixture.binding.isEnabled)
                    assertEquals(listeners, fixture.terminal.focusListeners.size)
                }
                assertTrue(
                    fixture.binding.provider
                        .suggestions(request)
                        .toList()
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `manual-only resources accept requests and feedback without automatic observation`() =
        runBlocking {
            Fixture().use { fixture ->
                var requests = 0
                var feedbackCount = 0
                val resources =
                    SwingCompletionResources(
                        provider = {
                            requests++
                            flowOf(listOf(suggestion))
                        },
                        feedbackHandler = { feedbackCount++ },
                    )
                val listeners = fixture.terminal.focusListeners.size
                SwingUtilities.invokeAndWait { fixture.binding.update(resources, false) }
                assertEquals(
                    listOf(listOf(suggestion)),
                    fixture.binding.provider
                        .suggestions(request)
                        .toList(),
                )
                SwingUtilities.invokeAndWait {
                    fixture.binding.feedbackHandler.onSuggestionFeedback(feedback())
                    assertEquals(listeners, fixture.terminal.focusListeners.size)
                    fixture.binding.update(null, false)
                    fixture.binding.feedbackHandler.onSuggestionFeedback(feedback())
                }
                assertTrue(
                    fixture.binding.provider
                        .suggestions(request)
                        .toList()
                        .isEmpty(),
                )
                assertEquals(1, requests)
                assertEquals(1, feedbackCount)
            }
        }

    @Test
    fun `resource replacement rejects an old in-flight result`() =
        runBlocking {
            Fixture().use { fixture ->
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val old =
                    SwingCompletionResources(provider = {
                        flow {
                            started.complete(Unit)
                            release.await()
                            emit(listOf(suggestion))
                        }
                    }, feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE)
                SwingUtilities.invokeAndWait { fixture.binding.update(old, false) }
                val oldResult =
                    async {
                        fixture.binding.provider
                            .suggestions(request)
                            .toList()
                    }
                started.await()
                val replacement =
                    SwingCompletionResources(provider = { flowOf(emptyList()) }, feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE)
                SwingUtilities.invokeAndWait {
                    fixture.binding.update(null, false)
                    fixture.binding.update(replacement, false)
                }
                release.complete(Unit)
                assertTrue(oldResult.await().isEmpty())
                assertEquals(
                    listOf(emptyList()),
                    fixture.binding.provider
                        .suggestions(request)
                        .toList(),
                )
            }
        }

    @Test
    fun `automatic toggle removes and restores exactly one observer per pane`() {
        Fixture().use { first ->
            Fixture().use { second ->
                SwingUtilities.invokeAndWait {
                    val resources =
                        SwingCompletionResources(
                            provider = { flowOf(emptyList()) },
                            feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
                        )
                    val firstCount = first.terminal.focusListeners.size
                    val secondCount = second.terminal.focusListeners.size
                    repeat(3) {
                        first.binding.update(resources, true)
                        first.binding.update(resources, true)
                        second.binding.update(resources, true)
                        assertEquals(firstCount + 1, first.terminal.focusListeners.size)
                        assertEquals(secondCount + 1, second.terminal.focusListeners.size)
                        first.binding.update(resources, false)
                        assertTrue(first.binding.isEnabled)
                        assertEquals(firstCount, first.terminal.focusListeners.size)
                        first.binding.update(null, false)
                        second.binding.update(null, false)
                        assertEquals(secondCount, second.terminal.focusListeners.size)
                    }
                }
            }
        }
    }

    private class Fixture : AutoCloseable {
        val session =
            TerminalSession.create(
                TerminalBuffers.create(30, 4),
                connector =
                    object : TerminalConnector {
                        override fun start(listener: TerminalConnectorListener) = Unit

                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ) = Unit

                        override fun resize(
                            columns: Int,
                            rows: Int,
                        ) = Unit

                        override fun close() = Unit
                    },
            )
        val binding = SwingCompletionBinding(session)
        val terminal =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider = binding.provider,
                        shellSuggestionFeedbackHandler = binding.feedbackHandler,
                    ),
            )

        init {
            SwingUtilities.invokeAndWait {
                terminal.bind(session)
                binding.attach(terminal)
            }
        }

        override fun close() {
            SwingUtilities.invokeAndWait {
                binding.close()
                terminal.dispose()
            }
            session.close()
        }
    }

    private companion object {
        val request = SwingShellSuggestionRequest("git s", 5, 5, 0)
        val suggestion = SwingShellSuggestion("git status", 0, 5, "test", "COMMAND")

        fun feedback() = SwingShellSuggestionFeedback(SwingShellSuggestionFeedbackKind.ACCEPTED, suggestion, 0, request)
    }
}

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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.*
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration.Companion.milliseconds

class SwingTerminalShellSuggestionTest {
    @Test
    fun `master off rejects supplied automatic and explicit suggestions`() {
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git s".utf8())
        SwingUtilities.invokeAndWait {
            val component =
                SwingTerminal(
                    hostServices =
                        SwingHostServices(
                            shellSuggestionProvider = SwingShellSuggestionProvider { error("Disabled provider was invoked") },
                            shellSuggestionViewFactory = SwingShellSuggestionViewFactory { error("Disabled view was constructed") },
                            shellSuggestionHandler = SwingShellSuggestionHandler { error("Disabled suggestion was accepted") },
                        ),
                )
            try {
                component.bind(session)
                component.requestShellSuggestions("git s", 5, 5, 0)
                component.requestActiveShellSuggestions()
                component.showShellSuggestions(request(), suggestions())
                assertFalse(component.currentShellSuggestionState().visible)
                assertFalse(component.isAutomaticShellSuggestionEligible())
            } finally {
                component.dispose()
            }
        }
        session.close()
    }

    @Test
    fun `master off cancels explicit request while automatic popup was already off`() {
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git s".utf8())
        var settings = SwingSettings(smartSuggestionsEnabled = true, shellSuggestionsEnabled = false)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val component =
            SwingTerminal(
                settingsProvider = { settings },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider {
                                flow {
                                    started.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        cancelled.complete(Unit)
                                    }
                                }
                            },
                    ),
            )
        try {
            SwingUtilities.invokeAndWait {
                component.bind(session)
                component.requestActiveShellSuggestions()
            }
            runBlocking { withTimeout(2_000.milliseconds) { started.await() } }
            SwingUtilities.invokeAndWait {
                settings = settings.copy(smartSuggestionsEnabled = false)
                component.reloadSettings()
                assertFalse(component.currentShellSuggestionState().visible)
            }
            runBlocking { withTimeout(2_000.milliseconds) { cancelled.await() } }
        } finally {
            SwingUtilities.invokeAndWait { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `suggestion popup fits around the prompt in short terminals`() {
        SwingUtilities.invokeAndWait {
            val view =
                object : SwingShellSuggestionView {
                    override val component = JPanel().apply { preferredSize = java.awt.Dimension(320, 200) }

                    override fun update(snapshot: SwingShellSuggestionViewSnapshot) = Unit
                }
            val terminal =
                SwingTerminal(
                    settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(5, 4, 7, 6)) },
                    hostServices = SwingHostServices(shellSuggestionViewFactory = SwingShellSuggestionViewFactory { view }),
                )
            try {
                for (height in listOf(12, 100, 200, 500)) {
                    terminal.setSize(250, height)
                    for (row in listOf(0, 2, 4, 8)) {
                        terminal.showShellSuggestions(request(anchorColumn = 0, anchorRow = row), suggestions(), 0)
                        terminal.doLayout()
                        val bounds = view.component.bounds
                        assertTrue(bounds.x >= 0 && bounds.y >= 0, "Popup starts outside terminal: $bounds")
                        assertTrue(bounds.x + bounds.width <= terminal.width, "Popup exceeds terminal width: $bounds")
                        assertTrue(bounds.y + bounds.height <= height, "Popup exceeds terminal height: $bounds")
                        if (bounds.height > 0) {
                            assertTrue(bounds.y >= 5 && bounds.y + bounds.height <= height - 7)
                        }
                    }
                }
            } finally {
                terminal.dispose()
            }
        }
    }

    @Test
    fun `new suggestion request cancels the previous provider call`() {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val view = RecordingSuggestionView()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request ->
                                flow {
                                    if (request.commandText == "first") {
                                        firstStarted.complete(Unit)
                                        try {
                                            awaitCancellation()
                                        } finally {
                                            firstCancelled.complete(Unit)
                                        }
                                    }
                                    emit(suggestions(request.commandText))
                                }
                            },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.requestShellSuggestions("first", 5, 0, 0)
        }
        runBlocking { firstStarted.await() }
        SwingUtilities.invokeAndWait { component.requestShellSuggestions("second", 6, 0, 0) }
        runBlocking { firstCancelled.await() }
        val update = view.awaitUpdate()

        SwingUtilities.invokeAndWait {
            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(2, state.count)
            assertTrue(update.onEdt)
            component.dispose()
        }
    }

    @Test
    fun `provider runs on default dispatcher and publishes on EDT`() {
        val providerFactoryWasOnEdt = CompletableDeferred<Boolean>()
        val providerWasOnEdt = CompletableDeferred<Boolean>()
        val providerDispatcher = CompletableDeferred<ContinuationInterceptor?>()
        val view = RecordingSuggestionView()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request ->
                                providerFactoryWasOnEdt.complete(SwingUtilities.isEventDispatchThread())
                                flow {
                                    providerWasOnEdt.complete(SwingUtilities.isEventDispatchThread())
                                    providerDispatcher.complete(currentCoroutineContext()[ContinuationInterceptor])
                                    emit(suggestions(request.commandText))
                                }
                            },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.requestShellSuggestions("git s", 5, 0, 0)
        }
        val update = view.awaitUpdate()

        assertFalse(runBlocking { providerFactoryWasOnEdt.await() })
        assertFalse(runBlocking { providerWasOnEdt.await() })
        assertSame(Dispatchers.Default, runBlocking { providerDispatcher.await() })
        assertTrue(update.onEdt)
        SwingUtilities.invokeAndWait {
            assertTrue(component.currentShellSuggestionState().visible)
            component.dispose()
        }
    }

    @Test
    fun `replaced progressive flow cannot publish after newer request`() {
        val firstRelease = CompletableDeferred<Unit>()
        val view = RecordingSuggestionView()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request ->
                                flow {
                                    emit(suggestions(request.commandText))
                                    if (request.commandText == "first") {
                                        firstRelease.await()
                                        emit(listOf(suggestion("stale", commandText = request.commandText)))
                                    }
                                }
                            },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.requestShellSuggestions("first", 5, 0, 0)
        }
        view.awaitUpdate()
        SwingUtilities.invokeAndWait { component.requestShellSuggestions("second", 6, 0, 0) }
        view.awaitUpdate()
        firstRelease.complete(Unit)

        runBlocking { delay(100.milliseconds) }
        SwingUtilities.invokeAndWait {
            assertTrue(component.currentShellSuggestionState().visible)
            assertEquals(2, component.currentShellSuggestionState().count)
            component.dispose()
        }
    }

    @Test
    fun `shell input hides popup before invalidation listeners run`() {
        val component =
            SwingTerminal(settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) })
        val visibleDuringInvalidation = ArrayList<Boolean>()
        val listener =
            SwingShellSuggestionInvalidationListener {
                visibleDuringInvalidation += component.currentShellSuggestionState().visible
            }
        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.addShellSuggestionInvalidationListener(listener)
            component.showShellSuggestions(request(), suggestions())
            component.keyListeners.forEach { it.keyPressed(keyPressed(component, KeyEvent.VK_BACK_SPACE)) }
        }

        assertEquals(listOf(false), visibleDuringInvalidation)
        SwingUtilities.invokeAndWait { component.dispose() }
    }

    @Test
    fun `clear screen hides popup before command bytes are submitted`() {
        val session = activeSuggestionSession(RecordingConnector())
        val component =
            SwingTerminal(settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) })
        val visibleDuringInvalidation = ArrayList<Boolean>()
        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.bind(session)
            component.addShellSuggestionInvalidationListener {
                visibleDuringInvalidation += component.currentShellSuggestionState().visible
            }
            component.showShellSuggestions(request(), suggestions())

            assertTrue(component.clearScreen())
        }

        assertEquals(listOf(false), visibleDuringInvalidation)
        SwingUtilities.invokeAndWait { component.dispose() }
        session.close()
    }

    @Test
    fun `public suggestion popup requires tab selection before tab acceptance`() {
        val accepted = ArrayList<SwingShellSuggestion>()
        val indexes = ArrayList<Int>()
        val requests = ArrayList<SwingShellSuggestionRequest>()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionHandler =
                            SwingShellSuggestionHandler { acceptance ->
                                accepted += acceptance.suggestion
                                indexes += acceptance.index
                                requests += acceptance.request
                            },
                    ),
            )
        val request = request(anchorColumn = 1, anchorRow = 1)
        val suggestions = suggestions(request.commandText)

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.showShellSuggestions(request, suggestions)

            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_DOWN)) }
            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_TAB)) }

            assertFalse(component.currentShellSuggestionState().visible)
        }

        assertEquals(listOf(suggestions[0]), accepted)
        assertEquals(listOf(0), indexes)
        assertEquals(listOf(request), requests)
    }

    @Test
    fun `provider backed suggestion request shows results and preserves acceptance context`() {
        val providerRequests = ArrayList<SwingShellSuggestionRequest>()
        val acceptedRequests = ArrayList<SwingShellSuggestionRequest>()
        val view = RecordingSuggestionView()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request ->
                                providerRequests += request
                                flowOf(suggestions(request.commandText))
                            },
                        shellSuggestionHandler =
                            SwingShellSuggestionHandler { acceptance ->
                                acceptedRequests += acceptance.request
                            },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.requestShellSuggestions(
                commandText = "git s",
                cursorOffset = 5,
                anchorColumn = 5,
                anchorRow = 2,
            )
        }
        view.awaitUpdate()

        SwingUtilities.invokeAndWait {
            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(2, state.count)
            assertEquals(5, state.anchorColumn)
            assertEquals(2, state.anchorRow)

            assertEquals(-1, state.selectedIndex)
            assertNull(state.selectedSuggestion)

            // First TAB highlights first suggestion
            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_TAB)) }
            component.keyListeners.forEach { listener -> listener.keyReleased(keyReleased(component, KeyEvent.VK_TAB)) }
            val highlightedState = component.currentShellSuggestionState()
            assertEquals(0, highlightedState.selectedIndex)
            assertEquals("git status", highlightedState.selectedSuggestion?.replacementText)

            // Second TAB accepts the highlighted suggestion
            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_TAB)) }
            component.keyListeners.forEach { listener -> listener.keyReleased(keyReleased(component, KeyEvent.VK_TAB)) }
        }

        val expectedRequest =
            SwingShellSuggestionRequest(
                commandText = "git s",
                cursorOffset = 5,
                anchorColumn = 5,
                anchorRow = 2,
            )
        assertEquals(listOf(expectedRequest), providerRequests)
        assertEquals(listOf(expectedRequest), acceptedRequests)
    }

    @Test
    fun `enter on passive suggestions is not consumed and does not accept`() {
        val view = RecordingSuggestionView()
        val accepted = mutableListOf<SwingShellSuggestionAcceptance>()
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider = SwingShellSuggestionProvider { flowOf(suggestions("git s")) },
                        shellSuggestionHandler = { accepted += it },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.bind(session)
            component.requestShellSuggestions(commandText = "git s", cursorOffset = 5, anchorColumn = 5, anchorRow = 0)
        }
        view.awaitUpdate()

        val enterEvent = keyPressed(component, KeyEvent.VK_ENTER)
        SwingUtilities.invokeAndWait {
            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(-1, state.selectedIndex)
            component.keyListeners.forEach { listener -> listener.keyPressed(enterEvent) }
        }

        assertTrue(enterEvent.isConsumed)
        assertTrue(accepted.isEmpty())
        assertTrue(connector.writtenBytes.size() > 0)
        SwingUtilities.invokeAndWait {
            assertFalse(component.currentShellSuggestionState().visible)
            component.dispose()
        }
        session.close()
    }

    @Test
    fun `progressive provider rankings preserve the selected outcome across reranking updates`() {
        val emissions = Channel<List<SwingShellSuggestion>>(Channel.UNLIMITED)
        val view = RecordingSuggestionView()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider = SwingShellSuggestionProvider { emissions.receiveAsFlow() },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )
        val initial =
            suggestions() +
                suggestion(
                    replacementText = "git stash",
                    detail = "stash working tree changes",
                    source = "git",
                    kind = "SUBCOMMAND",
                )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(20, 4)
            component.requestShellSuggestions("git s", 5, 5, 0)
        }
        assertTrue(emissions.trySend(initial).isSuccess)
        view.awaitUpdate()

        SwingUtilities.invokeAndWait {
            // First DOWN highlights index 0; second DOWN navigates to index 1
            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_DOWN)) }
            component.keyListeners.forEach { listener -> listener.keyReleased(keyReleased(component, KeyEvent.VK_DOWN)) }
            component.keyListeners.forEach { listener -> listener.keyPressed(keyPressed(component, KeyEvent.VK_DOWN)) }
            component.keyListeners.forEach { listener -> listener.keyReleased(keyReleased(component, KeyEvent.VK_DOWN)) }
            assertEquals(initial[1], component.currentShellSuggestionState().selectedSuggestion)
        }
        view.awaitUpdate()

        val reranked = listOf(initial[2], initial[0], initial[1])
        assertTrue(emissions.trySend(reranked).isSuccess)
        view.awaitUpdate()

        SwingUtilities.invokeAndWait {
            val state = component.currentShellSuggestionState()
            assertEquals(2, state.selectedIndex)
            assertEquals(initial[1], state.selectedSuggestion)
            component.dispose()
        }
        emissions.close()
    }

    @Test
    fun `provider empty result hides popup and later results reopen in passive state`() {
        val emissions = Channel<List<SwingShellSuggestion>>(Channel.UNLIMITED)
        val view = RecordingSuggestionView()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider = SwingShellSuggestionProvider { emissions.receiveAsFlow() },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.requestShellSuggestions(commandText = "missing", cursorOffset = 7, anchorColumn = 0, anchorRow = 0)
        }
        val initialSuggestions = suggestions("missing")
        assertTrue(emissions.trySend(initialSuggestions).isSuccess)
        assertEquals(initialSuggestions, view.awaitUpdate().suggestions)

        SwingUtilities.invokeAndWait {
            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(-1, state.selectedIndex)
            assertNull(state.selectedSuggestion)
        }

        assertTrue(emissions.trySend(emptyList()).isSuccess)
        assertTrue(view.awaitUpdate().suggestions.isEmpty())

        SwingUtilities.invokeAndWait {
            assertFalse(component.currentShellSuggestionState().visible)
        }

        val laterSuggestions = initialSuggestions.reversed()
        assertTrue(emissions.trySend(laterSuggestions).isSuccess)
        view.awaitUpdate()

        SwingUtilities.invokeAndWait {
            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(-1, state.selectedIndex)
            assertNull(state.selectedSuggestion)
            component.dispose()
        }
        emissions.close()
    }

    @Test
    fun `active shell suggestion request uses bound session command snapshot`() {
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git s".utf8())
        val providerRequests = ArrayList<SwingShellSuggestionRequest>()
        val view = RecordingSuggestionView()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request ->
                                providerRequests += request
                                flowOf(suggestions(request.commandText))
                            },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(30, 4)
            component.bind(session)
            component.requestActiveShellSuggestions()
        }
        view.awaitUpdate()

        SwingUtilities.invokeAndWait {
            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(-1, state.selectedIndex)
            assertNull(state.selectedSuggestion)
        }

        assertEquals(
            listOf(
                SwingShellSuggestionRequest(
                    commandText = "git s",
                    cursorOffset = 5,
                    anchorColumn = "PS> git s".length,
                    anchorRow = 0,
                ),
            ),
            providerRequests,
        )
        session.close()
    }

    @Test
    fun `active shell suggestion request hides popup when session has no active command snapshot`() {
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git s\u001B]133;C\u0007".utf8())
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request -> flowOf(suggestions(request.commandText)) },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(30, 4)
            component.bind(session)
            component.showShellSuggestions(request(), suggestions())
            assertTrue(component.currentShellSuggestionState().visible)

            component.requestActiveShellSuggestions()

            assertFalse(component.currentShellSuggestionState().visible)
        }

        session.close()
    }

    @Test
    fun `disabled automatic suggestions setting ignores automatic provider requests`() {
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, shellSuggestionsEnabled = false) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider = SwingShellSuggestionProvider { flowOf(suggestions(it.commandText)) },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.requestShellSuggestions(commandText = "git s", cursorOffset = 5, anchorColumn = 5, anchorRow = 0)

            assertFalse(component.currentShellSuggestionState().visible)
        }
    }

    @Test
    fun `manual active request works when automatic suggestions are disabled`() {
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        connector.feedFromHost("\u001B]133;A\u0007PS> \u001B]133;B\u0007git s".utf8())
        val providerRequests = ArrayList<SwingShellSuggestionRequest>()
        val view = RecordingSuggestionView()
        val component =
            SwingTerminal(
                settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, shellSuggestionsEnabled = false) },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider { request ->
                                providerRequests += request
                                flowOf(suggestions(request.commandText))
                            },
                        shellSuggestionViewFactory = view.factory(),
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(30, 4)
            component.bind(session)
            component.requestActiveShellSuggestions()
        }
        view.awaitUpdate()

        SwingUtilities.invokeAndWait {
            assertTrue(component.currentShellSuggestionState().visible)
        }

        assertEquals(1, providerRequests.size)
        session.close()
    }

    @Test
    fun `disabling automatic suggestions cancels a suspended provider before publishing eligibility`() {
        var currentSettings = SwingSettings(smartSuggestionsEnabled = true, shellSuggestionsEnabled = true)
        val providerStarted = CompletableDeferred<Unit>()
        val providerCancelled = CompletableDeferred<Unit>()
        val eligibilityDuringCallback = ArrayList<Boolean>()
        val component =
            SwingTerminal(
                settingsProvider = { currentSettings },
                hostServices =
                    SwingHostServices(
                        shellSuggestionProvider =
                            SwingShellSuggestionProvider {
                                flow {
                                    providerStarted.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        providerCancelled.complete(Unit)
                                    }
                                }
                            },
                    ),
            )

        SwingUtilities.invokeAndWait {
            component.addShellSuggestionEligibilityListener { eligible ->
                eligibilityDuringCallback += component.currentShellSuggestionState().visible
                assertEquals(eligible, component.isAutomaticShellSuggestionEligible())
            }
            component.requestShellSuggestions("git s", 5, 5, 0)
        }
        runBlocking { providerStarted.await() }

        currentSettings = SwingSettings(smartSuggestionsEnabled = true, shellSuggestionsEnabled = false)
        SwingUtilities.invokeAndWait { component.reloadSettings() }
        runBlocking { providerCancelled.await() }

        SwingUtilities.invokeAndWait {
            assertFalse(component.isAutomaticShellSuggestionEligible())
            assertFalse(component.currentShellSuggestionState().visible)
            assertEquals(listOf(false), eligibilityDuringCallback)
            component.dispose()
        }
    }

    @Test
    fun `leaving live viewport hides suggestions before publishing ineligibility`() {
        val connector = RecordingConnector()
        val session = activeSuggestionSession(connector)
        connector.feedFromHost((1..8).joinToString("") { "line$it\r\n" }.utf8())
        runBlocking { withTimeout(1_000.milliseconds) { session.renderGeneration.first { it >= 0L } } }
        val visibleDuringCallback = ArrayList<Boolean>()
        val component =
            SwingTerminal(settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) })

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(30, 4)
            component.bind(session)
            component.addShellSuggestionEligibilityListener {
                visibleDuringCallback += component.currentShellSuggestionState().visible
            }
            assertTrue(component.viewportState().historySize > 0)
            component.showShellSuggestions(request(), suggestions())
            assertTrue(component.currentShellSuggestionState().visible)

            component.scrollToScrollbackOffset(1)

            assertFalse(component.currentShellSuggestionState().visible)
            assertFalse(component.isAutomaticShellSuggestionEligible())
            assertEquals(listOf(false), visibleDuringCallback)
            component.dispose()
        }
        session.close()
    }

    @Test
    fun `shown shell suggestion state exposes selected item`() {
        val component =
            SwingTerminal(settingsProvider = { SwingSettings(smartSuggestionsEnabled = true, padding = SwingPadding(0, 0, 0, 0)) })
        val request = request(anchorColumn = 2, anchorRow = 1)
        val suggestions = suggestions(request.commandText)

        SwingUtilities.invokeAndWait {
            component.size = component.preferredGridSize(12, 4)
            component.showShellSuggestions(request, suggestions, selectedIndex = 1)

            val state = component.currentShellSuggestionState()
            assertTrue(state.visible)
            assertEquals(2, state.count)
            assertEquals(1, state.selectedIndex)
            assertEquals(suggestions[1], state.selectedSuggestion)
        }
    }

    @Test
    fun `default handler deletes standard ASCII prefix and pastes replacement`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = request(commandText = "git s")
        val suggestion =
            suggestion(
                replacementText = "git status",
                commandText = request.commandText,
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(5, session.keys.size)
        assertTrue(session.keys.all { it.key == TerminalKey.BACKSPACE })
        assertEquals(1, session.pastes.size)
        assertEquals("git status", session.pastes[0].text)
    }

    @Test
    fun `default handler deletes emoji prefix using grapheme clusters count`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)
        val commandText = "a\uD83D\uDE02"

        val request = request(commandText = commandText)
        val suggestion =
            suggestion(
                replacementText = "$commandText b",
                commandText = commandText,
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(2, session.keys.size)
        assertTrue(session.keys.all { it.key == TerminalKey.BACKSPACE })
        assertEquals(1, session.pastes.size)
        assertEquals("$commandText b", session.pastes[0].text)
    }

    @Test
    fun `default handler deletes combining accents prefix using grapheme clusters count`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)
        val commandText = "e\u0301"

        val request = request(commandText = commandText)
        val suggestion =
            suggestion(
                replacementText = "$commandText test",
                commandText = commandText,
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(1, session.keys.size)
        assertTrue(session.keys.all { it.key == TerminalKey.BACKSPACE })
        assertEquals(1, session.pastes.size)
        assertEquals("$commandText test", session.pastes[0].text)
    }

    @Test
    fun `default handler treats extended emoji sequences as single grapheme clusters`() {
        val clusters = listOf("\uD83D\uDC69\u200D\uD83D\uDCBB", "\uD83C\uDDE6\uD83C\uDDF2", "\uD83D\uDC4D\uD83C\uDFFD")

        for (cluster in clusters) {
            val session = RecordingInputEncoder()
            val handler = SwingShellSuggestionHandler.createDefault(session)
            val request = request(commandText = cluster)
            val suggestion = suggestion(replacementText = "$cluster accepted", commandText = cluster)

            handler.onSuggestionAccepted(SwingShellSuggestionAcceptance(suggestion, 0, request))

            assertEquals(1, session.keys.size, "cluster=$cluster")
            assertEquals(TerminalKey.BACKSPACE, session.keys.single().key, "cluster=$cluster")
            assertEquals(1, session.replacements.size, "cluster=$cluster")
        }
    }

    @Test
    fun `default handler rejects replacement range inside grapheme cluster`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)
        val commandText = "e\u0301"
        val request = request(commandText = commandText)
        val suggestion =
            suggestion(
                replacementText = "x",
                startOffset = 1,
                endOffset = commandText.length,
            )

        handler.onSuggestionAccepted(SwingShellSuggestionAcceptance(suggestion, 0, request))

        assertTrue(session.replacements.isEmpty())
        assertTrue(session.keys.isEmpty())
        assertTrue(session.pastes.isEmpty())
    }

    @Test
    fun `default handler replaces token range`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = request(commandText = "git s")
        val suggestion =
            suggestion(
                replacementText = "status",
                startOffset = 4,
                endOffset = 5,
                source = "spec",
                kind = "SUBCOMMAND",
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(1, session.keys.size)
        assertEquals(TerminalKey.BACKSPACE, session.keys[0].key)
        assertEquals(1, session.pastes.size)
        assertEquals("status", session.pastes[0].text)
    }

    @Test
    fun `default handler replaces explicit range around cursor`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = request(commandText = "git che", cursorOffset = 6)
        val suggestion =
            suggestion(
                replacementText = "checkout",
                startOffset = 4,
                endOffset = 7,
                source = "spec",
                kind = "SUBCOMMAND",
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertEquals(
            listOf(TerminalKey.DELETE, TerminalKey.BACKSPACE, TerminalKey.BACKSPACE),
            session.keys.map { it.key },
        )
        assertEquals(1, session.pastes.size)
        assertEquals("checkout", session.pastes[0].text)
        assertEquals(
            TerminalTextReplacementEvent(
                deleteAfterCursorCount = 1,
                deleteBeforeCursorCount = 2,
                replacementText = "checkout",
            ),
            session.replacements.single(),
        )
    }

    @Test
    fun `default handler ignores explicit range outside request text`() {
        val session = RecordingInputEncoder()
        val handler = SwingShellSuggestionHandler.createDefault(session)

        val request = request(commandText = "git che", cursorOffset = 6)
        val suggestion =
            suggestion(
                replacementText = "checkout",
                startOffset = 4,
                endOffset = 20,
                source = "spec",
                kind = "SUBCOMMAND",
            )
        val acceptance = SwingShellSuggestionAcceptance(suggestion, 0, request)

        handler.onSuggestionAccepted(acceptance)

        assertTrue(session.keys.isEmpty())
        assertTrue(session.pastes.isEmpty())
    }

    private class RecordingInputEncoder : TerminalInputEncoder {
        val keys = ArrayList<TerminalKeyEvent>()
        val pastes = ArrayList<TerminalPasteEvent>()
        val replacements = ArrayList<TerminalTextReplacementEvent>()

        override fun encodeKey(event: TerminalKeyEvent) {
            keys += event
        }

        override fun encodePaste(event: TerminalPasteEvent) {
            pastes += event
        }

        override fun encodeTextReplacement(event: TerminalTextReplacementEvent) {
            replacements += event
            super.encodeTextReplacement(event)
        }

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }

    private class RecordingConnector : TerminalConnector {
        private var listener: TerminalConnectorListener? = null
        val writtenBytes = ByteArrayOutputStream()

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writtenBytes.write(bytes, offset, length)
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit

        fun feedFromHost(bytes: ByteArray) {
            listener?.onBytes(bytes, 0, bytes.size)
        }
    }

    private class RecordingSuggestionView : SwingShellSuggestionView {
        override val component = JPanel()
        private val updates = LinkedBlockingQueue<RecordedSuggestionUpdate>()

        override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
            updates +=
                RecordedSuggestionUpdate(
                    suggestions = snapshot.visibleSuggestions,
                    selectedIndex = snapshot.selectedIndex,
                    onEdt = SwingUtilities.isEventDispatchThread(),
                )
        }

        fun factory(): SwingShellSuggestionViewFactory = SwingShellSuggestionViewFactory { this }

        fun awaitUpdate(): RecordedSuggestionUpdate = updates.poll(5, TimeUnit.SECONDS) ?: fail("Suggestion view update was not published")
    }

    private data class RecordedSuggestionUpdate(
        val suggestions: List<SwingShellSuggestion>,
        val selectedIndex: Int,
        val onEdt: Boolean,
    )

    private fun activeSuggestionSession(connector: RecordingConnector): TerminalSession {
        val terminal = TerminalBuffers.create(width = 30, height = 4, maxHistory = 20)
        val session = TerminalSession.create(terminal = terminal, connector = connector)
        session.start(columns = 30, rows = 4)
        return session
    }

    private fun suggestions(commandText: String = "git s"): List<SwingShellSuggestion> =
        listOf(
            suggestion(
                replacementText = "git status",
                commandText = commandText,
                detail = "show working tree status",
                source = "learned",
                kind = "SUBCOMMAND",
            ),
            suggestion(
                replacementText = "git switch main",
                commandText = commandText,
                detail = "switch to main branch",
                source = "git",
                kind = "SUBCOMMAND",
            ),
        )

    private fun suggestion(
        replacementText: String,
        commandText: String = "git s",
        startOffset: Int = 0,
        endOffset: Int = commandText.length,
        source: String = "test",
        kind: String = "COMMAND",
        detail: String = "",
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = replacementText,
            replacementStartOffset = startOffset,
            replacementEndOffset = endOffset,
            source = source,
            kind = kind,
            detail = detail,
        )

    private fun request(
        commandText: String = "git s",
        cursorOffset: Int = commandText.length,
        anchorColumn: Int = cursorOffset,
        anchorRow: Int = 0,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
            anchorColumn = anchorColumn,
            anchorRow = anchorRow,
        )

    private fun keyPressed(
        component: SwingTerminal,
        keyCode: Int,
    ): KeyEvent =
        KeyEvent(
            component,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private fun keyReleased(
        component: SwingTerminal,
        keyCode: Int,
    ): KeyEvent =
        KeyEvent(
            component,
            KeyEvent.KEY_RELEASED,
            System.currentTimeMillis(),
            0,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}

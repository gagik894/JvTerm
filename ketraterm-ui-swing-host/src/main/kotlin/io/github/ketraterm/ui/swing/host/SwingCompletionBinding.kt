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

import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.swing.Swing
import javax.swing.SwingUtilities

/**
 * Connects optional host completion resources to one Swing terminal.
 *
 * Hosts own provider construction and persistence. This binding owns automatic
 * observation and forwards requests only to the current resources. Configure,
 * attach, and close on the EDT. An empty binding starts no coroutine work.
 */
class SwingCompletionBinding(
    private val session: TerminalSession,
    private val rankingContextKey: () -> String? = { null },
) : AutoCloseable {
    @Volatile private var resources: SwingCompletionResources? = null
    private var terminal: SwingTerminal? = null
    private var liveBinding: SwingLiveCompletionBinding? = null
    private var observationScope: CoroutineScope? = null
    private var closed = false

    /** Stable provider installed in host services before the terminal is attached. */
    val provider =
        SwingShellSuggestionProvider { request ->
            flow {
                val current = resources ?: return@flow
                emitAll(current.provider.suggestions(request).takeWhile { resources === current })
            }
        }

    /** Stable callback; obsolete or disabled bindings cannot record feedback. */
    val feedbackHandler =
        SwingShellSuggestionFeedbackHandler { feedback ->
            checkEdt()
            val current = resources
            if (current != null) {
                (liveBinding?.suggestionFeedbackHandler ?: current.feedbackHandler).onSuggestionFeedback(feedback)
            }
        }

    /** Whether this pane currently has a host-provided completion implementation. */
    val isEnabled: Boolean get() = resources != null

    /** Attaches once after the terminal has been bound to its session. */
    fun attach(terminal: SwingTerminal) {
        checkEdt()
        check(!closed && this.terminal == null)
        this.terminal = terminal
    }

    /** Replaces resources; null disables all completion and releases observation. */
    fun update(
        resources: SwingCompletionResources?,
        automaticPopup: Boolean,
    ) {
        checkEdt()
        check(!closed)
        val terminal = checkNotNull(terminal)
        if (this.resources !== resources) {
            this.resources = resources
            terminal.hideShellSuggestions()
            stopObservation()
        }
        if (resources == null || !automaticPopup) {
            stopObservation()
            return
        }
        if (liveBinding != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val live =
            SwingLiveCompletionBinding(
                session = session,
                coroutineScope = scope,
                suggestionsEnabled = { this.resources != null },
                rankingContextKey = rankingContextKey,
                feedbackHandler = resources.feedbackHandler,
            )
        observationScope = scope
        liveBinding = live
        try {
            live.attach(terminal)
            live.scheduleRefresh()
        } catch (failure: Throwable) {
            stopObservation()
            throw failure
        }
    }

    private fun stopObservation() {
        liveBinding?.detach()
        liveBinding = null
        observationScope?.cancel()
        observationScope = null
    }

    /** Cancels requests and observation and releases references to host resources. */
    override fun close() {
        checkEdt()
        if (closed) return
        closed = true
        resources = null
        terminal?.hideShellSuggestions()
        stopObservation()
        terminal = null
    }

    private fun checkEdt() = check(SwingUtilities.isEventDispatchThread()) { "completion binding must run on the EDT" }
}

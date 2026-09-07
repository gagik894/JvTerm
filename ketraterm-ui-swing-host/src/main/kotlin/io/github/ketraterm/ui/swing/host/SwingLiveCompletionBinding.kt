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
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionEligibilityListener
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionInvalidationListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.swing.Swing
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import javax.swing.SwingUtilities

/**
 * Binds host-neutral live-completion behavior to one Swing terminal.
 *
 * The binding observes dedicated shell-edit revisions, applies the shared debounce policy,
 * adapts shell snapshots to Swing requests, hides suggestions when focus leaves
 * the terminal, and invalidates request deduplication after user feedback. It
 * owns only its observation job; completion providers, enablement, ranking
 * context, and feedback persistence remain host supplied.
 *
 * Construct the binding before the terminal so [suggestionFeedbackHandler] can
 * be passed through [io.github.ketraterm.ui.swing.api.SwingHostServices]. After
 * binding the terminal to the session, call [attach]. Call [close] from the EDT
 * before disposing the terminal.
 */
@OptIn(FlowPreview::class)
class SwingLiveCompletionBinding
    internal constructor(
        private val activeCommandLine: () -> TerminalShellCommandLineSnapshot?,
        private val shellCommandLineRevisions: StateFlow<Long>,
        private val suggestionsEnabled: () -> Boolean,
        private val rankingContextKey: () -> String?,
        feedbackHandler: SwingShellSuggestionFeedbackHandler,
        private val observationScope: CoroutineScope,
        private val edtDispatcher: CoroutineDispatcher = Dispatchers.Swing,
        private val debounceMillis: Int = DEFAULT_DEBOUNCE_MILLIS,
        private val minimumNonWhitespaceCharacters: Int = DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS,
    ) : AutoCloseable {
        private var target: SwingLiveCompletionTarget? = null
        private var observationJob: Job? = null
        private var invalidatedRevision: Long? = null
        private val refreshes = MutableStateFlow<Long?>(null)
        private var refreshSequence = 0L
        private var lastRequest: RequestKey? = null

        private var closed = false

        private val focusListener =
            object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    scheduleRefresh()
                }

                override fun focusLost(event: FocusEvent) {
                    cancelAndHide()
                }
            }

        private val invalidationListener =
            SwingShellSuggestionInvalidationListener {
                check(SwingUtilities.isEventDispatchThread()) { "shell suggestion invalidation must run on the EDT" }
                if (closed) return@SwingShellSuggestionInvalidationListener
                invalidatedRevision = shellCommandLineRevisions.value
                cancelAndHideInternal()
            }

        private val eligibilityListener =
            SwingShellSuggestionEligibilityListener { eligible ->
                check(SwingUtilities.isEventDispatchThread()) { "shell suggestion eligibility must run on the EDT" }
                if (closed) return@SwingShellSuggestionEligibilityListener
                if (eligible && isEligibleOnEdt() && invalidatedRevision == null) {
                    lastRequest = null
                    scheduleRefresh()
                } else {
                    refreshes.value = null
                    lastRequest = null
                }
            }

        /**
         * Feedback callback to install in the terminal's host services.
         *
         * It makes an unchanged command eligible for a fresh ranking request
         * before forwarding the event to the host callback.
         */
        val suggestionFeedbackHandler =
            SwingShellSuggestionFeedbackHandler { feedback ->
                check(SwingUtilities.isEventDispatchThread()) { "shell suggestion feedback must run on the EDT" }
                lastRequest = null
                feedbackHandler.onSuggestionFeedback(feedback)
            }

        /**
         * Creates a live-completion binding for [session].
         *
         * @param session terminal session that supplies shell and render state.
         * @param coroutineScope caller-owned lifecycle scope used for observation.
         * @param suggestionsEnabled returns whether automatic suggestions are enabled.
         * @param rankingContextKey host context that can change ranking for equal text.
         * @param feedbackHandler host callback for suggestion feedback.
         */
        constructor(
            session: TerminalSession,
            coroutineScope: CoroutineScope,
            suggestionsEnabled: () -> Boolean,
            rankingContextKey: () -> String? = { null },
            feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
        ) : this(
            activeCommandLine = session::activeShellCommandLine,
            shellCommandLineRevisions = session.activeShellCommandLineRevision,
            suggestionsEnabled = suggestionsEnabled,
            rankingContextKey = rankingContextKey,
            feedbackHandler = feedbackHandler,
            observationScope = coroutineScope,
        )

        /**
         * Attaches this binding to [terminal] and observes shell-edit revisions.
         *
         * A binding may be attached exactly once. The terminal must already be
         * bound to the session supplied to the constructor.
         *
         * @param terminal Swing terminal that presents suggestions.
         */
        fun attach(terminal: SwingTerminal) {
            attach(SwingTerminalLiveCompletionTarget(terminal))
        }

        internal fun attach(target: SwingLiveCompletionTarget) {
            check(SwingUtilities.isEventDispatchThread()) { "live completion must be attached on the EDT" }
            check(!closed) { "live completion binding is closed" }
            check(this.target == null) { "live completion binding is already attached" }
            this.target = target
            var focusListenerAttached = false
            try {
                target.addFocusListener(focusListener)
                focusListenerAttached = true
                target.addInvalidationListener(invalidationListener)
                target.addEligibilityListener(eligibilityListener)
                observationJob =
                    observationScope.launch(edtDispatcher + CoroutineName("swing-live-completion")) {
                        launch {
                            refreshes
                                .debounce { if (it == null) 0L else debounceMillis.toLong() }
                                .collect { revision ->
                                    if (revision != null && revision == refreshes.value) refreshNow()
                                }
                        }
                        shellCommandLineRevisions.collect { revision ->
                            if (revision >= 0L) {
                                onShellCommandLineRevisionOnEdt(revision)
                            }
                        }
                    }
            } catch (failure: Throwable) {
                observationJob?.cancel()
                observationJob = null
                if (focusListenerAttached) runCatching { target.removeFocusListener(focusListener) }
                runCatching { target.removeInvalidationListener(invalidationListener) }
                runCatching { target.removeEligibilityListener(eligibilityListener) }
                this.target = null
                throw failure
            }
        }

        /** Re-evaluates live completion after the shared debounce interval. */
        fun scheduleRefresh() =
            onEdt {
                if (!closed && invalidatedRevision == null && isEligibleOnEdt()) refreshes.value = ++refreshSequence
            }

        /** Cancels pending live completion and hides the current popup. */
        fun cancelAndHide() =
            onEdt {
                if (!closed) cancelAndHideInternal()
            }

        internal fun refreshNow() {
            check(SwingUtilities.isEventDispatchThread()) { "live completion must refresh on the EDT" }
            if (closed || invalidatedRevision != null || !isEligibleOnEdt()) {
                cancelAndHideInternal()
                return
            }
            val snapshot = activeCommandLine()
            if (snapshot == null || !shouldRequest(snapshot)) {
                lastRequest = null
                target?.hideSuggestions()
                return
            }
            val request = RequestKey(snapshot, rankingContextKey())
            if (request == lastRequest) return
            lastRequest = request
            target?.requestSuggestions(snapshot)
        }

        /** Stops observation, removes focus wiring, and hides suggestions. */
        override fun close() {
            check(SwingUtilities.isEventDispatchThread()) { "live completion must be closed on the EDT" }
            if (closed) return
            cancelAndHideInternal()
            detach()
        }

        /** Stops automatic observation without dismissing an explicit completion request. */
        fun detach() {
            check(SwingUtilities.isEventDispatchThread()) { "live completion must be closed on the EDT" }
            if (closed) return
            closed = true
            observationJob?.cancel()
            observationJob = null
            refreshes.value = null
            lastRequest = null
            target?.removeFocusListener(focusListener)
            target?.removeInvalidationListener(invalidationListener)
            target?.removeEligibilityListener(eligibilityListener)
            target = null
        }

        private fun onEdt(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) {
                action()
            } else {
                observationScope.launch(edtDispatcher) { action() }
            }
        }

        private fun onShellCommandLineRevisionOnEdt(revision: Long) {
            if (closed) return
            if (revision == invalidatedRevision) return
            invalidatedRevision = null
            cancelAndHideInternal()
            scheduleRefresh()
        }

        private fun cancelAndHideInternal() {
            refreshes.value = null
            lastRequest = null
            target?.hideSuggestions()
        }

        private fun shouldRequest(snapshot: TerminalShellCommandLineSnapshot): Boolean {
            val text = snapshot.commandText
            val cursorOffset = snapshot.cursorOffset
            var nonWhitespaceCharacters = 0
            for (index in 0 until cursorOffset) {
                if (!text[index].isWhitespace()) nonWhitespaceCharacters++
            }
            if (nonWhitespaceCharacters == 0) return false
            if (nonWhitespaceCharacters >= minimumNonWhitespaceCharacters) return true

            return when (text.getOrNull(cursorOffset - 1)) {
                '-', '/', '\\', '$', '=' -> true
                ' ' ->
                    nonWhitespaceCharacters >= MINIMUM_SPACE_TRIGGER_CHARACTERS &&
                        text.getOrNull(cursorOffset - 2)?.isWhitespace() == false
                else -> false
            }
        }

        private fun isEligibleOnEdt(): Boolean =
            !closed &&
                target?.isFocusOwner() == true &&
                target?.isAutomaticSuggestionEligible() == true &&
                suggestionsEnabled()

        private data class RequestKey(
            val snapshot: TerminalShellCommandLineSnapshot,
            val rankingContextKey: String?,
        )

        private companion object {
            private const val DEFAULT_DEBOUNCE_MILLIS = 75
            private const val DEFAULT_MINIMUM_NON_WHITESPACE_CHARACTERS = 2
            private const val MINIMUM_SPACE_TRIGGER_CHARACTERS = 2
        }
    }

internal interface SwingLiveCompletionTarget {
    fun requestSuggestions(snapshot: TerminalShellCommandLineSnapshot)

    fun hideSuggestions()

    fun addFocusListener(listener: FocusListener)

    fun removeFocusListener(listener: FocusListener)

    fun addInvalidationListener(listener: SwingShellSuggestionInvalidationListener)

    fun removeInvalidationListener(listener: SwingShellSuggestionInvalidationListener)

    fun addEligibilityListener(listener: SwingShellSuggestionEligibilityListener)

    fun removeEligibilityListener(listener: SwingShellSuggestionEligibilityListener)

    fun isFocusOwner(): Boolean

    fun isAutomaticSuggestionEligible(): Boolean
}

private class SwingTerminalLiveCompletionTarget(
    private val terminal: SwingTerminal,
) : SwingLiveCompletionTarget {
    override fun requestSuggestions(snapshot: TerminalShellCommandLineSnapshot) {
        terminal.requestShellSuggestions(
            commandText = snapshot.commandText,
            cursorOffset = snapshot.cursorOffset,
            anchorColumn = snapshot.cursorColumn,
            anchorRow = snapshot.cursorRow,
        )
    }

    override fun hideSuggestions() = terminal.hideShellSuggestions()

    override fun addFocusListener(listener: FocusListener) = terminal.addFocusListener(listener)

    override fun removeFocusListener(listener: FocusListener) = terminal.removeFocusListener(listener)

    override fun addInvalidationListener(listener: SwingShellSuggestionInvalidationListener) =
        terminal.addShellSuggestionInvalidationListener(listener)

    override fun removeInvalidationListener(listener: SwingShellSuggestionInvalidationListener) =
        terminal.removeShellSuggestionInvalidationListener(listener)

    override fun addEligibilityListener(listener: SwingShellSuggestionEligibilityListener) =
        terminal.addShellSuggestionEligibilityListener(listener)

    override fun removeEligibilityListener(listener: SwingShellSuggestionEligibilityListener) =
        terminal.removeShellSuggestionEligibilityListener(listener)

    override fun isFocusOwner(): Boolean = terminal.isFocusOwner

    override fun isAutomaticSuggestionEligible(): Boolean = terminal.isAutomaticShellSuggestionEligible()
}

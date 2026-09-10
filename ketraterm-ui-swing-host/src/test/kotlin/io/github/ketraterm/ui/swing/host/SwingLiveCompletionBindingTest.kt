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

import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.*
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwingLiveCompletionBindingTest {
    @Test
    fun `detach cancels debounce and removes observers without hiding an explicit popup`() =
        onEdtTest {
            val revisions = MutableStateFlow(-1L)
            val target = RecordingTarget()
            val binding = binding(backgroundScope, revisions)
            binding.attach(target)
            binding.scheduleRefresh()
            runCurrent()
            binding.detach()
            revisions.value = 2L
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertEquals(0, target.hideCount)
            assertEquals(1, target.removeFocusListenerCount)
            assertTrue(target.requests.isEmpty())
            binding.close()
        }

    @Test
    fun `shell edit revisions debounce the latest shell snapshot`() =
        onEdtTest {
            val revisions = MutableStateFlow(-1L)
            val target = RecordingTarget()
            var active = snapshot("git s")
            val binding = binding(backgroundScope, revisions, { active })

            binding.attach(target)
            runCurrent()
            active = snapshot("git st")
            revisions.value = 1L
            runCurrent()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()

            assertEquals(listOf(snapshot("git st")), target.requests)
            binding.close()
        }

    @Test
    fun `feedback invalidates request deduplication before reaching the host`() =
        onEdtTest {
            val forwarded = ArrayList<SwingShellSuggestionFeedback>()
            val target = RecordingTarget()
            val binding =
                binding(
                    scope = backgroundScope,
                    feedbackHandler = SwingShellSuggestionFeedbackHandler { forwarded += it },
                )
            binding.attach(target)

            binding.refreshNow()
            binding.refreshNow()
            val feedback = feedback()
            binding.suggestionFeedbackHandler.onSuggestionFeedback(feedback)
            binding.refreshNow()

            assertEquals(2, target.requests.size)
            assertEquals(1, forwarded.size)
            assertSame(feedback, forwarded.single())
            binding.close()
        }

    @Test
    fun `focus loss hides suggestions and close removes all lifecycle wiring`() =
        onEdtTest {
            val revisions = MutableStateFlow(-1L)
            val target = RecordingTarget()
            val binding = binding(backgroundScope, revisions)
            binding.attach(target)
            runCurrent()

            target.loseFocus()
            binding.close()
            revisions.value = 2L
            binding.scheduleRefresh()
            runCurrent()

            assertEquals(2, target.hideCount)
            assertEquals(1, target.removeFocusListenerCount)
            assertEquals(emptyList(), target.requests)
        }

    @Test
    fun `shell edit revisions cannot reopen suggestions until focus returns`() =
        onEdtTest {
            val revisions = MutableStateFlow(-1L)
            val target = RecordingTarget()
            val binding = binding(backgroundScope, revisions)
            binding.attach(target)
            revisions.value = 0L
            runCurrent()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(1, target.requests.size)

            target.loseFocus()
            revisions.value = 1L
            runCurrent()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(1, target.requests.size)

            target.gainFocus()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(2, target.requests.size)
            binding.close()
        }

    @Test
    fun `input invalidation rejects unchanged command snapshots until command changes`() =
        onEdtTest {
            val revisions = MutableStateFlow(-1L)
            val target = RecordingTarget()
            var active = snapshot("git s")
            val binding = binding(backgroundScope, revisions, { active })
            binding.attach(target)
            revisions.value = 0L
            runCurrent()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(1, target.requests.size)

            target.invalidateSuggestions()
            target.loseFocus()
            target.gainFocus()
            revisions.value = 0L
            runCurrent()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(1, target.requests.size)

            active = TerminalShellCommandLineSnapshot("git s", 5, 18, cursorRow = 9)
            revisions.value = 0L
            runCurrent()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(1, target.requests.size)

            active = snapshot("git st")
            revisions.value = 1L
            runCurrent()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(listOf(snapshot("git s"), snapshot("git st")), target.requests)
            binding.close()
        }

    @Test
    fun `changed shell snapshot hides old popup before debounce fires`() =
        onEdtTest {
            val revisions = MutableStateFlow(-1L)
            val target = RecordingTarget()
            var active = snapshot("git s")
            val binding = binding(backgroundScope, revisions, { active })
            binding.attach(target)
            revisions.value = 0L
            runCurrent()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            val hidesBeforeChange = target.hideCount

            active = snapshot("git st")
            revisions.value = 1L
            runCurrent()

            assertEquals(hidesBeforeChange + 1, target.hideCount)
            assertEquals(1, target.requests.size)
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(2, target.requests.size)
            binding.close()
        }

    @Test
    fun `binding rejects duplicate attachment and attachment after close`() =
        onEdtTest {
            val binding = binding(backgroundScope)
            binding.attach(RecordingTarget())

            assertFailsWith<IllegalStateException> { binding.attach(RecordingTarget()) }
            binding.close()
            assertFailsWith<IllegalStateException> { binding.attach(RecordingTarget()) }
        }

    @Test
    fun `failed attachment rolls back target ownership`() =
        onEdtTest {
            val binding = binding(backgroundScope)

            assertFailsWith<IllegalStateException> { binding.attach(RecordingTarget(failOnAttach = true)) }
            binding.attach(RecordingTarget())

            binding.close()
        }

    @Test
    fun `ineligible viewport cancels automatic trigger and live viewport reschedules`() =
        onEdtTest {
            val revisions = MutableStateFlow(-1L)
            val target = RecordingTarget()
            val binding = binding(backgroundScope, revisions)
            binding.attach(target)
            revisions.value = 0L
            runCurrent()

            target.setAutomaticSuggestionEligible(false)
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(0, target.requests.size)

            target.setAutomaticSuggestionEligible(true)
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(1, target.requests.size)
            binding.close()
        }

    @Test
    fun `cheap trigger characters bypass the normal length threshold`() =
        onEdtTest {
            val target = RecordingTarget()
            var active = snapshot("-")
            val binding =
                binding(
                    scope = backgroundScope,
                    activeCommandLine = { active },
                    minimumNonWhitespaceCharacters = 99,
                )
            binding.attach(target)

            listOf("-", "/", "\\", "$", "=", "go ").forEach { command ->
                active = snapshot(command)
                binding.refreshNow()
            }

            assertEquals(listOf("-", "/", "\\", "$", "=", "go "), target.requests.map { it.commandText })
            binding.close()
        }

    @Test
    fun `short ordinary text is hidden`() =
        onEdtTest {
            val target = RecordingTarget()
            val binding = binding(scope = backgroundScope, activeCommandLine = { snapshot("g") })
            binding.attach(target)

            binding.refreshNow()

            assertEquals(emptyList(), target.requests)
            assertEquals(1, target.hideCount)
            binding.close()
        }

    @Test
    fun `ranking context change invalidates request deduplication`() =
        onEdtTest {
            val target = RecordingTarget()
            val active = snapshot("git s")
            var directory = "file:///one"
            val binding =
                SwingLiveCompletionBinding(
                    activeCommandLine = { active },
                    shellCommandLineRevisions = MutableStateFlow(-1L),
                    suggestionsEnabled = { true },
                    rankingContextKey = { directory },
                    feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
                    observationScope = backgroundScope,
                    edtDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            binding.attach(target)

            binding.refreshNow()
            binding.refreshNow()
            directory = "file:///two"
            binding.refreshNow()

            assertEquals(listOf(active, active), target.requests)
            binding.close()
        }

    @Test
    fun `burst edits wait a full debounce interval after the last edit`() =
        onEdtTest {
            val revisions = MutableStateFlow(-1L)
            var active = snapshot("gi")
            val target = RecordingTarget()
            val binding = binding(backgroundScope, revisions, { active })
            binding.attach(target)
            revisions.value = 0
            runCurrent()
            advanceTimeBy(50.milliseconds)
            active = snapshot("git")
            revisions.value = 1
            runCurrent()
            advanceTimeBy(74.milliseconds)
            runCurrent()
            assertEquals(emptyList(), target.requests)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(listOf(snapshot("git")), target.requests)
            binding.close()
        }

    @Test
    fun `cancel and close suppress pending debounce without cancelling caller scope`() =
        onEdtTest {
            val target = RecordingTarget()
            val binding = binding(backgroundScope)
            binding.attach(target)
            binding.scheduleRefresh()
            runCurrent()
            advanceTimeBy(74.milliseconds)
            binding.cancelAndHide()
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(emptyList(), target.requests)
            binding.scheduleRefresh()
            runCurrent()
            binding.close()
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertEquals(emptyList(), target.requests)
            assertTrue(backgroundScope.coroutineContext[Job]!!.isActive)
        }

    @Test
    fun `caller scope cancellation stops delayed requests`() =
        onEdtTest {
            val owner = Job(backgroundScope.coroutineContext[Job])
            val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
            val target = RecordingTarget()
            val binding = binding(scope)
            binding.attach(target)
            binding.scheduleRefresh()
            runCurrent()
            owner.cancel()
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertEquals(emptyList(), target.requests)
            binding.close()
        }

    @Test
    fun `real Swing dispatcher supports attachment refresh callbacks and disposal`() =
        runBlocking<Unit> {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val requested = CompletableDeferred<Unit>()
            val target = RecordingTarget(onRequest = { requested.complete(Unit) })
            val revisions = MutableStateFlow(-1L)
            val binding =
                SwingLiveCompletionBinding(
                    activeCommandLine = { snapshot("git s") },
                    shellCommandLineRevisions = revisions,
                    suggestionsEnabled = { true },
                    rankingContextKey = { null },
                    feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
                    observationScope = owner,
                )
            try {
                withContext(Dispatchers.Swing) {
                    binding.attach(target)
                    target.gainFocus()
                }
                revisions.value = 0
                withTimeout(5000.milliseconds) { requested.await() }
                withContext(Dispatchers.Swing) {
                    assertEquals(listOf(snapshot("git s")), target.requests)
                    val hides = target.hideCount
                    binding.cancelAndHide()
                    assertEquals(hides + 1, target.hideCount)
                    target.invalidateSuggestions()
                    target.setAutomaticSuggestionEligible(false)
                    target.setAutomaticSuggestionEligible(true)
                    binding.suggestionFeedbackHandler.onSuggestionFeedback(feedback())
                    target.loseFocus()
                }
            } finally {
                withContext(Dispatchers.Swing) { binding.close() }
                owner.cancel()
            }
            assertEquals(1, target.removeFocusListenerCount)
        }

    @Test
    fun `production binding rejects attachment and disposal off the EDT`() =
        runBlocking<Unit> {
            assertTrue(!SwingUtilities.isEventDispatchThread())
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val binding =
                SwingLiveCompletionBinding(
                    activeCommandLine = { snapshot("git s") },
                    shellCommandLineRevisions = MutableStateFlow(-1L),
                    suggestionsEnabled = { true },
                    rankingContextKey = { null },
                    feedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
                    observationScope = owner,
                )
            try {
                assertFailsWith<IllegalStateException> { binding.attach(RecordingTarget()) }
                withContext(Dispatchers.Swing) { binding.attach(RecordingTarget()) }
                assertFailsWith<IllegalStateException> { binding.close() }
            } finally {
                withContext(Dispatchers.Swing) { binding.close() }
                owner.cancel()
            }
        }

    private fun onEdtTest(block: suspend TestScope.() -> Unit) {
        SwingUtilities.invokeAndWait { runTest { block() } }
    }

    private fun binding(
        scope: CoroutineScope,
        revisions: MutableStateFlow<Long> = MutableStateFlow(-1L),
        activeCommandLine: () -> TerminalShellCommandLineSnapshot? = { snapshot("git s") },
        feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
        minimumNonWhitespaceCharacters: Int = 2,
    ): SwingLiveCompletionBinding =
        SwingLiveCompletionBinding(
            activeCommandLine = activeCommandLine,
            shellCommandLineRevisions = revisions,
            suggestionsEnabled = { true },
            rankingContextKey = { "file:///workspace" },
            feedbackHandler = feedbackHandler,
            observationScope = scope,
            edtDispatcher = UnconfinedTestDispatcher(scope.coroutineContext[TestCoroutineScheduler]),
            minimumNonWhitespaceCharacters = minimumNonWhitespaceCharacters,
        )

    private fun snapshot(command: String): TerminalShellCommandLineSnapshot =
        TerminalShellCommandLineSnapshot(command, command.length, command.length, cursorRow = 2)

    private fun feedback(): SwingShellSuggestionFeedback {
        val request = SwingShellSuggestionRequest("git s", 5, 5, 2)
        return SwingShellSuggestionFeedback(
            kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
            suggestion =
                SwingShellSuggestion(
                    replacementText = "status",
                    replacementStartOffset = 4,
                    replacementEndOffset = 5,
                    source = "spec",
                    kind = "SUBCOMMAND",
                ),
            index = 0,
            request = request,
        )
    }

    private class RecordingTarget(
        private val failOnAttach: Boolean = false,
        private val onRequest: () -> Unit = {},
    ) : SwingLiveCompletionTarget {
        val requests = ArrayList<TerminalShellCommandLineSnapshot>()
        var hideCount = 0
        var removeFocusListenerCount = 0
        private var focusListener: FocusListener? = null
        private var invalidationListener: SwingShellSuggestionInvalidationListener? = null
        private var eligibilityListener: SwingShellSuggestionEligibilityListener? = null
        private var focused = true
        private var automaticSuggestionEligible = true

        override fun requestSuggestions(snapshot: TerminalShellCommandLineSnapshot) {
            assertTrue(SwingUtilities.isEventDispatchThread())
            requests += snapshot
            onRequest()
        }

        override fun hideSuggestions() {
            assertTrue(SwingUtilities.isEventDispatchThread())
            hideCount++
        }

        override fun addFocusListener(listener: FocusListener) {
            check(!failOnAttach) { "attachment failed" }
            focusListener = listener
        }

        override fun removeFocusListener(listener: FocusListener) {
            if (focusListener === listener) focusListener = null
            removeFocusListenerCount++
        }

        override fun addInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
            invalidationListener = listener
        }

        override fun removeInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
            if (invalidationListener === listener) invalidationListener = null
        }

        override fun addEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
            eligibilityListener = listener
        }

        override fun removeEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
            if (eligibilityListener === listener) eligibilityListener = null
        }

        override fun isFocusOwner(): Boolean = focused

        override fun isAutomaticSuggestionEligible(): Boolean = automaticSuggestionEligible

        fun loseFocus() {
            focused = false
            focusListener?.focusLost(FocusEvent(JPanel(), FocusEvent.FOCUS_LOST))
        }

        fun gainFocus() {
            focused = true
            focusListener?.focusGained(FocusEvent(JPanel(), FocusEvent.FOCUS_GAINED))
        }

        fun invalidateSuggestions() {
            invalidationListener?.onShellSuggestionsInvalidated()
        }

        fun setAutomaticSuggestionEligible(eligible: Boolean) {
            automaticSuggestionEligible = eligible
            eligibilityListener?.onAutomaticShellSuggestionEligibilityChanged(eligible)
        }
    }
}

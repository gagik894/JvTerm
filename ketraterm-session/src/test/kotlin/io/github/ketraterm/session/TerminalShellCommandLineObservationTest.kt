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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalShellCommandLineObservationTest {
    @Test
    fun `unobserved renders never scan command history even when the revision property is read`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed(MULTILINE_PROMPT)
                runCurrent()
                assertTrue(fixture.session.renderGeneration.value >= 0L)
                assertEquals(0, fixture.terminal.historyReads)

                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)
                runCurrent()
                repeat(5) {
                    fixture.feed("x")
                    runCurrent()
                }
                assertEquals(
                    "threexxxxx",
                    fixture.session.terminal
                        .getLineAsString(1)
                        .trimEnd(),
                )
                assertEquals(0, fixture.terminal.historyReads)
                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)
            }
        }

    @Test
    fun `first subscriber samples an already rendered command without new output`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed(MULTILINE_PROMPT)
                runCurrent()
                val rendered = fixture.session.renderGeneration.value
                val revisions = mutableListOf<Long>()
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect { revisions += it } }
                runCurrent()

                assertEquals(listOf(-1L, rendered), revisions)
                assertEquals(1, fixture.terminal.historyReads)
                assertEquals(rendered, fixture.session.renderGeneration.value)
            }
        }

    @Test
    fun `subscribers share tracking and the last cancellation stops history scans`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed(MULTILINE_PROMPT)
                runCurrent()
                val first = backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                val second = backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                assertEquals(1, fixture.terminal.historyReads)

                first.cancelAndJoin()
                runCurrent()
                fixture.feed("x")
                runCurrent()
                assertEquals(2, fixture.terminal.historyReads)
                assertEquals(fixture.session.renderGeneration.value, fixture.session.activeShellCommandLineRevision.value)

                second.cancelAndJoin()
                runCurrent()
                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)
                fixture.feed("y")
                runCurrent()
                assertEquals(2, fixture.terminal.historyReads)
            }
        }

    @Test
    fun `resubscription discards stale state and samples edits made while unobserved`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed(MULTILINE_PROMPT)
                runCurrent()
                repeat(3) {
                    val revisions = mutableListOf<Long>()
                    val observer =
                        backgroundScope.launch {
                            fixture.session.activeShellCommandLineRevision.collect { revisions += it }
                        }
                    runCurrent()
                    assertEquals(listOf(-1L, fixture.session.renderGeneration.value), revisions)
                    assertEquals(it + 1, fixture.terminal.historyReads)

                    observer.cancelAndJoin()
                    runCurrent()
                    fixture.feed("x")
                    runCurrent()
                    assertEquals(it + 1, fixture.terminal.historyReads)
                }
            }
        }

    @Test
    fun `revisions change only for command text cursor or availability`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val revisions = mutableListOf<Long>()
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect { revisions += it } }
                runCurrent()
                assertEquals(listOf(-1L), revisions)

                fixture.feed("\u001B]133;A\u0007PS> \u001B]133;B\u0007git s")
                runCurrent()
                val firstRevision = fixture.session.activeShellCommandLineRevision.value
                assertTrue(firstRevision >= 0L)

                fixture.session.setCursorShape(TerminalRenderCursorShape.UNDERLINE)
                fixture.session.requestRender(0)
                runCurrent()
                assertEquals(listOf(-1L, firstRevision), revisions)

                fixture.feed("t")
                runCurrent()
                assertEquals("git st", fixture.session.activeShellCommandLine()?.commandText)
                assertTrue(revisions.last() > firstRevision)
                assertEquals(3, revisions.size)

                // Moving before the visible end makes the command unavailable.
                fixture.feed("\u001B[D")
                runCurrent()
                assertNull(fixture.session.activeShellCommandLine())
                assertEquals(4, revisions.size)

                fixture.feed("\u001B[C")
                runCurrent()
                assertEquals("git st", fixture.session.activeShellCommandLine()?.commandText)
                assertEquals(5, revisions.size)

                fixture.feed("\u001B]133;C\u0007")
                runCurrent()
                assertNull(fixture.session.activeShellCommandLine())
                assertEquals(6, revisions.size)

                fixture.feed("\r\ncommand output")
                runCurrent()
                assertEquals(6, revisions.size)
            }
        }

    @Test
    fun `observation skips scrolled viewports and resumes when the live viewport returns`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed(MULTILINE_PROMPT)
                runCurrent()
                fixture.session.requestRender(1)
                runCurrent()
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()

                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)
                assertEquals(0, fixture.terminal.historyReads)

                fixture.session.requestRender(0)
                runCurrent()
                assertEquals(fixture.session.renderGeneration.value, fixture.session.activeShellCommandLineRevision.value)
                assertEquals(1, fixture.terminal.historyReads)
            }
        }

    @Test
    fun `slow revision collectors do not delay terminal rendering or newer revisions`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed(MULTILINE_PROMPT)
                runCurrent()
                val received = CompletableDeferred<Long>()
                val release = CompletableDeferred<Unit>()
                backgroundScope.launch {
                    fixture.session.activeShellCommandLineRevision.collect { revision ->
                        if (revision >= 0L) {
                            received.complete(revision)
                            release.await()
                        }
                    }
                }
                runCurrent()
                val previous = received.await()
                fixture.feed("x")
                runCurrent()

                assertTrue(fixture.session.renderGeneration.value > previous)
                assertEquals(fixture.session.renderGeneration.value, fixture.session.activeShellCommandLineRevision.value)
                release.complete(Unit)
            }
        }

    @Test
    fun `session closure cancels tracking even when a collector remains attached`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed(MULTILINE_PROMPT)
                runCurrent()
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                assertEquals(1, fixture.terminal.historyReads)

                fixture.session.close()
                runCurrent()
                fixture.session.requestRender(0)
                runCurrent()

                assertFalse(fixture.session.isCoroutineScopeActive)
                assertEquals(1, fixture.terminal.historyReads)
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
    ) : AutoCloseable {
        val terminal = HistoryCountingBuffer(TerminalBuffers.create(width = 30, height = 2))
        private val connector = MockConnector()
        val session = TerminalSession.create(terminal, connector, workerDispatcher = dispatcher)

        init {
            session.start(columns = 30, rows = 2)
        }

        fun feed(text: String) {
            connector.feedFromHost(text.toByteArray(Charsets.UTF_8))
            session.requestRender(0)
        }

        override fun close() = session.close()
    }

    private class HistoryCountingBuffer(
        private val delegate: TerminalBuffer,
    ) : TerminalBuffer by delegate,
        TerminalRenderFrameReader {
        private val reader = delegate as TerminalRenderFrameReader
        var historyReads = 0
            private set

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) = reader.readRenderFrame(consumer)

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) = reader.readRenderFrame(scrollbackOffset, consumer)

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            if (viewportRows > height) historyReads++
            reader.readRenderFrame(scrollbackOffset, viewportRows, consumer)
        }
    }

    private companion object {
        const val MULTILINE_PROMPT = "\u001B]133;A\u0007P> \u001B]133;B\u0007one\r\ntwo\r\nthree"
    }
}

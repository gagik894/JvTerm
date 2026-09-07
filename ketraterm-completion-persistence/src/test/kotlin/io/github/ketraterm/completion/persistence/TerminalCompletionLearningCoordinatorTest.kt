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
package io.github.ketraterm.completion.persistence

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCompletionLearningCoordinatorTest {
    @Test
    fun `non-flushing close cancels pending hydration without reading or writing`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val coordinator = coordinator(TerminalCompletionLearningStore(), files)
            coordinator.recordCommandResult("git status", true, null, null, 1L)
            coordinator.closeWithoutFlush()
            advanceUntilIdle()
            assertEquals(0, files.loadCount.get())
            assertEquals(0, files.writeAttempts.get())
        }

    @Test
    fun `non-flushing close drops dirty checkpoint and rejects subsequent feedback`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)
            runCurrent()
            coordinator.recordCommandResult("git status", true, null, null, 1L)
            runCurrent()
            coordinator.closeWithoutFlush()
            val snapshot = learning.snapshot()
            assertFailsWith<IllegalStateException> { coordinator.recordCommandResult("git branch", true, null, null, 2L) }
            coordinator.closeWithoutFlush()
            advanceTimeBy((CHECKPOINT_INTERVAL_MILLIS * 2).milliseconds)
            runCurrent()
            assertEquals(snapshot, learning.snapshot())
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `non-flushing close awaits in-flight load and prevents later checkpoint`() =
        runTest {
            val loadStarted = CountDownLatch(1)
            val releaseLoad = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(beforeLoad = {
                    loadStarted.countDown()
                    check(releaseLoad.await(5, TimeUnit.SECONDS))
                })
            val coordinator = coordinator(TerminalCompletionLearningStore(), files, ioDispatcher = Dispatchers.IO)
            try {
                assertTrue(loadStarted.await(5, TimeUnit.SECONDS))
                coordinator.recordCommandResult("git status", true, null, null, 1L)
                val closing = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.closeWithoutFlush() }
                assertFalse(closing.isCompleted)
                releaseLoad.countDown()
                closing.join()
                assertTrue(files.writes.isEmpty())
            } finally {
                releaseLoad.countDown()
            }
        }

    @Test
    fun `reset clears memory and persists empty state immediately when persistence is disabled`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, enabled = false)
            coordinator.recordCommandResult("old command", true, null, null, 1L)

            coordinator.resetLearning()
            coordinator.recordCommandResult("new command", true, null, null, 2L)

            assertEquals(listOf("new command"), learning.snapshot().replayCommands.map { it.commandLine })
            runCurrent()
            assertEquals(listOf(TerminalCompletionLearningSnapshot.EMPTY), files.writes)
            coordinator.closeAndFlush()
            assertEquals(1, files.writeAttempts.get())
        }

    @Test
    fun `reset during hydration discards the loaded snapshot`() =
        runTest {
            val loadStarted = CountDownLatch(1)
            val releaseLoad = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("old command", 1L)),
                    beforeLoad = {
                        loadStarted.countDown()
                        check(releaseLoad.await(5L, TimeUnit.SECONDS)) { "test load was not released" }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                assertTrue(loadStarted.await(5L, TimeUnit.SECONDS))
                coordinator.resetLearning()
                releaseLoad.countDown()
                coordinator.closeAndFlush()

                assertSame(TerminalCompletionLearningSnapshot.EMPTY, learning.snapshot())
                assertEquals(listOf(TerminalCompletionLearningSnapshot.EMPTY), files.writes)
            } finally {
                releaseLoad.countDown()
            }
        }

    @Test
    fun `reset replaces a rejected snapshot after hydration blocked ordinary persistence`() =
        runTest {
            val diagnostics = CopyOnWriteArrayList<Throwable>()
            val files = RecordingSnapshotFiles(initialLoadOutcome = CompletionLearningFileLoadOutcome.Rejected)
            val coordinator =
                coordinator(
                    learning = TerminalCompletionLearningStore(),
                    files = files,
                    onPersistenceLoadFailure = diagnostics::add,
                )
            runCurrent()

            coordinator.resetLearning()
            runCurrent()
            coordinator.closeAndFlush()

            assertEquals(1, diagnostics.size)
            assertEquals(listOf(TerminalCompletionLearningSnapshot.EMPTY), files.writes)
        }

    @Test
    fun `large mutation burst is synchronous and checkpoints one latest snapshot`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            repeat(BURST_SIZE) { index ->
                coordinator.recordCommandResult("git status", true, "bash", "file:///repo", index + 1L)
            }

            assertEquals(
                BURST_SIZE,
                learning
                    .snapshot()
                    .rankingStats
                    .single()
                    .useCount,
            )
            assertTrue(files.writes.isEmpty())

            runCurrent()
            advanceTimeBy((CHECKPOINT_INTERVAL_MILLIS - 1L).milliseconds)
            runCurrent()
            assertTrue(files.writes.isEmpty())

            advanceTimeBy(1L.milliseconds)
            runCurrent()
            assertEquals(listOf(learning.snapshot()), files.writes)

            coordinator.closeAndFlush()
            assertEquals(1, files.writes.size)
        }

    @Test
    fun `later mutations do not postpone the fixed checkpoint`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("git status", true, null, null, 1L)
            runCurrent()
            advanceTimeBy((CHECKPOINT_INTERVAL_MILLIS * 3L / 5L).milliseconds)
            coordinator.recordCommandResult("git status", true, null, null, 2L)
            advanceTimeBy((CHECKPOINT_INTERVAL_MILLIS * 2L / 5L).milliseconds)
            runCurrent()

            assertEquals(1, files.writes.size)
            assertEquals(
                2,
                files.writes
                    .single()
                    .rankingStats
                    .single()
                    .useCount,
            )
            coordinator.closeAndFlush()
        }

    @Test
    fun `a clean store schedules another checkpoint after its next mutation`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("git status", true, null, null, 1L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            coordinator.recordCommandResult("npm test", true, null, null, 2L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()

            assertEquals(2, files.writes.size)
            assertEquals(learning.snapshot(), files.writes.last())
            coordinator.closeAndFlush()
        }

    @Test
    fun `event recorded during hydration is immediately visible and persisted after merge`() =
        runTest {
            val loadStarted = CountDownLatch(1)
            val releaseLoad = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("git status", 1L)),
                    beforeLoad = {
                        loadStarted.countDown()
                        check(releaseLoad.await(5L, TimeUnit.SECONDS)) { "test load was not released" }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                assertTrue(loadStarted.await(5L, TimeUnit.SECONDS))
                coordinator.recordCommandResult("npm test", true, null, null, 2L)
                assertEquals(listOf("npm test"), learning.snapshot().replayCommands.map { it.commandLine })

                releaseLoad.countDown()
                coordinator.closeAndFlush()

                assertEquals(
                    setOf("git status", "npm test"),
                    learning.snapshot().replayCommands.mapTo(mutableSetOf()) { it.commandLine },
                )
                assertEquals(listOf(learning.snapshot()), files.writes)
            } finally {
                releaseLoad.countDown()
            }
        }

    @Test
    fun `disable during hydration lets the started load merge but prevents writes`() =
        runTest {
            val loadStarted = CountDownLatch(1)
            val releaseLoad = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("git status", 1L)),
                    beforeLoad = {
                        loadStarted.countDown()
                        check(releaseLoad.await(5L, TimeUnit.SECONDS)) { "test load was not released" }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                assertTrue(loadStarted.await(5L, TimeUnit.SECONDS))
                coordinator.recordCommandResult("npm test", true, null, null, 2L)
                coordinator.setPersistenceEnabled(false)
                releaseLoad.countDown()
                coordinator.closeAndFlush()

                assertEquals(
                    setOf("git status", "npm test"),
                    learning.snapshot().replayCommands.mapTo(mutableSetOf()) { it.commandLine },
                )
                assertTrue(files.writes.isEmpty())
            } finally {
                releaseLoad.countDown()
            }
        }

    @Test
    fun `disabled persistence cancels a checkpoint but keeps memory mutations`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            repeat(BURST_SIZE) { index ->
                coordinator.recordCommandResult("npm test", true, null, null, index + 1L)
            }
            runCurrent()
            coordinator.setPersistenceEnabled(false)
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            coordinator.closeAndFlush()

            assertEquals(
                BURST_SIZE,
                learning
                    .snapshot()
                    .rankingStats
                    .single()
                    .useCount,
            )
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `first enable hydrates once and reenable never reloads`() =
        runTest {
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("git status", 1L)),
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, enabled = false)

            runCurrent()
            assertEquals(0, files.loadCount.get())
            coordinator.recordCommandResult("npm test", true, null, null, 2L)
            coordinator.setPersistenceEnabled(true)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()

            coordinator.setPersistenceEnabled(false)
            files.loadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("external rewrite", 3L))
            coordinator.recordCommandResult("npm test", true, null, null, 4L)
            coordinator.setPersistenceEnabled(true)
            coordinator.closeAndFlush()

            assertEquals(1, files.loadCount.get())
            val learned = learning.snapshot()
            val statsByIdentity = learned.rankingStats.associateBy { it.identityDigest }
            assertEquals(
                mapOf("git status" to 1, "npm test" to 2),
                learned.replayCommands.associate { it.commandLine to statsByIdentity.getValue(it.identityDigest).useCount },
            )
            assertEquals(2, files.writes.size)
            assertEquals(learning.snapshot(), files.writes.last())
        }

    @Test
    fun `clean hydration and shutdown do not rewrite the file`() =
        runTest {
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Loaded(snapshot("git status", 1L)),
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            runCurrent()
            coordinator.closeAndFlush()

            assertEquals(listOf("git status"), learning.snapshot().replayCommands.map { it.commandLine })
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `sensitive memory changes advance the dirty opaque revision`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("npm token list", true, null, null, 42L)
            coordinator.closeAndFlush()

            assertEquals(1, files.writes.size)
            val persisted = files.writes.single()
            assertEquals(1, persisted.rankingStats.single().useCount)
            assertTrue(persisted.replayCommands.isEmpty())
        }

    @Test
    fun `replay policy suppresses plaintext but not opaque in-memory learning`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, enabled = false)

            coordinator.recordCommandResult("npm token list", true, null, null, 42L)
            coordinator.recordSuggestionFeedback(
                commandLine = "npm token create",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = 2L,
            )
            coordinator.recordCommandResult("git status", true, null, null, -1L)
            coordinator.closeAndFlush()

            val learned = learning.snapshot()
            assertEquals(2, learned.rankingStats.size)
            assertEquals(listOf(0, 1), learned.rankingStats.map { it.acceptedCount }.sorted())
            assertTrue(learned.replayCommands.isEmpty())
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `leading-space command retains only opaque evidence in memory and on disk`() =
        runTest {
            val path =
                createTempDirectory("completion-leading-space")
                    .resolve(TerminalCompletionLearningCoordinator.currentFileName())
            val learning = TerminalCompletionLearningStore()
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    learningStore = learning,
                    fileStore = CompletionLearningFileStore(path),
                    coroutineScope = this,
                    persistenceEnabled = true,
                    checkpointIntervalMillis = CHECKPOINT_INTERVAL_MILLIS,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            runCurrent()

            coordinator.recordCommandResult(" historyless-command", true, null, null, 42L)
            coordinator.closeAndFlush()

            assertEquals(1, learning.snapshot().rankingStats.size)
            assertTrue(learning.snapshot().replayCommands.isEmpty())
            val persisted =
                requireNotNull(
                    CompletionLearningSnapshotCodec.decode(
                        Files.readAllLines(path),
                    ),
                )
            assertEquals(1, persisted.rankingStats.size)
            assertTrue(persisted.replayCommands.isEmpty())
        }

    @Test
    fun `exact suggestion feedback uses the same checkpoint path`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                feedbackAtEpochMillis = 42L,
            )
            coordinator.closeAndFlush()

            assertEquals(
                1,
                learning
                    .snapshot()
                    .rankingStats
                    .single()
                    .acceptedCount,
            )
            assertEquals(listOf(learning.snapshot()), files.writes)
        }

    @Test
    fun `evicted no-op mutation does not request a checkpoint`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore(capacity = 1)
            learning.recordCommandResult("retained", true, null, null, 100L)
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("obsolete", true, null, null, 1L)
            coordinator.closeAndFlush()

            assertEquals(listOf("retained"), learning.snapshot().replayCommands.map { it.commandLine })
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `failed hydration reports the original cause once and blocks overwrite`() =
        runTest {
            val expectedFailure = IOException("test load failure")
            val diagnostics = CopyOnWriteArrayList<Throwable>()
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Failed(expectedFailure),
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator =
                coordinator(
                    learning = learning,
                    files = files,
                    onPersistenceLoadFailure = diagnostics::add,
                )

            coordinator.recordCommandResult("git status", true, null, null, 42L)
            coordinator.closeAndFlush()
            coordinator.closeAndFlush()

            assertEquals(listOf("git status"), learning.snapshot().replayCommands.map { it.commandLine })
            assertTrue(files.writes.isEmpty())
            assertEquals(1, diagnostics.size)
            assertSame(expectedFailure, diagnostics.single())
        }

    @Test
    fun `rejected hydration reports once and blocks overwrite`() =
        runTest {
            val diagnostics = CopyOnWriteArrayList<Throwable>()
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Rejected,
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator =
                coordinator(
                    learning = learning,
                    files = files,
                    onPersistenceLoadFailure = diagnostics::add,
                )

            coordinator.recordCommandResult("git status", true, null, null, 42L)
            coordinator.closeAndFlush()
            coordinator.closeAndFlush()

            assertEquals(listOf("git status"), learning.snapshot().replayCommands.map { it.commandLine })
            assertTrue(files.writes.isEmpty())
            assertEquals(1, diagnostics.size)
            assertTrue(
                diagnostics
                    .single()
                    .message
                    .orEmpty()
                    .contains("snapshot was rejected"),
            )
        }

    @Test
    fun `diagnostic callback failure does not destabilize the worker`() =
        runTest {
            val callbackCount = AtomicInteger()
            val files =
                RecordingSnapshotFiles(
                    initialLoadOutcome = CompletionLearningFileLoadOutcome.Rejected,
                )
            val coordinator =
                coordinator(
                    learning = TerminalCompletionLearningStore(),
                    files = files,
                    onPersistenceLoadFailure = {
                        callbackCount.incrementAndGet()
                        error("test diagnostic failure")
                    },
                )

            coordinator.recordCommandResult("git status", true, null, null, 42L)
            coordinator.closeAndFlush()

            assertEquals(1, callbackCount.get())
            assertTrue(files.writes.isEmpty())
        }

    @Test
    fun `checkpoint failure retries only after a newer mutation`() =
        runTest {
            val expectedFailure = IOException("test write failure")
            val files = RecordingSnapshotFiles(writeFailure = expectedFailure)
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("git status", true, null, null, 42L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            assertEquals(1, files.writeAttempts.get())

            files.writeFailure = null
            coordinator.recordCommandResult("npm test", true, null, null, 43L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()
            coordinator.closeAndFlush()

            assertEquals(2, files.writeAttempts.get())
            assertEquals(listOf(learning.snapshot()), files.writes)
        }

    @Test
    fun `shutdown retries a failed checkpoint once and surfaces the final failure`() =
        runTest {
            val expectedFailure = IOException("test write failure")
            val files = RecordingSnapshotFiles(writeFailure = expectedFailure)
            val coordinator = coordinator(TerminalCompletionLearningStore(), files)

            coordinator.recordCommandResult("git status", true, null, null, 42L)
            runCurrent()
            advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
            runCurrent()

            val thrown = assertFailsWith<IOException> { coordinator.closeAndFlush() }
            assertEquals(expectedFailure.message, thrown.message)
            assertEquals(2, files.writeAttempts.get())
            assertFailsWith<IOException> { coordinator.closeAndFlush() }
            assertEquals(2, files.writeAttempts.get())
        }

    @Test
    fun `mutation accepted during an in-flight write is included in the final write`() =
        runTest {
            val loadFinished = CompletableDeferred<Unit>()
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    afterLoad = { loadFinished.complete(Unit) },
                    beforePersist = { attempt ->
                        if (attempt == 1) {
                            writeStarted.complete(Unit)
                            check(releaseWrite.await(5L, TimeUnit.SECONDS)) { "test write was not released" }
                        }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                loadFinished.await()
                runCurrent()
                coordinator.recordCommandResult("git status", true, null, null, 1L)
                runCurrent()
                advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
                runCurrent()
                writeStarted.await()

                coordinator.recordCommandResult("git status", true, null, null, 2L)
                releaseWrite.countDown()
                coordinator.closeAndFlush()

                assertEquals(2, files.writes.size)
                assertEquals(
                    1,
                    files.writes
                        .first()
                        .rankingStats
                        .single()
                        .useCount,
                )
                assertEquals(
                    2,
                    files.writes
                        .last()
                        .rankingStats
                        .single()
                        .useCount,
                )
            } finally {
                releaseWrite.countDown()
            }
        }

    @Test
    fun `disable during an in-flight write allows only that write to finish`() =
        runTest {
            val loadFinished = CompletableDeferred<Unit>()
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CountDownLatch(1)
            val files =
                RecordingSnapshotFiles(
                    afterLoad = { loadFinished.complete(Unit) },
                    beforePersist = { attempt ->
                        if (attempt == 1) {
                            writeStarted.complete(Unit)
                            check(releaseWrite.await(5L, TimeUnit.SECONDS)) { "test write was not released" }
                        }
                    },
                )
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files, ioDispatcher = Dispatchers.IO)

            try {
                loadFinished.await()
                runCurrent()
                coordinator.recordCommandResult("git status", true, null, null, 1L)
                runCurrent()
                advanceTimeBy(CHECKPOINT_INTERVAL_MILLIS.milliseconds)
                runCurrent()
                writeStarted.await()

                coordinator.recordCommandResult("git status", true, null, null, 2L)
                coordinator.setPersistenceEnabled(false)
                releaseWrite.countDown()
                coordinator.closeAndFlush()

                assertEquals(1, files.writes.size)
                assertEquals(
                    1,
                    files.writes
                        .single()
                        .rankingStats
                        .single()
                        .useCount,
                )
                assertEquals(
                    2,
                    learning
                        .snapshot()
                        .rankingStats
                        .single()
                        .useCount,
                )
            } finally {
                releaseWrite.countDown()
            }
        }

    @Test
    fun `close flushes once and remains idempotent`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)

            coordinator.recordCommandResult("gradle test", true, null, null, 42L)
            coordinator.closeAndFlush()
            coordinator.closeAndFlush()

            assertEquals(listOf(learning.snapshot()), files.writes)
        }

    @Test
    fun `cancelling a close waiter does not wait for blocked file IO`() =
        runBlocking {
            val loadFinished = CountDownLatch(1)
            val writeStarted = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val files =
                    RecordingSnapshotFiles(
                        afterLoad = loadFinished::countDown,
                        beforePersist = {
                            writeStarted.countDown()
                            releaseWrite.await()
                        },
                    )
                val coordinator =
                    TerminalCompletionLearningCoordinator(
                        learningStore = TerminalCompletionLearningStore(),
                        fileStore = files,
                        coroutineScope = workerScope,
                        persistenceEnabled = true,
                        checkpointIntervalMillis = CHECKPOINT_INTERVAL_MILLIS,
                        ioDispatcher = Dispatchers.IO,
                    )
                assertTrue(loadFinished.await(5L, TimeUnit.SECONDS))
                coordinator.recordCommandResult("git status", true, null, null, 42L)

                val closeWaiter = async(Dispatchers.Default) { coordinator.closeAndFlush() }
                assertTrue(writeStarted.await(5L, TimeUnit.SECONDS))
                closeWaiter.cancel()
                withTimeout(500L.milliseconds) { closeWaiter.join() }

                assertTrue(closeWaiter.isCancelled)
                assertEquals(1, files.writeAttempts.get())
            } finally {
                releaseWrite.countDown()
                workerScope.cancel()
            }
        }

    @Test
    fun `concurrent close persists every mutation that returned normally`() =
        runTest {
            val files = RecordingSnapshotFiles()
            val learning = TerminalCompletionLearningStore()
            val coordinator = coordinator(learning, files)
            val start = CompletableDeferred<Unit>()
            val recordings =
                List(CONCURRENT_RECORDINGS) { index ->
                    async<Throwable?>(Dispatchers.Default) {
                        start.await()
                        try {
                            coordinator.recordCommandResult("git status", true, null, null, index + 1L)
                            null
                        } catch (failure: Throwable) {
                            failure
                        }
                    }
                }
            val closing =
                async(Dispatchers.Default) {
                    start.await()
                    coordinator.closeAndFlush()
                }

            start.complete(Unit)
            val outcomes = recordings.awaitAll()
            outcomes.filterNotNull().forEach { failure ->
                assertTrue(failure is IllegalStateException, "unexpected recording failure: $failure")
                assertEquals("completion-learning owner is closed", failure.message)
            }
            val accepted = outcomes.count { it == null }
            closing.await()
            coordinator.closeAndFlush()

            val retained =
                learning
                    .snapshot()
                    .rankingStats
                    .singleOrNull()
                    ?.useCount ?: 0
            assertEquals(accepted, retained)
            if (accepted == 0) {
                assertTrue(files.writes.isEmpty())
            } else {
                assertEquals(
                    accepted,
                    files.writes
                        .last()
                        .rankingStats
                        .single()
                        .useCount,
                )
            }
        }

    @Test
    fun `checkpoint interval must be positive`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                TerminalCompletionLearningCoordinator(
                    learningStore = TerminalCompletionLearningStore(),
                    fileStore = RecordingSnapshotFiles(),
                    coroutineScope = this,
                    persistenceEnabled = true,
                    checkpointIntervalMillis = 0L,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            }
        }

    private fun TestScope.coordinator(
        learning: TerminalCompletionLearningStore,
        files: RecordingSnapshotFiles,
        enabled: Boolean = true,
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler, name = "learning-io"),
        onPersistenceLoadFailure: (Throwable) -> Unit = {},
    ): TerminalCompletionLearningCoordinator =
        TerminalCompletionLearningCoordinator(
            learningStore = learning,
            fileStore = files,
            coroutineScope = this,
            persistenceEnabled = enabled,
            checkpointIntervalMillis = CHECKPOINT_INTERVAL_MILLIS,
            ioDispatcher = ioDispatcher,
            onPersistenceLoadFailure = onPersistenceLoadFailure,
        )

    private class RecordingSnapshotFiles(
        initialLoadOutcome: CompletionLearningFileLoadOutcome = CompletionLearningFileLoadOutcome.Missing,
        @Volatile var writeFailure: Throwable? = null,
        private val beforeLoad: () -> Unit = {},
        private val afterLoad: () -> Unit = {},
        private val beforePersist: (Int) -> Unit = {},
    ) : CompletionLearningSnapshotFileStore {
        @Volatile
        var loadOutcome: CompletionLearningFileLoadOutcome = initialLoadOutcome

        val loadCount = AtomicInteger()
        val writeAttempts = AtomicInteger()
        val writes = CopyOnWriteArrayList<TerminalCompletionLearningSnapshot>()

        override fun loadSnapshot(): CompletionLearningFileLoadOutcome {
            beforeLoad()
            loadCount.incrementAndGet()
            val outcome = loadOutcome
            afterLoad()
            return outcome
        }

        override fun persist(snapshot: TerminalCompletionLearningSnapshot) {
            val attempt = writeAttempts.incrementAndGet()
            beforePersist(attempt)
            writeFailure?.let { throw it }
            writes += snapshot
        }
    }

    private fun snapshot(
        commandLine: String,
        timestamp: Long,
    ): TerminalCompletionLearningSnapshot =
        TerminalCompletionLearningStore()
            .apply { recordCommandResult(commandLine, true, null, null, timestamp) }
            .snapshot()

    private companion object {
        private const val BURST_SIZE = 5_000
        private const val CONCURRENT_RECORDINGS = 128
        private const val CHECKPOINT_INTERVAL_MILLIS = 1_000L
    }
}

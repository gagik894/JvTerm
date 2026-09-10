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
import kotlinx.coroutines.channels.Channel
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns synchronous exact-command learning and optional fixed-path persistence.
 *
 * One caller-owned worker hydrates the file once, observes last-value
 * enablement, and checkpoints the latest dirty snapshot at a fixed interval.
 * Recording and reset mutate memory synchronously and never wait for disk.
 * Reset bypasses enablement and the checkpoint interval so persisted learning
 * is replaced promptly. Shutdown awaits the final required write.
 */
class TerminalCompletionLearningCoordinator
    internal constructor(
        private val learningStore: TerminalCompletionLearningStore,
        private val fileStore: CompletionLearningSnapshotFileStore,
        coroutineScope: CoroutineScope,
        persistenceEnabled: Boolean,
        private val checkpointIntervalMillis: Long,
        private val ioDispatcher: CoroutineDispatcher,
        private val onPersistenceLoadFailure: (Throwable) -> Unit = {},
    ) {
        /**
         * Creates a lifecycle-bound learning owner for one fixed persistence path.
         *
         * @param learningStore bounded in-memory learning used immediately by completion ranking.
         * @param coroutineScope caller-owned lifecycle scope for the persistence worker.
         * @param persistencePath fixed snapshot path owned by the product.
         * @param persistenceEnabled whether the snapshot may initially be read and written.
         * @param onPersistenceLoadFailure invoked once when an existing file is rejected or cannot be read.
         * Exceptions thrown by this diagnostic callback are ignored.
         */
        constructor(
            learningStore: TerminalCompletionLearningStore,
            coroutineScope: CoroutineScope,
            persistencePath: Path,
            persistenceEnabled: Boolean,
            onPersistenceLoadFailure: (Throwable) -> Unit,
        ) : this(
            learningStore = learningStore,
            fileStore =
                CompletionLearningFileStore(
                    path = persistencePath.toAbsolutePath().normalize(),
                ),
            coroutineScope = coroutineScope,
            persistenceEnabled = persistenceEnabled,
            checkpointIntervalMillis = DEFAULT_CHECKPOINT_INTERVAL_MILLIS,
            ioDispatcher = Dispatchers.IO,
            onPersistenceLoadFailure = onPersistenceLoadFailure,
        )

        init {
            require(checkpointIntervalMillis > 0L) {
                "checkpointIntervalMillis must be > 0, was $checkpointIntervalMillis"
            }
        }

        private val stateLock = Any()
        private val wakeups = Channel<Unit>(Channel.CONFLATED)
        private var acceptingEvents = true
        private var closeRequested = false
        private var finalWriteAttempted = false
        private var persistenceEnabled = persistenceEnabled
        private var hydrated = false
        private var loadBlocked = false
        private var mutationRevision = 0L
        private var persistedRevision = 0L
        private var attemptedRevision = 0L
        private var resetRevision = 0L
        private var persistenceFailure: Throwable? = null
        private val worker: Deferred<Unit> =
            coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
                runWorker()
            }

        /**
         * Records one completed command in memory and marks a changed snapshot dirty for optional persistence.
         *
         * @param commandLine command text reported by shell integration.
         * @param successful whether the command completed successfully.
         * @param profileId optional host profile identifier.
         * @param workingDirectoryUri optional working-directory URI captured for the command.
         * @param usedAtEpochMillis non-negative completion timestamp.
         */
        fun recordCommandResult(
            commandLine: String,
            successful: Boolean,
            profileId: String?,
            workingDirectoryUri: String?,
            usedAtEpochMillis: Long,
        ) {
            if (usedAtEpochMillis < 0L) return
            synchronized(stateLock) {
                check(acceptingEvents) { "completion-learning owner is closed" }
                val changed =
                    learningStore.recordCommandResult(
                        commandLine = commandLine,
                        successful = successful,
                        profileId = profileId,
                        workingDirectoryUri = workingDirectoryUri,
                        usedAtEpochMillis = usedAtEpochMillis,
                    )
                if (changed) markDirty()
            }
        }

        /**
         * Records accepted or dismissed feedback for one exact suggested command.
         *
         * @param commandLine command text produced by accepting the suggestion.
         * @param feedback accepted or dismissed feedback kind.
         * @param profileId optional host profile identifier.
         * @param workingDirectoryUri optional working-directory URI captured for the event.
         * @param feedbackAtEpochMillis non-negative feedback timestamp.
         */
        fun recordSuggestionFeedback(
            commandLine: String,
            feedback: TerminalCompletionFeedbackKind,
            profileId: String?,
            workingDirectoryUri: String?,
            feedbackAtEpochMillis: Long,
        ) {
            if (feedbackAtEpochMillis < 0L) return
            synchronized(stateLock) {
                check(acceptingEvents) { "completion-learning owner is closed" }
                val changed =
                    learningStore.recordSuggestionFeedback(
                        commandLine = commandLine,
                        feedback = feedback,
                        profileId = profileId,
                        workingDirectoryUri = workingDirectoryUri,
                        feedbackAtEpochMillis = feedbackAtEpochMillis,
                    )
                if (changed) markDirty()
            }
        }

        /**
         * Removes all completion learning and schedules immediate replacement of the persisted snapshot.
         *
         * The in-memory reset is visible before this method returns. A reset
         * supersedes an in-flight hydration and is written even when ordinary
         * persistence is disabled.
         */
        fun resetLearning() {
            synchronized(stateLock) {
                check(acceptingEvents) { "completion-learning owner is closed" }
                learningStore.clear()
                hydrated = true
                loadBlocked = false
                persistenceFailure = null
                markDirty()
                resetRevision = mutationRevision
                wakeups.trySend(Unit)
            }
        }

        /**
         * Enables or disables persistence for the fixed configured path.
         *
         * The first enable hydrates the file once. Disabling cancels a pending
         * checkpoint but does not clear in-memory learning. File I/O already in
         * progress may still finish.
         *
         * @param enabled whether the fixed snapshot may be read and written.
         */
        fun setPersistenceEnabled(enabled: Boolean) {
            synchronized(stateLock) {
                check(acceptingEvents) { "completion-learning owner is closed" }
                if (persistenceEnabled == enabled) return
                persistenceEnabled = enabled
                wakeups.trySend(Unit)
            }
        }

        /**
         * Stops accepting events and awaits the final dirty write when persistence is enabled.
         *
         * Calling this method more than once is safe. An unrecovered final
         * write failure is rethrown after the worker stops.
         */
        suspend fun closeAndFlush() {
            synchronized(stateLock) {
                if (acceptingEvents) {
                    acceptingEvents = false
                    closeRequested = true
                    wakeups.trySend(Unit)
                }
            }
            worker.await()
            synchronized(stateLock) {
                persistenceFailure.takeIf { persistenceEnabled || resetRevision > persistedRevision }
            }?.let { throw it }
        }

        /** Stops learning and awaits the worker without loading or saving pending state. */
        suspend fun closeWithoutFlush() {
            synchronized(stateLock) {
                acceptingEvents = false
                closeRequested = true
                persistenceEnabled = false
            }
            worker.cancelAndJoin()
        }

        private suspend fun runWorker() {
            try {
                while (true) {
                    when (nextAction()) {
                        WorkerAction.HYDRATE -> hydrate()
                        WorkerAction.WAIT -> wakeups.receive()
                        WorkerAction.WAIT_FOR_CHECKPOINT -> waitForCheckpoint()
                        WorkerAction.WRITE_NOW -> persistLatest()
                        WorkerAction.WRITE_RESET -> persistReset()
                        WorkerAction.STOP -> return
                    }
                }
            } finally {
                synchronized(stateLock) {
                    acceptingEvents = false
                    closeRequested = true
                }
                wakeups.close()
            }
        }

        private fun nextAction(): WorkerAction =
            synchronized(stateLock) {
                when {
                    persistenceEnabled && !hydrated -> WorkerAction.HYDRATE
                    resetRevision > attemptedRevision -> WorkerAction.WRITE_RESET
                    closeRequested && canPersist() && !finalWriteAttempted -> {
                        finalWriteAttempted = true
                        WorkerAction.WRITE_NOW
                    }
                    closeRequested -> WorkerAction.STOP
                    canCheckpoint() -> WorkerAction.WAIT_FOR_CHECKPOINT
                    else -> WorkerAction.WAIT
                }
            }

        private suspend fun hydrate() {
            val outcome = withContext(ioDispatcher) { fileStore.loadSnapshot() }
            val loadFailure =
                when (outcome) {
                    CompletionLearningFileLoadOutcome.Rejected ->
                        IllegalStateException(
                            "Completion-learning persistence was disabled because the existing snapshot was rejected",
                        )
                    is CompletionLearningFileLoadOutcome.Failed -> outcome.cause
                    else -> null
                }
            val reportedFailure =
                synchronized(stateLock) {
                    hydrated = true
                    if (resetRevision != 0L) {
                        null
                    } else {
                        if (outcome is CompletionLearningFileLoadOutcome.Loaded) learningStore.mergeSnapshot(outcome.snapshot)
                        loadBlocked = loadFailure != null
                        loadFailure
                    }
                }
            if (reportedFailure != null) {
                runCatching { onPersistenceLoadFailure(reportedFailure) }
            }
        }

        private suspend fun waitForCheckpoint() {
            while (wakeups.tryReceive().isSuccess) {
                // Collapse signals already represented by the current state.
            }
            if (synchronized(stateLock) { !canCheckpoint() || closeRequested }) return

            val stateChanged =
                withTimeoutOrNull(checkpointIntervalMillis.milliseconds) {
                    wakeups.receive()
                    true
                } ?: false
            if (!stateChanged) persistLatest()
        }

        private suspend fun persistLatest() {
            var revision = 0L
            val snapshot =
                synchronized(stateLock) {
                    if (!canPersist()) return
                    revision = mutationRevision
                    learningStore.snapshot()
                }
            val shouldPersist =
                synchronized(stateLock) {
                    if (!canPersist()) return@synchronized false
                    attemptedRevision = maxOf(attemptedRevision, revision)
                    true
                }
            if (!shouldPersist) return

            val failure =
                try {
                    withContext(ioDispatcher) { fileStore.persist(snapshot) }
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (writeFailure: Throwable) {
                    writeFailure
                }

            synchronized(stateLock) {
                if (failure == null) {
                    if (revision > persistedRevision) persistedRevision = revision
                    persistenceFailure = null
                } else {
                    persistenceFailure = failure
                }
            }
        }

        private suspend fun persistReset() {
            val revision =
                synchronized(stateLock) {
                    if (resetRevision <= attemptedRevision) return
                    resetRevision.also { attemptedRevision = maxOf(attemptedRevision, it) }
                }
            val failure =
                try {
                    withContext(ioDispatcher) { fileStore.persist(TerminalCompletionLearningSnapshot.EMPTY) }
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (writeFailure: Throwable) {
                    writeFailure
                }

            synchronized(stateLock) {
                if (failure == null) {
                    persistedRevision = maxOf(persistedRevision, revision)
                    persistenceFailure = null
                } else {
                    persistenceFailure = failure
                }
            }
        }

        private fun markDirty() {
            check(mutationRevision != Long.MAX_VALUE) { "completion-learning mutation revision overflow" }
            val shouldSignal = mutationRevision == persistedRevision || mutationRevision == attemptedRevision
            ++mutationRevision
            if (shouldSignal) wakeups.trySend(Unit)
        }

        private fun canPersist(): Boolean = persistenceEnabled && hydrated && !loadBlocked && mutationRevision > persistedRevision

        private fun canCheckpoint(): Boolean = canPersist() && mutationRevision > attemptedRevision

        private enum class WorkerAction {
            HYDRATE,
            WAIT,
            WAIT_FOR_CHECKPOINT,
            WRITE_NOW,
            WRITE_RESET,
            STOP,
        }

        companion object {
            private const val DEFAULT_CHECKPOINT_INTERVAL_MILLIS = 30_000L

            /**
             * Returns the versioned filename used for persisted learning.
             *
             * Hosts choose the parent directory while this module owns the
             * file-format identity.
             *
             * @return current completion-learning filename.
             */
            @JvmStatic
            fun currentFileName(): String = CompletionLearningSnapshotCodec.currentFileName()
        }
    }

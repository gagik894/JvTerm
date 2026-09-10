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
package io.github.ketraterm.intellij.services

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.host.SwingCompletionFeedbackRecorder
import io.github.ketraterm.ui.swing.host.SwingCompletionResources
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Plugin-owned bridge from IntelliJ context to completion sources and shared learning.
 *
 * @param specs immutable command specifications shared by every session.
 * @param learningStore bounded exact-command learning store.
 * @param persistencePath fixed product-owned learning destination.
 * @param persistenceEnabled whether the fixed destination may initially be read and written.
 * @param coroutineScope host lifecycle scope that parents completion work.
 * @param onPersistenceLoadFailure host diagnostic invoked when existing learning cannot be loaded safely.
 */
internal class IntellijCompletionRegistry(
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val learningStore: TerminalCompletionLearningStore = TerminalCompletionLearningStore(),
    persistencePath: Path,
    persistenceEnabled: Boolean,
    coroutineScope: CoroutineScope,
    onPersistenceLoadFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var closed = false
    private val commandSpecs = specs
    private val learning =
        TerminalCompletionLearningCoordinator(
            learningStore = learningStore,
            persistencePath = persistencePath,
            persistenceEnabled = persistenceEnabled,
            coroutineScope = coroutineScope,
            onPersistenceLoadFailure = onPersistenceLoadFailure,
        )
    private val feedbackRecorder =
        SwingCompletionFeedbackRecorder(
            recordSuggestionFeedback = { commandLine, feedback, profileId, workingDirectoryUri, feedbackAtEpochMillis ->
                synchronized(lock) {
                    if (!closed) {
                        learning.recordSuggestionFeedback(
                            commandLine = commandLine,
                            feedback = feedback,
                            profileId = profileId,
                            workingDirectoryUri = workingDirectoryUri,
                            feedbackAtEpochMillis = feedbackAtEpochMillis,
                        )
                    }
                }
            },
        )

    /**
     * Creates completion resources for one terminal pane.
     *
     * @param context host capabilities and additional suspending sources for the session.
     * @return provider and feedback resources for the pane.
     * @throws IllegalStateException if this registry is closed.
     */
    fun createResources(context: IntellijCompletionContext): SwingCompletionResources {
        synchronized(lock) {
            check(!closed) { "IntelliJ completion registry is closed" }
            val fileSystemProvider = TerminalLocalFileSystemProvider(scanner = context.directoryScanner)
            val sources =
                buildList(context.additionalSources.size + 1) {
                    add(
                        TerminalCompletionSourceEntry(
                            TerminalCompletionSources.path(fileSystemProvider),
                            TerminalCompletionSourcePrior.DIRECTORY_PATH,
                        ),
                    )
                    addAll(context.additionalSources)
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = sources,
                    commandSpecs = commandSpecs,
                    learningStore = learningStore,
                )
            return SwingCompletionResources(
                provider = SwingCompletionSuggestionProvider(engine, context::swingContext),
                feedbackHandler = feedbackRecorder.createHandler(),
            )
        }
    }

    /**
     * Records privacy-filtered shared learning from one finished command.
     *
     * @param profileId stable terminal profile identifier used for ranking context.
     * @param metadata trusted shell-integration lifecycle metadata.
     */
    fun recordFinishedCommand(
        profileId: String,
        metadata: TerminalShellIntegrationCommandMetadata,
    ) {
        val command = metadata.commandText ?: return
        val successful = metadata.lifecycle == TerminalShellIntegrationCommandLifecycle.SUCCEEDED
        synchronized(lock) {
            if (closed) return
            learning.recordCommandResult(
                commandLine = command,
                successful = successful,
                profileId = profileId,
                workingDirectoryUri = metadata.workingDirectoryUri,
                usedAtEpochMillis = metadata.finishedAtEpochMillis ?: System.currentTimeMillis(),
            )
        }
    }

    /**
     * Changes whether the registry may read and write learned statistics on disk.
     *
     * @param enabled `true` to persist sanitized learning across IDE restarts.
     */
    fun setPersistenceEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (closed) return
            learning.setPersistenceEnabled(enabled)
        }
    }

    /** Removes all session and persisted completion learning. */
    fun resetLearning() {
        synchronized(lock) {
            if (!closed) learning.resetLearning()
        }
    }

    /** Disables learning and stops persistence without a final write. */
    suspend fun closeWithoutFlush() {
        synchronized(lock) { closed = true }
        learning.closeWithoutFlush()
    }

    /** Stops accepting learning events and waits for the final dirty persistence write. */
    suspend fun closeAndFlush() {
        synchronized(lock) {
            closed = true
        }
        learning.closeAndFlush()
    }
}

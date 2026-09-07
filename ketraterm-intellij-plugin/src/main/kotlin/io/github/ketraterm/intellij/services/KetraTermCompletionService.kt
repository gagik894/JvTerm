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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalCompletionSourcePrior
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.host.SwingCompletionResources
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

/**
 * Application-level owner of IntelliJ completion learning and product sources.
 *
 * The service owns one [IntellijCompletionRegistry] and an independent scope
 * that remains alive until final learning persistence has completed.
 */
@Service(Service.Level.APP)
internal class KetraTermCompletionService : Disposable {
    private val lifecycle = IntellijCompletionLifecycle()
    private val settings = KetraTermIntellijSettings.getInstance()
    private val persistencePath =
        PathManager
            .getSystemDir()
            .resolve("ketraterm")
            .resolve(TerminalCompletionLearningCoordinator.currentFileName())

    @Volatile private var completionRuntime: CompletionRuntime? = null
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var shutdownJob: Job? = null
    private val resourcesByTab = java.util.IdentityHashMap<TerminalWorkspaceTab, SwingCompletionResources>()
    private val resourceListeners = CopyOnWriteArrayList<() -> Unit>()
    private val settingsListener: () -> Unit = {
        lifecycle.ifOpen {
            if (!settings.state.smartSuggestionsEnabled) {
                val retiring = completionRuntime
                completionRuntime = null
                if (retiring != null) {
                    resourcesByTab.clear()
                    shutdownJob =
                        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                retiring.registry.closeWithoutFlush()
                            } finally {
                                retiring.scope.cancel()
                                SwingUtilities.invokeLater {
                                    lifecycle.ifOpen { shutdownJob = null }
                                    notifyResourceListeners()
                                }
                            }
                        }
                }
            } else {
                completionRuntime?.registry?.setPersistenceEnabled(settings.completionLearningPersistenceEnabled())
            }
        }
        notifyResourceListeners()
    }

    fun addResourceListener(listener: () -> Unit) {
        resourceListeners.addIfAbsent(listener)
    }

    fun removeResourceListener(listener: () -> Unit) {
        resourceListeners.remove(listener)
    }

    private fun notifyResourceListeners() {
        val notify = Runnable { resourceListeners.forEach { it() } }
        if (SwingUtilities.isEventDispatchThread()) notify.run() else SwingUtilities.invokeLater(notify)
    }

    init {
        settings.addChangeListener(settingsListener)
    }

    /**
     * Creates completion resources for one terminal workspace tab.
     *
     * @param project IntelliJ project used for project-aware VFS and Git queries.
     * @param tab terminal tab providing identity, profile, and working-directory state.
     * @return provider and feedback resources consumed by the terminal pane.
     * @throws IllegalStateException if application-level completion has been disposed.
     */
    fun resourcesFor(
        project: Project,
        tab: TerminalWorkspaceTab,
    ): SwingCompletionResources? =
        lifecycle.requireOpen {
            if (!settings.state.smartSuggestionsEnabled || shutdownJob != null) return@requireOpen null
            resourcesByTab.getOrPut(tab) {
                val runtime =
                    completionRuntime ?: createCompletionRuntime(persistencePath, settings.completionLearningPersistenceEnabled())
                        .also { completionRuntime = it }
                createResources(runtime, project, tab)
            }
        }

    fun releaseResources(tab: TerminalWorkspaceTab) {
        lifecycle.ifOpen { resourcesByTab.remove(tab) }
    }

    private fun createResources(
        runtime: CompletionRuntime,
        project: Project,
        tab: TerminalWorkspaceTab,
    ): SwingCompletionResources {
        val context =
            IntellijCompletionContext(
                profileId = tab.profile.id,
                workingDirectoryUriProvider = { tab.currentWorkingDirectoryUri },
                shellCapabilities = tab.profile.kind.intellijCompletionShellCapabilities(),
                additionalSources =
                    listOf(
                        TerminalCompletionSourceEntry(
                            intellijGitCompletionSource(
                                loader = IntellijGitCompletionLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.GIT_REFERENCE,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijGitCommitCompletionSource(
                                loader = IntellijGitCommitCompletionLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.GIT_REFERENCE,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijGitStatusPathCompletionSource(
                                loader = IntellijGitStatusPathLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.GIT_STATUS_PATH,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijGradleTaskCompletionSource(
                                loader = IntellijGradleTaskLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.GRADLE_TASK,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijProjectFileCompletionSource(
                                loader = IntellijProjectFileLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.PROJECT_FUZZY_PATH,
                        ),
                    ),
                directoryScanner = IntellijProjectDirectoryScanner(project),
            )
        return runtime.registry.createResources(context)
    }

    /**
     * Records one shell-integration command completion for shared learning.
     *
     * Privacy policy is applied before any command is persisted.
     *
     * @param tab terminal tab that executed the command.
     * @param metadata trusted shell-integration command lifecycle metadata.
     */
    fun recordFinishedCommand(
        tab: TerminalWorkspaceTab,
        metadata: TerminalShellIntegrationCommandMetadata,
    ) {
        lifecycle.ifOpen {
            if (!settings.state.smartSuggestionsEnabled) return@ifOpen
            completionRuntime?.registry?.recordFinishedCommand(
                profileId = tab.profile.id,
                metadata = metadata,
            )
        }
    }

    /** Starts bounded final persistence without waiting on the EDT. */
    override fun dispose() {
        if (!lifecycle.beginClose()) return
        settings.removeChangeListener(settingsListener)
        resourceListeners.clear()
        resourcesByTab.clear()
        val runtime = completionRuntime
        completionRuntime = null
        val stopping = shutdownJob
        if (runtime == null && stopping == null) {
            lifecycleScope.cancel()
            return
        }
        val flush = settings.state.smartSuggestionsEnabled
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val completed =
                    withTimeoutOrNull(COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS.milliseconds) {
                        stopping?.join()
                        if (runtime != null) {
                            if (flush) runtime.registry.closeAndFlush() else runtime.registry.closeWithoutFlush()
                        }
                        true
                    } ?: false
                if (!completed) {
                    LOG.warn(
                        "Completion learning persistence exceeded its $COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS ms shutdown budget",
                    )
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                LOG.warn("Final completion learning persistence failed", failure)
            } finally {
                runtime?.scope?.cancel()
                lifecycleScope.cancel()
            }
        }
    }

    private class CompletionRuntime(
        val scope: CoroutineScope,
        val registry: IntellijCompletionRegistry,
    )

    companion object {
        private fun createCompletionRuntime(
            persistencePath: Path,
            persistenceEnabled: Boolean,
        ): CompletionRuntime {
            val scope =
                CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ketraterm-completion-persistence"))
            return try {
                CompletionRuntime(
                    scope = scope,
                    registry =
                        IntellijCompletionRegistry(
                            persistencePath = persistencePath,
                            persistenceEnabled = persistenceEnabled,
                            coroutineScope = scope,
                            onPersistenceLoadFailure = { failure ->
                                LOG.warn(
                                    "Completion learning persistence was disabled because existing data could not be loaded",
                                    failure,
                                )
                            },
                        ),
                )
            } catch (failure: Throwable) {
                scope.cancel()
                throw failure
            }
        }

        /**
         * Returns the application service instance.
         *
         * @return IntelliJ-managed completion service.
         */
        fun getInstance(): KetraTermCompletionService = service()

        fun getInstanceIfCreated(): KetraTermCompletionService? =
            com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .getServiceIfCreated(KetraTermCompletionService::class.java)

        private val LOG: Logger = Logger.getInstance(KetraTermCompletionService::class.java)
        private const val COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS = 500L
    }
}

internal class IntellijCompletionLifecycle {
    private val lock = Any()
    private var closed = false

    fun <T> requireOpen(action: () -> T): T =
        synchronized(lock) {
            check(!closed) { "IntelliJ completion service is disposed" }
            action()
        }

    fun ifOpen(action: () -> Unit) {
        synchronized(lock) {
            if (!closed) action()
        }
    }

    fun beginClose(): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            closed = true
            true
        }
}

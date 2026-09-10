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
import io.github.ketraterm.completion.host.TerminalDirectoryScanner
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class IntellijCompletionRegistryTest {
    @Test
    fun `reset removes shared completion learning`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult("git status", true, "bash", "file:///repo", 1L)
            val registry =
                IntellijCompletionRegistry(
                    learningStore = learningStore,
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )

            registry.resetLearning()

            assertTrue(learningStore.snapshot().rankingStats.isEmpty())
            assertTrue(learningStore.snapshot().replayCommands.isEmpty())
            registry.closeAndFlush()
        }

    @Test
    fun `directory completion suspends until the scanner returns real values`() =
        runBlocking {
            var scans = 0
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            val resources =
                registry.createResources(
                    context(
                        scanner =
                            TerminalDirectoryScanner { _, _ ->
                                scans++
                                listOf(TerminalFileEntry("src", true))
                            },
                    ),
                )

            val suggestions = resources.provider.suggestions(request("cd s")).last()

            assertEquals(1, scans)
            assertEquals("src/", suggestions.first { it.source == "path" }.replacementText)
            registry.closeAndFlush()
        }

    @Test
    fun `dynamic provider loads values in the completion coroutine`() =
        runBlocking {
            var loads = 0
            val source =
                TerminalCompletionSources.valueDomain(
                    sourceId = "test-branch",
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    valuesProvider = { _, _ ->
                        loads++
                        listOf(TerminalCompletionDomainValue("main"))
                    },
                )
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            val resources =
                registry.createResources(context(additionalSources = listOf(TerminalCompletionSourceEntry(source, 20))))

            resources.provider.suggestions(request("git switch m")).last()

            assertEquals(1, loads)
            registry.closeAndFlush()
        }

    @Test
    fun `successful commands feed shared learning`() =
        runBlocking {
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            val first = registry.createResources(context())
            val second = registry.createResources(context())
            registry.recordFinishedCommand(
                "bash",
                TerminalShellIntegrationCommandMetadata(
                    recordId = 1,
                    commandText = "git switch main",
                    lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                    workingDirectoryUri = "file:///repo",
                    exitCode = 0,
                    startedAtEpochMillis = 1L,
                    finishedAtEpochMillis = 2L,
                ),
            )

            assertTrue(
                first.provider
                    .suggestions(request("git s"))
                    .last()
                    .any { it.source == "learned" },
            )
            assertTrue(
                second.provider
                    .suggestions(request("git s"))
                    .last()
                    .any { it.source == "learned" },
            )
            registry.closeAndFlush()
        }

    @Test
    fun `suggestion feedback keeps the working directory captured by its request`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            val registry =
                IntellijCompletionRegistry(
                    specs =
                        listOf(
                            TerminalCommandSpec(
                                name = "git",
                                subcommands = listOf(TerminalCommandSpec("status")),
                            ),
                        ),
                    learningStore = learningStore,
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )
            var workingDirectoryUri = "file:///repo-a"
            val resources =
                registry.createResources(
                    context(workingDirectoryUriProvider = { workingDirectoryUri }),
                )
            val request = request("git s")
            val suggestion =
                resources.provider
                    .suggestions(request)
                    .last()
                    .first { it.replacementText == "status" }

            workingDirectoryUri = "file:///repo-b"
            resources.feedbackHandler.onSuggestionFeedback(
                SwingShellSuggestionFeedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    suggestion = suggestion,
                    index = 0,
                    request = request,
                ),
            )

            val snapshot = learningStore.snapshot()
            assertTrue(snapshot.replayCommands.isEmpty())
            val ranking = snapshot.rankingStats.single()
            assertEquals("bash", ranking.profileId)
            assertEquals("file:///repo-a/", ranking.workingDirectoryUri)
            assertEquals(1, ranking.acceptedCount)
            registry.closeAndFlush()
        }

    @Test
    fun `closed registry rejects new sessions`(): Unit =
        runBlocking {
            val registry =
                IntellijCompletionRegistry(
                    persistencePath = memoryOnlyPath(),
                    persistenceEnabled = false,
                    coroutineScope = this,
                )

            registry.closeAndFlush()
            registry.closeAndFlush()

            assertThrows(IllegalStateException::class.java) { registry.createResources(context()) }
        }

    @Test
    fun `close and flush persists the final queued command`() =
        runBlocking {
            val path =
                Files
                    .createTempDirectory("intellij-completion-registry")
                    .resolve(TerminalCompletionLearningCoordinator.currentFileName())
            val learningStore = TerminalCompletionLearningStore()
            val registry =
                IntellijCompletionRegistry(
                    learningStore = learningStore,
                    persistencePath = path,
                    persistenceEnabled = true,
                    coroutineScope = this,
                )

            registry.recordFinishedCommand(
                profileId = "bash",
                metadata =
                    TerminalShellIntegrationCommandMetadata(
                        recordId = 1,
                        commandText = "git status",
                        lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                        workingDirectoryUri = "file:///repo",
                        exitCode = 0,
                        startedAtEpochMillis = 1L,
                        finishedAtEpochMillis = 2L,
                    ),
            )

            registry.closeAndFlush()

            assertEquals(listOf("git status"), learningStore.snapshot().replayCommands.map { it.commandLine })
            assertEquals(listOf("git status"), persistedSnapshot(path).replayCommands.map { it.commandLine })
        }

    private suspend fun persistedSnapshot(path: Path) =
        coroutineScope {
            val learning = TerminalCompletionLearningStore()
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    learningStore = learning,
                    coroutineScope = this,
                    persistencePath = path,
                    persistenceEnabled = true,
                    onPersistenceLoadFailure = {},
                )
            coordinator.closeAndFlush()
            learning.snapshot()
        }

    private fun memoryOnlyPath(): Path =
        Files
            .createTempDirectory("intellij-completion-memory")
            .resolve(TerminalCompletionLearningCoordinator.currentFileName())

    private fun context(
        additionalSources: List<TerminalCompletionSourceEntry> = emptyList(),
        scanner: TerminalDirectoryScanner = TerminalDirectoryScanner { _: Path, _: String -> emptyList() },
        workingDirectoryUriProvider: () -> String? = { "file:///repo" },
    ) = IntellijCompletionContext(
        profileId = "bash",
        workingDirectoryUriProvider = workingDirectoryUriProvider,
        shellCapabilities = TerminalShellCapabilities.POSIX,
        additionalSources = additionalSources,
        directoryScanner = scanner,
    )

    private fun request(command: String) = SwingShellSuggestionRequest(command, command.length, 0, 0)
}

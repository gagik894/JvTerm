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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class StandaloneCompletionRegistryPersistenceTest {
    @Test
    fun `disable preserves disk state and replacement runtime loads only saved learning`(
        @TempDir directory: Path,
    ) = runBlocking {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val seed = registry(TerminalCompletionLearningStore(), path, persistenceEnabled = true)
        seed.recordFinishedCommand("git status", true, null, null, 1L)
        seed.closeAndFlush()
        val saved = Files.readAllBytes(path)
        repeat(3) {
            val learning = TerminalCompletionLearningStore()
            val current = registry(learning, path, persistenceEnabled = true)
            current.recordFinishedCommand("npm test", true, null, null, 2L)
            current.closeWithoutFlush()
            current.recordFinishedCommand("late command", true, null, null, 3L)
            assertFalse(learning.snapshot().replayCommands.any { it.commandLine == "late command" })
            assertContentEquals(saved, Files.readAllBytes(path))
        }
        assertEquals(listOf("git status"), persistedSnapshot(path).replayCommands.map { it.commandLine })
    }

    @Test
    fun `shutdown persists the final learned command`(
        @TempDir directory: Path,
    ) = runBlocking {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val learning = TerminalCompletionLearningStore()
        val registry = registry(learning, path, persistenceEnabled = true)

        registry.recordFinishedCommand("git status", true, "bash", "file:///repo", 42L)
        registry.closeAndFlush()

        assertEquals(listOf("git status"), learning.snapshot().replayCommands.map { it.commandLine })
        assertEquals(
            listOf("git status"),
            persistedSnapshot(path).replayCommands.map { it.commandLine },
        )
    }

    @Test
    fun `disabled persistence keeps learning in memory only`(
        @TempDir directory: Path,
    ) = runBlocking {
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val learning = TerminalCompletionLearningStore()
        val registry = registry(learning, path, persistenceEnabled = false)
        registry.recordFinishedCommand("git status", true, null, null, 1L)
        registry.recordFinishedCommand("npm test", true, null, null, 2L)
        registry.closeAndFlush()

        assertEquals(
            setOf("git status", "npm test"),
            learning
                .snapshot()
                .replayCommands
                .map { it.commandLine }
                .toSet(),
        )
        assertFalse(Files.exists(path))
    }

    @Test
    fun `failed final write still closes the registry and remains idempotent`(
        @TempDir directory: Path,
    ) = runBlocking {
        val parent = directory.resolve("learning")
        val path = parent.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val seedLearning = TerminalCompletionLearningStore()
        val seedRegistry = registry(seedLearning, path, persistenceEnabled = true)
        seedRegistry.recordFinishedCommand("git status", true, null, null, 1L)
        seedRegistry.closeAndFlush()

        val learning = TerminalCompletionLearningStore()
        val registry = registry(learning, path, persistenceEnabled = true)
        withTimeout(5_000L.milliseconds) {
            while (learning.snapshot().replayCommands.none { it.commandLine == "git status" }) {
                delay(10L.milliseconds)
            }
        }
        Files.delete(path)
        Files.delete(parent)
        Files.writeString(parent, "blocks the persistence directory")
        registry.recordFinishedCommand("npm test", true, null, null, 2L)
        val completionJob = requireNotNull(registry.completionScope.coroutineContext[Job])

        assertFailsWith<IOException> { registry.closeAndFlush() }
        assertTrue(completionJob.isCancelled)
        assertFailsWith<IOException> { registry.closeAndFlush() }
        assertFailsWith<IllegalStateException> { registry.createResources() }
        registry.recordFinishedCommand("late command", true, null, null, 3L)
        assertFalse(learning.snapshot().replayCommands.any { it.commandLine == "late command" })
    }

    private fun registry(
        learning: TerminalCompletionLearningStore,
        path: Path,
        persistenceEnabled: Boolean,
    ): StandaloneCompletionRegistry =
        StandaloneCompletionRegistry.create(
            persistencePath = path,
            persistenceEnabled = persistenceEnabled,
            specs = emptyList(),
            learningStore = learning,
        )

    private suspend fun persistedSnapshot(path: Path): TerminalCompletionLearningSnapshot =
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
}

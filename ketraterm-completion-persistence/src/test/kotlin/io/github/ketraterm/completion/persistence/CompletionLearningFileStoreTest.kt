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
import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CompletionLearningFileStoreTest {
    @Test
    fun `persist replaces the file synchronously`() {
        val path = path("completion-store")
        val store = CompletionLearningFileStore(path)
        val first = snapshot("git status")
        val second = snapshot("npm test")

        store.persist(first)
        assertEquals(first, store.loadSnapshot().loadedSnapshot())
        store.persist(second)
        assertEquals(second, store.loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `credential events round trip as opaque evidence without replay text`() {
        val path = path("completion-store-private")
        val store = CompletionLearningFileStore(path)
        val curl = "curl -u alice:s3cr3t https://example.test"
        val mysql = "mysql -p hunter2"
        val docker = "docker login -u alice -p hunter2"
        val redis = "redis-cli -a hunter2"
        val sshpass = "sshpass -p hunter2 ssh host"
        val learned = snapshot(curl, mysql, docker, redis, sshpass)

        assertEquals(5, learned.rankingStats.size)
        assertTrue(learned.replayCommands.isEmpty())
        store.persist(learned)

        val loaded = store.loadSnapshot().loadedSnapshot()
        val persistedText = Files.readString(path)
        assertEquals(learned.rankingStats, loaded.rankingStats)
        assertTrue(loaded.replayCommands.isEmpty())
        assertEquals(5, persistedText.lineSequence().count { it.startsWith("R\t") })
        assertFalse(persistedText.lineSequence().any { it.startsWith("H\t") })
        assertFalse("s3cr3t" in persistedText)
        assertFalse("hunter2" in persistedText)
    }

    @Test
    fun `storage boundary removes an injected sensitive replay projection`() {
        val path = path("completion-store-injected-private")
        val store = CompletionLearningFileStore(path)
        val command = "mysql -p hunter2"
        val learned = snapshot(command)
        val stats = learned.rankingStats.single()
        val injected =
            TerminalCommandReplay(
                identityDigest = stats.identityDigest,
                commandLine = command,
                profileId = stats.profileId,
                workingDirectoryUri = stats.workingDirectoryUri,
            )

        store.persist(TerminalCompletionLearningSnapshot(learned.rankingStats, listOf(injected)))

        val loaded = store.loadSnapshot().loadedSnapshot()
        assertEquals(learned.rankingStats, loaded.rankingStats)
        assertTrue(loaded.replayCommands.isEmpty())
    }

    @Test
    fun `storage boundary removes replay backed only by failed evidence`() {
        val path = path("completion-store-negative-replay")
        val store = CompletionLearningFileStore(path)
        val command = "git failed-safe-command"
        val learning = TerminalCompletionLearningStore()
        learning.recordCommandResult(command, false, null, null, 1L)
        val stats = learning.snapshot().rankingStats.single()
        val injected = TerminalCommandReplay(stats.identityDigest, command)

        store.persist(TerminalCompletionLearningSnapshot(listOf(stats), listOf(injected)))

        val loaded = store.loadSnapshot().loadedSnapshot()
        assertEquals(listOf(stats), loaded.rankingStats)
        assertTrue(loaded.replayCommands.isEmpty())
    }

    @Test
    fun `storage boundary removes replay backed only by accepted feedback`() {
        val path = path("completion-store-accepted-replay")
        val store = CompletionLearningFileStore(path)
        val command = "git status"
        val learning = TerminalCompletionLearningStore()
        learning.recordSuggestionFeedback(command, TerminalCompletionFeedbackKind.ACCEPTED, null, null, 1L)
        val stats = learning.snapshot().rankingStats.single()
        val injected = TerminalCommandReplay(stats.identityDigest, command)

        store.persist(TerminalCompletionLearningSnapshot(listOf(stats), listOf(injected)))

        val loaded = store.loadSnapshot().loadedSnapshot()
        assertEquals(listOf(stats), loaded.rankingStats)
        assertTrue(loaded.replayCommands.isEmpty())
    }

    @Test
    fun `over-bound replay row is dropped without dropping later evidence or replay`() {
        val path = path("completion-store-encoded-bound")
        val learned = snapshot("界".repeat(4_096), "git status")

        CompletionLearningFileStore(path).persist(learned)

        val loaded = CompletionLearningFileStore(path).loadSnapshot().loadedSnapshot()
        assertEquals(2, loaded.rankingStats.size)
        assertEquals(listOf("git status"), loaded.replayCommands.map { it.commandLine })
        assertTrue(Files.readAllLines(path).all { it.toByteArray().size <= MAX_LINE_BYTES })
    }

    @Test
    fun `each write uses a unique same-directory temporary file and cleans it`() {
        val directory = createTempDirectory("completion-store-temporary")
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val temporaryFiles = mutableListOf<java.nio.file.Path>()
        val store =
            CompletionLearningFileStore(
                path = path,
                createTemporaryFile = { parent, prefix, suffix ->
                    Files.createTempFile(parent, prefix, suffix).also(temporaryFiles::add)
                },
            )

        store.persist(snapshot("command-1"))
        store.persist(snapshot("command-2"))

        assertEquals(2, temporaryFiles.distinct().size)
        assertTrue(temporaryFiles.all { it.parent == directory.toAbsolutePath().normalize() })
        assertTrue(temporaryFiles.none(Files::exists))
        assertEquals(
            "command-2",
            CompletionLearningFileStore(path)
                .loadSnapshot()
                .loadedSnapshot()
                .replayCommands
                .single()
                .commandLine,
        )
    }

    @Test
    fun `missing file is reported separately`() {
        assertSame(CompletionLearningFileLoadOutcome.Missing, CompletionLearningFileStore(path("completion-store-missing")).loadSnapshot())
    }

    @Test
    fun `legacy unknown and malformed formats are rejected without changing bytes`() {
        val valid = CompletionLearningSnapshotCodec.encode(snapshot("git status"))
        val cases =
            listOf(
                "KetraTerm_COMMAND_COMPLETION_STATS\t2\n".encodeToByteArray(),
                "KetraTerm_COMMAND_COMPLETION_LEARNING\t999\n".encodeToByteArray(),
                (valid + "X\tunsupported").joinToString("\n", postfix = "\n").encodeToByteArray(),
                (valid.take(2) + "H\ttoo-short").joinToString("\n", postfix = "\n").encodeToByteArray(),
            )
        for (bytes in cases) assertRejectedWithoutMutation(bytes)
    }

    @Test
    fun `oversized input file line and line count are rejected`() {
        assertRejectedWithoutMutation(ByteArray(MAX_FILE_BYTES + 1) { 'x'.code.toByte() })
        assertRejectedWithoutMutation(HEADER.encodeToByteArray() + ByteArray(MAX_LINE_BYTES + 1) { 'x'.code.toByte() })

        val row = CompletionLearningSnapshotCodec.encode(snapshot("git status"))[1]
        val excessiveRows =
            buildString {
                appendLine(HEADER.trimEnd())
                repeat(MAX_FILE_LINES) { appendLine(row) }
            }.encodeToByteArray()
        assertRejectedWithoutMutation(excessiveRows)
    }

    @Test
    fun `non-file path is rejected`() {
        val directory = createTempDirectory("completion-store-directory")

        assertSame(CompletionLearningFileLoadOutcome.Rejected, CompletionLearningFileStore(directory).loadSnapshot())
        assertTrue(Files.isDirectory(directory))
    }

    @Test
    fun `input failure is returned separately`() {
        val path = path("completion-store-failed")
        Files.writeString(path, HEADER.trimEnd())
        val expectedFailure = IOException("test read failure")

        val outcome = CompletionLearningFileStore(path, openInput = { throw expectedFailure }).loadSnapshot()

        assertSame(expectedFailure, assertIs<CompletionLearningFileLoadOutcome.Failed>(outcome).cause)
        assertEquals(HEADER.trimEnd(), Files.readString(path))
    }

    @Test
    fun `output failure is rethrown`() {
        val expectedFailure = IOException("test write failure")
        val store =
            CompletionLearningFileStore(
                path = path("completion-store-output-failed"),
                createTemporaryFile = { _, _, _ -> throw expectedFailure },
            )

        assertSame(expectedFailure, assertFailsWith<IOException> { store.persist(snapshot("git status")) })
    }

    private fun assertRejectedWithoutMutation(originalBytes: ByteArray) {
        val path = path("completion-store-rejected")
        Files.write(path, originalBytes)

        assertSame(CompletionLearningFileLoadOutcome.Rejected, CompletionLearningFileStore(path).loadSnapshot())
        assertContentEquals(originalBytes, Files.readAllBytes(path))
    }

    private fun CompletionLearningFileLoadOutcome.loadedSnapshot(): TerminalCompletionLearningSnapshot =
        assertIs<CompletionLearningFileLoadOutcome.Loaded>(this).snapshot

    private fun snapshot(vararg commands: String): TerminalCompletionLearningSnapshot {
        val learning = TerminalCompletionLearningStore()
        for ((index, command) in commands.withIndex()) {
            learning.recordCommandResult(command, true, null, null, index + 1L)
        }
        return learning.snapshot()
    }

    private fun path(prefix: String) = createTempDirectory(prefix).resolve(TerminalCompletionLearningCoordinator.currentFileName())

    private companion object {
        private const val HEADER = "KetraTerm_COMMAND_COMPLETION_LEARNING\t3\n"
        private const val MAX_FILE_BYTES = 2_000_256
        private const val MAX_FILE_LINES = 1 + 2_048 + 2_048
        private const val MAX_LINE_BYTES = 16 * 1024
    }
}

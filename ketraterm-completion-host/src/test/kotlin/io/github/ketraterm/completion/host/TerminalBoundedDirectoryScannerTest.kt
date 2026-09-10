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
package io.github.ketraterm.completion.host

import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.ClosedFileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalBoundedDirectoryScannerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `scan observes deletion even when directory timestamp is restored`() =
        runTest {
            repeat(300) { index -> Files.createFile(directory.resolve("alpha-${index.toString().padStart(3, '0')}")) }
            val target = Files.createFile(directory.resolve("zebra-target"))
            val originalVersion = Files.getLastModifiedTime(directory)
            val scanner = scanner()

            val broad = scanner.scan(directory, "a")
            Files.delete(target)
            Files.setLastModifiedTime(directory, originalVersion)
            val narrow = scanner.scan(directory, "zebra")

            assertEquals(300, broad.size)
            assertEquals(emptyList(), narrow)
        }

    @Test
    fun `each scan observes current directory contents`() =
        runTest {
            Files.createFile(directory.resolve("alpha"))
            val scanner = scanner()
            assertEquals(listOf("alpha"), scanner.scan(directory, "").map { it.name })

            Files.createFile(directory.resolve("beta"))
            assertEquals(listOf("alpha", "beta"), scanner.scan(directory, "").map { it.name })
        }

    @Test
    fun `raw snapshot remains bounded and deterministic`() =
        runTest {
            repeat(20) { index -> Files.createFile(directory.resolve("entry-${19 - index}")) }
            val scanner = scanner(maxVisitedEntries = 10)

            val entries = scanner.scan(directory, "")

            assertTrue(entries.size <= 10)
            assertEquals(entries.sortedBy { it.name }, entries)
        }

    @Test
    fun `missing and non-directory paths are normal empty results`() =
        runTest {
            val scanner = scanner()
            val regularFile = Files.createFile(directory.resolve("regular-file"))

            assertEquals(emptyList(), scanner.scan(directory.resolve("missing"), ""))
            assertEquals(emptyList(), scanner.scan(regularFile, ""))
        }

    @Test
    fun `abnormal filesystem failure propagates`() =
        runTest {
            val archive = directory.resolve("closed.zip")
            val fileSystem = FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true"))
            val closedRoot = fileSystem.getPath("/")
            fileSystem.close()

            assertFailsWith<ClosedFileSystemException> {
                scanner().scan(closedRoot, "")
            }
        }

    @Test
    fun `scan propagates coroutine cancellation before blocking work starts`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scanner = scanner(ioDispatcher = dispatcher)
            val scanning = async(start = CoroutineStart.UNDISPATCHED) { scanner.scan(directory, "") }

            scanning.cancel(CancellationException("obsolete directory completion"))

            assertFailsWith<CancellationException> { scanning.await() }
        }

    private fun scanner(
        maxVisitedEntries: Int = 8_192,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): TerminalBoundedDirectoryScanner =
        TerminalBoundedDirectoryScanner(
            maxVisitedEntries = maxVisitedEntries,
            scanBudgetNanos = Long.MAX_VALUE,
            nanoTime = { 0L },
            ioDispatcher = ioDispatcher,
        )
}

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

import io.github.ketraterm.completion.api.TerminalDirectoryListingRequest
import io.github.ketraterm.completion.api.TerminalFileEntry
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TerminalLocalFileSystemProviderTest {
    @Test
    fun `resolves and scans each request directly`() =
        runTest {
            val directory = createTempDirectory("completion-directory")
            val scans = mutableListOf<Pair<Path, String>>()
            val provider =
                TerminalLocalFileSystemProvider(
                    scanner =
                        TerminalDirectoryScanner { scannedDirectory, prefix ->
                            scans += scannedDirectory to prefix
                            listOf(TerminalFileEntry("present", isDirectory = false))
                        },
                )

            val request = TerminalDirectoryListingRequest(directory.toUri().toString(), "", "pre")
            val first = provider.listDirectory(request)
            val second = provider.listDirectory(request)

            assertEquals(listOf("present"), first.map(TerminalFileEntry::name))
            assertEquals(first, second)
            assertEquals(listOf(directory to "pre", directory to "pre"), scans)
        }

    @Test
    fun `unsupported working-directory URI is a normal empty result`() =
        runTest {
            var scans = 0
            val provider =
                TerminalLocalFileSystemProvider(
                    scanner =
                        TerminalDirectoryScanner { _, _ ->
                            scans++
                            emptyList()
                        },
                )

            val entries = provider.listDirectory(TerminalDirectoryListingRequest("ssh://host/repo", "", "pre"))

            assertEquals(emptyList(), entries)
            assertEquals(0, scans)
        }

    @Test
    fun `scanner failure propagates unchanged`() =
        runTest {
            val directory = createTempDirectory("completion-failure")
            val failure = IOException("directory access failed")
            val provider = TerminalLocalFileSystemProvider(scanner = TerminalDirectoryScanner { _, _ -> throw failure })

            val thrown =
                assertFailsWith<IOException> {
                    provider.listDirectory(TerminalDirectoryListingRequest(directory.toUri().toString(), "", "pre"))
                }

            assertSame(failure, thrown)
        }
}

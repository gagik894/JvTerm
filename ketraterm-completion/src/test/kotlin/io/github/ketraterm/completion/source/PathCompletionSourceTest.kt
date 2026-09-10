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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.*

class PathCompletionSourceTest {
    private val mockProvider = FakeFileSystemProvider()
    private val source = PathCompletionSource(mockProvider)

    init {
        mockProvider.register(
            "file:///project/",
            listOf(
                TerminalFileEntry("src", isDirectory = true),
                TerminalFileEntry(".hidden", isDirectory = true),
                TerminalFileEntry("Idea Projects", isDirectory = true),
                TerminalFileEntry("O'Brien", isDirectory = true),
                TerminalFileEntry("README.md", isDirectory = false),
                TerminalFileEntry("LICENSE", isDirectory = false),
            ),
        )
        mockProvider.register(
            "file:///project/src/",
            listOf(
                TerminalFileEntry("main", isDirectory = true),
                TerminalFileEntry("test", isDirectory = true),
                TerminalFileEntry("App.kt", isDirectory = false),
            ),
        )
        mockProvider.register(
            "file:///usr/",
            listOf(
                TerminalFileEntry("local", isDirectory = true),
                TerminalFileEntry("bin", isDirectory = true),
            ),
        )
    }

    @Test
    fun `completes directories and files in current working directory`() =
        runBlocking {
            val request = request("cat R", "file:///project")
            val candidates = source.complete(request)

            assertEquals(1, candidates.size)
            val candidate = candidates.first()
            assertEquals("README.md", candidate.replacementText)
            assertEquals("README.md", candidate.displayText)
            assertEquals(TerminalCompletionCandidateKind.PATH, candidate.kind)
            assertEquals("file", candidate.detail)
            assertEquals(1, candidate.matchedRanges.rangeCount)
            assertEquals(0, candidate.matchedRanges.startOffset(0))
            assertEquals(1, candidate.matchedRanges.endOffset(0))
        }

    @Test
    fun `completes path with camel hump and word boundary match ranges`() =
        runBlocking {
            val request = request("cd IP", "file:///project")
            val candidates = source.complete(request)

            assertEquals(1, candidates.size)
            val candidate = candidates.first()
            assertEquals("Idea\\ Projects/", candidate.replacementText)
            assertEquals("Idea Projects/", candidate.displayText)
            assertEquals(2, candidate.matchedRanges.rangeCount)
            assertEquals(0, candidate.matchedRanges.startOffset(0))
            assertEquals(1, candidate.matchedRanges.endOffset(0))
            assertEquals(5, candidate.matchedRanges.startOffset(1))
            assertEquals(6, candidate.matchedRanges.endOffset(1))
        }

    @Test
    fun `completed command space lists visible directories for directory-changing commands`() =
        runBlocking {
            val request = request("cd ", "file:///project")
            val candidates = source.complete(request)

            assertEquals(listOf("src/", "Idea\\ Projects/", "O\\'Brien/"), candidates.map { it.replacementText })
            assertEquals(listOf("src/", "Idea Projects/", "O'Brien/"), candidates.map { it.displayText })
            assertTrue(candidates.all { it.detail == "directory" })
        }

    @Test
    fun `directory eligibility is applied before the final candidate limit`() =
        runBlocking {
            val entries =
                buildList {
                    repeat(300) { index ->
                        add(TerminalFileEntry("a-file-${index.toString().padStart(3, '0')}", isDirectory = false))
                    }
                    add(TerminalFileEntry("a-target-directory", isDirectory = true))
                }
            val source = PathCompletionSource(TerminalFileSystemProvider { entries })

            val candidates = source.complete(request("cd a", "file:///project"))

            assertEquals(listOf("a-target-directory/"), candidates.map { it.replacementText })
        }

    @Test
    fun `empty path argument prefix hides dot entries until dot is typed`() =
        runBlocking {
            val emptyPrefixCandidates = source.complete(request("cat ", "file:///project"))
            assertTrue(emptyPrefixCandidates.none { it.replacementText.startsWith(".") })

            val dotPrefixCandidates = source.complete(request("cat .", "file:///project"))
            assertEquals(listOf(".hidden/"), dotPrefixCandidates.map { it.replacementText })
        }

    @Test
    fun `path metadata can include hidden entries for an empty prefix`() =
        runBlocking {
            val source =
                PathCompletionSource(
                    fileSystemProvider = mockProvider,
                )

            val candidates = source.complete(request("tool ", "file:///project"))

            assertTrue(candidates.any { it.replacementText == ".hidden/" })
        }

    @Test
    fun `option path metadata can exclude hidden entries after a dot prefix`() =
        runBlocking {
            val source =
                PathCompletionSource(
                    fileSystemProvider = mockProvider,
                )

            assertTrue(source.complete(request("tool --config .", "file:///project")).isEmpty())
        }

    @Test
    fun `suppresses bare path completion for commands without path argument policy`() =
        runBlocking {
            val candidates = source.complete(request("git s", "file:///project"))

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `command spec positional path metadata enables subcommand path completion`() =
        runBlocking {
            val source =
                PathCompletionSource(
                    fileSystemProvider = mockProvider,
                )

            val candidates = source.complete(request("tool open ", "file:///project"))

            assertEquals(
                listOf("src/", "Idea\\ Projects/", "O\\'Brien/", "README.md", "LICENSE"),
                candidates.map { it.replacementText },
            )
        }

    @Test
    fun `option value path metadata enables directory-only path completion`() =
        runBlocking {
            val source =
                PathCompletionSource(
                    fileSystemProvider = mockProvider,
                )

            val candidates = source.complete(request("tool --cwd ", "file:///project"))

            assertEquals(listOf("src/", "Idea\\ Projects/", "O\\'Brien/"), candidates.map { it.replacementText })
        }

    @Test
    fun `attached option path value replaces only the text after the separator`() =
        runBlocking {
            val source =
                PathCompletionSource(
                    fileSystemProvider = mockProvider,
                )
            val commandLine = "tool --cwd=I"

            val candidates = source.complete(request(commandLine, "file:///project"))

            assertEquals(listOf("Idea\\ Projects/"), candidates.map { it.replacementText })
            assertEquals(commandLine.indexOf('=') + 1, candidates.single().replacementStartOffset)
            assertEquals(commandLine.length, candidates.single().replacementEndOffset)
        }

    @Test
    fun `attached quoted option path value preserves the quote style`() =
        runBlocking {
            val source =
                PathCompletionSource(
                    fileSystemProvider = mockProvider,
                )

            val candidates = source.complete(request("tool --cwd=\"Idea Pro", "file:///project"))

            assertEquals("\"Idea Projects/\"", candidates.single().replacementText)
        }

    @Test
    fun `variadic positional path argument uses the final declaration repeatedly`() =
        runBlocking {
            val source =
                PathCompletionSource(
                    fileSystemProvider = mockProvider,
                )

            assertTrue(source.complete(request("tool target src ", "file:///project")).isNotEmpty())
        }

    @Test
    fun `completes nested relative path with forward slashes`() =
        runBlocking {
            val request = request("cat src/m", "file:///project")
            val candidates = source.complete(request)

            assertEquals(1, candidates.size)
            val candidate = candidates.first()
            assertEquals("src/main/", candidate.replacementText)
            assertEquals("main/", candidate.displayText)
            assertEquals("directory", candidate.detail)
        }

    @Test
    fun `quoted path completion preserves double quoted token replacement`() =
        runBlocking {
            val request = request("cd \"Idea Pro", "file:///project")
            val candidates = source.complete(request)

            assertEquals("\"Idea Projects/\"", candidates.single().replacementText)
            assertEquals(3, candidates.single().replacementStartOffset)
            assertEquals(12, candidates.single().replacementEndOffset)
        }

    @Test
    fun `quoted path completion preserves single quoted token replacement`() =
        runBlocking {
            val request = request("cd 'Idea Pro", "file:///project")
            val candidates = source.complete(request)

            assertEquals("'Idea Projects/'", candidates.single().replacementText)
        }

    @Test
    fun `quoted path completion escapes single quote in single quoted token replacement`() =
        runBlocking {
            val request = request("cd 'O", "file:///project")
            val candidates = source.complete(request)

            assertEquals("'O'\\''Brien/'", candidates.single().replacementText)
        }

    @Test
    fun `unquoted path completion preserves existing backslash escaped style`() =
        runBlocking {
            val request = request("cd Idea\\ Pro", "file:///project")
            val candidates = source.complete(request)

            assertEquals("Idea\\ Projects/", candidates.single().replacementText)
        }

    @Test
    fun `powershell path completion quotes unquoted paths with spaces`() =
        runBlocking {
            val request =
                request(
                    commandLine = "cd Idea",
                    workingDirectoryUri = "file:///project",
                    shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                )
            val candidates = source.complete(request)

            assertEquals("'Idea Projects/'", candidates.single().replacementText)
        }

    @Test
    fun `powershell path completion escapes single quote in single quoted token replacement`() =
        runBlocking {
            val request =
                request(
                    commandLine = "cd 'O",
                    workingDirectoryUri = "file:///project",
                    shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                )
            val candidates = source.complete(request)

            assertEquals("'O''Brien/'", candidates.single().replacementText)
        }

    @Test
    fun `completes nested relative path with Windows backslashes`() =
        runBlocking {
            val request = request("cat src\\m", "file:///project", shellCapabilities = TerminalShellCapabilities.POWERSHELL)
            val candidates = source.complete(request)

            assertEquals(1, candidates.size)
            val candidate = candidates.first()
            assertEquals("src\\main\\", candidate.replacementText)
            assertEquals("main\\", candidate.displayText)
        }

    @Test
    fun `completes absolute paths bypassing working directory`() =
        runBlocking {
            val request = request("ls /usr/l", "file:///project")
            val candidates = source.complete(request)

            assertEquals(1, candidates.size)
            assertEquals("/usr/local/", candidates.first().replacementText)
            assertEquals("local/", candidates.first().displayText)
        }

    @Test
    fun `delegates home directory expansion to host and preserves tilde replacement`() =
        runBlocking {
            var captured: TerminalDirectoryListingRequest? = null
            val source =
                PathCompletionSource(
                    TerminalFileSystemProvider { request ->
                        captured = request
                        listOf(TerminalFileEntry("Documents", isDirectory = true))
                    },
                )

            val candidates = source.complete(request("cd ~", "file:///project"))

            assertEquals("~/", captured?.directoryPrefix)
            assertEquals("", captured?.entryNamePrefix)
            assertEquals("~/Documents/", candidates.single().replacementText)
        }

    @Test
    fun `delegates Windows drive path without interpreting drive letter as URI scheme`() =
        runBlocking {
            var captured: TerminalDirectoryListingRequest? = null
            val source =
                PathCompletionSource(
                    TerminalFileSystemProvider { request ->
                        captured = request
                        listOf(TerminalFileEntry("Gagik", isDirectory = true))
                    },
                )

            val candidates =
                source.complete(
                    request(
                        "cd C:\\Users\\Ga",
                        "file:///C:/workspace",
                        shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                    ),
                )

            assertEquals("C:/Users/", captured?.directoryPrefix)
            assertEquals("Ga", captured?.entryNamePrefix)
            assertEquals("C:\\Users\\Gagik\\", candidates.single().replacementText)
        }

    @Test
    fun `delegates UNC path explicitly and preserves working directory authority`() =
        runBlocking {
            var captured: TerminalDirectoryListingRequest? = null
            val source =
                PathCompletionSource(
                    TerminalFileSystemProvider { request ->
                        captured = request
                        listOf(TerminalFileEntry("Docs", isDirectory = true))
                    },
                )

            val candidates =
                source.complete(
                    request(
                        "cd \\\\server\\share\\Do",
                        "file://remote.example/workspace",
                        shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                    ),
                )

            assertEquals("file://remote.example/workspace", captured?.workingDirectoryUri)
            assertEquals("//server/share/", captured?.directoryPrefix)
            assertEquals("Do", captured?.entryNamePrefix)
            assertEquals("\\\\server\\share\\Docs\\", candidates.single().replacementText)
        }

    @Test
    fun `suppresses path completion when prefix is an option flag`() =
        runBlocking {
            val request = request("git -", "file:///project")
            val candidates = source.complete(request)

            assertTrue(candidates.isEmpty())
        }

    @Test
    fun `suppresses path completion in command position unless prefix is path-like`() =
        runBlocking {
            // typing "s" in command position -> should not complete files
            val request = request("s", "file:///project")
            val candidates = source.complete(request)
            assertTrue(candidates.isEmpty())

            // typing "./s" in command position -> explicitly path-like, should complete
            val pathLikeRequest = request("./s", "file:///project")
            val pathLikeCandidates = source.complete(pathLikeRequest)
            assertEquals(1, pathLikeCandidates.size)
            assertEquals("./src/", pathLikeCandidates.first().replacementText)
        }

    @Test
    fun `returns empty list when working directory is missing or invalid`() =
        runBlocking {
            val request = request("cat R", null)
            assertTrue(source.complete(request).isEmpty())

            val invalidRequest = request("cat R", "invalid-uri")
            assertTrue(source.complete(invalidRequest).isEmpty())
        }

    @Test
    fun `propagates file-system provider cancellation`() =
        runBlocking {
            val cancellation = CancellationException("obsolete completion request")
            val cancellingSource =
                PathCompletionSource(
                    TerminalFileSystemProvider { throw cancellation },
                )

            val thrown =
                assertFailsWith<CancellationException> {
                    cancellingSource.complete(request("cat R", "file:///project"))
                }

            assertSame(cancellation, thrown)
        }

    @Test
    fun `propagates operational file-system provider failure`() =
        runBlocking {
            val failure = IOException("directory access failed")
            val failingSource = PathCompletionSource(TerminalFileSystemProvider { throw failure })

            val thrown =
                assertFailsWith<IOException> {
                    failingSource.complete(request("cat R", "file:///project"))
                }

            assertSame(failure, thrown)
        }

    @Test
    fun `conservative shell omits unquoted path replacements that require escaping`() =
        runBlocking {
            val request =
                request(
                    commandLine = "cd Idea",
                    workingDirectoryUri = "file:///project",
                    shellCapabilities = TerminalShellCapabilities.PLAIN,
                )

            assertTrue(source.complete(request).isEmpty())
        }

    private fun request(
        commandLine: String,
        workingDirectoryUri: String?,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.POSIX,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            workingDirectoryUri = workingDirectoryUri,
            shellCapabilities = shellCapabilities,
        )

    private class FakeFileSystemProvider : TerminalFileSystemProvider {
        private val directories = HashMap<String, List<TerminalFileEntry>>()

        fun register(
            uri: String,
            entries: List<TerminalFileEntry>,
        ) {
            directories[
                java.net
                    .URI(uri)
                    .normalize()
                    .path
                    .removeSuffix("/"),
            ] = entries
        }

        override suspend fun listDirectory(request: TerminalDirectoryListingRequest): List<TerminalFileEntry> {
            val base = java.net.URI(request.workingDirectoryUri.removeSuffix("/") + "/")
            val key =
                base
                    .resolve(request.directoryPrefix)
                    .normalize()
                    .path
                    .removeSuffix("/")
            return directories[key].orEmpty()
        }
    }
}

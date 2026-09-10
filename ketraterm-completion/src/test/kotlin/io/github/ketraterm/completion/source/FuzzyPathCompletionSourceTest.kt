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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests context-aware completion from host-owned ready fuzzy path matches. */
class FuzzyPathCompletionSourceTest {
    private val entriesByPrefix =
        mapOf(
            "FzT" to
                listOf(
                    TerminalFuzzyPathEntry("src/main/kotlin/FuzzyTarget.kt", isDirectory = false),
                    TerminalFuzzyPathEntry("src/test/kotlin/FuzzyTargetTest.kt", isDirectory = false),
                ),
            "rs" to
                listOf(
                    TerminalFuzzyPathEntry("src/main/result.txt", isDirectory = false),
                    TerminalFuzzyPathEntry("src/main/resources", isDirectory = true),
                ),
            "Hidden" to listOf(TerminalFuzzyPathEntry("src/main/.generated/Hidden.kt", isDirectory = false)),
            "src/main/.g" to listOf(TerminalFuzzyPathEntry("src/main/.generated/Hidden.kt", isDirectory = false)),
            "NbySib" to listOf(TerminalFuzzyPathEntry("../shared/NearbySibling.kt", isDirectory = false)),
            "NbySec" to listOf(TerminalFuzzyPathEntry("../.private/NearbySecret.kt", isDirectory = false)),
            "../.p" to listOf(TerminalFuzzyPathEntry("../.private/NearbySecret.kt", isDirectory = false)),
            "MyF" to listOf(TerminalFuzzyPathEntry("notes/My File.txt", isDirectory = false)),
        )
    private val source =
        TerminalCompletionSources.fuzzyPath(
            sourceId = "project-file",
            entriesProvider = TerminalFuzzyPathProvider { _, context -> entriesByPrefix[context.activePrefix].orEmpty() },
        )

    @Test
    fun `materializes ready project file matches in provider order`() =
        runBlocking {
            val candidates = source.complete(request("cat FzT"))

            assertEquals(
                listOf("src/main/kotlin/FuzzyTarget.kt", "src/test/kotlin/FuzzyTargetTest.kt"),
                candidates.map(TerminalCompletionCandidate::replacementText),
            )
            assertTrue(candidates.all { it.source == "project-file" && it.kind == TerminalCompletionCandidateKind.PATH })
        }

    @Test
    fun `passes the immutable request and active prefix to a query-aware provider`() =
        runBlocking {
            var requestedDirectory: String? = null
            var requestedPrefix: String? = null
            val queryAwareSource =
                TerminalCompletionSources.fuzzyPath(
                    sourceId = "query-aware-project-file",
                    entriesProvider =
                        TerminalFuzzyPathProvider { request, context ->
                            requestedDirectory = request.workingDirectoryUri
                            requestedPrefix = context.activePrefix
                            listOf(TerminalFuzzyPathEntry("settings.gradle.kts", isDirectory = false))
                        },
                )

            val candidates = queryAwareSource.complete(request("cat sgk"))

            assertEquals("file:///project", requestedDirectory)
            assertEquals("sgk", requestedPrefix)
            assertEquals(listOf("settings.gradle.kts"), candidates.map(TerminalCompletionCandidate::replacementText))
        }

    @Test
    fun `applies directory filtering before the final candidate limit`() =
        runBlocking {
            val queryAwareSource =
                TerminalCompletionSources.fuzzyPath(
                    sourceId = "query-aware-project-file",
                    entriesProvider =
                        TerminalFuzzyPathProvider { _, _ ->
                            buildList {
                                repeat(300) { index -> add(TerminalFuzzyPathEntry("files/match-$index.kt", false)) }
                                add(TerminalFuzzyPathEntry("matching-directory", true))
                            }
                        },
                )

            assertEquals(
                listOf("matching-directory/"),
                queryAwareSource.complete(request("cd match")).map(TerminalCompletionCandidate::replacementText),
            )
        }

    @Test
    fun `filters fuzzy results to directories for cd`() =
        runBlocking {
            val candidates = source.complete(request("cd rs"))

            assertEquals(listOf("src/main/resources/"), candidates.map(TerminalCompletionCandidate::replacementText))
            assertEquals(listOf("project directory"), candidates.map(TerminalCompletionCandidate::detail))
        }

    @Test
    fun `leaves an empty path prefix to direct directory completion`() =
        runBlocking {
            assertTrue(source.complete(request("cd ")).isEmpty())
        }

    @Test
    fun `can opt into an empty prefix for a small context-specific provider`() =
        runBlocking {
            val statusSource =
                TerminalCompletionSources.fuzzyPath(
                    sourceId = "git-status-path",
                    entriesProvider =
                        TerminalFuzzyPathProvider { _, _ ->
                            listOf(TerminalFuzzyPathEntry("src/Changed.kt", isDirectory = false))
                        },
                    requiresNonEmptyPrefix = false,
                    allowedCommandNames = setOf("add", "restore", "rm", "diff"),
                )

            assertEquals(
                listOf("src/Changed.kt"),
                statusSource.complete(request("git add ")).map(TerminalCompletionCandidate::replacementText),
            )
            assertEquals(
                listOf("src/Changed.kt"),
                statusSource.complete(request("git restore ")).map(TerminalCompletionCandidate::replacementText),
            )
            assertEquals(
                listOf("src/Changed.kt"),
                statusSource.complete(request("git rm ")).map(TerminalCompletionCandidate::replacementText),
            )
            assertTrue(statusSource.complete(request("cd ")).isEmpty())
        }

    @Test
    fun `hides nested dot directories until the active path component starts with a dot`() =
        runBlocking {
            assertTrue(source.complete(request("cat Hidden")).isEmpty())

            assertEquals(
                listOf("src/main/.generated/Hidden.kt"),
                source.complete(request("cat src/main/.g")).map(TerminalCompletionCandidate::replacementText),
            )
        }

    @Test
    fun `parent navigation segments are not treated as hidden directories`() =
        runBlocking {
            assertEquals(
                listOf("../shared/NearbySibling.kt"),
                source.complete(request("cat NbySib")).map(TerminalCompletionCandidate::replacementText),
            )
            assertTrue(source.complete(request("cat NbySec")).isEmpty())
            assertEquals(
                listOf("../.private/NearbySecret.kt"),
                source.complete(request("cat ../.p")).map(TerminalCompletionCandidate::replacementText),
            )
        }

    @Test
    fun `quotes fuzzy replacements using the active shell contract`() =
        runBlocking {
            assertEquals(
                listOf("notes/My\\ File.txt"),
                source.complete(request("cat MyF")).map(TerminalCompletionCandidate::replacementText),
            )
            assertTrue(
                source
                    .complete(request("cat MyF", TerminalShellCapabilities.PLAIN))
                    .isEmpty(),
            )
        }

    @Test
    fun `does not provide generic fuzzy paths in non-path command positions`() =
        runBlocking {
            assertTrue(source.complete(request("git sw")).isEmpty())
        }

    private fun request(
        commandLine: String,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.POSIX,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            workingDirectoryUri = "file:///project",
            shellCapabilities = shellCapabilities,
        )
}

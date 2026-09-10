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

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Context, quoting, and replacement tests for host-published value domains. */
class ValueDomainCompletionSourceTest {
    private val source =
        TerminalCompletionSources.valueDomain(
            domain = TerminalCompletionValueDomain.GIT_BRANCH,
            sourceId = "intellij-git",
            valuesProvider = { _, _ ->
                listOf(
                    TerminalCompletionDomainValue("feature/terminal", detail = "local branch"),
                    TerminalCompletionDomainValue("fix/render"),
                )
            },
        )

    /** Verifies domain gating and candidate metadata in a matching command context. */
    @Test
    fun `returns domain values only in matching command context`() =
        runBlocking {
            val candidate = source.complete(request("git switch fe")).single()

            assertEquals("feature/terminal", candidate.replacementText)
            assertEquals(11, candidate.replacementStartOffset)
            assertEquals(13, candidate.replacementEndOffset)
            assertEquals("local branch", candidate.detail)
            assertEquals("intellij-git", candidate.source)
            assertEquals(TerminalCompletionValueDomain.GIT_BRANCH, candidate.valueDomain)
            assertEquals(TerminalCompletionCandidateKind.ARGUMENT, candidate.kind)
            assertTrue(source.complete(request("git status fe")).isEmpty())
        }

    /** Verifies branch-domain activation for merge and rebase commands. */
    @Test
    fun `supports merge and rebase branch contexts`() =
        runBlocking {
            assertEquals("feature/terminal", source.complete(request("git merge fe")).single().replacementText)
            assertEquals("feature/terminal", source.complete(request("git rebase fe")).single().replacementText)
        }

    @Test
    fun `can restrict a domain snapshot to selected command contexts`() =
        runBlocking {
            val remoteSource =
                TerminalCompletionSources.valueDomain(
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    sourceId = "intellij-git-remote-branch",
                    valuesProvider = { _, _ -> listOf(TerminalCompletionDomainValue("origin/feature/terminal")) },
                    allowedCommandNames = setOf("checkout", "merge", "rebase"),
                )

            assertEquals(
                "origin/feature/terminal",
                remoteSource.complete(request("git checkout or")).single().replacementText,
            )
            assertTrue(remoteSource.complete(request("git switch or")).isEmpty())
        }

    /** Verifies that host values pass through shell-specific safe replacement encoding. */
    @Test
    fun `escapes host values through the request shell policy`() =
        runBlocking {
            val specialSource =
                TerminalCompletionSources.valueDomain(
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    sourceId = "git",
                    valuesProvider = { _, _ -> listOf(TerminalCompletionDomainValue("release\$next")) },
                )

            assertEquals("release\\\$next", specialSource.complete(request("git switch rel")).single().replacementText)
            assertTrue(
                specialSource
                    .complete(request("git switch rel", TerminalShellCapabilities.PLAIN))
                    .isEmpty(),
            )
        }

    /** Verifies that exact values are not suggested as no-op replacements. */
    @Test
    fun `does not return an already complete value`() =
        runBlocking {
            assertTrue(source.complete(request("git switch feature/terminal")).isEmpty())
        }

    @Test
    fun `custom display labels receive display-relative match ranges`() =
        runBlocking {
            val customDisplaySource =
                TerminalCompletionSources.valueDomain(
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    sourceId = "git",
                    valuesProvider = { _, _ ->
                        listOf(TerminalCompletionDomainValue(value = "f-branch", displayText = "Feature Branch"))
                    },
                )

            val candidate = customDisplaySource.complete(request("git switch fb")).single()

            assertEquals("Feature Branch", candidate.displayText)
            assertContentEquals(intArrayOf(0, 1, 8, 9), candidate.matchedRanges.copyPackedOffsets())
        }

    @Test
    fun `custom display labels without a display match remain eligible without highlight ranges`() =
        runBlocking {
            val customDisplaySource =
                TerminalCompletionSources.valueDomain(
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    sourceId = "git",
                    valuesProvider = { _, _ ->
                        listOf(TerminalCompletionDomainValue(value = "feature-branch", displayText = "Release candidate"))
                    },
                )

            val candidate = customDisplaySource.complete(request("git switch fb")).single()

            assertEquals("feature-branch", candidate.replacementText)
            assertTrue(candidate.matchedRanges.isEmpty())
        }

    @Test
    fun `passes semantic context without truncating the provider snapshot`() =
        runBlocking {
            var requestedPrefix: String? = null
            val boundedSource =
                TerminalCompletionSources.valueDomain(
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    sourceId = "git",
                    valuesProvider = { _, context ->
                        requestedPrefix = context.activePrefix
                        buildList {
                            repeat(300) { index -> add(TerminalCompletionDomainValue("other-$index")) }
                            add(TerminalCompletionDomainValue("feature/needle"))
                        }
                    },
                )

            val candidates = boundedSource.complete(request("git switch feature/n"))

            assertEquals("feature/n", requestedPrefix)
            assertEquals(listOf("feature/needle"), candidates.map { it.replacementText })
        }

    @Test
    fun `retains a higher-ranked late value within the final candidate bound`() =
        runBlocking {
            val boundedSource =
                TerminalCompletionSources.valueDomain(
                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                    sourceId = "git",
                    valuesProvider = { _, _ ->
                        buildList {
                            repeat(300) { index -> add(TerminalCompletionDomainValue("feature/branch-$index")) }
                            add(TerminalCompletionDomainValue("feature/priority", scoreAdjustment = 1_000))
                        }
                    },
                )

            val candidates = boundedSource.complete(request("git switch feature/"))

            assertEquals(256, candidates.size)
            assertTrue(candidates.any { it.replacementText == "feature/priority" })
        }

    private fun request(
        commandLine: String,
        capabilities: TerminalShellCapabilities = TerminalShellCapabilities.POSIX,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            shellCapabilities = capabilities,
        )
}

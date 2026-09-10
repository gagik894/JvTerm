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
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntellijGitCompletionSourceTest {
    @Test
    fun `checkout loads one composite snapshot and exposes all reference groups`() =
        runBlocking {
            var loads = 0
            var requestedDirectory: String? = null
            val engine =
                engine { workingDirectoryUri ->
                    loads++
                    requestedDirectory = workingDirectoryUri
                    snapshot()
                }

            val candidates = engine.complete(request("git checkout "))

            assertEquals(1, loads)
            assertEquals("file:///repo", requestedDirectory)
            assertEquals(
                setOf("intellij-git-branch", "intellij-git-remote-branch", "intellij-git-tag"),
                candidates.mapTo(HashSet(), TerminalCompletionCandidate::source),
            )
        }

    @Test
    fun `switch exposes local branches only`() =
        runBlocking {
            val candidates = engine { _ -> snapshot() }.complete(request("git switch "))

            assertEquals(listOf("intellij-git-branch"), candidates.map { it.source }.distinct())
            assertEquals(listOf("feature/local"), candidates.map { it.replacementText })
        }

    @Test
    fun `merge and rebase expose every reference group`() =
        runBlocking {
            for (subcommand in listOf("merge", "rebase")) {
                val candidates = engine { _ -> snapshot() }.complete(request("git $subcommand "))

                assertEquals(
                    setOf("intellij-git-branch", "intellij-git-remote-branch", "intellij-git-tag"),
                    candidates.mapTo(HashSet(), TerminalCompletionCandidate::source),
                )
            }
        }

    @Test
    fun `irrelevant context returns empty without loading Git`() =
        runBlocking {
            var loads = 0
            val engine =
                engine { _ ->
                    loads++
                    snapshot()
                }

            val candidates = engine.complete(request("git status "))

            assertTrue(candidates.isEmpty())
            assertEquals(0, loads)
        }

    @Test
    fun `candidate limit remains bounded after combining reference groups`() =
        runBlocking {
            val engine = engine { _ -> snapshot() }

            val candidates = engine.complete(request("git checkout "))

            assertEquals(3, candidates.size)
        }

    private fun engine(loader: suspend (String?) -> IntellijGitCompletionSnapshot): TerminalCompletionEngine =
        TerminalCompletionEngines.fromSources(
            sources =
                listOf(
                    TerminalCompletionSourceEntry(
                        source = intellijGitCompletionSource(loader),
                        priority = TerminalCompletionSourcePrior.GIT_REFERENCE,
                    ),
                ),
        )

    private fun snapshot() =
        IntellijGitCompletionSnapshot(
            localBranches = listOf(TerminalCompletionDomainValue("feature/local", detail = "local branch")),
            remoteBranches = listOf(TerminalCompletionDomainValue("origin/feature", detail = "remote branch")),
            tags = listOf(TerminalCompletionDomainValue("v1.0", detail = "tag")),
        )

    private fun request(command: String) =
        TerminalCompletionRequest(
            commandLine = command,
            cursorOffset = command.length,
            workingDirectoryUri = "file:///repo",
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )
}

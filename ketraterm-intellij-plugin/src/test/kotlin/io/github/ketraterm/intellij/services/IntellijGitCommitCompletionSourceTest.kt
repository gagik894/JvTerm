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

import com.intellij.testFramework.LightVirtualFile
import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntellijGitCommitCompletionSourceTest {
    @Test
    fun `commit candidates display short hashes and insert full hashes`() =
        runBlocking {
            var requestedDirectory: String? = null
            val values =
                listOf(
                    TerminalCompletionDomainValue(
                        value = FIRST_FULL_HASH,
                        displayText = FIRST_SHORT_HASH,
                        detail = "Fix parser FSM transition",
                    ),
                )
            val candidates =
                engine { workingDirectoryUri ->
                    requestedDirectory = workingDirectoryUri
                    values
                }.complete(request("git show a3"))

            assertEquals("file:///repo", requestedDirectory)
            assertEquals(1, candidates.size)
            val candidate = candidates.single()
            assertEquals(FIRST_FULL_HASH, candidate.replacementText)
            assertEquals(FIRST_SHORT_HASH, candidate.displayText)
            assertEquals("Fix parser FSM transition", candidate.detail)
            assertEquals("intellij-git-commit", candidate.source)
            assertEquals(TerminalCompletionCandidateKind.ARGUMENT, candidate.kind)
            assertEquals(TerminalCompletionValueDomain.GIT_COMMIT, candidate.valueDomain)
        }

    @Test
    fun `all commit-valued Git commands load recent commits`() =
        runBlocking {
            var loads = 0
            val engine =
                engine { _ ->
                    loads++
                    listOf(TerminalCompletionDomainValue(FIRST_FULL_HASH, displayText = FIRST_SHORT_HASH))
                }

            for (subcommand in listOf("cherry-pick", "revert", "show")) {
                val candidates = engine.complete(request("git $subcommand "))
                assertTrue(candidates.any { it.source == "intellij-git-commit" })
            }
            assertEquals(3, loads)
        }

    @Test
    fun `non-value positions and show paths do not load Git history`() =
        runBlocking {
            var loads = 0
            val engine =
                engine { _ ->
                    loads++
                    listOf(TerminalCompletionDomainValue(FIRST_FULL_HASH, displayText = FIRST_SHORT_HASH))
                }

            engine.complete(request("git status "))
            engine.complete(request("git show --st"))
            engine.complete(request("git show $FIRST_SHORT_HASH -- "))

            assertEquals(0, loads)
        }

    @Test
    fun `loader preserves history order and sanitizes bounded subjects`() =
        runBlocking {
            var requestedLimit = 0
            val loader =
                IntellijGitCommitCompletionLoader(
                    GitCommitReadPort { _, limit ->
                        requestedLimit = limit
                        listOf(
                            GitCommitReadModel(FIRST_FULL_HASH, FIRST_SHORT_HASH, "  Fix parser\r\ntransition  "),
                            GitCommitReadModel(SECOND_FULL_HASH, SECOND_SHORT_HASH, "x".repeat(300)),
                        )
                    },
                )

            val values = loader.load("file:///repo")

            assertEquals(50, requestedLimit)
            assertEquals(listOf(FIRST_FULL_HASH, SECOND_FULL_HASH), values.map { it.value })
            assertEquals(listOf(FIRST_SHORT_HASH, SECOND_SHORT_HASH), values.map { it.displayText })
            assertEquals("Fix parser transition", values[0].detail)
            assertEquals("x".repeat(256), values[1].detail)
        }

    @Test
    fun `metadata is rejoined in timed commit order`() {
        val commits =
            joinGitCommitMetadata(
                identities =
                    listOf(
                        GitCommitIdentity(FIRST_FULL_HASH, FIRST_SHORT_HASH),
                        GitCommitIdentity(SECOND_FULL_HASH, SECOND_SHORT_HASH),
                    ),
                subjectsByHash =
                    linkedMapOf(
                        SECOND_FULL_HASH to "second",
                        FIRST_FULL_HASH to "first",
                    ),
            )

        assertEquals(listOf(FIRST_FULL_HASH, SECOND_FULL_HASH), commits.map { it.fullHash })
        assertEquals(listOf("first", "second"), commits.map { it.subject })
    }

    @Test
    fun `repository-state cache avoids duplicate history processes and invalidates on ref changes`() =
        runBlocking {
            val root = LightVirtualFile("repo")
            var target = repositoryTarget(root, revision = "head-1", tagVersion = "tags-1")
            var historyLoads = 0
            val port =
                IntellijGitCommitReadPort(
                    repositoryResolver = { target },
                    historyLoader = { _, _, _ ->
                        historyLoads++
                        listOf(GitCommitReadModel(FIRST_FULL_HASH, FIRST_SHORT_HASH, "first"))
                    },
                )

            port.read("file:///repo", 50)
            port.read("file:///repo", 50)
            assertEquals(1, historyLoads)

            target = repositoryTarget(root, revision = "head-2", tagVersion = "tags-1")
            port.read("file:///repo", 50)
            assertEquals(2, historyLoads)

            target = repositoryTarget(root, revision = "head-2", tagVersion = "tags-2")
            port.read("file:///repo", 50)
            assertEquals(3, historyLoads)
        }

    @Test
    fun `concurrent cache misses share one history load`() =
        runBlocking {
            val target = repositoryTarget(LightVirtualFile("repo"), revision = "head", tagVersion = "tags")
            val historyStarted = CompletableDeferred<Unit>()
            val releaseHistory = CompletableDeferred<Unit>()
            var historyLoads = 0
            val port =
                IntellijGitCommitReadPort(
                    repositoryResolver = { target },
                    historyLoader = { _, _, _ ->
                        historyLoads++
                        historyStarted.complete(Unit)
                        releaseHistory.await()
                        listOf(GitCommitReadModel(FIRST_FULL_HASH, FIRST_SHORT_HASH, "first"))
                    },
                )

            val first = async { port.read("file:///repo", 50)?.toList() }
            historyStarted.await()
            val second = async { port.read("file:///repo", 50)?.toList() }
            releaseHistory.complete(Unit)

            assertEquals(first.await(), second.await())
            assertEquals(1, historyLoads)
        }

    @Test
    fun `repository without HEAD still loads commits reachable from other refs`() =
        runBlocking {
            val target =
                repositoryTarget(
                    root = LightVirtualFile("repo"),
                    revision = "unborn",
                    tagVersion = "tags",
                    hasHead = false,
                )
            var receivedHasHead: Boolean? = null
            val port =
                IntellijGitCommitReadPort(
                    repositoryResolver = { target },
                    historyLoader = { _, hasHead, _ ->
                        receivedHasHead = hasHead
                        listOf(GitCommitReadModel(FIRST_FULL_HASH, FIRST_SHORT_HASH, "remote commit"))
                    },
                )

            val commits = port.read("file:///repo", 50)?.toList().orEmpty()

            assertEquals(false, receivedHasHead)
            assertEquals(listOf(FIRST_FULL_HASH), commits.map { it.fullHash })
            assertEquals(
                listOf("--max-count=50", "--branches", "--remotes", "--tags"),
                recentGitCommitLogParameters(limit = 50, hasHead = false).toList(),
            )
            assertEquals(
                listOf("--max-count=50", "--branches", "--remotes", "--tags", "HEAD"),
                recentGitCommitLogParameters(limit = 50, hasHead = true).toList(),
            )
        }

    private fun engine(loader: suspend (String?) -> List<TerminalCompletionDomainValue>): TerminalCompletionEngine =
        TerminalCompletionEngines.fromSources(
            sources =
                listOf(
                    TerminalCompletionSourceEntry(
                        source = intellijGitCommitCompletionSource(loader),
                        priority = TerminalCompletionSourcePrior.GIT_REFERENCE,
                    ),
                ),
        )

    private fun request(command: String) =
        TerminalCompletionRequest(
            commandLine = command,
            cursorOffset = command.length,
            workingDirectoryUri = "file:///repo",
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )

    private fun repositoryTarget(
        root: LightVirtualFile,
        revision: String,
        tagVersion: String,
        hasHead: Boolean = true,
    ): GitCommitRepositoryTarget =
        GitCommitRepositoryTarget(
            root = root,
            state = GitCommitRepositoryState(repositorySnapshot = revision, tagSnapshot = tagVersion),
            hasHead = hasHead,
        )

    private companion object {
        private const val FIRST_SHORT_HASH = "a3f91c2"
        private const val FIRST_FULL_HASH = "a3f91c2000000000000000000000000000000000"
        private const val SECOND_SHORT_HASH = "b4e02d3"
        private const val SECOND_FULL_HASH = "b4e02d3000000000000000000000000000000000"
    }
}

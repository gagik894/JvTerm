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

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Hostile-scale tests that execute the production IntelliJ completion loader classes. */
class IntellijCompletionLoaderBoundsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Git reference loader owns an input budget independent of popup results`() =
        runBlocking {
            val localBranches = CountingStringIterable(valueAt = { index -> "local-$index" })
            val loader =
                IntellijGitCompletionLoader(
                    GitReferenceReadPort { _, collector ->
                        collector(
                            GitReferenceReadModel(
                                currentBranchName = null,
                                localBranchNames = localBranches,
                                remoteBranchNames = emptyList(),
                                tagNames = emptyList(),
                            ),
                        )
                    },
                )

            val snapshot = loader.load("file:///repo")

            assertEquals(8_192, snapshot.localBranches.size)
            assertEquals(8_192, localBranches.nextCalls)
        }

    @Test
    fun `Git reference loader stops discovery when its request is cancelled`() =
        runBlocking {
            lateinit var loading: Deferred<IntellijGitCompletionSnapshot>
            val localBranches =
                CountingStringIterable(
                    valueAt = { index -> "local-$index" },
                    afterNext = { count ->
                        if (count == 4) loading.cancel(CancellationException("obsolete Git completion"))
                    },
                )
            val loader =
                IntellijGitCompletionLoader(
                    GitReferenceReadPort { _, collector ->
                        collector(GitReferenceReadModel(null, localBranches, emptyList(), emptyList()))
                    },
                )
            loading = async(start = CoroutineStart.LAZY) { loader.load("file:///repo") }

            loading.start()
            val failure = runCatching { loading.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(4, localBranches.nextCalls)
        }

    @Test
    fun `Git commit loader owns its recent-history budget`() =
        runBlocking {
            val commits = CountingGitCommitIterable()
            val loader =
                IntellijGitCommitCompletionLoader(
                    GitCommitReadPort { _, limit ->
                        assertEquals(50, limit)
                        commits
                    },
                )

            val values = loader.load("file:///repo")

            assertEquals(50, values.size)
            assertEquals(50, commits.nextCalls)
            assertEquals(commitHash(0), values.first().value)
            assertEquals(commitHash(49), values.last().value)
        }

    @Test
    fun `Git commit loader stops projection when its request is cancelled`() =
        runBlocking {
            lateinit var loading: Deferred<List<io.github.ketraterm.completion.model.TerminalCompletionDomainValue>>
            val commits =
                CountingGitCommitIterable { count ->
                    if (count == 4) loading.cancel(CancellationException("obsolete Git commit completion"))
                }
            val loader = IntellijGitCommitCompletionLoader(GitCommitReadPort { _, _ -> commits })
            loading = async(start = CoroutineStart.LAZY) { loader.load("file:///repo") }

            loading.start()
            val failure = runCatching { loading.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(4, commits.nextCalls)
        }

    @Test
    fun `Git status loader owns one visit budget across both groups`() =
        runBlocking {
            val repositoryRoot = temporaryFolder.newFolder("git-status-loader").toPath()
            val changed =
                CountingStringIterable(
                    size = 3,
                    valueAt = { index -> repositoryRoot.resolve("changed-$index").toString() },
                )
            val unversioned =
                CountingStringIterable(
                    valueAt = { index -> repositoryRoot.resolve("unversioned-$index").toString() },
                )
            val loader =
                IntellijGitStatusPathLoader(
                    GitStatusReadPort { _, collector ->
                        collector(
                            GitStatusReadModel(
                                repositoryRoot = repositoryRoot,
                                workingDirectory = repositoryRoot,
                                changedPathValues = changed,
                                unversionedPathValues = unversioned,
                            ),
                        )
                    },
                )

            val entries = loader.load(repositoryRoot.toUri().toString(), prefix = "")

            assertEquals(8_192, entries.size)
            assertEquals(3, changed.nextCalls)
            assertEquals(8_189, unversioned.nextCalls)
        }

    @Test
    fun `Git status loader matches during discovery without a candidate cap`() =
        runBlocking {
            val repositoryRoot = temporaryFolder.newFolder("git-status-query").toPath()
            val changedPaths =
                buildList {
                    repeat(300) { index -> add(repositoryRoot.resolve("other-$index.kt").toString()) }
                    add(repositoryRoot.resolve("src/NeedleTarget.kt").toString())
                }
            val loader =
                IntellijGitStatusPathLoader(
                    GitStatusReadPort { _, collector ->
                        collector(
                            GitStatusReadModel(
                                repositoryRoot = repositoryRoot,
                                workingDirectory = repositoryRoot,
                                changedPathValues = changedPaths,
                                unversionedPathValues = emptyList(),
                            ),
                        )
                    },
                )

            val entries = loader.load(repositoryRoot.toUri().toString(), prefix = "Needle")

            assertEquals(listOf("src/NeedleTarget.kt"), entries.map { it.path })
        }

    @Test
    fun `Git status loader stops discovery when its request is cancelled`() =
        runBlocking {
            val repositoryRoot = temporaryFolder.newFolder("git-status-cancellation").toPath()
            lateinit var loading: Deferred<List<io.github.ketraterm.completion.api.TerminalFuzzyPathEntry>>
            val changed =
                CountingStringIterable(
                    valueAt = { index -> repositoryRoot.resolve("changed-$index").toString() },
                    afterNext = { count ->
                        if (count == 4) loading.cancel(CancellationException("obsolete Git status completion"))
                    },
                )
            val loader =
                IntellijGitStatusPathLoader(
                    GitStatusReadPort { _, collector ->
                        collector(GitStatusReadModel(repositoryRoot, repositoryRoot, changed, emptyList()))
                    },
                )
            loading = async(start = CoroutineStart.LAZY) { loader.load(repositoryRoot.toUri().toString(), prefix = "") }

            loading.start()
            val failure = runCatching { loading.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(4, changed.nextCalls)
        }

    @Test
    fun `Gradle task loader owns an imported-model task budget`() =
        runBlocking {
            val projectDirectory = temporaryFolder.newFolder("gradle-loader").toPath()
            val tasks = CountingGradleTaskNodes(projectDirectory.toString())
            val root = TestGradleModelNode(moduleId = "root", children = tasks)
            val loader = IntellijGradleTaskLoader(GradleModelReadPort { collector -> collector(listOf(root)) })

            val entries = loader.load(projectDirectory.toUri().toString())

            assertEquals(8_192, entries.size)
            assertEquals(8_192, tasks.nextCalls)
        }

    @Test
    fun `Gradle task loader stops model traversal when its request is cancelled`() =
        runBlocking {
            val projectDirectory = temporaryFolder.newFolder("gradle-loader-cancellation").toPath()
            lateinit var loading: Deferred<List<io.github.ketraterm.completion.api.TerminalGradleTask>>
            val tasks =
                CountingGradleTaskNodes(
                    linkedProjectPath = projectDirectory.toString(),
                    afterNext = { count ->
                        if (count == 4) loading.cancel(CancellationException("obsolete Gradle completion"))
                    },
                )
            val root = TestGradleModelNode(moduleId = "root", children = tasks)
            val loader = IntellijGradleTaskLoader(GradleModelReadPort { collector -> collector(listOf(root)) })
            loading = async(start = CoroutineStart.LAZY) { loader.load(projectDirectory.toUri().toString()) }

            loading.start()
            val failure = runCatching { loading.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(4, tasks.nextCalls)
        }

    private class CountingStringIterable(
        private val size: Int? = null,
        private val valueAt: (Int) -> String,
        private val afterNext: (Int) -> Unit = {},
    ) : Iterable<String> {
        var nextCalls: Int = 0
            private set

        override fun iterator(): Iterator<String> =
            object : Iterator<String> {
                override fun hasNext(): Boolean = size == null || nextCalls < size

                override fun next(): String {
                    check(hasNext())
                    val value = valueAt(nextCalls++)
                    afterNext(nextCalls)
                    return value
                }
            }
    }

    private class CountingGitCommitIterable(
        private val afterNext: (Int) -> Unit = {},
    ) : Iterable<GitCommitReadModel> {
        var nextCalls: Int = 0
            private set

        override fun iterator(): Iterator<GitCommitReadModel> =
            object : Iterator<GitCommitReadModel> {
                override fun hasNext(): Boolean = true

                override fun next(): GitCommitReadModel {
                    val index = nextCalls++
                    afterNext(nextCalls)
                    val hash = commitHash(index)
                    return GitCommitReadModel(
                        fullHash = hash,
                        shortHash = hash.take(7),
                        subject = "commit $index",
                    )
                }
            }
    }

    private class CountingGradleTaskNodes(
        private val linkedProjectPath: String,
        private val afterNext: (Int) -> Unit = {},
    ) : Iterable<GradleModelNode> {
        var nextCalls: Int = 0
            private set

        override fun iterator(): Iterator<GradleModelNode> =
            object : Iterator<GradleModelNode> {
                override fun hasNext(): Boolean = true

                override fun next(): GradleModelNode {
                    val index = nextCalls++
                    afterNext(nextCalls)
                    return TestGradleModelNode(
                        task = GradleTaskReadModel("task-$index", "task $index", linkedProjectPath),
                    )
                }
            }
    }

    private data class TestGradleModelNode(
        override val moduleId: String? = null,
        override val task: GradleTaskReadModel? = null,
        override val children: Iterable<GradleModelNode> = emptyList(),
    ) : GradleModelNode

    private companion object {
        private fun commitHash(index: Int): String = index.toString(16).padStart(40, '0')
    }
}

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

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalFuzzyPathEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path

/** Lazy Git-status view consumed while the owning IntelliJ read action remains active. */
internal data class GitStatusReadModel(
    val repositoryRoot: Path,
    val workingDirectory: Path,
    val changedPathValues: Iterable<String?>,
    val unversionedPathValues: Iterable<String?>,
)

/** Executes bounded Git-status collection inside an implementation-owned read action. */
internal fun interface GitStatusReadPort {
    suspend fun read(
        workingDirectoryUri: String?,
        collector: (GitStatusReadModel) -> List<TerminalFuzzyPathEntry>,
    ): List<TerminalFuzzyPathEntry>?
}

private class IntellijGitStatusReadPort(
    private val project: Project,
) : GitStatusReadPort {
    override suspend fun read(
        workingDirectoryUri: String?,
        collector: (GitStatusReadModel) -> List<TerminalFuzzyPathEntry>,
    ): List<TerminalFuzzyPathEntry>? =
        readIntellijGitRepository(project, workingDirectoryUri) { repository, workingDirectory ->
            val changeListManager = ChangeListManager.getInstance(project)
            collector(
                GitStatusReadModel(
                    repositoryRoot = repository.root.toNioPath(),
                    workingDirectory = workingDirectory,
                    changedPathValues =
                        changeListManager.allChanges
                            .asSequence()
                            .map { change -> (change.afterRevision?.file ?: change.beforeRevision?.file)?.path }
                            .asIterable(),
                    unversionedPathValues =
                        changeListManager.unversionedFilesPaths
                            .asSequence()
                            .map { filePath -> filePath.path }
                            .asIterable(),
                ),
            )
        }
}

/**
 * Loads changed and unversioned paths tracked by IntelliJ for the Git repository containing a terminal directory.
 *
 * The IDE change-list model supplies the paths; no Git command is started. Collection runs under one read action
 * on a completion worker and returns only paths belonging to the selected, deepest containing repository.
 *
 * @property readPort owner of the IDE read action and Git-status model view.
 */
internal class IntellijGitStatusPathLoader(
    private val readPort: GitStatusReadPort,
) {
    constructor(project: Project) : this(IntellijGitStatusReadPort(project))

    /**
     * Loads bounded, relevance-ordered changed paths matching [prefix].
     *
     * Renamed paths use their post-rename path when available; deleted paths use their prior path.
     *
     * @param workingDirectoryUri local `file` URI used to select and relativize a repository.
     * @param prefix decoded active path prefix supplied by the completion context.
     * @return query-matched paths within the independent visit budget, or an
     * empty list for unusable project, URI, or repository state.
     */
    suspend fun load(
        workingDirectoryUri: String?,
        prefix: String,
    ): List<TerminalFuzzyPathEntry> {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        val normalizedPrefix = prefix.replace('\\', '/')
        return readPort.read(workingDirectoryUri) { model ->
            val retained = ArrayList<ScoredGitStatusPath>(INITIAL_RESULT_CAPACITY)
            val retainedPaths = HashSet<String>(INITIAL_RESULT_CAPACITY)
            val visitBudget =
                BoundedVisitBudget(MAX_VISITED_CHANGES) {
                    cancellationContext.ensureActive()
                    ProgressManager.checkCanceled()
                }

            fun retain(
                path: Path,
                isDirectory: Boolean,
                detail: String,
            ) {
                if (!path.startsWith(model.repositoryRoot)) return
                val relativePath = relativePath(model.workingDirectory, path) ?: return
                val score = gitStatusFuzzyScore(relativePath, normalizedPrefix) ?: return
                if (retainedPaths.add(relativePath)) {
                    retained +=
                        ScoredGitStatusPath(
                            entry = TerminalFuzzyPathEntry(relativePath, isDirectory = isDirectory, detail = detail),
                            score = score,
                        )
                }
            }
            visitBudget.visit(model.changedPathValues) { pathValue ->
                pathValue?.let { runCatching { Path.of(it) }.getOrNull() }?.let { path ->
                    retain(path, isDirectory = false, detail = "changed file")
                }
                true
            }
            visitBudget.visit(model.unversionedPathValues) { pathValue ->
                pathValue?.let { runCatching { Path.of(it) }.getOrNull() }?.let { path ->
                    retain(path, isDirectory = false, detail = "untracked file")
                }
                true
            }
            retained.sortWith(GIT_STATUS_PATH_ORDER)
            val entries = ArrayList<TerminalFuzzyPathEntry>(retained.size)
            for (match in retained) entries += match.entry
            entries
        } ?: emptyList()
    }

    private fun relativePath(
        workingDirectory: Path,
        file: Path,
    ): String? = toRelativeCompletionPath(workingDirectory, file).takeIf(String::isNotEmpty)

    private companion object {
        private const val MAX_VISITED_CHANGES = 8_192
        private const val INITIAL_RESULT_CAPACITY = 64
    }
}

/** Creates changed-Git-path completion without exposing IntelliJ VCS APIs to the shared engine. */
internal fun intellijGitStatusPathCompletionSource(loader: suspend (String?, String) -> List<TerminalFuzzyPathEntry>) =
    TerminalCompletionSources.fuzzyPath(
        sourceId = "intellij-git-status-path",
        entriesProvider = { request, context -> loader(request.workingDirectoryUri, context.activePrefix) },
        requiresNonEmptyPrefix = false,
        allowedCommandNames = setOf("add", "restore", "rm", "diff"),
    )

private fun gitStatusFuzzyScore(
    path: String,
    prefix: String,
): Int? {
    val fileNameStart = path.lastIndexOf('/') + 1
    return when {
        path.regionMatches(fileNameStart, prefix, 0, prefix.length, ignoreCase = true) -> 4_000 - path.length
        path.startsWith(prefix, ignoreCase = true) -> 3_000 - path.length
        else -> gitStatusSubsequenceScore(path, prefix, fileNameStart)?.plus(2_000) ?: gitStatusSubsequenceScore(path, prefix)
    }
}

private fun gitStatusSubsequenceScore(
    value: String,
    query: String,
    startIndex: Int = 0,
): Int? {
    var valueIndex = startIndex
    var queryIndex = 0
    var gaps = 0
    var previousMatch = startIndex - 1
    while (valueIndex < value.length && queryIndex < query.length) {
        if (value[valueIndex].equals(query[queryIndex], ignoreCase = true)) {
            gaps += valueIndex - previousMatch - 1
            previousMatch = valueIndex
            queryIndex++
        }
        valueIndex++
    }
    return if (queryIndex == query.length) 1_000 - gaps * 3 - value.length else null
}

private data class ScoredGitStatusPath(
    val entry: TerminalFuzzyPathEntry,
    val score: Int,
)

private val GIT_STATUS_PATH_ORDER =
    compareByDescending<ScoredGitStatusPath> { it.score }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.path }
        .thenBy { it.entry.path }

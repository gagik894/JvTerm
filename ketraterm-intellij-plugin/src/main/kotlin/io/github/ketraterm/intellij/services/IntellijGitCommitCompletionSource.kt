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
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.history.GitHistoryUtils
import io.github.ketraterm.completion.api.TerminalCompletionActivePosition
import io.github.ketraterm.completion.api.TerminalCompletionSource
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One recent Git commit detached from IntelliJ history objects. */
internal data class GitCommitReadModel(
    val fullHash: String,
    val shortHash: String,
    val subject: String,
)

/** Lightweight commit identity captured before metadata is joined by hash. */
internal data class GitCommitIdentity(
    val fullHash: String,
    val shortHash: String,
)

/** Reads a bounded, newest-first commit snapshot for one terminal directory. */
internal fun interface GitCommitReadPort {
    suspend fun read(
        workingDirectoryUri: String?,
        limit: Int,
    ): Iterable<GitCommitReadModel>?
}

/** Selected repository and ref state used to key one recent-commit snapshot. */
internal data class GitCommitRepositoryTarget(
    val root: VirtualFile,
    val state: GitCommitRepositoryState,
    val hasHead: Boolean,
)

/** Opaque equality-stable IntelliJ snapshots covering branch, remote, HEAD, and tag refs. */
internal data class GitCommitRepositoryState(
    val repositorySnapshot: Any,
    val tagSnapshot: Any,
)

/** Caches one successful repository-state snapshot and single-flights cache misses. */
internal class IntellijGitCommitReadPort(
    private val repositoryResolver: suspend (String?) -> GitCommitRepositoryTarget?,
    private val historyLoader: suspend (VirtualFile, Boolean, Int) -> List<GitCommitReadModel>,
) : GitCommitReadPort {
    constructor(project: Project) : this(
        repositoryResolver = { workingDirectoryUri -> resolveGitCommitRepository(project, workingDirectoryUri) },
        historyLoader = { root, hasHead, limit -> loadRecentCommits(project, root, hasHead, limit) },
    )

    private val cacheMutex = Mutex()

    @Volatile
    private var cachedSnapshot: CachedGitCommitSnapshot? = null

    override suspend fun read(
        workingDirectoryUri: String?,
        limit: Int,
    ): Iterable<GitCommitReadModel>? {
        require(limit > 0) { "limit must be > 0, was $limit" }
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        val target = repositoryResolver(workingDirectoryUri) ?: return null
        cancellationContext.ensureActive()
        cachedSnapshot?.takeIf { it.matches(target, limit) }?.let { return it.commits }

        return cacheMutex.withLock {
            cancellationContext.ensureActive()
            cachedSnapshot?.takeIf { it.matches(target, limit) }?.let { return@withLock it.commits }

            val loaded = historyLoader(target.root, target.hasHead, limit)
            cancellationContext.ensureActive()
            val snapshot =
                if (loaded.size <= limit) {
                    loaded.toList()
                } else {
                    loaded.subList(0, limit).toList()
                }
            cachedSnapshot = CachedGitCommitSnapshot(target, limit, snapshot)
            snapshot
        }
    }
}

private data class CachedGitCommitSnapshot(
    val target: GitCommitRepositoryTarget,
    val limit: Int,
    val commits: List<GitCommitReadModel>,
) {
    fun matches(
        requestedTarget: GitCommitRepositoryTarget,
        requestedLimit: Int,
    ): Boolean = limit == requestedLimit && target == requestedTarget
}

private suspend fun resolveGitCommitRepository(
    project: Project,
    workingDirectoryUri: String?,
): GitCommitRepositoryTarget? =
    readIntellijGitRepository(project, workingDirectoryUri) { repository, _ ->
        val repositoryInfo = repository.info
        val tagState = repository.tagsHolder.state.value
        val hasHead = repositoryInfo.currentRevision != null
        val hasOtherRefs =
            repositoryInfo.localBranchesWithHashes.isNotEmpty() ||
                repositoryInfo.remoteBranchesWithHashes.isNotEmpty() ||
                tagState.tagsToCommitHashes.isNotEmpty()
        if (!hasHead && !hasOtherRefs) {
            null
        } else {
            GitCommitRepositoryTarget(
                root = repository.root,
                state =
                    GitCommitRepositoryState(
                        repositorySnapshot = repositoryInfo,
                        tagSnapshot = tagState,
                    ),
                hasHead = hasHead,
            )
        }
    }

private suspend fun loadRecentCommits(
    project: Project,
    root: VirtualFile,
    hasHead: Boolean,
    limit: Int,
): List<GitCommitReadModel> =
    withContext(Dispatchers.IO) {
        coroutineToIndicator { _ ->
            ProgressManager.checkCanceled()
            val logParameters = recentGitCommitLogParameters(limit, hasHead)
            val timedCommits =
                GitHistoryUtils.collectTimedCommits(
                    project,
                    root,
                    *logParameters,
                )
            if (timedCommits.isEmpty()) return@coroutineToIndicator emptyList()

            val identities = ArrayList<GitCommitIdentity>(timedCommits.size)
            val fullHashes = ArrayList<String>(timedCommits.size)
            for (commit in timedCommits) {
                ProgressManager.checkCanceled()
                val fullHash = commit.id.asString()
                identities += GitCommitIdentity(fullHash, commit.id.toShortString())
                fullHashes += fullHash
            }

            ProgressManager.checkCanceled()
            val metadata =
                GitHistoryUtils.collectCommitsMetadata(
                    project,
                    root,
                    *fullHashes.toTypedArray(),
                )
            ProgressManager.checkCanceled()
            val subjectsByHash = HashMap<String, String>(metadata?.size ?: 0)
            metadata?.forEach { commit ->
                ProgressManager.checkCanceled()
                subjectsByHash[commit.id.asString()] = commit.subject
            }
            joinGitCommitMetadata(identities, subjectsByHash)
        }
    }

/** Builds bounded Git log parameters without naming an unavailable `HEAD`. */
internal fun recentGitCommitLogParameters(
    limit: Int,
    hasHead: Boolean,
): Array<String> {
    require(limit > 0) { "limit must be > 0, was $limit" }
    val parameters = ArrayList<String>(if (hasHead) 5 else 4)
    parameters += "--max-count=$limit"
    parameters += "--branches"
    parameters += "--remotes"
    parameters += "--tags"
    if (hasHead) parameters += "HEAD"
    return parameters.toTypedArray()
}

/** Preserves Git's newest-first identity order while attaching independently loaded metadata. */
internal fun joinGitCommitMetadata(
    identities: List<GitCommitIdentity>,
    subjectsByHash: Map<String, String>,
): List<GitCommitReadModel> {
    val commits = ArrayList<GitCommitReadModel>(identities.size)
    for (identity in identities) {
        commits +=
            GitCommitReadModel(
                fullHash = identity.fullHash,
                shortHash = identity.shortHash,
                subject = subjectsByHash[identity.fullHash].orEmpty(),
            )
    }
    return commits
}

/** Converts a bounded IntelliJ Git history snapshot into host-neutral completion values. */
internal class IntellijGitCommitCompletionLoader(
    private val readPort: GitCommitReadPort,
) {
    constructor(project: Project) : this(IntellijGitCommitReadPort(project))

    /**
     * Loads recent commits reachable from HEAD, branches, remotes, or tags.
     *
     * @param workingDirectoryUri local `file` URI used to select the containing repository.
     * @return at most 50 newest commit values, preserving Git history order.
     */
    suspend fun load(workingDirectoryUri: String?): List<TerminalCompletionDomainValue> {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        val commits = readPort.read(workingDirectoryUri, MAX_RECENT_COMMITS) ?: return emptyList()
        val values = ArrayList<TerminalCompletionDomainValue>(MAX_RECENT_COMMITS)
        val visitBudget =
            BoundedVisitBudget(MAX_RECENT_COMMITS) {
                cancellationContext.ensureActive()
                ProgressManager.checkCanceled()
            }
        visitBudget.visit(commits) { commit ->
            val fullHash = commit.fullHash.trim()
            val shortHash = commit.shortHash.trim()
            if (fullHash.isNotEmpty() && shortHash.isNotEmpty()) {
                values +=
                    TerminalCompletionDomainValue(
                        value = fullHash,
                        displayText = shortHash,
                        detail = sanitizeCommitSubject(commit.subject),
                    )
            }
            true
        }
        return values
    }
}

/** Creates recent-commit completion without exposing Git4Idea objects to the shared engine. */
internal fun intellijGitCommitCompletionSource(
    loader: suspend (String?) -> List<TerminalCompletionDomainValue>,
): TerminalCompletionSource =
    TerminalCompletionSource { request, context, limit ->
        val commandName = context.currentCommand?.name
        if (context.expectedValueDomain != TerminalCompletionValueDomain.GIT_COMMIT ||
            (
                context.activePosition != TerminalCompletionActivePosition.POSITIONAL_ARGUMENT &&
                    context.activePosition != TerminalCompletionActivePosition.OPTION_VALUE
            ) ||
            commandName == "show" &&
            context.optionsTerminated
        ) {
            return@TerminalCompletionSource emptyList()
        }
        TerminalCompletionSources.valueDomainCandidates(
            request = request,
            context = context,
            domain = TerminalCompletionValueDomain.GIT_COMMIT,
            sourceId = GIT_COMMIT_SOURCE_ID,
            values = loader(request.workingDirectoryUri),
            limit = limit,
        )
    }

private fun sanitizeCommitSubject(subject: String): String {
    val trimmed = subject.trim()
    if (trimmed.isEmpty()) return ""
    var endOffset = minOf(trimmed.length, MAX_SUBJECT_LENGTH)
    if (endOffset < trimmed.length &&
        endOffset > 0 &&
        trimmed[endOffset - 1].isHighSurrogate() &&
        trimmed[endOffset].isLowSurrogate()
    ) {
        endOffset--
    }
    var requiresNormalization = endOffset != trimmed.length
    var previousWasSpace = false
    for (index in 0 until endOffset) {
        val character = trimmed[index]
        val isSpace = character.isISOControl() || character.isWhitespace()
        if (isSpace && (character != ' ' || previousWasSpace)) requiresNormalization = true
        previousWasSpace = isSpace
    }
    if (!requiresNormalization) return trimmed

    return buildString(endOffset) {
        var pendingSpace = false
        for (index in 0 until endOffset) {
            val character = trimmed[index]
            if (character.isISOControl() || character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }
}

private const val MAX_RECENT_COMMITS = 50
private const val MAX_SUBJECT_LENGTH = 256
private const val GIT_COMMIT_SOURCE_ID = "intellij-git-commit"

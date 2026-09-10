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
import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Immutable Git references captured from one IntelliJ repository read action. */
internal data class IntellijGitCompletionSnapshot(
    val localBranches: List<TerminalCompletionDomainValue>,
    val remoteBranches: List<TerminalCompletionDomainValue>,
    val tags: List<TerminalCompletionDomainValue>,
) {
    companion object {
        val EMPTY = IntellijGitCompletionSnapshot(emptyList(), emptyList(), emptyList())
    }
}

/** Lazy Git-reference view whose iterables are consumed inside the owning IDE read action. */
internal data class GitReferenceReadModel(
    val currentBranchName: String?,
    val localBranchNames: Iterable<String>,
    val remoteBranchNames: Iterable<String>,
    val tagNames: Iterable<String>,
)

/** Executes bounded Git-reference collection inside an implementation-owned read action. */
internal fun interface GitReferenceReadPort {
    suspend fun read(
        workingDirectoryUri: String?,
        collector: (GitReferenceReadModel) -> IntellijGitCompletionSnapshot,
    ): IntellijGitCompletionSnapshot?
}

private class IntellijGitReferenceReadPort(
    private val project: Project,
) : GitReferenceReadPort {
    override suspend fun read(
        workingDirectoryUri: String?,
        collector: (GitReferenceReadModel) -> IntellijGitCompletionSnapshot,
    ): IntellijGitCompletionSnapshot? =
        readIntellijGitRepository(project, workingDirectoryUri) { repository, _ ->
            collector(
                GitReferenceReadModel(
                    currentBranchName = repository.currentBranch?.name,
                    localBranchNames =
                        repository.branches.localBranches
                            .asSequence()
                            .map { it.name }
                            .asIterable(),
                    remoteBranchNames =
                        repository.branches.remoteBranches
                            .asSequence()
                            .map { it.name }
                            .asIterable(),
                    tagNames =
                        repository.tagsHolder.state.value.tagsToCommitHashes.keys
                            .asSequence()
                            .map { it.name }
                            .asIterable(),
                ),
            )
        }
}

/** Reads all Git-reference candidate groups from one selected repository read action. */
internal class IntellijGitCompletionLoader(
    private val readPort: GitReferenceReadPort,
) {
    constructor(project: Project) : this(IntellijGitReferenceReadPort(project))

    suspend fun load(workingDirectoryUri: String?): IntellijGitCompletionSnapshot {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        return readPort.read(workingDirectoryUri) { model ->
            IntellijGitCompletionSnapshot(
                localBranches =
                    collectValues(model.localBranchNames, cancellationContext) { branchName ->
                        if (branchName == model.currentBranchName) {
                            null
                        } else {
                            TerminalCompletionDomainValue(
                                value = branchName,
                                detail = "local branch",
                                scoreAdjustment = LOCAL_SCORE_ADJUSTMENT,
                            )
                        }
                    },
                remoteBranches =
                    collectValues(model.remoteBranchNames, cancellationContext) { branchName ->
                        TerminalCompletionDomainValue(
                            value = branchName,
                            detail = "remote branch",
                            scoreAdjustment = REMOTE_SCORE_ADJUSTMENT,
                        )
                    },
                tags =
                    collectValues(model.tagNames, cancellationContext) { tagName ->
                        TerminalCompletionDomainValue(tagName, detail = "tag")
                    },
            )
        } ?: IntellijGitCompletionSnapshot.EMPTY
    }

    private fun collectValues(
        values: Iterable<String>,
        cancellationContext: CoroutineContext,
        toValue: (String) -> TerminalCompletionDomainValue?,
    ): List<TerminalCompletionDomainValue> {
        val retained = ArrayList<TerminalCompletionDomainValue>(INITIAL_VALUE_CAPACITY)
        val visitBudget =
            BoundedVisitBudget(MAX_VISITED_VALUES_PER_GROUP) {
                cancellationContext.ensureActive()
                ProgressManager.checkCanceled()
            }
        visitBudget.visit(values) { value ->
            toValue(value)?.let(retained::add)
            true
        }
        retained.sortWith(VALUE_ORDER)
        return retained
    }

    private companion object {
        private const val MAX_VISITED_VALUES_PER_GROUP = 8_192
        private const val INITIAL_VALUE_CAPACITY = 64
        private const val LOCAL_SCORE_ADJUSTMENT = 2
        private const val REMOTE_SCORE_ADJUSTMENT = 1
        private val VALUE_ORDER =
            compareBy<TerminalCompletionDomainValue, String>(String.CASE_INSENSITIVE_ORDER) { it.value }
                .thenBy { it.value }
    }
}

/** Creates one completion source backed by one composite Git-reference load per request. */
internal fun intellijGitCompletionSource(loader: suspend (String?) -> IntellijGitCompletionSnapshot): TerminalCompletionSource =
    TerminalCompletionSource { request, context, limit ->
        if (context.expectedValueDomain != TerminalCompletionValueDomain.GIT_BRANCH) {
            return@TerminalCompletionSource emptyList()
        }
        val commandName = context.currentCommand?.name
        val snapshot = loader(request.workingDirectoryUri)
        val candidates = ArrayList<TerminalCompletionCandidate>(limit)
        candidates += projectValues(request, context, LOCAL_SOURCE_ID, snapshot.localBranches, limit)
        if (commandName in REMOTE_REFERENCE_COMMANDS) {
            candidates += projectValues(request, context, REMOTE_SOURCE_ID, snapshot.remoteBranches, limit)
            candidates += projectValues(request, context, TAG_SOURCE_ID, snapshot.tags, limit)
        }
        candidates
            .sortedWith(CANDIDATE_ORDER)
            .take(limit)
    }

private fun projectValues(
    request: TerminalCompletionRequest,
    context: TerminalCompletionContext,
    sourceId: String,
    values: List<TerminalCompletionDomainValue>,
    limit: Int,
): List<TerminalCompletionCandidate> =
    TerminalCompletionSources.valueDomainCandidates(
        request = request,
        context = context,
        domain = TerminalCompletionValueDomain.GIT_BRANCH,
        sourceId = sourceId,
        values = values,
        limit = limit,
    )

private const val LOCAL_SOURCE_ID = "intellij-git-branch"
private const val REMOTE_SOURCE_ID = "intellij-git-remote-branch"
private const val TAG_SOURCE_ID = "intellij-git-tag"
private val REMOTE_REFERENCE_COMMANDS = setOf("checkout", "merge", "rebase")
private val CANDIDATE_ORDER =
    compareByDescending<TerminalCompletionCandidate> { it.score }
        .thenBy { it.displayText }
        .thenBy { it.replacementText }

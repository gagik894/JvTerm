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

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFileManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path

/**
 * Resolves one local terminal directory and reads its containing Git repository.
 *
 * Uses IntelliJ's [GitRepositoryManager.getRepositoryForFileQuick] to resolve the active
 * repository (including submodules, worktrees, and nested roots) under a read action.
 */
internal suspend fun <T> readIntellijGitRepository(
    project: Project,
    workingDirectoryUri: String?,
    reader: (repository: GitRepository, workingDirectory: Path) -> T,
): T? {
    val cancellationContext = currentCoroutineContext()
    cancellationContext.ensureActive()
    if (project.isDisposed) return null
    val workingDirectory =
        TerminalLocalFileUriResolver.resolve(workingDirectoryUri)
            ?: project.guessProjectDir()?.toNioPath()
            ?: return null
    return readAction {
        cancellationContext.ensureActive()
        ProgressManager.checkCanceled()
        if (project.isDisposed) return@readAction null
        val vFile = VirtualFileManager.getInstance().findFileByNioPath(workingDirectory) ?: return@readAction null
        val repository =
            GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(vFile)
                ?: return@readAction null
        reader(repository, workingDirectory)
    }
}

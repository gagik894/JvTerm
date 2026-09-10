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
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import io.github.ketraterm.completion.api.TerminalFileEntry
import io.github.ketraterm.completion.host.TerminalBoundedDirectoryScanner
import io.github.ketraterm.completion.host.TerminalDirectoryEntrySnapshot
import io.github.ketraterm.completion.host.TerminalDirectoryScanner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path

/**
 * Uses IntelliJ's project-aware VFS snapshot for directories in project
 * content, falling back to bounded local scanning outside the project.
 *
 * VFS access and project-content checks run under an IntelliJ read action.
 * Only direct children already represented by the VFS are inspected; this
 * adapter does not refresh the VFS or perform filesystem I/O while in the read
 * action.
 *
 * @property project project whose content index constrains VFS suggestions.
 * @property fallback scanner used for directories outside indexed project content.
 * @property virtualFileResolver resolver for an existing VFS directory snapshot.
 * @property maxVisitedEntries positive cap on inspected VFS children.
 * @throws IllegalArgumentException if [maxVisitedEntries] is not positive.
 */
internal class IntellijProjectDirectoryScanner(
    private val project: Project,
    private val fallback: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(),
    private val virtualFileResolver: (Path) -> VirtualFile? = VirtualFileManager.getInstance()::findFileByNioPath,
    private val maxVisitedEntries: Int = DEFAULT_MAX_VISITED_ENTRIES,
) : TerminalDirectoryScanner {
    @Volatile
    private var cachedSnapshot: VfsDirectorySnapshot? = null

    init {
        require(maxVisitedEntries > 0) { "maxVisitedEntries must be > 0, was $maxVisitedEntries" }
    }

    /**
     * Reads a bounded project-content snapshot or delegates to [fallback].
     *
     * @param directory normalized absolute local directory.
     * @param entryNamePrefix case-insensitive direct-child name prefix.
     * @return deterministically ordered entries; an empty list for a disposed
     * project or an indexed non-directory; otherwise [fallback]'s result when
     * no applicable project-content snapshot exists.
     */
    override suspend fun scan(
        directory: Path,
        entryNamePrefix: String,
    ): List<TerminalFileEntry> {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        if (project.isDisposed) return emptyList()
        val projectEntries =
            readAction {
                cancellationContext.ensureActive()
                ProgressManager.checkCanceled()
                if (project.isDisposed) return@readAction emptyList()
                val virtualDirectory = virtualFileResolver(directory) ?: return@readAction null
                if (!virtualDirectory.isDirectory) return@readAction emptyList()
                val fileIndex = ProjectRootManager.getInstance(project).fileIndex
                if (!fileIndex.isInContent(virtualDirectory)) return@readAction null

                val version =
                    VfsDirectoryVersion(
                        directoryUrl = virtualDirectory.url,
                        modificationStamp = virtualDirectory.modificationStamp,
                        projectRootsModificationCount = ProjectRootManager.getInstance(project).modificationCount,
                    )
                val cached = cachedSnapshot
                if (cached?.version == version) return@readAction cached.entries.matching(entryNamePrefix)
                val entries = ArrayList<TerminalFileEntry>(minOf(virtualDirectory.children.size, maxVisitedEntries))
                val children = virtualDirectory.children
                val limit = minOf(children.size, maxVisitedEntries)
                for (index in 0 until limit) {
                    cancellationContext.ensureActive()
                    ProgressManager.checkCanceled()
                    val child = children[index]
                    if (!fileIndex.isInContent(child)) continue
                    entries += TerminalFileEntry(child.name, child.isDirectory)
                }
                val snapshotEntries = TerminalDirectoryEntrySnapshot(entries)
                val finalVersion =
                    version.copy(
                        modificationStamp = virtualDirectory.modificationStamp,
                        projectRootsModificationCount = ProjectRootManager.getInstance(project).modificationCount,
                    )
                if (children.size <= maxVisitedEntries && finalVersion == version) {
                    cachedSnapshot = VfsDirectorySnapshot(version, snapshotEntries)
                }
                snapshotEntries.matching(entryNamePrefix)
            }
        return projectEntries ?: fallback.scan(directory, entryNamePrefix)
    }

    private companion object {
        private const val DEFAULT_MAX_VISITED_ENTRIES = 8_192
    }

    private data class VfsDirectoryVersion(
        val directoryUrl: String,
        val modificationStamp: Long,
        val projectRootsModificationCount: Long,
    )

    private data class VfsDirectorySnapshot(
        val version: VfsDirectoryVersion,
        val entries: TerminalDirectoryEntrySnapshot,
    )
}

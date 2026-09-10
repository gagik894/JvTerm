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

import com.intellij.ide.util.gotoByName.*
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalFuzzyPathEntry
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path

/**
 * Queries IntelliJ's Go to File engine for bounded fuzzy paths.
 *
 * IntelliJ owns project discovery, fuzzy matching, path qualification, and result
 * ordering. This adapter only converts PSI items into shell-facing paths relative
 * to the terminal working directory. Shared completion still owns hidden-file
 * policy, replacement ranges, and shell quoting.
 *
 * @property project project whose Go to File provider is queried.
 * @property filePathResolver converts native VFS files into local paths.
 */
internal class IntellijProjectFileLoader(
    private val project: Project,
    private val filePathResolver: (VirtualFile) -> Path? = VirtualFile::toNioPathOrNull,
) {
    /**
     * Returns IntelliJ-ranked project paths matching [prefix].
     *
     * @param workingDirectoryUri local `file` URI used to relativize project entries.
     * @param prefix decoded active terminal path token.
     * @return ready project paths within this loader's independent query and
     * visit budgets, or an empty list for disposed projects and unsupported
     * working-directory URIs.
     */
    suspend fun load(
        workingDirectoryUri: String?,
        prefix: String,
    ): List<TerminalFuzzyPathEntry> {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        if (project.isDisposed) return emptyList()
        val normalizedPrefix = prefix.replace('\\', '/')
        if (normalizedPrefix.substringAfterLast('/').isEmpty()) return emptyList()
        val workingDirectory =
            TerminalLocalFileUriResolver.resolve(workingDirectoryUri)
                ?: project.guessProjectDir()?.toNioPath()
                ?: project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
                ?: return emptyList()
        return readAction {
            ProgressManager.checkCanceled()
            if (project.isDisposed) return@readAction emptyList()
            val model = GotoFileModel(project)
            try {
                val itemProvider = model.getItemProvider(null)
                if (itemProvider !is ChooseByNameInScopeItemProvider) return@readAction emptyList()
                val viewModel = IntellijProjectFileSearchViewModel(project, model, MAX_QUERY_RESULTS)
                val initialCapacity = minOf(INITIAL_RESULT_CAPACITY, MAX_QUERY_RESULTS)
                val results = ArrayList<TerminalFuzzyPathEntry>(initialCapacity)
                val paths = HashSet<String>(initialCapacity)
                var visited = 0
                val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
                val parameters = FindSymbolParameters.wrap(normalizedPrefix, GlobalSearchScope.projectScope(project))
                itemProvider.filterElementsWithWeights(
                    viewModel,
                    parameters,
                    indicator,
                    Processor { descriptor ->
                        cancellationContext.ensureActive()
                        ProgressManager.checkCanceled()
                        if (visited == MAX_VISITED_ENTRIES) return@Processor false
                        visited++
                        val file = (descriptor.item as? PsiFileSystemItem)?.virtualFile ?: return@Processor true
                        val filePath = filePathResolver(file) ?: return@Processor true
                        val path = relativePath(workingDirectory, filePath) ?: return@Processor true
                        if (paths.add(path)) {
                            results.add(TerminalFuzzyPathEntry(path, file.isDirectory))
                        }
                        results.size < MAX_QUERY_RESULTS
                    },
                )
                results
            } finally {
                Disposer.dispose(model)
            }
        }
    }

    private fun relativePath(
        workingDirectory: Path,
        file: Path,
    ): String? = toRelativeCompletionPath(workingDirectory, file).takeIf(String::isNotEmpty)

    private companion object {
        private const val INITIAL_RESULT_CAPACITY = 64
        private const val MAX_QUERY_RESULTS = 8_192
        private const val MAX_VISITED_ENTRIES = 8_192
    }
}

/** Minimal public-API equivalent of Search Everywhere's package-private view-model adapter. */
private class IntellijProjectFileSearchViewModel(
    private val project: Project,
    private val model: ChooseByNameModel,
    private val limit: Int,
) : ChooseByNameViewModel {
    override fun getProject(): Project = project

    override fun getModel(): ChooseByNameModel = model

    override fun isSearchInAnyPlace(): Boolean = model.useMiddleMatching()

    override fun transformPattern(pattern: String): String = ChooseByNamePopup.getTransformedPattern(pattern, model)

    override fun canShowListForEmptyPattern(): Boolean = false

    override fun getMaximumListSizeLimit(): Int = limit
}

/** Creates query-aware IntelliJ project paths without exposing VFS APIs to the shared engine. */
internal fun intellijProjectFileCompletionSource(loader: suspend (String?, String) -> List<TerminalFuzzyPathEntry>) =
    TerminalCompletionSources.fuzzyPath(
        sourceId = "intellij-project-file",
        entriesProvider = { request, context ->
            loader(request.workingDirectoryUri, context.activePrefix)
        },
    )

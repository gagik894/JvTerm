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
package io.github.ketraterm.intellij.ui

import com.intellij.execution.filters.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.search.GlobalSearchScope
import io.github.ketraterm.ui.swing.api.*
import java.util.concurrent.CancellationException

/**
 * IntelliJ-backed detector for visible terminal text hyperlinks.
 *
 * The detector adapts IntelliJ console filters to KetraTerm's primitive
 * viewport hyperlink overlay. Filter execution happens under read access on
 * the reusable terminal's background analysis worker; only compact actions are
 * handed back to Swing for later explicit activation.
 */
internal class IntellijTerminalHyperlinkDetector(
    private val project: Project,
) : SwingHyperlinkDetector {
    // Console filters can carry exception/navigation context between consecutive lines.
    override val context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.VIEWPORT

    override fun detect(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
    ) {
        if (project.isDisposed) return
        try {
            ApplicationManager.getApplication().runReadAction {
                if (project.isDisposed) return@runReadAction
                val urlFilter = UrlFilter(project)
                val providerFilter = CompositeFilter(project, providerFilters())
                val emittedRanges = HashSet<DetectedRangeKey>()
                var lineIndex = 0
                while (lineIndex < request.lineCount) {
                    ProgressManager.checkCanceled()
                    applyFilter(request, sink, urlFilter, providerFilter, emittedRanges, lineIndex)
                    lineIndex++
                }
            }
        } catch (
            @Suppress("IncorrectCancellationExceptionHandling") exception: ProcessCanceledException,
        ) {
            // Platform cancellation must not become a cached "no links" result in the reusable worker.
            throw CancellationException("IntelliJ hyperlink detection cancelled").apply { initCause(exception) }
        }
    }

    private fun applyFilter(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
        urlFilter: UrlFilter,
        providerFilter: CompositeFilter,
        emittedRanges: MutableSet<DetectedRangeKey>,
        lineIndex: Int,
    ) {
        val lineText = request.lineText(lineIndex)
        val lineStartOffset = request.lineStartOffset(lineIndex)
        val lineEndOffset = request.lineEndOffset(lineIndex)
        // UrlFilter precedes provider filters and returns EXIT for every match.
        // Keep its provenance so only its text-derived actions can outlive edits elsewhere.
        var urlResult: Filter.Result? = null
        val result =
            try {
                urlResult = urlFilter.applyFilter(lineText, lineEndOffset)
                urlResult ?: providerFilter.applyFilter(lineText, lineEndOffset)
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
        if (result == null) return

        for (item in result.resultItems) {
            val hyperlinkInfo = item.hyperlinkInfo ?: continue
            val startOffset = item.highlightStartOffset.coerceIn(lineStartOffset, lineEndOffset)
            val endOffset = item.highlightEndOffset.coerceIn(startOffset, lineEndOffset)
            if (startOffset >= endOffset) continue

            val rangeKey = DetectedRangeKey(lineIndex, startOffset, endOffset)
            if (!emittedRanges.add(rangeKey)) continue

            val validationRange =
                if (urlResult != null) {
                    urlValidationRange(lineText, startOffset - lineStartOffset, endOffset - lineStartOffset)
                } else {
                    null
                }
            sink.addHyperlink(
                lineIndex = lineIndex,
                startOffset = startOffset - lineStartOffset,
                endOffset = endOffset - lineStartOffset,
                action = IntellijTerminalHyperlinkAction(project, hyperlinkInfo),
                validationStartOffset = validationRange?.startOffset ?: 0,
                validationEndOffset = validationRange?.endOffset ?: Int.MAX_VALUE,
            )
        }
    }

    private fun providerFilters(): List<Filter> {
        val filters = ArrayList<Filter>()

        val scope = GlobalSearchScope.allScope(project)
        val providers =
            try {
                ConsoleFilterProvider.FILTER_PROVIDERS.getExtensionList(ApplicationManager.getApplication())
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        for (provider in providers) {
            val providerFilters =
                try {
                    if (provider is ConsoleFilterProviderEx) {
                        provider.getDefaultFilters(project, scope)
                    } else {
                        provider.getDefaultFilters(project)
                    }
                } catch (exception: ProcessCanceledException) {
                    throw exception
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Throwable) {
                    Filter.EMPTY_ARRAY
                }
            for (filter in providerFilters) {
                if (filter.javaClass == UrlFilter::class.java) continue
                filters += filter
            }
        }
        return filters
    }

    private data class DetectedRangeKey(
        val lineIndex: Int,
        val startOffset: Int,
        val endOffset: Int,
    )
}

/** Includes the full token and its delimiters because punctuation can extend a URL match later. */
internal fun urlValidationRange(
    lineText: String,
    startOffset: Int,
    endOffset: Int,
): TextRange {
    var start = startOffset
    while (start > 0 && !isUrlTokenDelimiter(lineText[start - 1])) start--
    if (start > 0) start--
    var end = endOffset
    while (end < lineText.length && !isUrlTokenDelimiter(lineText[end])) end++
    if (end < lineText.length) end++
    return TextRange(start, end)
}

// URLUtil's URL and file patterns stop at ASCII regex whitespace, not all Unicode whitespace.
private fun isUrlTokenDelimiter(character: Char): Boolean = character == ' ' || character in '\t'..'\r'

private class IntellijTerminalHyperlinkAction(
    private val project: Project,
    private val hyperlinkInfo: HyperlinkInfo,
) : SwingHyperlinkAction {
    override fun open(): Boolean {
        return !project.isDisposed && try {
            hyperlinkInfo.navigate(project)
            true
        } catch (_: Exception) {
            false
        }
    }
}

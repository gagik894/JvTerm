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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.internal.BoundedCompletionCandidateCollector
import io.github.ketraterm.completion.matching.CompletionMatcher

/**
 * Autocomplete source for directory contents and file paths.
 *
 * Path completion is conservative for bare argument positions so command and
 * subcommand suggestions are not drowned out by every file in the current
 * directory. Known path-taking commands may request file, directory, or
 * directory-only completion from an empty argument prefix; otherwise callers
 * must type an explicitly path-like prefix such as `./`, `/`, `~`, or a token
 * containing a path separator.
 */
internal class PathCompletionSource(
    private val fileSystemProvider: TerminalFileSystemProvider,
) : TerminalCompletionSource {
    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        require(limit > 0) { "limit must be > 0, was $limit" }
        val workingDir = request.workingDirectoryUri ?: return emptyList()
        val prefix = context.activePrefix
        if (!allowsPathCompletion(context.activePosition, context.expectedPathKind, prefix)) {
            return emptyList()
        }

        val normalizedPrefix = prefix.replace('\\', '/')
        val pathParts = splitPathPrefix(normalizedPrefix) ?: return emptyList()
        val directoryPortion = pathParts.directoryPrefix
        val filePrefix = pathParts.entryNamePrefix
        val entries =
            fileSystemProvider.listDirectory(
                TerminalDirectoryListingRequest(
                    workingDirectoryUri = workingDir,
                    directoryPrefix = directoryPortion,
                    entryNamePrefix = filePrefix,
                ),
            )

        val pathSeparator = if (prefix.contains('\\')) '\\' else '/'
        val candidates = BoundedCompletionCandidateCollector(limit)
        var orderIndex = 0

        for ((name, isDirectory) in entries) {
            if (!context.expectedPathKind.acceptsPathEntry(isDirectory)) continue
            if (!context.expectedHiddenPathPolicy.acceptsPath(name, filePrefix)) continue
            val match = CompletionMatcher.match(name, filePrefix) ?: continue
            if (!match.matchedRanges.isEmpty() && match.matchedRanges.startOffset(0) != 0) continue
            val rawSuffix = if (isDirectory) "$pathSeparator" else ""
            val rawReplacement = directoryPortion + name + rawSuffix
            val normalizedReplacement = if (pathSeparator == '\\') rawReplacement.replace('/', '\\') else rawReplacement
            if (!ShellReplacementText.canEncode(
                    normalizedReplacement,
                    context.activeTokenQuote,
                    request.shellCapabilities.quoting,
                )
            ) {
                continue
            }
            val candidateScore = match.sourceScore(PATH_BASE_SCORE, filePrefix, orderIndex++)
            if (!candidates.shouldMaterialize(candidateScore)) continue
            val replacementText =
                ShellReplacementText.encode(
                    value = normalizedReplacement,
                    activeTokenQuote = context.activeTokenQuote,
                    policy = request.shellCapabilities.quoting,
                ) ?: continue

            candidates.offer(
                TerminalCompletionCandidate(
                    replacementText = replacementText,
                    replacementStartOffset = context.replacementStartOffset,
                    replacementEndOffset = context.replacementEndOffset,
                    displayText = name + rawSuffix,
                    detail = if (isDirectory) "directory" else "file",
                    source = SOURCE_PATH,
                    kind = TerminalCompletionCandidateKind.PATH,
                    score = candidateScore,
                    matchedRanges = match.matchedRanges,
                ),
            )
        }

        return candidates.finish()
    }

    private fun splitPathPrefix(prefix: String): PathParts? {
        if (prefix == "~") return PathParts(directoryPrefix = "~/", entryNamePrefix = "")
        if (prefix.startsWith("~") && !prefix.startsWith("~/")) return null
        val lastSlashIndex = prefix.lastIndexOf('/')
        return if (lastSlashIndex >= 0) {
            PathParts(
                directoryPrefix = prefix.substring(0, lastSlashIndex + 1),
                entryNamePrefix = prefix.substring(lastSlashIndex + 1),
            )
        } else {
            PathParts(directoryPrefix = "", entryNamePrefix = prefix)
        }
    }

    private companion object {
        private const val SOURCE_PATH = "path"
        private const val PATH_BASE_SCORE = 200
    }

    private data class PathParts(
        val directoryPrefix: String,
        val entryNamePrefix: String,
    )
}

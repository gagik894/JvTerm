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
import io.github.ketraterm.completion.internal.GradleCompletionSyntax
import io.github.ketraterm.completion.matching.CompletionMatcher

/**
 * Gradle-task source backed by a bounded suspending host loader.
 *
 * This source has no Gradle or filesystem dependency. It recognises the
 * declared `gradle` command (including its wrapper aliases), full `:project:task`
 * paths, and task names scoped by a preceding `-p` or `--project-dir` option.
 * It retains no loaded task state.
 */
internal class GradleTaskCompletionSource(
    private val sourceId: String,
    private val tasksProvider:
        suspend (TerminalCompletionRequest, TerminalCompletionContext) -> List<TerminalGradleTask>,
) : TerminalCompletionSource {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
    }

    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        require(limit > 0) { "limit must be > 0, was $limit" }
        if (context.command?.name != GradleCompletionSyntax.COMMAND_NAME ||
            context.activePosition != TerminalCompletionActivePosition.SUBCOMMAND
        ) {
            return emptyList()
        }

        val prefix = context.activePrefix
        val projectDirectory = projectDirectoryBeforeActiveToken(context)
        val tasks = tasksProvider(request, context)
        if (tasks.isEmpty()) return emptyList()

        val candidates = BoundedCompletionCandidateCollector(limit)
        val emitted = HashSet<String>()
        for ((index, task) in tasks.withIndex()) {
            val replacement = replacementFor(task, prefix, projectDirectory) ?: continue
            if (prefix.isNotEmpty() && replacement.equals(prefix, ignoreCase = true)) continue
            val match = CompletionMatcher.match(replacement, prefix) ?: continue
            if (!emitted.add(replacement)) continue
            if (!ShellReplacementText.canEncode(replacement, context.activeTokenQuote, request.shellCapabilities.quoting)) {
                continue
            }
            val candidateScore =
                match.sourceScore(
                    baseScore =
                        BASE_SCORE +
                            (if (task.isRootProjectTask) ROOT_TASK_BONUS else 0) +
                            (if (prefix.isEmpty()) EMPTY_PREFIX_BONUS else 0),
                    query = prefix,
                    orderIndex = index,
                )
            if (!candidates.shouldMaterialize(candidateScore)) continue
            val encodedReplacement =
                ShellReplacementText.encode(
                    value = replacement,
                    activeTokenQuote = context.activeTokenQuote,
                    policy = request.shellCapabilities.quoting,
                ) ?: continue
            candidates.offer(
                TerminalCompletionCandidate(
                    replacementText = encodedReplacement,
                    replacementStartOffset = context.replacementStartOffset,
                    replacementEndOffset = context.replacementEndOffset,
                    displayText = if (replacement == task.path) task.path else replacement,
                    detail = task.description.ifBlank { "Gradle task ${task.path}" },
                    source = sourceId,
                    kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    score = candidateScore,
                    matchedRanges = match.matchedRanges,
                ),
            )
        }
        return candidates.finish()
    }

    private fun replacementFor(
        task: TerminalGradleTask,
        prefix: String,
        projectDirectory: String?,
    ): String? =
        when {
            projectDirectory != null ->
                task.takeIf { normalizeProjectDirectory(it.projectDirectory) == projectDirectory }?.shortName

            prefix.startsWith(':') -> task.path
            task.isRootProjectTask -> task.shortName
            else -> null
        }

    private fun projectDirectoryBeforeActiveToken(context: TerminalCompletionContext): String? {
        var selectedDirectory: String? = null
        val tokens = context.commandLineContext.tokens
        var index = context.commandTokenIndex + 1
        while (index < context.commandLineContext.activeTokenIndex) {
            val token = tokens[index].text
            when {
                token in GradleCompletionSyntax.PROJECT_DIRECTORY_OPTION_NAMES -> {
                    val value = tokens.getOrNull(++index)?.text ?: return selectedDirectory
                    selectedDirectory = normalizeProjectDirectory(value)
                }

                token.startsWith("${GradleCompletionSyntax.PROJECT_DIRECTORY_OPTION_LONG}=") -> {
                    selectedDirectory = normalizeProjectDirectory(token.substringAfter('='))
                }
            }
            index++
        }
        return selectedDirectory
    }

    private fun normalizeProjectDirectory(value: String?): String? {
        var normalized = value?.replace('\\', '/') ?: return null
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        if (normalized.length > 1) normalized = normalized.removeSuffix("/")
        return normalized.ifBlank { "." }
    }

    private val TerminalGradleTask.shortName: String get() = path.substringAfterLast(':')

    private val TerminalGradleTask.isRootProjectTask: Boolean get() = path.indexOf(':', startIndex = 1) < 0

    private companion object {
        private const val BASE_SCORE = 300
        private const val ROOT_TASK_BONUS = 20
        private const val EMPTY_PREFIX_BONUS = 40
    }
}

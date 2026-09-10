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
package io.github.ketraterm.completion.spec

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.matching.CompletionMatcher
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec

internal class SpecCompletionSource(
    specs: List<TerminalCommandSpec>,
) : TerminalCompletionSource {
    private val commandSpecs = specs

    init {
        require(specs.none { it.name.isBlank() }) { "specs must not contain blank command names" }
    }

    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        if (commandSpecs.isEmpty()) return emptyList()
        val candidates =
            when (context.activePosition) {
                TerminalCompletionActivePosition.OPERATOR -> emptyList()
                TerminalCompletionActivePosition.COMMAND -> completeCommands(context.commandLineContext)
                TerminalCompletionActivePosition.OPTION_NAME -> completeOptions(context)
                TerminalCompletionActivePosition.OPTION_VALUE -> completeOptionValues(context)
                TerminalCompletionActivePosition.SUBCOMMAND -> completeSubcommands(context)
                TerminalCompletionActivePosition.POSITIONAL_ARGUMENT -> completePositionalValues(context)
            }
        return candidates
            .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
            .take(limit)
    }

    private fun completeCommands(context: TerminalCommandLineContext): List<TerminalCompletionCandidate> {
        val prefix = context.activePrefix
        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0
        for (i in commandSpecs.indices) {
            val spec = commandSpecs[i]
            if (prefix.isNotEmpty() && spec.name.equals(prefix, ignoreCase = true)) continue
            val match = CompletionMatcher.match(spec.name, prefix) ?: continue
            candidates +=
                candidate(
                    replacementText = spec.name,
                    displayText = spec.name,
                    detail = spec.description,
                    kind = TerminalCompletionCandidateKind.COMMAND,
                    context = context,
                    score = match.sourceScore(COMMAND_BASE_SCORE, prefix, orderIndex++),
                    matchedRanges = match.matchedRanges,
                )
        }
        return candidates
    }

    private fun completeSubcommands(context: TerminalCompletionContext): List<TerminalCompletionCandidate> {
        val subcommands = context.subcommandCandidateSource?.subcommands ?: return emptyList()
        val prefix = context.activePrefix
        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0
        for (i in subcommands.indices) {
            val spec = subcommands[i]
            if (context.isAlreadyUsedRepeatableSubcommand(spec)) continue
            if (prefix.isNotEmpty() && spec.name.equals(prefix, ignoreCase = true)) continue
            val match = CompletionMatcher.match(spec.name, prefix) ?: continue
            candidates +=
                candidate(
                    replacementText = spec.name,
                    displayText = spec.name,
                    detail = spec.description,
                    kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    context = context.commandLineContext,
                    score = match.sourceScore(SUBCOMMAND_BASE_SCORE, prefix, orderIndex++),
                    matchedRanges = match.matchedRanges,
                )
        }
        return candidates
    }

    private fun TerminalCompletionContext.isAlreadyUsedRepeatableSubcommand(spec: TerminalCommandSpec): Boolean {
        val source = subcommandCandidateSource ?: return false
        return source.repeatableSubcommands &&
            commandPath.dropWhile { it != source }.drop(1).any { command ->
                command.name.equals(spec.name, ignoreCase = true)
            }
    }

    private fun completeOptions(context: TerminalCompletionContext): List<TerminalCompletionCandidate> {
        val options = ArrayList<TerminalOptionSpec>()
        for (command in context.commandPath) {
            options += command.options
        }

        val prefix = context.activePrefix
        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0
        for (option in options) {
            if (option.exclusiveGroupIds.any(context.usedOptionExclusiveGroupIds::contains)) continue
            for (name in option.names) {
                if (prefix.isNotEmpty() && name.equals(prefix, ignoreCase = true)) continue
                val match = CompletionMatcher.match(name, prefix) ?: continue
                candidates +=
                    candidate(
                        replacementText = name,
                        displayText = name,
                        detail = option.description,
                        kind = TerminalCompletionCandidateKind.OPTION,
                        context = context.commandLineContext,
                        score = match.sourceScore(OPTION_BASE_SCORE, prefix, orderIndex++),
                        matchedRanges = match.matchedRanges,
                    )
            }
        }
        return candidates
    }

    private fun completeOptionValues(context: TerminalCompletionContext): List<TerminalCompletionCandidate> {
        val values = context.staticValueCandidates
        if (values.isEmpty()) return emptyList()
        val prefix = context.activePrefix
        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0
        for (i in values.indices) {
            val value = values[i]
            if (prefix.isNotEmpty() && value.equals(prefix, ignoreCase = true)) continue
            val match = CompletionMatcher.match(value, prefix) ?: continue
            candidates +=
                candidate(
                    replacementText = value,
                    displayText = value,
                    detail = context.activeOption?.description.orEmpty(),
                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                    context = context,
                    score = match.sourceScore(OPTION_VALUE_BASE_SCORE, prefix, orderIndex++),
                    matchedRanges = match.matchedRanges,
                )
        }
        return candidates
    }

    private fun completePositionalValues(context: TerminalCompletionContext): List<TerminalCompletionCandidate> {
        val values = context.staticValueCandidates
        if (values.isEmpty()) return emptyList()
        val prefix = context.activePrefix
        val candidates = ArrayList<TerminalCompletionCandidate>()
        var orderIndex = 0
        for (i in values.indices) {
            val value = values[i]
            if (prefix.isNotEmpty() && value.equals(prefix, ignoreCase = true)) continue
            val match = CompletionMatcher.match(value, prefix) ?: continue
            candidates +=
                candidate(
                    replacementText = value,
                    displayText = value,
                    detail = context.activePositionalArgument?.description.orEmpty(),
                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                    context = context,
                    score = match.sourceScore(OPTION_VALUE_BASE_SCORE, prefix, orderIndex++),
                    matchedRanges = match.matchedRanges,
                )
        }
        return candidates
    }

    private fun candidate(
        replacementText: String,
        displayText: String,
        detail: String,
        kind: TerminalCompletionCandidateKind,
        context: TerminalCompletionContext,
        score: Int,
        matchedRanges: TerminalCompletionMatchRanges = TerminalCompletionMatchRanges.EMPTY,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacementText,
            replacementStartOffset = context.replacementStartOffset,
            replacementEndOffset = context.replacementEndOffset,
            displayText = displayText,
            detail = detail,
            source = SOURCE_SPEC,
            kind = kind,
            score = score,
            matchedRanges = matchedRanges,
        )

    private fun candidate(
        replacementText: String,
        displayText: String,
        detail: String,
        kind: TerminalCompletionCandidateKind,
        context: TerminalCommandLineContext,
        score: Int,
        matchedRanges: TerminalCompletionMatchRanges = TerminalCompletionMatchRanges.EMPTY,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacementText,
            replacementStartOffset = context.replacementStartOffset,
            replacementEndOffset = context.replacementEndOffset,
            displayText = displayText,
            detail = detail,
            source = SOURCE_SPEC,
            kind = kind,
            score = score,
            matchedRanges = matchedRanges,
        )

    private companion object {
        private const val SOURCE_SPEC = "spec"
        private const val COMMAND_BASE_SCORE = 300
        private const val SUBCOMMAND_BASE_SCORE = 250
        private const val OPTION_BASE_SCORE = 220
        private const val OPTION_VALUE_BASE_SCORE = 210
    }
}

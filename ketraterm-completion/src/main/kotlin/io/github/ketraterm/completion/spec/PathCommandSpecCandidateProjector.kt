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
import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken
import io.github.ketraterm.completion.matching.CompletionMatcher
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.source.ShellReplacementText

/** Promotes existing path-backed command aliases to their declared command semantics. */
internal class PathCommandSpecCandidateProjector(
    commandSpecs: List<TerminalCommandSpec>,
) {
    private val resolvedCommandIndex = buildResolvedCommandIndex(commandSpecs)
    private val encodedCommandsByContext = buildEncodedCommandIndexes(resolvedCommandIndex.commandsInResolutionOrder)

    fun project(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        candidates: List<TerminalCompletionCandidate>,
    ): List<TerminalCompletionCandidate> {
        if (context.activePosition != TerminalCompletionActivePosition.COMMAND || candidates.isEmpty()) {
            return candidates
        }

        var projected: ArrayList<TerminalCompletionCandidate>? = null
        for (index in candidates.indices) {
            val candidate = candidates[index]
            val replacement = projectCandidate(request, context, candidate)
            if (replacement !== candidate && projected == null) {
                projected = ArrayList(candidates.size)
                projected.addAll(candidates.subList(0, index))
            }
            projected?.add(replacement)
        }
        return projected ?: candidates
    }

    private fun projectCandidate(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        candidate: TerminalCompletionCandidate,
    ): TerminalCompletionCandidate {
        if (candidate.kind != TerminalCompletionCandidateKind.PATH) return candidate
        if (candidate.replacementStartOffset != context.replacementStartOffset ||
            candidate.replacementEndOffset != context.replacementEndOffset
        ) {
            return candidate
        }
        val resolution =
            resolveCandidate(candidate.replacementText, context.activeTokenQuote, request.shellCapabilities.quoting)
                ?: return candidate
        val resolvedCommand = resolution.command
        if (!resolvedCommand.isAlias) return candidate

        val displayText = resolution.displayText
        val matchedRanges =
            CompletionMatcher.match(displayText, context.activePrefix)?.matchedRanges ?: TerminalCompletionMatchRanges.EMPTY
        return candidate.copy(
            source = SOURCE_SPEC,
            kind = TerminalCompletionCandidateKind.COMMAND,
            displayText = displayText,
            detail = resolvedCommand.spec.description,
            matchedRanges = matchedRanges,
        )
    }

    private fun resolveCandidate(
        replacementText: String,
        activeTokenQuote: Char,
        quotingPolicy: TerminalShellQuotingPolicy,
    ): CandidateResolution? {
        val normalizedReplacement = normalizeTerminalCommandToken(replacementText)
        resolvedCommandIndex.commandsByToken[normalizedReplacement]?.let {
            return CandidateResolution(it, replacementText)
        }
        val encodedIndex = encodedCommandsByContext[EncodingContext(activeTokenQuote, quotingPolicy)]
        val command = encodedIndex?.get(normalizedReplacement) ?: return null
        return CandidateResolution(command, command.token)
    }

    private fun buildResolvedCommandIndex(commandSpecs: List<TerminalCommandSpec>): ResolvedCommandIndex {
        val index = HashMap<String, ResolvedCommand>()
        val commandsInResolutionOrder = ArrayList<ResolvedCommand>()
        for (spec in commandSpecs) {
            addResolvedCommand(index, commandsInResolutionOrder, ResolvedCommand(spec.name, spec, isAlias = false))
            for (alias in spec.aliases) {
                addResolvedCommand(index, commandsInResolutionOrder, ResolvedCommand(alias, spec, isAlias = true))
            }
        }
        return ResolvedCommandIndex(index, commandsInResolutionOrder)
    }

    private fun addResolvedCommand(
        index: MutableMap<String, ResolvedCommand>,
        commandsInResolutionOrder: MutableList<ResolvedCommand>,
        command: ResolvedCommand,
    ) {
        if (index.putIfAbsent(normalizeTerminalCommandToken(command.token), command) == null) {
            commandsInResolutionOrder += command
        }
    }

    private fun buildEncodedCommandIndexes(
        commandsInResolutionOrder: List<ResolvedCommand>,
    ): Map<EncodingContext, Map<String, ResolvedCommand>> {
        val indexes = HashMap<EncodingContext, Map<String, ResolvedCommand>>()
        for (policy in TerminalShellQuotingPolicy.entries) {
            for (quote in ACTIVE_TOKEN_QUOTES) {
                val commandsByEncodedToken = HashMap<String, ResolvedCommand>()
                for (command in commandsInResolutionOrder) {
                    val encoded = ShellReplacementText.encode(command.token, quote, policy) ?: continue
                    commandsByEncodedToken.putIfAbsent(normalizeTerminalCommandToken(encoded), command)
                }
                indexes[EncodingContext(quote, policy)] = commandsByEncodedToken
            }
        }
        return indexes
    }

    private data class ResolvedCommand(
        val token: String,
        val spec: TerminalCommandSpec,
        val isAlias: Boolean,
    )

    private data class ResolvedCommandIndex(
        val commandsByToken: Map<String, ResolvedCommand>,
        val commandsInResolutionOrder: List<ResolvedCommand>,
    )

    private data class CandidateResolution(
        val command: ResolvedCommand,
        val displayText: String,
    )

    private data class EncodingContext(
        val activeTokenQuote: Char,
        val quotingPolicy: TerminalShellQuotingPolicy,
    )

    private companion object {
        private const val SOURCE_SPEC = "spec"
        private const val NO_QUOTE = '\u0000'
        private val ACTIVE_TOKEN_QUOTES = charArrayOf(NO_QUOTE, '\'', '"')
    }
}

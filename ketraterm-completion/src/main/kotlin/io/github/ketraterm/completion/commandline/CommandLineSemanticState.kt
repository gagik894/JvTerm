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
package io.github.ketraterm.completion.commandline

import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import io.github.ketraterm.completion.spec.findNextCommandSpec
import io.github.ketraterm.completion.spec.findOptionSpec

/** Semantic state produced by one pass over tokens following a known command. */
internal class CommandLineSemanticState(
    val commandPath: List<TerminalCommandSpec>,
    val positionalArgumentCount: Int,
    val usedOptionExclusiveGroupIds: Set<String>,
    val optionsTerminated: Boolean,
    val pendingOptionValue: TerminalOptionSpec?,
)

/** Analyzes a known command path once, retaining the state needed by completion. */
internal fun analyzeCommandTokens(
    tokens: List<TerminalCommandLineToken>,
    startIndex: Int,
    endIndexExclusive: Int,
    rootSpec: TerminalCommandSpec,
): CommandLineSemanticState {
    val commandPath = ArrayList<TerminalCommandSpec>(TERMINAL_COMMAND_LIST_CAPACITY)
    var usedExclusiveGroupIds: LinkedHashSet<String>? = null
    var pendingOptionValue: TerminalOptionSpec? = null
    var positionalArgumentCount = 0
    var acceptingSubcommands = true
    var optionsTerminated = false
    commandPath += rootSpec

    var tokenIndex = startIndex
    val safeEnd = minOf(endIndexExclusive, tokens.size)
    while (tokenIndex < safeEnd) {
        val token = tokens[tokenIndex].text
        val normalized = normalizeTerminalCommandToken(token)
        if (normalized.isBlank()) {
            tokenIndex++
            continue
        }

        when {
            pendingOptionValue != null -> pendingOptionValue = null

            normalized == TERMINAL_COMMAND_OPTION_TERMINATOR -> {
                acceptingSubcommands = false
                optionsTerminated = true
            }

            !optionsTerminated && normalized.isTerminalOptionToken() -> {
                val option = findOptionSpec(commandPath, token)
                if (option != null) {
                    if (option.exclusiveGroupIds.isNotEmpty()) {
                        if (usedExclusiveGroupIds == null) {
                            usedExclusiveGroupIds = LinkedHashSet(option.exclusiveGroupIds.size)
                        }
                        usedExclusiveGroupIds.addAll(option.exclusiveGroupIds)
                    }
                    if (option.requiresValue && !token.hasAttachedOptionValue()) {
                        pendingOptionValue = option
                    }
                }
            }

            acceptingSubcommands -> {
                val next = findNextCommandSpec(commandPath, normalized)
                if (next != null) {
                    commandPath += next
                } else {
                    positionalArgumentCount++
                    acceptingSubcommands = false
                }
            }

            else -> positionalArgumentCount++
        }
        tokenIndex++
    }

    return CommandLineSemanticState(
        commandPath = commandPath,
        positionalArgumentCount = positionalArgumentCount,
        usedOptionExclusiveGroupIds = usedExclusiveGroupIds ?: emptySet(),
        optionsTerminated = optionsTerminated,
        pendingOptionValue = pendingOptionValue,
    )
}

private fun String.hasAttachedOptionValue(): Boolean = indexOf(OPTION_VALUE_SEPARATOR) > 1

private const val OPTION_VALUE_SEPARATOR = '='

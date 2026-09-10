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
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.commandEndOffset
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/** Projects a learned full command into the request's active completion range. */
internal fun projectLearnedCommandCandidate(
    request: TerminalCompletionRequest,
    requestLine: TerminalCommandLineContext,
    completionContext: TerminalCompletionContext,
    learnedCommand: String,
    source: String,
    score: Int,
    detailPrefix: String,
    learnedLine: TerminalCommandLineContext,
): TerminalCompletionCandidate? {
    val activeIndex = requestLine.activeTokenIndex
    val learnedToken = learnedLine.tokens.getOrNull(activeIndex) ?: return null
    if (!matchingPrefixTokens(requestLine, learnedLine, activeIndex)) return null
    if (!learnedToken.text.startsWith(requestLine.activePrefix, ignoreCase = true)) return null

    val replacementStartInLearned = replacementStartInLearnedCommand(requestLine, completionContext, learnedToken.startOffset)
    if (replacementStartInLearned !in learnedToken.startOffset..learnedLine.commandEndOffset) return null
    val preserveFollowingText = requestLine.hasFollowingCommandText(request.commandLine)
    val learnedReplacementEnd = if (preserveFollowingText) learnedToken.endOffset else learnedLine.commandEndOffset
    val replacementText = learnedCommand.substring(replacementStartInLearned, learnedReplacementEnd)
    if (replacementText.isBlank()) return null
    val replacementEnd =
        if (preserveFollowingText) {
            completionContext.replacementEndOffset
        } else {
            maxOf(requestLine.commandEndOffset, completionContext.replacementEndOffset)
        }
    if (request.commandLine.replaceRange(completionContext.replacementStartOffset, replacementEnd, replacementText) ==
        request.commandLine
    ) {
        return null
    }

    val kind = semanticKind(completionContext) ?: return null
    return TerminalCompletionCandidate(
        replacementText = replacementText,
        replacementStartOffset = completionContext.replacementStartOffset,
        replacementEndOffset = replacementEnd,
        displayText = replacementText,
        detail = learnedCandidateDetail(detailPrefix, kind, completionContext.expectedPathKind),
        source = source,
        kind = kind,
        score = score,
        valueDomain = completionContext.expectedValueDomain,
    )
}

private fun TerminalCommandLineContext.hasFollowingCommandText(commandLine: String): Boolean {
    val activeToken = tokens.getOrNull(activeTokenIndex) ?: return false
    return activeToken.endOffset < commandEndOffset && commandLine.substring(activeToken.endOffset, commandEndOffset).isNotBlank()
}

private fun matchingPrefixTokens(
    requestLine: TerminalCommandLineContext,
    learnedLine: TerminalCommandLineContext,
    activeIndex: Int,
): Boolean {
    if (activeIndex > learnedLine.tokens.size || activeIndex > requestLine.tokens.size) return false
    for (index in 0 until activeIndex) {
        if (!requestLine.tokens[index].text.equals(learnedLine.tokens[index].text, ignoreCase = true)) return false
    }
    return true
}

private fun replacementStartInLearnedCommand(
    requestLine: TerminalCommandLineContext,
    completionContext: TerminalCompletionContext,
    learnedTokenStart: Int,
): Int {
    val requestTokenStart = requestLine.tokens.getOrNull(requestLine.activeTokenIndex)?.startOffset ?: requestLine.cursorOffset
    val offsetWithinToken = (completionContext.replacementStartOffset - requestTokenStart).coerceAtLeast(0)
    return learnedTokenStart + offsetWithinToken
}

private fun semanticKind(context: TerminalCompletionContext): TerminalCompletionCandidateKind? =
    when (context.activePosition) {
        TerminalCompletionActivePosition.COMMAND -> TerminalCompletionCandidateKind.COMMAND
        TerminalCompletionActivePosition.SUBCOMMAND -> TerminalCompletionCandidateKind.SUBCOMMAND
        TerminalCompletionActivePosition.OPTION_NAME -> TerminalCompletionCandidateKind.OPTION
        TerminalCompletionActivePosition.OPTION_VALUE,
        TerminalCompletionActivePosition.POSITIONAL_ARGUMENT,
        ->
            if (context.expectedPathKind == TerminalPathArgumentKind.NONE) {
                TerminalCompletionCandidateKind.ARGUMENT
            } else {
                TerminalCompletionCandidateKind.PATH
            }
        TerminalCompletionActivePosition.OPERATOR -> null
    }

private fun learnedCandidateDetail(
    prefix: String,
    kind: TerminalCompletionCandidateKind,
    pathKind: TerminalPathArgumentKind,
): String =
    when {
        kind != TerminalCompletionCandidateKind.PATH -> "$prefix command"
        pathKind == TerminalPathArgumentKind.DIRECTORY -> "$prefix directory"
        else -> "$prefix path"
    }

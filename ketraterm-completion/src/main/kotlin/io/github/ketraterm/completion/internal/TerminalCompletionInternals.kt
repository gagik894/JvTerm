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
package io.github.ketraterm.completion.internal

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionRequest

internal val TERMINAL_COMPLETION_CANDIDATE_ORDER: Comparator<TerminalCompletionCandidate> =
    compareByDescending<TerminalCompletionCandidate> { it.score }
        .thenBy { it.displayText }
        .thenBy { it.replacementText }

internal fun isRecordableTerminalCompletionCommand(commandLine: String): Boolean =
    commandLine.isNotBlank() &&
        !commandLine.hasTerminalCompletionLineBreak() &&
        commandLine.hasWellFormedTerminalCompletionUtf16()

internal fun String.hasTerminalCompletionLineBreak(): Boolean = indexOf('\n') >= 0 || indexOf('\r') >= 0

private fun String.hasWellFormedTerminalCompletionUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index++
            }
            Character.isLowSurrogate(character) -> return false
        }
        index++
    }
    return true
}

/** Returns whether command text is structurally safe and bounded for retained plaintext replay. */
internal fun isStructurallyValidTerminalCompletionReplay(commandLine: String): Boolean {
    if (!isRecordableTerminalCompletionCommand(commandLine)) return false
    if (commandLine.length > MAX_REPLAY_UTF16_CHARS) return false

    var utf8Bytes = 0
    var index = 0
    while (index < commandLine.length) {
        val character = commandLine[index]
        if (Character.isISOControl(character) && character != '\t') return false
        utf8Bytes +=
            when {
                character.code <= 0x7F -> 1
                character.code <= 0x7FF -> 2
                Character.isHighSurrogate(character) -> {
                    index++
                    4
                }
                else -> 3
            }
        if (utf8Bytes > MAX_REPLAY_UTF8_BYTES) return false
        index++
    }
    return true
}

/**
 * Returns whether [offset] is a valid UTF-16 scalar boundary in this string.
 */
internal fun String.isTerminalCompletionUtf16Boundary(offset: Int): Boolean {
    if (offset !in 0..length) return false
    val afterHighSurrogate = offset > 0 && Character.isHighSurrogate(this[offset - 1])
    val beforeLowSurrogate = offset < length && Character.isLowSurrogate(this[offset])
    return !afterHighSurrogate && !beforeLowSurrogate
}

/**
 * Projects [candidate] onto this request command line when its replacement
 * range is contained in the command line and does not split a surrogate pair.
 */
internal fun TerminalCompletionRequest.commandLineAfterCandidate(candidate: TerminalCompletionCandidate): String? {
    if (!candidate.hasValidReplacementRangeFor(this)) return null
    return commandLine.replaceRange(candidate.replacementStartOffset, candidate.replacementEndOffset, candidate.replacementText)
}

/**
 * Returns whether [candidate] can be applied to this request's active cursor
 * range without crossing command-line or UTF-16 scalar boundaries.
 */
internal fun TerminalCompletionCandidate.hasValidReplacementRangeFor(request: TerminalCompletionRequest): Boolean {
    val startOffset = replacementStartOffset
    val endOffset = replacementEndOffset
    if (startOffset > request.cursorOffset) return false
    if (request.cursorOffset > endOffset) return false
    if (endOffset > request.commandLine.length) return false
    if (!request.commandLine.isTerminalCompletionUtf16Boundary(startOffset)) return false
    if (!request.commandLine.isTerminalCompletionUtf16Boundary(endOffset)) return false
    return true
}

internal fun saturatedCompletionCounterIncrement(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

internal fun canonicalizeWorkingDirectoryUri(uri: String): String {
    val trimmed = uri.trim()
    return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
}

private const val MAX_REPLAY_UTF16_CHARS = 4_096
private const val MAX_REPLAY_UTF8_BYTES = 8_192

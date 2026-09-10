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
package io.github.ketraterm.completion.matching

import io.github.ketraterm.completion.api.TerminalCompletionMatchRanges

/** Allocation-minimal immutable result of one completion match. */
internal class CompletionMatchResult(
    val score: Int,
    val matchedRanges: TerminalCompletionMatchRanges,
) {
    /** Maps matcher relevance into a source-local score with stable direct-prefix scoring. */
    fun sourceScore(
        baseScore: Int,
        query: String,
        orderIndex: Int,
    ): Int =
        if (query.isEmpty()) {
            baseScore - orderIndex
        } else {
            baseScore + score - DIRECT_PREFIX_SCORE_BASE - orderIndex
        }
}

/**
 * Fast, allocation-minimal matcher for prefix, CamelHump, and word-boundary completion.
 */
internal object CompletionMatcher {
    /** Returns a scored match and display-relative ranges, or `null` when unmatched. */
    fun match(
        target: String,
        query: String,
    ): CompletionMatchResult? {
        if (query.isEmpty()) {
            return CompletionMatchResult(score = 0, matchedRanges = TerminalCompletionMatchRanges.EMPTY)
        }
        if (target.isEmpty() || query.length > target.length) {
            return null
        }

        if (target.startsWith(query, ignoreCase = true)) {
            val caseBonus = if (target.startsWith(query)) 40 else 20
            val exactBonus = if (target.length == query.length) 50 else 0
            val score = DIRECT_PREFIX_SCORE_BASE + caseBonus + exactBonus - (target.length - query.length)
            return CompletionMatchResult(
                score = score,
                matchedRanges =
                    TerminalCompletionMatchRanges.fromOwnedPackedOffsets(
                        target,
                        intArrayOf(0, query.length),
                    ),
            )
        }

        return matchWordBoundaries(target, query)
    }

    private fun matchWordBoundaries(
        target: String,
        query: String,
    ): CompletionMatchResult? {
        val targetLength = target.length
        val queryLength = query.length
        val offsets = IntArray(queryLength shl 1)
        var rangeCount = 0
        var targetIndex = 0
        var queryIndex = 0
        var score = WORD_BOUNDARY_SCORE_BASE - targetLength

        while (targetIndex < targetLength && queryIndex < queryLength) {
            val queryCharacter = query[queryIndex]
            val targetCharacter = target[targetIndex]
            if (isWordBoundary(target, targetIndex) && targetCharacter.equals(queryCharacter, ignoreCase = true)) {
                val start = targetIndex
                var end = targetIndex + 1
                queryIndex++
                targetIndex++

                while (targetIndex < targetLength && queryIndex < queryLength) {
                    val nextQueryCharacter = query[queryIndex]
                    val nextTargetCharacter = target[targetIndex]
                    if (nextQueryCharacter.isUpperCase() && !nextTargetCharacter.isUpperCase()) break
                    if (isSeparator(nextQueryCharacter) && !isSeparator(nextTargetCharacter)) break

                    if (nextTargetCharacter.equals(nextQueryCharacter, ignoreCase = true)) {
                        end++
                        queryIndex++
                        targetIndex++
                        score += CONTIGUOUS_MATCH_BONUS
                    } else if (isSeparator(nextQueryCharacter) && isSeparator(nextTargetCharacter)) {
                        end++
                        queryIndex++
                        targetIndex++
                    } else {
                        break
                    }
                }

                offsets[rangeCount shl 1] = start
                offsets[(rangeCount shl 1) + 1] = end
                rangeCount++
            } else if (isSeparator(queryCharacter) && isSeparator(targetCharacter)) {
                offsets[rangeCount shl 1] = targetIndex
                offsets[(rangeCount shl 1) + 1] = targetIndex + 1
                rangeCount++
                queryIndex++
                targetIndex++
            } else {
                targetIndex++
            }
        }

        if (queryIndex != queryLength) return null
        val mergedCount = mergeConsecutiveRangesInPlace(offsets, rangeCount)
        val packedSize = mergedCount shl 1
        val packedOffsets = if (packedSize == offsets.size) offsets else offsets.copyOf(packedSize)
        return CompletionMatchResult(
            score = score,
            matchedRanges = TerminalCompletionMatchRanges.fromOwnedPackedOffsets(target, packedOffsets),
        )
    }

    private fun mergeConsecutiveRangesInPlace(
        offsets: IntArray,
        count: Int,
    ): Int {
        var readIndex = 0
        var writeIndex = 0
        while (readIndex < count) {
            val start = offsets[readIndex shl 1]
            var end = offsets[(readIndex shl 1) + 1]
            while (readIndex + 1 < count && offsets[(readIndex + 1) shl 1] == end) {
                readIndex++
                end = offsets[(readIndex shl 1) + 1]
            }
            offsets[writeIndex shl 1] = start
            offsets[(writeIndex shl 1) + 1] = end
            writeIndex++
            readIndex++
        }
        return writeIndex
    }

    private fun isWordBoundary(
        text: String,
        index: Int,
    ): Boolean {
        if (index == 0) return true
        val current = text[index]
        val previous = text[index - 1]
        return (isSeparator(previous) && !isSeparator(current)) ||
            (previous.isLowerCase() && current.isUpperCase()) ||
            (previous.isLetter() && current.isDigit())
    }

    private fun isSeparator(character: Char): Boolean =
        character == '-' ||
            character == '_' ||
            character == '.' ||
            character == '/' ||
            character == '\\' ||
            character == ' ' ||
            character == ':'
}

private const val DIRECT_PREFIX_SCORE_BASE = 500
private const val WORD_BOUNDARY_SCORE_BASE = 300
private const val CONTIGUOUS_MATCH_BONUS = 15

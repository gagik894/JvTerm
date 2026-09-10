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
package io.github.ketraterm.completion.engine

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.commandLineAfterCandidate
import io.github.ketraterm.completion.internal.isTerminalCompletionUtf16Boundary
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CompletionOutcomeProjectionPropertyTest {
    @Test
    fun `valid scalar replacement ranges project exactly`() =
        runBlocking {
            val random = Random(PROJECTION_SEED)

            repeat(PROJECTION_CASE_COUNT) { caseIndex ->
                val commandLine = randomCommandLine(random)
                val boundaries = (0..commandLine.length).filter(commandLine::isTerminalCompletionUtf16Boundary)
                val startIndex = random.nextInt(boundaries.size)
                val endIndex = random.nextInt(startIndex, boundaries.size)
                val startOffset = boundaries[startIndex]
                val endOffset = boundaries[endIndex]
                val cursorOffset = boundaries[random.nextInt(startIndex, endIndex + 1)]
                val replacement = REPLACEMENTS[random.nextInt(REPLACEMENTS.size)]
                val request = request(commandLine, cursorOffset = cursorOffset)
                val candidate = candidate(replacement, startOffset, endOffset, "projection")

                val projected = request.commandLineAfterCandidate(candidate)

                assertEquals(
                    commandLine.substring(0, startOffset) + replacement + commandLine.substring(endOffset),
                    projected,
                    "seed=$PROJECTION_SEED case=$caseIndex range=$startOffset..$endOffset line=${commandLine.debug()}",
                )
            }
        }

    @Test
    fun `hostile replacement ranges are rejected before publication`(): Unit =
        runBlocking {
            val commandLine = "git 😀 status"
            val request = request(commandLine)
            val splitSurrogateOffset = commandLine.indexOf("😀") + 1
            val invalidRanges =
                buildList {
                    add(splitSurrogateOffset to splitSurrogateOffset)
                    add(0 to splitSurrogateOffset)
                    repeat(INVALID_RANGE_CASE_COUNT) { index ->
                        val startOffset = commandLine.length + 1 + index
                        add(startOffset to startOffset + (index % 3))
                    }
                }

            for ((caseIndex, range) in invalidRanges.withIndex()) {
                val first = candidate("status", range.first, range.second, "first")
                val second = first.copy(source = "second")
                val distinct =
                    first.copy(
                        replacementStartOffset = range.first + 1,
                        replacementEndOffset = range.second + 1,
                        source = "distinct",
                    )

                assertNull(
                    request.commandLineAfterCandidate(first),
                    "Invalid projection accepted for case=$caseIndex range=$range",
                )
                val completionEngine = engine(listOf(first), listOf(second), listOf(distinct))
                val firstResult = completionEngine.complete(request)

                assertEquals(0, firstResult.size, "Invalid range was published for case=$caseIndex range=$range")
                assertEquals(
                    firstResult,
                    completionEngine.complete(request),
                    "Invalid-range rejection is not deterministic for case=$caseIndex",
                )
            }

            assertFailsWith<IllegalArgumentException> { candidate("x", -1, 0, "negative") }
            assertFailsWith<IllegalArgumentException> { candidate("x", 2, 1, "reversed") }
        }

    @Test
    fun `quoted representations of generated equivalent outcomes fuse deterministically`() =
        runBlocking {
            val random = Random(FUSION_SEED)

            for (shellSyntax in listOf(TerminalShellSyntax.POSIX, TerminalShellSyntax.POWERSHELL)) {
                repeat(FUSION_CASE_COUNT) { caseIndex ->
                    val value = VALUES[random.nextInt(VALUES.size)] + "-$caseIndex"
                    val wholeQuote = if (caseIndex % 2 == 0) "'$value'" else "\"$value\""
                    val tokenQuote = if (caseIndex % 2 == 0) "\"$value\"" else "'$value'"
                    val commandLine = "git sw"
                    val request = request(commandLine, shellSyntax)
                    val completionEngine =
                        engine(
                            listOf(
                                candidate(
                                    "git switch $wholeQuote",
                                    0,
                                    commandLine.length,
                                    "history",
                                    TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            listOf(
                                candidate(
                                    "switch $tokenQuote",
                                    4,
                                    commandLine.length,
                                    "spec",
                                    TerminalCompletionCandidateKind.SUBCOMMAND,
                                ),
                            ),
                        )

                    val candidates = completionEngine.complete(request)

                    assertEquals(
                        1,
                        candidates.size,
                        "Equivalent outcome did not fuse for syntax=$shellSyntax case=$caseIndex",
                    )
                    assertEquals(
                        candidates,
                        completionEngine.complete(request),
                        "Fusion is not deterministic for syntax=$shellSyntax case=$caseIndex",
                    )
                    val projected = requireNotNull(request.commandLineAfterCandidate(candidates.single()))
                    val tokens =
                        TerminalCommandLineTokenizer
                            .parse(projected, projected.length, shellSyntax)
                            .tokens
                            .map { it.text }
                    assertEquals(
                        listOf("git", "switch", value),
                        tokens,
                        "Representative changed semantics for syntax=$shellSyntax case=$caseIndex",
                    )
                }
            }
        }

    @Test
    fun `generated distinct arguments case variants and option order never fuse`() =
        runBlocking {
            repeat(DISTINCT_CASE_COUNT) { caseIndex ->
                val branchRequest = request("git sw", TerminalShellSyntax.POSIX)
                val branchEngine =
                    engine(
                        listOf(
                            candidate("switch 'Feature-$caseIndex'", 4, 6, "upper"),
                            candidate("switch 'feature-$caseIndex'", 4, 6, "lower"),
                        ),
                    )
                val optionRequest = request("t", TerminalShellSyntax.POSIX)
                val optionEngine =
                    engine(
                        listOf(
                            candidate("tool --alpha --beta value-$caseIndex", 0, 1, "alpha-first"),
                            candidate("tool --beta --alpha value-$caseIndex", 0, 1, "beta-first"),
                        ),
                    )

                assertEquals(
                    2,
                    branchEngine.complete(branchRequest).size,
                    "Case-sensitive arguments fused for case=$caseIndex",
                )
                assertEquals(2, optionEngine.complete(optionRequest).size, "Reordered options fused for case=$caseIndex")
            }
        }

    private fun engine(vararg candidateGroups: List<TerminalCompletionCandidate>): TerminalCompletionEngine =
        MergedCompletionEngine(
            sources =
                candidateGroups.mapIndexed { index, candidates ->
                    TerminalCompletionSourceEntry(
                        source = TerminalCompletionSource { _, _, _ -> candidates },
                        priority = index,
                    )
                },
            commandSpecs = emptyList(),
        )

    private fun request(
        commandLine: String,
        shellSyntax: TerminalShellSyntax = TerminalShellSyntax.PLAIN,
        cursorOffset: Int = commandLine.length,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = cursorOffset,
            shellCapabilities =
                when (shellSyntax) {
                    TerminalShellSyntax.PLAIN -> TerminalShellCapabilities.PLAIN
                    TerminalShellSyntax.POSIX -> TerminalShellCapabilities.POSIX
                    TerminalShellSyntax.POWERSHELL -> TerminalShellCapabilities.POWERSHELL
                },
        )

    private fun candidate(
        replacementText: String,
        startOffset: Int,
        endOffset: Int,
        source: String,
        kind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.ARGUMENT,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacementText,
            replacementStartOffset = startOffset,
            replacementEndOffset = endOffset,
            source = source,
            kind = kind,
        )

    private fun randomCommandLine(random: Random): String =
        buildString {
            repeat(random.nextInt(MAX_PROJECTION_FRAGMENT_COUNT + 1)) {
                append(PROJECTION_FRAGMENTS[random.nextInt(PROJECTION_FRAGMENTS.size)])
            }
        }

    private fun String.debug(): String = replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")

    private companion object {
        private const val PROJECTION_SEED = 0x50524F4A
        private const val FUSION_SEED = 0x46555345
        private const val PROJECTION_CASE_COUNT = 1_000
        private const val INVALID_RANGE_CASE_COUNT = 100
        private const val FUSION_CASE_COUNT = 100
        private const val DISTINCT_CASE_COUNT = 100
        private const val MAX_PROJECTION_FRAGMENT_COUNT = 24

        private val PROJECTION_FRAGMENTS = listOf("git", " ", "\t", "'", "\"", "\\", "`", "&&", "|", ";", "é", "中", "😀")
        private val REPLACEMENTS = listOf("x", "two words", "'quoted'", "中", "😀", "a\\b", "a`b", "&&")
        private val VALUES = listOf("feature", "release candidate", "éclair", "分支", "emoji 😀")
    }
}

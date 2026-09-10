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

import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.CompletionLearningIndexCache
import io.github.ketraterm.completion.testing.TestCommandLearning
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LearnedHistoryCandidateIndexTest {
    @Test
    fun `lookup selects prior-token context and active prefix without scanning unrelated commands`() =
        runBlocking {
            val snapshot =
                learningSnapshot(
                    rows =
                        listOf(
                            positive("git switch main"),
                            positive("git switch maintenance"),
                            positive("git checkout main"),
                            positive("gradle assemble"),
                            commandLearning(commandLine = "git switch malformed"),
                        ),
                )
            val index = CompletionLearningIndexCache().indexesFor(snapshot, TerminalShellSyntax.POSIX).history
            val requestLine = TerminalCommandLineTokenizer.parse("git switch ma", "git switch ma".length, TerminalShellSyntax.POSIX)

            val matches = index.matching(requestLine, CompletionLearningContextKey.of(null, null))

            assertEquals(listOf("git switch main", "git switch maintenance"), matches.map { it.replay.commandLine })
        }

    @Test
    fun `lookup partitions every command by exact canonical learning context`() {
        val snapshot =
            learningSnapshot(
                rows =
                    listOf(
                        positive("tool scoped", profileId = "bash", workingDirectoryUri = "file:///repo"),
                        positive("tool unknown"),
                    ),
            )
        val index = CompletionLearningIndexCache().indexesFor(snapshot, TerminalShellSyntax.POSIX).history
        val requestLine = TerminalCommandLineTokenizer.parse("tool ", "tool ".length, TerminalShellSyntax.POSIX)

        fun matches(
            profileId: String?,
            workingDirectoryUri: String?,
        ): List<String> =
            index
                .matching(requestLine, CompletionLearningContextKey.of(profileId, workingDirectoryUri))
                .map { it.replay.commandLine }

        assertEquals(listOf("tool scoped"), matches("bash", "file:///repo/"))
        assertEquals(emptyList(), matches("pwsh", "file:///repo"))
        assertEquals(emptyList(), matches("bash", "file:///other"))
        assertEquals(emptyList(), matches("bash", null))
        assertEquals(listOf("tool unknown"), matches(null, null))
    }

    private fun positive(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
    ): TestCommandLearning =
        commandLearning(
            commandLine = commandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            successCount = 1,
        )
}

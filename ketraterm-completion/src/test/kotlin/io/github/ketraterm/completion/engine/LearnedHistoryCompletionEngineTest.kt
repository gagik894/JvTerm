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
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.testing.commandLearning
import io.github.ketraterm.completion.testing.learningSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearnedHistoryCompletionEngineTest {
    @Test
    fun `learned history matches case-insensitively while preserving exact command case`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult("cat Foo", true, null, null, 10)
            learningStore.recordCommandResult("cat foo", true, null, null, 20)

            val candidates =
                engine(
                    learningStore = learningStore,
                    commandSpecs = listOf(TerminalCommandSpec("cat")),
                ).complete(request("cat f"))

            assertEquals(setOf("Foo", "foo"), candidates.map { it.replacementText }.toSet())
            assertTrue(candidates.all { it.source == "learned" })
            assertTrue(candidates.all { it.kind == TerminalCompletionCandidateKind.ARGUMENT })
            assertTrue(candidates.all { it.replacementStartOffset == 4 && it.replacementEndOffset == 5 })
        }

    @Test
    fun `every learned command remains scoped to its recorded directory`() =
        runBlocking {
            val recordedDirectory = "file:///C:/Users/gagik"
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult("cd IdeaProjects/JvTerm/", true, null, recordedDirectory, 10)
            learningStore.recordCommandResult("cd /usr/bin", true, null, recordedDirectory, 20)
            learningStore.recordCommandResult("cd ..", true, null, recordedDirectory, 30)
            learningStore.recordCommandResult("cat relative/file.txt", true, null, recordedDirectory, 40)
            learningStore.recordCommandResult("cd -- /opt/tools", true, null, recordedDirectory, 50)
            learningStore.recordCommandResult("cd -- sibling", true, null, recordedDirectory, 60)
            val engine = engine(learningStore)

            val otherDirectoryCandidates =
                engine
                    .complete(request("c", workingDirectoryUri = "$recordedDirectory/IdeaProjects/JvTerm"))
                    .filter { it.source == "learned" }
            assertTrue(otherDirectoryCandidates.isEmpty())

            val matchingDirectoryCandidates =
                engine
                    .complete(request("c", workingDirectoryUri = "$recordedDirectory/"))
                    .filter { it.source == "learned" }
                    .mapTo(mutableSetOf()) { it.replacementText }

            assertEquals(
                setOf(
                    "cd IdeaProjects/JvTerm/",
                    "cd /usr/bin",
                    "cd ..",
                    "cat relative/file.txt",
                    "cd -- /opt/tools",
                    "cd -- sibling",
                ),
                matchingDirectoryCandidates,
            )

            val matchingDirectoryCandidate =
                engine
                    .complete(request("cd I", workingDirectoryUri = "$recordedDirectory/"))
                    .single { it.source == "learned" }

            assertEquals("IdeaProjects/JvTerm/", matchingDirectoryCandidate.replacementText)
            assertEquals(TerminalCompletionCandidateKind.PATH, matchingDirectoryCandidate.kind)
            assertEquals("learned directory", matchingDirectoryCandidate.detail)
        }

    @Test
    fun `learned completion at the cursor preserves following command text`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult("git switch main", true, null, null, 10)
            val commandLine = "git switch ma --detach"

            val candidate =
                engine(learningStore)
                    .complete(
                        request(
                            commandLine = commandLine,
                            cursorOffset = "git switch ma".length,
                            shellCapabilities = TerminalShellCapabilities.POSIX,
                        ),
                    ).single { it.source == "learned" }

            assertEquals("main", candidate.replacementText)
            assertEquals("git switch ".length, candidate.replacementStartOffset)
            assertEquals("git switch ma".length, candidate.replacementEndOffset)
            assertEquals(
                "git switch main --detach",
                commandLine.replaceRange(
                    candidate.replacementStartOffset,
                    candidate.replacementEndOffset,
                    candidate.replacementText,
                ),
            )
        }

    @Test
    fun `learned history recency is based on age`() =
        runBlocking {
            val now = 2_000_000_000_000L
            val learningStore =
                TerminalCompletionLearningStore().apply {
                    mergeSnapshot(
                        learningSnapshot(
                            rows =
                                listOf(
                                    commandLearning(
                                        commandLine = "tool a-old",
                                        successCount = 1,
                                        lastUsedEpochMillis = now - 31L * 24L * 60L * 60L * 1_000L,
                                    ),
                                    commandLearning(
                                        commandLine = "tool z-new",
                                        successCount = 1,
                                        lastUsedEpochMillis = now - 60L * 1_000L,
                                    ),
                                ),
                        ),
                    )
                }

            val candidates =
                engine(
                    learningStore = learningStore,
                    commandSpecs = listOf(TerminalCommandSpec("tool")),
                    clockEpochMillis = { now },
                ).complete(request("tool "))

            assertEquals(listOf("z-new", "a-old"), candidates.map { it.replacementText })
        }

    @Test
    fun `learned evidence promotes a specification result without replacing its presentation`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            val request = request("./gradlew ")
            val coldCandidate = engine(learningStore).complete(request).single { it.replacementText == "test" }
            repeat(3) { index ->
                learningStore.recordCommandResult("./gradlew test", true, null, null, 1_000L + index)
            }

            val learnedCandidate = engine(learningStore).complete(request).single { it.replacementText == "test" }

            assertTrue(learnedCandidate.score > coldCandidate.score)
            assertEquals("spec", learnedCandidate.source)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, learnedCandidate.kind)
            assertEquals("run tests", learnedCandidate.detail)
        }

    @Test
    fun `learned-only outcomes keep fallback presentation and remain distinct from specification results`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult("unknown-cli deploy", true, null, null, 10)
            learningStore.recordCommandResult("./gradlew build --scan", true, null, null, 20)

            val unknownCandidate =
                engine(
                    learningStore = learningStore,
                    commandSpecs = listOf(TerminalCommandSpec("unknown-cli")),
                ).complete(request("unknown-cli d"))
                    .single()

            assertEquals("deploy", unknownCandidate.replacementText)
            assertEquals("learned", unknownCandidate.source)
            assertEquals("learned command", unknownCandidate.detail)
            assertEquals(TerminalCompletionCandidateKind.ARGUMENT, unknownCandidate.kind)

            val gradleCandidates = engine(learningStore).complete(request("./gradlew bu"))
            assertEquals("spec", gradleCandidates.single { it.replacementText == "build" }.source)
            assertEquals("learned", gradleCandidates.single { it.replacementText == "build --scan" }.source)
        }

    @Test
    fun `observed tokens are derived automatically from the learning store`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult("abc deploy --fast", true, null, null, 10)
            val engine = engine(learningStore, commandSpecs = emptyList())

            val firstCandidates = engine.complete(request("abc d"))
            val optionCandidates = engine.complete(request("abc deploy --f"))
            assertTrue(firstCandidates.any { it.source == "observed" && it.replacementText == "deploy" }, firstCandidates.toString())
            val firstArgument = firstCandidates.single { it.source == "observed" && it.replacementText == "deploy" }
            val option = optionCandidates.single { it.replacementText == "--fast" }

            assertEquals(TerminalCompletionCandidateKind.ARGUMENT, firstArgument.kind)
            assertEquals("learned from successful commands", firstArgument.detail)
            assertEquals(TerminalCompletionCandidateKind.OPTION, option.kind)
        }

    @Test
    fun `path-backed command aliases retain specification presentation across shell styles`() =
        runBlocking {
            val posixCandidates =
                engine(
                    learningStore = TerminalCompletionLearningStore(),
                    fileSystemProvider = fileSystemWith("gradlew"),
                ).complete(
                    request(
                        commandLine = "\"./gr",
                        workingDirectoryUri = "file:///project",
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    ),
                )
            val posixWrapper = posixCandidates.single { it.replacementText == "\"./gradlew\"" }

            assertEquals("./gradlew", posixWrapper.displayText)
            assertEquals("build automation", posixWrapper.detail)
            assertEquals("spec", posixWrapper.source)
            assertEquals(TerminalCompletionCandidateKind.COMMAND, posixWrapper.kind)

            val powershellCandidates =
                engine(
                    learningStore = TerminalCompletionLearningStore(),
                    fileSystemProvider = fileSystemWith("gradlew.bat"),
                ).complete(
                    request(
                        commandLine = ".\\gr",
                        workingDirectoryUri = "file:///project",
                        shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                    ),
                )
            val powershellWrapper = powershellCandidates.single { it.replacementText == ".\\gradlew.bat" }

            assertEquals(".\\gradlew.bat", powershellWrapper.displayText)
            assertEquals("build automation", powershellWrapper.detail)
            assertEquals("spec", powershellWrapper.source)
            assertEquals(TerminalCompletionCandidateKind.COMMAND, powershellWrapper.kind)
        }

    @Test
    fun `ordinary command-position paths retain path presentation`() =
        runBlocking {
            val candidate =
                engine(
                    learningStore = TerminalCompletionLearningStore(),
                    fileSystemProvider = fileSystemWith("gradient"),
                ).complete(
                    request(
                        commandLine = "./gr",
                        workingDirectoryUri = "file:///project",
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    ),
                ).single()

            assertEquals("./gradient", candidate.replacementText)
            assertEquals("gradient", candidate.displayText)
            assertEquals("file", candidate.detail)
            assertEquals("path", candidate.source)
            assertEquals(TerminalCompletionCandidateKind.PATH, candidate.kind)
        }

    private fun engine(
        learningStore: TerminalCompletionLearningStore,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
        fileSystemProvider: TerminalFileSystemProvider? = null,
        clockEpochMillis: () -> Long = System::currentTimeMillis,
    ): TerminalCompletionEngine =
        MergedCompletionEngine(
            sources =
                fileSystemProvider?.let {
                    listOf(
                        TerminalCompletionSourceEntry(
                            source = TerminalCompletionSources.path(it),
                            priority = TerminalCompletionSourcePrior.DIRECTORY_PATH,
                        ),
                    )
                } ?: emptyList(),
            commandSpecs = commandSpecs,
            learningStore = learningStore,
            clockEpochMillis = clockEpochMillis,
        )

    private fun request(
        commandLine: String,
        cursorOffset: Int = commandLine.length,
        workingDirectoryUri: String? = null,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = cursorOffset,
            workingDirectoryUri = workingDirectoryUri,
            shellCapabilities = shellCapabilities,
        )

    private fun fileSystemWith(name: String): TerminalFileSystemProvider =
        TerminalFileSystemProvider { request ->
            if (name.startsWith(request.entryNamePrefix, ignoreCase = true)) {
                listOf(TerminalFileEntry(name, isDirectory = false))
            } else {
                emptyList()
            }
        }
}

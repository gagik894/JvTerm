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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests Gradle task syntax handling against a host-owned immutable task snapshot. */
class GradleTaskCompletionSourceTest {
    private val source =
        TerminalCompletionSources.gradleTask(
            sourceId = "gradle-task",
            tasksProvider = { _, _ ->
                listOf(
                    TerminalGradleTask(":test", "run root tests", projectDirectory = "."),
                    TerminalGradleTask(":app:run", "run app", projectDirectory = "app"),
                    TerminalGradleTask(":app:runIde", "launch IDE sandbox", projectDirectory = "app"),
                    TerminalGradleTask(":tools:run", "run tools", projectDirectory = "tools"),
                )
            },
        )

    @Test
    fun `completes root tasks through the wrapper alias`() =
        runBlocking {
            val candidates = source.complete(request("./gradlew te"))

            assertEquals(listOf("test"), candidates.map { it.replacementText })
            assertEquals(listOf("run root tests"), candidates.map { it.detail })
            assertTrue(candidates.all { it.kind == TerminalCompletionCandidateKind.SUBCOMMAND })
        }

    @Test
    fun `completes fully qualified module tasks after another Gradle task`() =
        runBlocking {
            assertEquals(
                listOf(":app:run", ":app:runIde"),
                source.complete(request("./gradlew test :app:ru")).map { it.replacementText },
            )
            assertEquals(
                listOf(":app:run", ":app:runIde"),
                source.complete(request("gradle test :app:ru")).map { it.replacementText },
            )
        }

    @Test
    fun `scopes short task names after project directory options`() =
        runBlocking {
            assertEquals(
                listOf("runIde"),
                source.complete(request("./gradlew -p ./app runI")).map { it.replacementText },
            )
            assertEquals(
                listOf("run"),
                source.complete(request("gradlew --project-dir tools ru")).map { it.replacementText },
            )
            assertEquals(
                listOf("test"),
                source.complete(request("./gradlew -p ./ te")).map { it.replacementText },
            )
            assertEquals(
                listOf("runIde"),
                source.complete(request("./gradlew --project-dir=app runI")).map { it.replacementText },
            )
            assertEquals(
                listOf("runIde"),
                source.complete(request("./gradlew -p \"./app\" runI")).map { it.replacementText },
            )
        }

    @Test
    fun `does not complete Gradle tasks while an option value is active`() =
        runBlocking {
            assertTrue(source.complete(request("./gradlew -p ap")).isEmpty())
        }

    @Test
    fun `last project directory option wins without leaking tasks from another module`() =
        runBlocking {
            assertEquals(
                listOf("run"),
                source.complete(request("./gradlew -p app --project-dir tools ru")).map { it.replacementText },
            )
        }

    @Test
    fun `does not activate for unrelated commands`() =
        runBlocking {
            assertTrue(source.complete(request("npm run runI")).isEmpty())
        }

    @Test
    fun `passes the immutable request to the task provider`() =
        runBlocking {
            var requestedDirectory: String? = null
            var requestedPrefix: String? = null
            val requestAwareSource =
                TerminalCompletionSources.gradleTask(
                    sourceId = "request-aware-gradle-task",
                    tasksProvider = { request, context ->
                        requestedDirectory = request.workingDirectoryUri
                        requestedPrefix = context.activePrefix
                        buildList {
                            repeat(300) { index -> add(TerminalGradleTask(":otherTask$index", projectDirectory = ".")) }
                            add(TerminalGradleTask(":needleTask", projectDirectory = "."))
                        }
                    },
                )

            val candidates = requestAwareSource.complete(request("gradle needle"))

            assertEquals("file:///project", requestedDirectory)
            assertEquals("needle", requestedPrefix)
            assertEquals(listOf("needleTask"), candidates.map { it.replacementText })
        }

    @Test
    fun `retains a higher-ranked late task within the final candidate bound`() =
        runBlocking {
            val boundedSource =
                TerminalCompletionSources.gradleTask(
                    sourceId = "bounded-gradle-task",
                    tasksProvider = { _, _ ->
                        buildList {
                            repeat(300) { index -> add(TerminalGradleTask(":task-run-$index", projectDirectory = ".")) }
                            add(TerminalGradleTask(":tr-target", projectDirectory = "."))
                        }
                    },
                )

            val candidates = boundedSource.complete(request("gradle tr"))

            assertEquals(256, candidates.size)
            assertTrue(candidates.any { it.replacementText == "tr-target" })
        }

    private fun request(commandLine: String): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            workingDirectoryUri = "file:///project",
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )
}

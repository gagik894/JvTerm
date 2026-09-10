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

import io.github.ketraterm.completion.api.TerminalCompletionActivePosition
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionContextResolverTest {
    @Test
    fun `git subcommand prefix resolves subcommand position`() {
        val context = resolve("git s")

        assertEquals(TerminalCompletionActivePosition.SUBCOMMAND, context.activePosition)
        assertEquals("git", context.command?.name)
        assertEquals(listOf("git"), context.commandPath.map { it.name })
        assertEquals("s", context.activePrefix)
    }

    @Test
    fun `git option prefix resolves option name position`() {
        val context = resolve("git -")

        assertEquals(TerminalCompletionActivePosition.OPTION_NAME, context.activePosition)
        assertEquals("-", context.activePrefix)
    }

    @Test
    fun `active double dash remains an option prefix`() {
        val context = resolve("git --")

        assertEquals(TerminalCompletionActivePosition.OPTION_NAME, context.activePosition)
        assertEquals(false, context.optionsTerminated)
        assertEquals("--", context.activePrefix)
    }

    @Test
    fun `double dash terminates options after the cursor passes its token`() {
        val context = resolve("git -- ")

        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(true, context.optionsTerminated)
        assertEquals("", context.activePrefix)
    }

    @Test
    fun `double dash at a mid-line cursor remains an option prefix`() {
        val commandLine = "git -- status"
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = commandLine,
                cursorOffset = "git --".length,
                commandSpecs = specs,
            )

        assertEquals(TerminalCompletionActivePosition.OPTION_NAME, context.activePosition)
        assertEquals(false, context.optionsTerminated)
        assertEquals("--", context.activePrefix)
        assertEquals(4, context.replacementStartOffset)
        assertEquals(6, context.replacementEndOffset)
    }

    @Test
    fun `option terminator makes the active token positional`() {
        val context = resolve("git -- --help")

        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(true, context.optionsTerminated)
        assertEquals(null, context.activeOption)
        assertEquals("--help", context.activePrefix)
    }

    @Test
    fun `option terminator prevents later tokens from resolving as subcommands`() {
        val context = resolve("git -- status")

        assertEquals(listOf("git"), context.commandPath.map { it.name })
        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(null, context.subcommandCandidateSource)
    }

    @Test
    fun `git directory option value resolves option value path context`() {
        val context = resolve("git -C ")

        assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, context.activePosition)
        assertEquals("-C", context.activeOption?.names?.single())
        assertEquals(TerminalPathArgumentKind.DIRECTORY, context.expectedPathKind)
    }

    @Test
    fun `static option value candidates are exposed through context`() {
        val context = resolve("aws --output t")

        assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, context.activePosition)
        assertEquals(listOf("json", "text", "table", "yaml", "yaml-stream"), context.staticValueCandidates)
        assertEquals("t", context.activePrefix)
    }

    @Test
    fun `attached option value exposes value-only prefix and replacement range`() {
        val commandLine = "aws --output=ta"
        val context = resolve(commandLine)

        assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, context.activePosition)
        assertEquals("ta", context.activePrefix)
        assertEquals(commandLine.indexOf('=') + 1, context.replacementStartOffset)
        assertEquals(commandLine.length, context.replacementEndOffset)
        assertEquals(listOf("json", "text", "table", "yaml", "yaml-stream"), context.staticValueCandidates)
    }

    @Test
    fun `dynamic option value domain is exposed through context`() {
        val context = resolve("kubectl --namespace def")

        assertEquals(TerminalCompletionActivePosition.OPTION_VALUE, context.activePosition)
        assertEquals(TerminalCompletionValueDomain.KUBERNETES_NAMESPACE, context.expectedValueDomain)
        assertEquals("def", context.activePrefix)
    }

    @Test
    fun `used option conflict groups are exposed through context`() {
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = "tool --quiet ",
                cursorOffset = "tool --quiet ".length,
                commandSpecs =
                    listOf(
                        TerminalCommandSpec(
                            name = "tool",
                            options = listOf(TerminalOptionSpec(listOf("--quiet"), exclusiveGroupIds = listOf("verbosity"))),
                        ),
                    ),
            )

        assertEquals(setOf("verbosity"), context.usedOptionExclusiveGroupIds)
    }

    @Test
    fun `option path hidden policy is exposed through context`() {
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = "tool --config ",
                cursorOffset = "tool --config ".length,
                commandSpecs =
                    listOf(
                        TerminalCommandSpec(
                            name = "tool",
                            options =
                                listOf(
                                    TerminalOptionSpec(
                                        names = listOf("--config"),
                                        requiresValue = true,
                                        valuePathKind = TerminalPathArgumentKind.FILE,
                                        valueHiddenPathPolicy = TerminalHiddenPathPolicy.EXCLUDE,
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(TerminalHiddenPathPolicy.EXCLUDE, context.expectedHiddenPathPolicy)
    }

    @Test
    fun `dynamic positional value domain is exposed through context`() {
        val context = resolve("git switch mai")

        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(TerminalCompletionValueDomain.GIT_BRANCH, context.expectedValueDomain)
        assertEquals("mai", context.activePrefix)
    }

    @Test
    fun `recent Git commit domain remains active for repeated commit arguments`() {
        for (subcommand in listOf("cherry-pick", "revert", "show")) {
            val firstCommit = resolve("git $subcommand ")
            assertEquals(listOf("git", subcommand), firstCommit.commandPath.map { it.name })
            assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, firstCommit.activePosition)
            assertEquals(TerminalCompletionValueDomain.GIT_COMMIT, firstCommit.expectedValueDomain)

            val nextCommit = resolve("git $subcommand deadbeef ")
            assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, nextCommit.activePosition)
            assertEquals(TerminalCompletionValueDomain.GIT_COMMIT, nextCommit.expectedValueDomain)
        }
    }

    @Test
    fun `ordered positional arguments advance through optional and variadic declarations`() {
        val spec =
            TerminalCommandSpec(
                name = "tool",
                positionalArguments =
                    listOf(
                        TerminalArgumentSpec(name = "target", valueCandidates = listOf("alpha")),
                        TerminalArgumentSpec(name = "profile", isOptional = true, valueCandidates = listOf("dev")),
                        TerminalArgumentSpec(name = "file", isVariadic = true, pathKind = TerminalPathArgumentKind.FILE),
                    ),
            )

        val optionalContext = resolve("tool alpha ", listOf(spec))
        assertEquals("profile", optionalContext.activePositionalArgument?.name)
        assertEquals(listOf("dev"), optionalContext.staticValueCandidates)

        val variadicContext = resolve("tool alpha dev first ", listOf(spec))
        assertEquals("file", variadicContext.activePositionalArgument?.name)
        assertEquals(TerminalPathArgumentKind.FILE, variadicContext.expectedPathKind)
    }

    @Test
    fun `only positional tokens advance the positional argument counter`() {
        val spec =
            TerminalCommandSpec(
                name = "tool",
                options = listOf(TerminalOptionSpec(listOf("--config"), requiresValue = true)),
                positionalArguments =
                    listOf(
                        TerminalArgumentSpec(name = "first"),
                        TerminalArgumentSpec(name = "second"),
                    ),
            )

        assertEquals("second", resolve("tool --config config.toml first ", listOf(spec)).activePositionalArgument?.name)
        assertEquals("second", resolve("tool --config=config.toml first ", listOf(spec)).activePositionalArgument?.name)
        assertEquals("second", resolve("tool -- --config ", listOf(spec)).activePositionalArgument?.name)
    }

    @Test
    fun `repeatable subcommands keep suggesting siblings after an existing sibling`() {
        val context = resolve("./gradlew clean bu")

        assertEquals(TerminalCompletionActivePosition.SUBCOMMAND, context.activePosition)
        assertEquals(listOf("gradle", "clean"), context.commandPath.map { it.name })
        assertEquals("gradle", context.subcommandCandidateSource?.name)
        assertEquals("bu", context.activePrefix)
    }

    @Test
    fun `quoted directory argument exposes path and quote context`() {
        val context = resolve("cd \"Idea")

        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(TerminalPathArgumentKind.DIRECTORY, context.expectedPathKind)
        assertEquals('"', context.activeTokenQuote)
        assertEquals(3, context.replacementStartOffset)
    }

    @Test
    fun `unknown command pathlike token remains positional without expected path kind`() {
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = "unknown ./s",
                cursorOffset = "unknown ./s".length,
                commandSpecs = specs,
            )

        assertEquals(TerminalCompletionActivePosition.POSITIONAL_ARGUMENT, context.activePosition)
        assertEquals(TerminalPathArgumentKind.NONE, context.expectedPathKind)
        assertEquals("./s", context.activePrefix)
    }

    private fun resolve(
        commandLine: String,
        commandSpecs: List<TerminalCommandSpec> = specs,
    ): TerminalCompletionContext =
        TerminalCompletionContextResolver.resolve(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            commandSpecs = commandSpecs,
        )

    private companion object {
        private val specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults()
    }
}

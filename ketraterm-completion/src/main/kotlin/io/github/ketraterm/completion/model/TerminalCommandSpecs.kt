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
package io.github.ketraterm.completion.model

/**
 * Curated static command specs useful as a bootstrap source before richer
 * imported corpora and host context providers are available.
 */
object TerminalCommandSpecs {
    private val DEFAULT_CATALOG: List<TerminalCommandSpec> =
        freezeSpecs(
            listOf(
                cd(),
                pushd(),
                ls(),
                cat(),
                mkdir(),
                rm(),
                cp(),
                mv(),
                code(),
                GitCommandSpecs.git(),
                GradleCommandSpecs.gradle(),
                PackageManagerCommandSpecs.npm(),
                PackageManagerCommandSpecs.pnpm(),
                PackageManagerCommandSpecs.yarn(),
                PackageManagerCommandSpecs.bun(),
                ContainerCommandSpecs.docker(),
                ContainerCommandSpecs.dockerCompose(),
                PackageManagerCommandSpecs.cargo(),
                ContainerCommandSpecs.kubectl(),
                PackageManagerCommandSpecs.gh(),
                PackageManagerCommandSpecs.pip(),
                PackageManagerCommandSpecs.go(),
                ToolchainCommandSpecs.aws(),
                ToolchainCommandSpecs.kotlin(),
                ToolchainCommandSpecs.kotlinc(),
                ToolchainCommandSpecs.adb(),
                ToolchainCommandSpecs.ketra(),
            ),
        )

    /**
     * Returns the shared immutable default spec catalog for common developer commands.
     *
     * @return built-in command specifications.
     */
    @JvmStatic
    fun defaults(): List<TerminalCommandSpec> = DEFAULT_CATALOG

    private fun cd(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cd",
            description = "change directory",
            aliases = listOf("chdir", "sl", "set-location"),
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
        )

    private fun pushd(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pushd",
            description = "change directory and save the current location",
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
        )

    private fun ls(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "ls",
            description = "list directory contents",
            aliases = listOf("dir"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-l"), "use a long listing format"),
                    TerminalOptionSpec(listOf("-a", "--all"), "do not ignore entries starting with ."),
                    TerminalOptionSpec(listOf("-h", "--human-readable"), "with -l, print sizes like 1K 234M 2G"),
                    TerminalOptionSpec(listOf("-t"), "sort by modification time"),
                    TerminalOptionSpec(listOf("-r", "--reverse"), "reverse order while sorting"),
                ),
        )

    private fun cat(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cat",
            description = "print file contents",
            aliases = listOf("type"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-n", "--number"), "number all output lines"),
                ),
        )

    private fun mkdir(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mkdir",
            description = "create directories",
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-p", "--parents"), "no error if existing, make parent directories as needed"),
                ),
        )

    private fun rm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "rm",
            description = "remove files or directories",
            aliases = listOf("del", "erase"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "-R", "--recursive"), "remove directories and their contents recursively"),
                    TerminalOptionSpec(listOf("-f", "--force"), "ignore nonexistent files and arguments, never prompt"),
                ),
        )

    private fun cp(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cp",
            description = "copy files or directories",
            aliases = listOf("copy"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "-R", "--recursive"), "copy directories recursively"),
                    TerminalOptionSpec(
                        listOf("-f", "--force"),
                        "if an existing destination file cannot be opened, remove it and try again",
                    ),
                ),
        )

    private fun mv(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mv",
            description = "move files or directories",
            aliases = listOf("move"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-f", "--force"), "do not prompt before overwriting"),
                ),
        )

    private fun code(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "code",
            description = "open files or directories in Visual Studio Code",
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "--reuse-window"), "force opening a file or folder in an already opened window"),
                    TerminalOptionSpec(listOf("-n", "--new-window"), "force opening a new window"),
                    TerminalOptionSpec(listOf("-g", "--goto"), "open a file at the path on the specified line and column"),
                    TerminalOptionSpec(listOf("-d", "--diff"), "compare two files with each other"),
                    TerminalOptionSpec(listOf("-w", "--wait"), "wait for the files to be closed before returning"),
                ),
        )

    internal val KUBECTL_RESOURCES: List<String>
        get() = ContainerCommandSpecs.KUBECTL_RESOURCES

    private fun freezeSpecs(specs: List<TerminalCommandSpec>): List<TerminalCommandSpec> =
        immutableList(
            specs.map { spec ->
                spec.copy(
                    aliases = immutableList(spec.aliases),
                    subcommands = freezeSpecs(spec.subcommands),
                    options =
                        immutableList(
                            spec.options.map { option ->
                                option.copy(
                                    names = immutableList(option.names),
                                    valueCandidates = immutableList(option.valueCandidates),
                                    exclusiveGroupIds = immutableList(option.exclusiveGroupIds),
                                )
                            },
                        ),
                    positionalArguments =
                        immutableList(
                            spec.positionalArguments.map { argument ->
                                argument.copy(valueCandidates = immutableList(argument.valueCandidates))
                            },
                        ),
                )
            },
        )

    private fun <T> immutableList(values: List<T>): List<T> = if (values.isEmpty()) emptyList() else java.util.List.copyOf(values)
}

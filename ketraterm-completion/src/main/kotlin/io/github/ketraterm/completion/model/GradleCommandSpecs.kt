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

import io.github.ketraterm.completion.internal.GradleCompletionSyntax

/**
 * Curated Gradle command specifications focused on universal project tasks and options.
 */
internal object GradleCommandSpecs {
    fun gradle(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = GradleCompletionSyntax.COMMAND_NAME,
            description = "build automation",
            aliases = listOf("./gradlew", "./gradlew.bat", ".\\gradlew", ".\\gradlew.bat", "gradlew", "gradlew.bat"),
            repeatableSubcommands = true,
            subcommands =
                listOf(
                    TerminalCommandSpec("build", "assemble and test the project"),
                    TerminalCommandSpec("test", "run tests"),
                    TerminalCommandSpec("check", "run verification tasks"),
                    TerminalCommandSpec("clean", "delete build outputs"),
                    TerminalCommandSpec("tasks", "list available tasks"),
                    TerminalCommandSpec("run", "run the application"),
                    TerminalCommandSpec("assemble", "assembles the outputs of this project"),
                    TerminalCommandSpec("help", "displays a help message"),
                    TerminalCommandSpec("projects", "displays the sub-projects of the current project"),
                    TerminalCommandSpec("properties", "displays the properties of the current project"),
                    TerminalCommandSpec("dependencies", "displays all dependencies declared in the project"),
                    TerminalCommandSpec("dependencyInsight", "displays the insight into a specific dependency"),
                    TerminalCommandSpec("wrapper", "generates Gradle wrapper files"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(
                        names = listOf("--console"),
                        description = "console output style",
                        requiresValue = true,
                        valueCandidates = listOf("auto", "plain", "rich", "verbose"),
                    ),
                    TerminalOptionSpec(listOf("--info", "-i"), "set log level to info"),
                    TerminalOptionSpec(listOf("--debug", "-d"), "set log level to debug"),
                    TerminalOptionSpec(listOf("--warn", "-w"), "set log level to warn"),
                    TerminalOptionSpec(listOf("--quiet", "-q"), "set log level to quiet"),
                    TerminalOptionSpec(listOf("--stacktrace", "-s"), "print out the stacktrace for all exceptions"),
                    TerminalOptionSpec(listOf("--full-stacktrace", "-S"), "print out the full stacktrace for all exceptions"),
                    TerminalOptionSpec(listOf("--scan"), "create a build scan"),
                    TerminalOptionSpec(listOf("--no-scan"), "do not create a build scan"),
                    TerminalOptionSpec(listOf("--build-cache"), "enables the Gradle build cache"),
                    TerminalOptionSpec(listOf("--no-build-cache"), "disables the Gradle build cache"),
                    TerminalOptionSpec(listOf("--configuration-cache"), "enables the configuration cache"),
                    TerminalOptionSpec(listOf("--no-configuration-cache"), "disables the configuration cache"),
                    TerminalOptionSpec(
                        names = listOf("--configuration-cache-problems"),
                        description = "how to handle configuration cache problems",
                        requiresValue = true,
                        valueCandidates = listOf("fail", "warn"),
                    ),
                    TerminalOptionSpec(listOf("--daemon"), "uses the Gradle Daemon"),
                    TerminalOptionSpec(listOf("--no-daemon"), "do not use the Gradle Daemon"),
                    TerminalOptionSpec(listOf("--stop"), "stops all Gradle Daemons"),
                    TerminalOptionSpec(listOf("--status"), "shows status of running and recently stopped Gradle Daemons"),
                    TerminalOptionSpec(listOf("--offline"), "build without network access"),
                    TerminalOptionSpec(listOf("--parallel"), "build projects in parallel"),
                    TerminalOptionSpec(listOf("--no-parallel"), "disables parallel execution"),
                    TerminalOptionSpec(listOf("--max-workers"), "sets the maximum number of workers", requiresValue = true),
                    TerminalOptionSpec(listOf("--continuous", "-t"), "enables continuous build and execution"),
                    TerminalOptionSpec(listOf("--refresh-dependencies"), "refresh the state of dependencies"),
                    TerminalOptionSpec(listOf("--dry-run", "-m"), "run the build with all task actions disabled"),
                    TerminalOptionSpec(listOf("--rerun-tasks"), "ignore previously cached task outputs"),
                    TerminalOptionSpec(listOf("--continue"), "continues task execution after a task failure"),
                    TerminalOptionSpec(
                        listOf("--exclude-task", "-x"),
                        "specifies a task to be excluded from execution",
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(
                        names = GradleCompletionSyntax.PROJECT_DIRECTORY_OPTION_NAMES,
                        description = "use the specified project directory",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--settings-file", "-c"),
                        description = "specify the settings file",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--build-file", "-b"),
                        description = "specify the build file",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--init-script", "-I"),
                        description = "specify an initialization script",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                ),
        )
}

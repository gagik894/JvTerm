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
 * Curated cloud, compiler, device, and launcher specifications (AWS, Kotlin, Kotlinc, ADB, Ketra).
 */
internal object ToolchainCommandSpecs {
    fun aws(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "aws",
            description = "AWS Unified Command Line Interface",
            subcommands =
                listOf(
                    TerminalCommandSpec("s3", "manage S3 storage resources"),
                    TerminalCommandSpec("ec2", "manage elastic compute cloud resources"),
                    TerminalCommandSpec("rds", "manage relational database service instances"),
                    TerminalCommandSpec("dynamodb", "manage DynamoDB tables and items"),
                    TerminalCommandSpec("lambda", "manage AWS Lambda functions"),
                    TerminalCommandSpec("iam", "manage Identity and Access Management"),
                    TerminalCommandSpec("sts", "manage Security Token Service credentials"),
                    TerminalCommandSpec("configure", "configure AWS CLI settings"),
                    TerminalCommandSpec("cloudformation", "manage CloudFormation stacks"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(listOf("--version"), "show version"),
                    TerminalOptionSpec(
                        names = listOf("--profile"),
                        description = "select AWS CLI profile to use",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.AWS_PROFILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--region"),
                        description = "AWS region to target",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.AWS_REGION,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--output"),
                        description = "output format json, text, table",
                        requiresValue = true,
                        valueCandidates = listOf("json", "text", "table", "yaml", "yaml-stream"),
                    ),
                ),
        )

    fun kotlin(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "kotlin",
            description = "Kotlin command-line runner and REPL",
            subcommands =
                listOf(
                    TerminalCommandSpec("run", "runs a Kotlin application or script"),
                    TerminalCommandSpec("build", "builds a Kotlin project"),
                    TerminalCommandSpec("test", "runs Kotlin tests"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-version", "--version", "-v"), "display compiler version"),
                    TerminalOptionSpec(listOf("-help", "-h"), "how help"),
                    TerminalOptionSpec(listOf("-e"), "evaluate inline Kotlin expression", requiresValue = true),
                    TerminalOptionSpec(
                        listOf("-classpath", "-cp"),
                        "paths where to find user class files and annotation processors",
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(listOf("-include-runtime"), "include Kotlin runtime in to resulting JAR"),
                    TerminalOptionSpec(listOf("-nowarn"), "generate no warnings"),
                    TerminalOptionSpec(listOf("-verbose"), "enable verbose logging output"),
                ),
        )

    fun kotlinc(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "kotlinc",
            description = "Kotlin command-line compiler",
            aliases = listOf("kotlinc-jvm", "kotlinc-js", "kotlinc-native"),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-version", "--version", "-v"), "display compiler version"),
                    TerminalOptionSpec(listOf("-help", "-h"), "show help"),
                    TerminalOptionSpec(
                        names = listOf("-d"),
                        description = "destination for generated class files",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalOptionSpec(
                        listOf("-classpath", "-cp"),
                        "paths where to find user class files and annotation processors",
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(listOf("-include-runtime"), "include Kotlin runtime in to resulting JAR"),
                    TerminalOptionSpec(
                        names = listOf("-jvm-target"),
                        description = "target version of the generated JVM bytecode",
                        requiresValue = true,
                        valueCandidates = listOf("1.8", "11", "17", "21", "22", "23", "24", "25"),
                    ),
                    TerminalOptionSpec(
                        listOf("-language-version"),
                        "provide source compatibility with specified version of Kotlin",
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(
                        listOf("-api-version"),
                        "allow using declarations only from the specified version of Kotlin",
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(
                        listOf("-opt-in"),
                        "enable API usages that require opt-in with a requirement annotation",
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(listOf("-Xcontext-receivers"), "enable experimental context receivers"),
                    TerminalOptionSpec(listOf("-Xcontext-parameters"), "enable experimental context parameters"),
                    TerminalOptionSpec(listOf("-Xmulti-platform"), "enable multiplatform support"),
                    TerminalOptionSpec(listOf("-Werror"), "turn all warnings into errors"),
                    TerminalOptionSpec(listOf("-nowarn"), "generate no warnings"),
                    TerminalOptionSpec(listOf("-verbose"), "enable verbose logging output"),
                ),
        )

    fun adb(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "adb",
            description = "Android Debug Bridge CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("devices", "list connected devices"),
                    TerminalCommandSpec("logcat", "view device log stream"),
                    TerminalCommandSpec(
                        "install",
                        "install an Android package (APK) to device",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalCommandSpec("uninstall", "remove an application package from device"),
                    TerminalCommandSpec("shell", "run remote shell command on device"),
                    TerminalCommandSpec(
                        "push",
                        "copy local files to device",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalCommandSpec(
                        "pull",
                        "copy files from device to local",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalCommandSpec("reboot", "reboot the device"),
                    TerminalCommandSpec("reverse", "reverse socket connections"),
                    TerminalCommandSpec("forward", "forward socket connections"),
                    TerminalCommandSpec("start-server", "ensure that there is a server running"),
                    TerminalCommandSpec("kill-server", "kill the server if it is running"),
                    TerminalCommandSpec("connect", "connect to a device via TCP/IP"),
                    TerminalCommandSpec("disconnect", "disconnect from a given TCP/IP device"),
                    TerminalCommandSpec("tcpip", "restart host in TCP mode"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-s"), "use device with given serial number", requiresValue = true),
                    TerminalOptionSpec(listOf("-d"), "direct an adb command to the only connected USB device"),
                    TerminalOptionSpec(listOf("-e"), "direct an adb command to the only running emulator"),
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version"), "show version"),
                ),
        )

    fun ketra(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "ketra",
            description = "KetraTerm launcher CLI",
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--profile", "-p"), "launch with specific shell profile", requiresValue = true),
                    TerminalOptionSpec(
                        names = listOf("--directory", "-d"),
                        description = "start in specific directory",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )
}

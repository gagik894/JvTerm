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
 * Curated package and project management specifications (NPM, PNPM, Yarn, Bun, Cargo, Pip, Go, GitHub CLI).
 */
internal object PackageManagerCommandSpecs {
    fun npm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "npm",
            description = "JavaScript package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("install", "install package dependencies", aliases = listOf("i")),
                    TerminalCommandSpec(
                        name = "run",
                        description = "run a package script",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", "run the test script", aliases = listOf("t")),
                    TerminalCommandSpec("start", "run the start script"),
                    TerminalCommandSpec("update", "update packages", aliases = listOf("up")),
                    TerminalCommandSpec("publish", "publish a package"),
                    TerminalCommandSpec("init", "create a package.json file"),
                    TerminalCommandSpec("outdated", "check for outdated packages"),
                    TerminalCommandSpec("audit", "run a security audit"),
                    TerminalCommandSpec("uninstall", "remove a package", aliases = listOf("un", "rm")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--global", "-g"), "operate globally"),
                    TerminalOptionSpec(listOf("--save-dev", "-D"), "save to dev dependencies"),
                ),
        )

    fun pnpm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pnpm",
            description = "Fast, disk space efficient package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("install", "install all dependencies for a project", aliases = listOf("i")),
                    TerminalCommandSpec("add", "install a package and any packages that it depends on"),
                    TerminalCommandSpec(
                        name = "run",
                        description = "runs a defined package script",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", "runs an arbitrary command specified in package's test property", aliases = listOf("t")),
                    TerminalCommandSpec("build", "build package"),
                    TerminalCommandSpec("start", "runs an arbitrary command specified in package's start property"),
                    TerminalCommandSpec("remove", "removes packages from package.json", aliases = listOf("rm")),
                    TerminalCommandSpec(
                        "update",
                        "updates packages to their latest version based on the specified range",
                        aliases = listOf("up"),
                    ),
                    TerminalCommandSpec("exec", "executes a shell script in scope of the project"),
                    TerminalCommandSpec("dlx", "fetches a package from the registry and runs its default binary command"),
                    TerminalCommandSpec("publish", "publishes a package to the registry"),
                    TerminalCommandSpec("outdated", "check for outdated packages"),
                    TerminalCommandSpec("audit", "checks for known security issues with the installed packages"),
                    TerminalCommandSpec("why", "shows all packages that depend on the specified package"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(
                        listOf("--recursive", "-r"),
                        "run the command on every package in subdirectories or in every workspace package",
                    ),
                    TerminalOptionSpec(listOf("--filter"), "filter packages by pattern", requiresValue = true),
                    TerminalOptionSpec(listOf("--save-dev", "-D"), "save to dev dependencies"),
                    TerminalOptionSpec(listOf("--save-peer"), "save to peer dependencies"),
                    TerminalOptionSpec(listOf("--global", "-g"), "install packages globally"),
                    TerminalOptionSpec(
                        listOf("--workspace-root", "-w"),
                        "run the command as if it was invoked in the root of the workspace",
                    ),
                    TerminalOptionSpec(listOf("--frozen-lockfile"), "do not generate a lockfile and fail if an update is needed"),
                ),
        )

    fun yarn(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "yarn",
            description = "JavaScript package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("add", "install a package and any packages that it depends on"),
                    TerminalCommandSpec("install", "install all the dependencies for a project", aliases = listOf("i")),
                    TerminalCommandSpec("remove", "remove a package"),
                    TerminalCommandSpec(
                        name = "run",
                        description = "run a defined package script",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", "run the test script"),
                    TerminalCommandSpec("build", "build package"),
                    TerminalCommandSpec("start", "run the start script"),
                    TerminalCommandSpec("publish", "publish a package"),
                    TerminalCommandSpec("info", "show information about a package"),
                    TerminalCommandSpec("why", "show information about why a package is installed"),
                    TerminalCommandSpec("cache", "inspect the yarn cache directory"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--dev", "-D"), "save package to your devDependencies"),
                    TerminalOptionSpec(listOf("--peer", "-P"), "save package to your peerDependencies"),
                    TerminalOptionSpec(listOf("--exact", "-E"), "install package as exact version"),
                ),
        )

    fun bun(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "bun",
            description = "Fast all-in-one JavaScript runtime & toolkit",
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "run",
                        description = "run JavaScript and TypeScript files, executable packages, and package.json scripts",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", "run unit tests with Bun's test runner"),
                    TerminalCommandSpec("install", "install dependencies for a project", aliases = listOf("i")),
                    TerminalCommandSpec("add", "add a dependency to package.json", aliases = listOf("a")),
                    TerminalCommandSpec("remove", "remove a dependency from package.json", aliases = listOf("rm")),
                    TerminalCommandSpec("update", "update dependencies to their latest version"),
                    TerminalCommandSpec("build", "bundle TypeScript and JavaScript into a single file"),
                    TerminalCommandSpec("dev", "start a development server with live reload"),
                    TerminalCommandSpec("create", "create a new project from a template"),
                    TerminalCommandSpec("pm", "manage packages"),
                    TerminalCommandSpec("upgrade", "upgrade Bun to the latest version"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--watch"), "automatically restart the process on file changes"),
                    TerminalOptionSpec(listOf("--hot"), "enable hot module reloading"),
                    TerminalOptionSpec(
                        listOf("--cwd"),
                        "set current working directory",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )

    fun cargo(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cargo",
            description = "Rust package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("build", "compile the current package"),
                    TerminalCommandSpec("run", "run a binary or example of the local package"),
                    TerminalCommandSpec("test", "execute all unit and integration tests"),
                    TerminalCommandSpec("check", "analyze the current package and report errors"),
                    TerminalCommandSpec("clean", "remove artifacts that cargo has generated"),
                    TerminalCommandSpec("new", "create a new cargo package"),
                    TerminalCommandSpec("init", "create a new cargo package in an existing directory"),
                    TerminalCommandSpec("update", "update dependencies as recorded in the local lock file"),
                    TerminalCommandSpec("doc", "build this package's and its dependencies' documentation"),
                    TerminalCommandSpec("publish", "package and upload this package to the registry"),
                    TerminalCommandSpec("clippy", "checks a package to catch common mistakes and improve Rust code"),
                    TerminalCommandSpec("fmt", "formats all bin and lib files of the current crate"),
                    TerminalCommandSpec("add", "add dependencies to a manifest file"),
                    TerminalCommandSpec("remove", "remove dependencies from a manifest file", aliases = listOf("rm")),
                    TerminalCommandSpec("tree", "display a tree visualization of a dependency graph"),
                    TerminalCommandSpec("metadata", "output the resolved dependencies of a package"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-V"), "show version"),
                    TerminalOptionSpec(listOf("--verbose", "-v"), "use verbose output"),
                    TerminalOptionSpec(listOf("--quiet", "-q"), "do not print cargo log messages"),
                    TerminalOptionSpec(listOf("--release", "-r"), "build artifacts in release mode, with optimizations"),
                    TerminalOptionSpec(listOf("--all-targets"), "activate all available targets"),
                    TerminalOptionSpec(listOf("--workspace", "--all"), "process all packages in the workspace"),
                    TerminalOptionSpec(listOf("--features"), "space or comma separated list of features to activate", requiresValue = true),
                    TerminalOptionSpec(listOf("--all-features"), "activate all available features"),
                    TerminalOptionSpec(listOf("--no-default-features"), "do not activate the default feature"),
                    TerminalOptionSpec(
                        names = listOf("--manifest-path"),
                        description = "path to Cargo.toml",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(listOf("-p", "--package"), "package to operate on", requiresValue = true),
                ),
        )

    fun pip(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pip",
            description = "Python package installer",
            subcommands =
                listOf(
                    TerminalCommandSpec("install", "install packages"),
                    TerminalCommandSpec("uninstall", "uninstall packages"),
                    TerminalCommandSpec("list", "list installed packages"),
                    TerminalCommandSpec("show", "show information about installed packages"),
                    TerminalCommandSpec("search", "search PyPI for packages"),
                    TerminalCommandSpec("freeze", "output installed packages in requirements format"),
                    TerminalCommandSpec("wheel", "build wheels from your requirements"),
                    TerminalCommandSpec("cache", "inspect and manage pip's wheel cache"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-V"), "show version"),
                    TerminalOptionSpec(listOf("--verbose", "-v"), "give more output"),
                    TerminalOptionSpec(listOf("--quiet", "-q"), "give less output"),
                ),
        )

    fun go(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "go",
            description = "Go toolchain CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("build", "compile packages and dependencies"),
                    TerminalCommandSpec("run", "compile and run Go program"),
                    TerminalCommandSpec("test", "test packages"),
                    TerminalCommandSpec("fmt", "gofmt (reformat) package sources"),
                    TerminalCommandSpec("get", "add dependencies to current module and install them"),
                    TerminalCommandSpec("install", "compile and install packages and dependencies"),
                    TerminalCommandSpec("mod", "module maintenance"),
                    TerminalCommandSpec("clean", "remove object files and cached files"),
                    TerminalCommandSpec("doc", "show documentation for package or symbol"),
                    TerminalCommandSpec("vet", "report likely mistakes in packages"),
                    TerminalCommandSpec("version", "print Go version"),
                    TerminalCommandSpec("env", "print Go environment information"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-h"), "show help"),
                ),
        )

    fun gh(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "gh",
            description = "GitHub CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("pr", "manage pull requests"),
                    TerminalCommandSpec("issue", "manage issues"),
                    TerminalCommandSpec("repo", "manage repositories"),
                    TerminalCommandSpec("auth", "login, logout, and select active accounts"),
                    TerminalCommandSpec("run", "view details of workflow runs"),
                    TerminalCommandSpec("workflow", "view and run GitHub Actions workflows"),
                    TerminalCommandSpec("gist", "manage gists"),
                    TerminalCommandSpec("secret", "manage GitHub secrets"),
                    TerminalCommandSpec("api", "make an authenticated GitHub API request"),
                    TerminalCommandSpec("completion", "generate shell completion scripts"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(listOf("--version"), "show version"),
                ),
        )
}

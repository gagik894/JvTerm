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
 * Declarative command specification consumed by the in-process completion
 * engine.
 *
 * This is KetraTerm's stable internal model. External formats, including
 * Fig-style specs, should be imported into this representation instead of being
 * exposed to UI modules.
 *
 * @property name canonical command or subcommand token.
 * @property description short human-readable description.
 * @property aliases additional tokens that resolve to this command.
 * @property subcommands nested subcommands available after this command.
 * @property options options available at this command level.
 * @property positionalArguments ordered positional argument declarations. When
 * nonempty, they take precedence over the scalar positional metadata.
 * @property positionalArgumentPathKind file-system path kind accepted by bare
 * positional arguments after this command or subcommand.
 * @property positionalArgumentValueDomain dynamic host-owned value domain
 * accepted by bare positional arguments after this command or subcommand.
 * @property positionalArgumentHiddenPathPolicy hidden-entry policy used when
 * [positionalArgumentPathKind] accepts a path.
 * @property repeatableSubcommands whether multiple child subcommand tokens may
 * appear as sibling positional command values. This models task-style CLIs such
 * as Gradle, where `gradle clean build` is a sequence of tasks rather than
 * `build` being nested below `clean`.
 * @throws IllegalArgumentException if [name] or any alias is blank.
 */
data class TerminalCommandSpec
    @JvmOverloads
    constructor(
        val name: String,
        val description: String = "",
        val aliases: List<String> = emptyList(),
        val subcommands: List<TerminalCommandSpec> = emptyList(),
        val options: List<TerminalOptionSpec> = emptyList(),
        val positionalArguments: List<TerminalArgumentSpec> = emptyList(),
        val positionalArgumentPathKind: TerminalPathArgumentKind = TerminalPathArgumentKind.NONE,
        val positionalArgumentValueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
        val positionalArgumentHiddenPathPolicy: TerminalHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
        val repeatableSubcommands: Boolean = false,
    ) {
        init {
            require(name.isNotBlank()) { "name must not be blank" }
            require(aliases.none(String::isBlank)) { "aliases must not contain blank values" }
        }
    }

/**
 * Declarative metadata for one ordered positional command argument.
 *
 * @property name concise display label for the argument.
 * @property description short explanation shown with static value candidates.
 * @property isOptional whether callers may omit this argument.
 * @property isVariadic whether this declaration applies to every remaining
 * positional argument after its ordinal position.
 * @property pathKind filesystem path category accepted by this argument.
 * @property valueCandidates bounded static values accepted by this argument.
 * @property valueDomain dynamic host-owned value domain accepted by this argument.
 * @property hiddenPathPolicy hidden-entry policy used when [pathKind] accepts a path.
 * @throws IllegalArgumentException if [valueCandidates] contains a blank value.
 */
data class TerminalArgumentSpec
    @JvmOverloads
    constructor(
        val name: String = "",
        val description: String = "",
        val isOptional: Boolean = false,
        val isVariadic: Boolean = false,
        val pathKind: TerminalPathArgumentKind = TerminalPathArgumentKind.NONE,
        val valueCandidates: List<String> = emptyList(),
        val valueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
        val hiddenPathPolicy: TerminalHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
    ) {
        init {
            require(valueCandidates.none(String::isBlank)) { "valueCandidates must not contain blank values" }
        }
    }

/**
 * Declarative option or flag specification.
 *
 * @property names accepted option tokens, such as `--help` and `-h`.
 * @property description short human-readable description.
 * @property requiresValue whether this option consumes the following token as a
 * value.
 * @property valuePathKind file-system path kind accepted by the option's
 * separate value token when [requiresValue] is true.
 * @property valueCandidates static bounded option values, such as log levels or
 * output modes. Dynamic host-owned domains belong in host providers instead.
 * @property valueDomain dynamic host-owned value domain accepted by the option's
 * separate value token when [requiresValue] is true.
 * @property valueHiddenPathPolicy hidden-entry policy used when
 * [valuePathKind] accepts a path.
 * @property exclusiveGroupIds identifiers of mutually exclusive option groups.
 * Options that share any identifier cannot be suggested together once one has
 * already been entered before the completion cursor.
 * @throws IllegalArgumentException if names are empty, or names, values, or
 * exclusive-group identifiers contain blank entries.
 */
data class TerminalOptionSpec
    @JvmOverloads
    constructor(
        val names: List<String>,
        val description: String = "",
        val requiresValue: Boolean = false,
        val valuePathKind: TerminalPathArgumentKind = TerminalPathArgumentKind.NONE,
        val valueCandidates: List<String> = emptyList(),
        val valueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
        val valueHiddenPathPolicy: TerminalHiddenPathPolicy = TerminalHiddenPathPolicy.DEFAULT,
        val exclusiveGroupIds: List<String> = emptyList(),
    ) {
        init {
            require(names.isNotEmpty()) { "names must not be empty" }
            require(names.none(String::isBlank)) { "names must not contain blank values" }
            require(valueCandidates.none(String::isBlank)) { "valueCandidates must not contain blank values" }
            require(exclusiveGroupIds.none(String::isBlank)) { "exclusiveGroupIds must not contain blank values" }
        }
    }

/**
 * File-system path category accepted by a command positional argument or option
 * value.
 */
enum class TerminalPathArgumentKind {
    /** The argument has no known path semantics. */
    NONE,

    /** The argument may reference either a file or directory. */
    FILE_OR_DIRECTORY,

    /** The argument should reference a directory. */
    DIRECTORY,

    /** The argument should reference a regular file. */
    FILE,
}

/**
 * Policy for hidden filesystem entries exposed by one path-taking argument.
 */
enum class TerminalHiddenPathPolicy {
    /** Hide dot-prefixed entries for an empty prefix, but show them after `.` is typed. */
    DEFAULT,

    /** Include dot-prefixed entries even when the user has not typed a prefix. */
    INCLUDE,

    /** Never return dot-prefixed entries for this argument. */
    EXCLUDE,
}

/**
 * Stable identifier for a dynamic value domain supplied by host-owned
 * completion providers.
 *
 * The shared completion engine uses this value only for context-aware ranking.
 * It does not fetch domain values itself. Standalone and plugin integrations may
 * provide candidates with matching domains using local process caches, IDE
 * services, project indexes, or other host-owned data sources.
 *
 * @property id stable lowercase domain id.
 * @throws IllegalArgumentException if [id] is blank or contains uppercase characters.
 */
data class TerminalCompletionValueDomain(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(id == id.lowercase()) { "id must be lowercase, was $id" }
    }

    companion object {
        /** No known dynamic value domain. */
        @JvmField
        val NONE: TerminalCompletionValueDomain = TerminalCompletionValueDomain("none")

        /** Git branch or ref name. */
        @JvmField
        val GIT_BRANCH: TerminalCompletionValueDomain = TerminalCompletionValueDomain("git.branch")

        /** Git commit object identifier. */
        @JvmField
        val GIT_COMMIT: TerminalCompletionValueDomain = TerminalCompletionValueDomain("git.commit")

        /** Docker CLI context name. */
        @JvmField
        val DOCKER_CONTEXT: TerminalCompletionValueDomain = TerminalCompletionValueDomain("docker.context")

        /** Kubernetes namespace name. */
        @JvmField
        val KUBERNETES_NAMESPACE: TerminalCompletionValueDomain = TerminalCompletionValueDomain("kubernetes.namespace")

        /** Kubernetes kubeconfig context name. */
        @JvmField
        val KUBERNETES_CONTEXT: TerminalCompletionValueDomain = TerminalCompletionValueDomain("kubernetes.context")

        /** npm package script name. */
        @JvmField
        val NPM_SCRIPT: TerminalCompletionValueDomain = TerminalCompletionValueDomain("npm.script")

        /** AWS CLI profile name. */
        @JvmField
        val AWS_PROFILE: TerminalCompletionValueDomain = TerminalCompletionValueDomain("aws.profile")

        /** AWS region name. */
        @JvmField
        val AWS_REGION: TerminalCompletionValueDomain = TerminalCompletionValueDomain("aws.region")
    }
}

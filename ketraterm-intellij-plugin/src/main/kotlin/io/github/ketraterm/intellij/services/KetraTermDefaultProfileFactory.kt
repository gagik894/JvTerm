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
package io.github.ketraterm.intellij.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettingsNormalizer
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileRegistry
import java.nio.file.Path

/**
 * Creates launch profiles for IntelliJ-hosted local terminal tabs.
 */
internal object KetraTermDefaultProfileFactory {
    /**
     * Creates the default profile for [project].
     *
     * The shell command comes from the host-neutral profile registry. The
     * IntelliJ-specific contribution is only the initial working directory.
     *
     * @param project current IntelliJ project.
     * @return local terminal launch profile.
     */
    fun defaultProfile(
        project: Project,
        settings: KetraTermIntellijSettings.State = KetraTermIntellijSettings.getInstance().state,
    ): TerminalProfile = defaultProfile(project.guessProjectDir()?.path ?: project.basePath, settings)

    /**
     * Creates a default profile for a nullable project path.
     *
     * @param basePath project base path, or `null` when the IDE has no local project path.
     * @param settings normalized IntelliJ terminal settings.
     * @return local terminal launch profile.
     */
    fun defaultProfile(
        basePath: String?,
        settings: KetraTermIntellijSettings.State = KetraTermIntellijSettings.State(),
    ): TerminalProfile {
        val workingDirectory = workingDirectory(basePath, settings.startDirectory)
        return TerminalProfileRegistry()
            .configuredProfile(settings.shellPath, workingDirectory)
            .copy(
                displayName = settings.defaultTabName,
                environment = KetraTermIntellijSettingsNormalizer.parseEnvironmentVariables(settings.environmentVariables),
            )
    }

    /**
     * Applies IntelliJ launch settings to a shell profile selected from the shared registry.
     *
     * @param project current IntelliJ project.
     * @param profile selected discovered shell profile.
     * @param settings normalized IntelliJ terminal settings.
     * @return launch profile with IDE working directory and environment settings applied.
     */
    fun profileForSelectedShell(
        project: Project,
        profile: TerminalProfile,
        settings: KetraTermIntellijSettings.State = KetraTermIntellijSettings.getInstance().state,
    ): TerminalProfile = profileForSelectedShell(project.guessProjectDir()?.path ?: project.basePath, profile, settings)

    /**
     * Applies IntelliJ launch settings to a selected shell profile.
     *
     * @param basePath project base path, or `null` when the IDE has no local project path.
     * @param profile selected discovered shell profile.
     * @param settings normalized IntelliJ terminal settings.
     * @return launch profile with IDE working directory and environment settings applied.
     */
    fun profileForSelectedShell(
        basePath: String?,
        profile: TerminalProfile,
        settings: KetraTermIntellijSettings.State = KetraTermIntellijSettings.State(),
    ): TerminalProfile =
        profile.copy(
            workingDirectory = workingDirectory(basePath, settings.startDirectory),
            environment = KetraTermIntellijSettingsNormalizer.parseEnvironmentVariables(settings.environmentVariables),
        )

    private fun workingDirectory(
        basePath: String?,
        configuredStartDirectory: String,
    ): Path {
        if (configuredStartDirectory.isNotBlank()) {
            return pathOrUserHome(configuredStartDirectory)
        }
        return if (basePath.isNullOrBlank()) {
            userHome()
        } else {
            pathOrUserHome(basePath)
        }
    }

    private fun pathOrUserHome(path: String): Path =
        try {
            Path.of(path)
        } catch (_: RuntimeException) {
            userHome()
        }

    private fun userHome(): Path = Path.of(System.getProperty("user.home"))
}

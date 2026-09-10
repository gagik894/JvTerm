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
package io.github.ketraterm.app

import io.github.ketraterm.workspace.TerminalProfileRegistry
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Starts the KetraTerm standalone terminal application.
 */
fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        KetraTermApp.start(args.toList())
    }
}

private object KetraTermApp {
    fun start(args: List<String>) {
        KetraTermLookAndFeel.install()

        val settings =
            io.github.ketraterm.app.config
                .KetraTermSettings()
        io.github.ketraterm.app.ui.Chrome
            .applyPalette(settings.current().palette)
        val profileRegistry =
            TerminalProfileRegistry()
        val windowFactory =
            io.github.ketraterm.app.ui
                .WindowFactory(settings, profileRegistry)
        val window = windowFactory.createWindow()
        val frame = window.frame

        // The initial profile carries the CLI shell command when args are given,
        // or defaults to the first available profile. Either way, stamp the
        // configured startDirectory so the user's preference is always honoured
        // for the first tab (profile.workingDirectory is null for all built-in
        // profiles, so this only replaces the null default).
        val initialWorkingDirectory =
            settings.config.startDirectory
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { Path.of(it) }.getOrNull() }
        val initialProfile =
            if (args.isNotEmpty()) {
                profileRegistry.initialProfile(args).let { profile ->
                    if (initialWorkingDirectory != null && profile.workingDirectory == null) {
                        profile.copy(workingDirectory = initialWorkingDirectory)
                    } else {
                        profile
                    }
                }
            } else {
                profileRegistry.configuredProfile(
                    shellPath = settings.config.shellPath,
                    workingDirectory = initialWorkingDirectory,
                )
            }
        window.tabManager.openTab(initialProfile)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        window.tabManager.selectedPane?.requestFocus()
    }
}

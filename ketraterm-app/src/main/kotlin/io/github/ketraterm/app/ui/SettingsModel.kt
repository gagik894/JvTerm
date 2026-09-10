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
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.workspace.TerminalProfileRegistry
import io.github.ketraterm.workspace.config.TerminalConfig

/** Owns the settings dialog's saved baseline; drafts use the shared validated configuration. */
internal class SettingsModel(
    private val settings: KetraTermSettings,
    private val profileRegistry: TerminalProfileRegistry,
) {
    var initialUiState: TerminalConfig = settings.config
        private set

    fun hasChanges(uiState: TerminalConfig): Boolean = uiState != initialUiState

    /** Validates and persists a complete draft; called by the dialog's save worker. */
    fun applyChanges(uiState: TerminalConfig) {
        val validated =
            uiState.copy(
                shellPath =
                    if (profileRegistry.isValidShellPath(uiState.shellPath)) {
                        uiState.shellPath
                    } else {
                        TerminalConfig.DEFAULT_SHELL_PATH
                    },
            )
        settings.update(validated)
        initialUiState = settings.config
    }
}

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
import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.TerminalProfileRegistry
import io.github.ketraterm.workspace.config.TerminalConfig

internal class SettingsModel(
    private val settings: KetraTermSettings,
    private val profileRegistry: TerminalProfileRegistry,
) {
    var initialUiState: SettingsState = getSettingsState()
        private set

    fun getSettingsState(): SettingsState =
        SettingsState(
            theme = settings.theme.name,
            treatAmbiguousAsWide = settings.treatAmbiguousAsWide,
            fontFamily = settings.fontFamily,
            fontSize = settings.fontSize,
            columns = settings.columns,
            rows = settings.rows,
            cursorBlinkMillis = settings.cursorBlinkMillis,
            useSystemFallbackFonts = settings.useSystemFallbackFonts,
            cursorShape = settings.cursorShape,
            shellPath = settings.shellPath,
            startDirectory = settings.startDirectory,
            audibleBell = settings.audibleBell,
            visualBell = settings.visualBell,
            pasteOnMiddleClick = settings.pasteOnMiddleClick,
            pasteSanitizationPolicy = settings.pasteSanitizationPolicy,
            scrollbackLines = settings.scrollbackLines,
            lineHeight = settings.lineHeight.toDouble(),
            shellRequestResizeWindow = settings.shellRequestResizeWindow,
            shellRequestWindowManipulation = settings.shellRequestWindowManipulation,
            clipboardLocalWrite = settings.clipboardLocalWrite,
            clipboardRemoteWrite = settings.clipboardRemoteWrite,
            clipboardRead = settings.clipboardRead,
            clipboardMaxDecodedBytes = settings.clipboardMaxDecodedBytes,
            titleLocalPermission = settings.titleLocalPermission,
            titleRemotePermission = settings.titleRemotePermission,
            scrollOnOutput = settings.scrollOnOutput,
        )

    fun hasChanges(uiState: SettingsState): Boolean = uiState != initialUiState

    fun isValidShellPath(path: String): Boolean = profileRegistry.isValidShellPath(path)

    fun applyChanges(
        uiState: SettingsState,
        onApply: () -> Unit,
    ) {
        val finalShellPath =
            if (isValidShellPath(uiState.shellPath)) {
                uiState.shellPath
            } else {
                TerminalConfig.DEFAULT_SHELL_PATH
            }

        settings.shellPath = finalShellPath
        settings.startDirectory = uiState.startDirectory
        settings.audibleBell = uiState.audibleBell
        settings.visualBell = uiState.visualBell
        settings.pasteOnMiddleClick = uiState.pasteOnMiddleClick
        settings.pasteSanitizationPolicy = uiState.pasteSanitizationPolicy
        settings.scrollbackLines = uiState.scrollbackLines
        settings.lineHeight = uiState.lineHeight.toFloat()
        settings.shellRequestResizeWindow = uiState.shellRequestResizeWindow
        settings.shellRequestWindowManipulation = uiState.shellRequestWindowManipulation

        settings.fontFamily = uiState.fontFamily
        settings.fontSize = uiState.fontSize
        settings.columns = uiState.columns
        settings.rows = uiState.rows
        settings.treatAmbiguousAsWide = uiState.treatAmbiguousAsWide
        settings.useSystemFallbackFonts = uiState.useSystemFallbackFonts
        settings.cursorBlinkMillis = uiState.cursorBlinkMillis
        settings.cursorShape = uiState.cursorShape
        settings.clipboardLocalWrite = uiState.clipboardLocalWrite
        settings.clipboardRemoteWrite = uiState.clipboardRemoteWrite
        settings.clipboardRead = uiState.clipboardRead
        settings.clipboardMaxDecodedBytes = uiState.clipboardMaxDecodedBytes
        settings.titleLocalPermission = uiState.titleLocalPermission
        settings.titleRemotePermission = uiState.titleRemotePermission
        settings.scrollOnOutput = uiState.scrollOnOutput

        settings.theme = TerminalTheme.entries.firstOrNull { it.name == uiState.theme } ?: TerminalTheme.TOKYO_NIGHT

        initialUiState = getSettingsState()
        onApply()
    }
}

internal data class SettingsState(
    val theme: String,
    val treatAmbiguousAsWide: Boolean,
    val fontFamily: String,
    val fontSize: Int,
    val columns: Int,
    val rows: Int,
    val cursorBlinkMillis: Int,
    val useSystemFallbackFonts: Boolean,
    val cursorShape: String,
    val shellPath: String,
    val startDirectory: String,
    val audibleBell: Boolean,
    val visualBell: Boolean,
    val pasteOnMiddleClick: Boolean,
    val pasteSanitizationPolicy: io.github.ketraterm.input.policy.PasteSanitizationPolicy,
    val scrollbackLines: Int,
    val lineHeight: Double,
    val shellRequestResizeWindow: Boolean,
    val shellRequestWindowManipulation: Boolean,
    val clipboardLocalWrite: TerminalClipboardPermission,
    val clipboardRemoteWrite: TerminalClipboardPermission,
    val clipboardRead: TerminalClipboardPermission,
    val clipboardMaxDecodedBytes: Int,
    val titleLocalPermission: TerminalTitlePermission,
    val titleRemotePermission: TerminalTitlePermission,
    val scrollOnOutput: Boolean,
)

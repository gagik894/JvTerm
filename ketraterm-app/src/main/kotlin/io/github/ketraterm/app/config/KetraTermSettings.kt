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
package io.github.ketraterm.app.config

import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.host.*
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.config.TerminalConfig
import io.github.ketraterm.workspace.config.TerminalWorkspaceConfigManager
import java.awt.Font
import java.nio.file.Path
import java.util.*

/**
 * Standalone application settings model integrated with TOML configuration.
 *
 * It acts as the bridge between host-neutral persisted [TerminalConfig] and
 * host-specific Swing settings. Changes to configuration parameters are immediately
 * saved to disk.
 */
internal class KetraTermSettings(
    private val configManager: TerminalWorkspaceConfigManager = TerminalWorkspaceConfigManager.getDefault(),
) {
    private var config: TerminalConfig = configManager.load()

    var theme: TerminalTheme
        get() =
            TerminalTheme.entries.firstOrNull {
                it.name.lowercase(Locale.ROOT).replace('_', '-') == config.theme
            } ?: TerminalTheme.ONE_DARK
        set(value) {
            updateConfig(config.copy(theme = value.name.lowercase(Locale.ROOT).replace('_', '-')))
        }

    var treatAmbiguousAsWide: Boolean
        get() = config.treatAmbiguousAsWide
        set(value) {
            updateConfig(config.copy(treatAmbiguousAsWide = value))
        }

    var columns: Int
        get() = config.columns
        set(value) {
            updateConfig(config.copy(columns = value))
        }

    var rows: Int
        get() = config.rows
        set(value) {
            updateConfig(config.copy(rows = value))
        }

    var fontFamily: String
        get() = config.fontFamily
        set(value) {
            updateConfig(config.copy(fontFamily = value))
        }

    var fontSize: Int
        get() = config.fontSize
        set(value) {
            updateConfig(config.copy(fontSize = value))
        }

    var useSystemFallbackFonts: Boolean
        get() = config.useSystemFallbackFonts
        set(value) {
            updateConfig(config.copy(useSystemFallbackFonts = value))
        }

    var cursorBlinkMillis: Int
        get() = config.cursorBlinkMillis
        set(value) {
            updateConfig(config.copy(cursorBlinkMillis = value))
        }

    var cursorShape: String
        get() = config.cursorShape
        set(value) {
            updateConfig(config.copy(cursorShape = value))
        }

    var shellPath: String
        get() = config.shellPath
        set(value) {
            updateConfig(config.copy(shellPath = value))
        }

    var startDirectory: String
        get() = config.startDirectory
        set(value) {
            updateConfig(config.copy(startDirectory = value))
        }

    var audibleBell: Boolean
        get() = config.audibleBell
        set(value) {
            updateConfig(config.copy(audibleBell = value))
        }

    var visualBell: Boolean
        get() = config.visualBell
        set(value) {
            updateConfig(config.copy(visualBell = value))
        }

    var pasteOnMiddleClick: Boolean
        get() = config.pasteOnMiddleClick
        set(value) {
            updateConfig(config.copy(pasteOnMiddleClick = value))
        }

    var pasteSanitizationPolicy: io.github.ketraterm.input.policy.PasteSanitizationPolicy
        get() = config.pasteSanitizationPolicy
        set(value) {
            updateConfig(config.copy(pasteSanitizationPolicy = value))
        }

    var scrollbackLines: Int
        get() = config.scrollbackLines
        set(value) {
            updateConfig(config.copy(scrollbackLines = value))
        }

    var lineHeight: Float
        get() = config.lineHeight
        set(value) {
            updateConfig(config.copy(lineHeight = value))
        }

    var shellRequestResizeWindow: Boolean
        get() = config.shellRequestResizeWindow
        set(value) {
            updateConfig(config.copy(shellRequestResizeWindow = value))
        }

    var shellRequestWindowManipulation: Boolean
        get() = config.shellRequestWindowManipulation
        set(value) {
            updateConfig(config.copy(shellRequestWindowManipulation = value))
        }

    var desktopNotificationsEnabled: Boolean
        get() = config.desktopNotificationsEnabled
        set(value) {
            updateConfig(config.copy(desktopNotificationsEnabled = value))
        }

    var persistentSuggestionLearningEnabled: Boolean
        get() = config.persistentSuggestionLearningEnabled
        set(value) {
            updateConfig(config.copy(persistentSuggestionLearningEnabled = value))
        }

    var smartSuggestionsEnabled: Boolean
        get() = config.smartSuggestionsEnabled
        set(value) {
            updateConfig(config.copy(smartSuggestionsEnabled = value))
        }

    var shellSuggestionsEnabled: Boolean
        get() = config.shellSuggestionsEnabled
        set(value) {
            updateConfig(config.copy(shellSuggestionsEnabled = value))
        }

    var acceptSelectedSuggestionWithEnter: Boolean
        get() = config.acceptSelectedSuggestionWithEnter
        set(value) {
            updateConfig(config.copy(acceptSelectedSuggestionWithEnter = value))
        }

    var scrollOnOutput: Boolean
        get() = config.scrollOnOutput
        set(value) {
            updateConfig(config.copy(scrollOnOutput = value))
        }

    var clipboardLocalWrite: TerminalClipboardPermission
        get() = config.clipboardLocalWrite
        set(value) {
            updateConfig(config.copy(clipboardLocalWrite = value))
        }

    var clipboardRemoteWrite: TerminalClipboardPermission
        get() = config.clipboardRemoteWrite
        set(value) {
            updateConfig(config.copy(clipboardRemoteWrite = value))
        }

    var clipboardRead: TerminalClipboardPermission
        get() = config.clipboardRead
        set(value) {
            updateConfig(config.copy(clipboardRead = value))
        }

    var clipboardMaxDecodedBytes: Int
        get() = config.clipboardMaxDecodedBytes
        set(value) {
            updateConfig(config.copy(clipboardMaxDecodedBytes = value))
        }

    var titleLocalPermission: TerminalTitlePermission
        get() = config.titleLocalPermission
        set(value) {
            updateConfig(config.copy(titleLocalPermission = value))
        }

    var titleRemotePermission: TerminalTitlePermission
        get() = config.titleRemotePermission
        set(value) {
            updateConfig(config.copy(titleRemotePermission = value))
        }

    /** Path for the compact persisted command-completion stats index. */
    val commandCompletionStatsPath: Path
        get() = configManager.configPath.resolveSibling(TerminalCompletionLearningCoordinator.currentFileName())

    fun current(): SwingSettings {
        val resolvedFamily = SwingSettings.resolveFontFamily(config.fontFamily)
        return SwingSettings(
            font = Font(resolvedFamily, Font.PLAIN, config.fontSize),
            columns = config.columns,
            rows = config.rows,
            palette = theme.createPalette(),
            treatAmbiguousAsWide = config.treatAmbiguousAsWide,
            cursorBlinkMillis = config.cursorBlinkMillis,
            useSystemFallbackFonts = config.useSystemFallbackFonts,
            visualBellEnabled = config.visualBell,
            pasteSanitizationPolicy = config.pasteSanitizationPolicy,
            cursorShape = parseCursorShape(config.cursorShape),
            scrollbackLines = config.scrollbackLines,
            lineHeight = config.lineHeight,
            shellRequestResizeWindow = config.shellRequestResizeWindow,
            shellRequestWindowManipulation = config.shellRequestWindowManipulation,
            smartSuggestionsEnabled = config.smartSuggestionsEnabled,
            shellSuggestionsEnabled = config.shellSuggestionsEnabled,
            acceptSelectedSuggestionWithEnter = config.acceptSelectedSuggestionWithEnter,
            scrollOnOutput = config.scrollOnOutput,
        )
    }

    fun createHostPolicy(command: List<String>): HostPolicy {
        val isRemote = command.firstOrNull()?.let(::isSshExecutable) == true
        val clipboardOrigin = if (isRemote) TerminalClipboardOrigin.REMOTE else TerminalClipboardOrigin.LOCAL
        val titleOrigin = if (isRemote) TerminalTitleOrigin.REMOTE else TerminalTitleOrigin.LOCAL

        return HostPolicy(
            titlePolicy =
                TerminalTitlePolicy(
                    origin = titleOrigin,
                    localPermission = config.titleLocalPermission,
                    remotePermission = config.titleRemotePermission,
                ),
            clipboardPolicy =
                TerminalClipboardPolicy(
                    origin = clipboardOrigin,
                    localWritePermission = config.clipboardLocalWrite,
                    remoteWritePermission = config.clipboardRemoteWrite,
                    readPermission = config.clipboardRead,
                    maxDecodedBytes = config.clipboardMaxDecodedBytes,
                ),
            windowManipulationPolicy =
                if (config.shellRequestResizeWindow || config.shellRequestWindowManipulation) {
                    HostControlPolicy.ALLOW
                } else {
                    HostControlPolicy.DENY
                },
        )
    }

    private fun parseCursorShape(shape: String): io.github.ketraterm.render.api.TerminalRenderCursorShape =
        when (shape.lowercase(Locale.ROOT)) {
            "beam" -> io.github.ketraterm.render.api.TerminalRenderCursorShape.BAR
            "underline" -> io.github.ketraterm.render.api.TerminalRenderCursorShape.UNDERLINE
            else -> io.github.ketraterm.render.api.TerminalRenderCursorShape.BLOCK
        }

    private fun isSshExecutable(command: String): Boolean {
        val executable =
            command
                .trim()
                .trim('"')
                .replace('\\', '/')
                .substringAfterLast('/')
                .lowercase(Locale.ROOT)
        return executable == "ssh" || executable == "ssh.exe"
    }

    private fun updateConfig(newConfig: TerminalConfig) {
        config = newConfig
        configManager.save(newConfig)
    }
}

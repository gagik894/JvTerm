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
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * Standalone application settings model integrated with TOML configuration.
 *
 * It acts as the bridge between host-neutral persisted [TerminalConfig] and
 * host-specific Swing settings. Each update saves one complete immutable snapshot
 * before publishing it to consumers on the Swing EDT.
 */
internal class KetraTermSettings(
    private val configManager: TerminalWorkspaceConfigManager = TerminalWorkspaceConfigManager.getDefault(),
    private val saveConfig: (TerminalConfig) -> Unit = configManager::save,
) {
    @Volatile
    var config: TerminalConfig = configManager.load()
        private set

    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    private val updateLock = Any()

    val theme: TerminalTheme
        get() = TerminalTheme.fromId(config.theme) ?: TerminalTheme.ONE_DARK

    /** Path for the compact persisted command-completion stats index. */
    val commandCompletionStatsPath: Path
        get() = configManager.configPath.resolveSibling(TerminalCompletionLearningCoordinator.currentFileName())

    fun current(): SwingSettings {
        val config = config
        val resolvedFamily = SwingSettings.resolveFontFamily(config.fontFamily)
        return SwingSettings(
            font = Font(resolvedFamily, Font.PLAIN, config.fontSize),
            columns = config.columns,
            rows = config.rows,
            palette = (TerminalTheme.fromId(config.theme) ?: TerminalTheme.ONE_DARK).createPalette(),
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
        val config = config
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

    /** Registers a consumer notified on the EDT after a successfully persisted update. */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners -= listener
    }

    /**
     * Saves and publishes one validated snapshot. Call off the EDT because saving blocks.
     * A failed save leaves the active snapshot unchanged. Equal updates do no work.
     */
    fun update(newConfig: TerminalConfig) {
        check(!SwingUtilities.isEventDispatchThread()) { "Settings must be saved off the EDT" }
        synchronized(updateLock) {
            if (config == newConfig) return
            saveConfig(newConfig)
            SwingUtilities.invokeAndWait {
                config = newConfig
                var notificationFailure: Exception? = null
                for (listener in changeListeners) {
                    try {
                        listener()
                    } catch (failure: Exception) {
                        val previous = notificationFailure
                        if (previous == null) {
                            notificationFailure = failure
                        } else {
                            previous.addSuppressed(failure)
                        }
                    }
                }
                // Persistence already succeeded. Report consumer failures separately from saving.
                notificationFailure?.let { failure -> SwingUtilities.invokeLater { throw failure } }
            }
        }
    }
}

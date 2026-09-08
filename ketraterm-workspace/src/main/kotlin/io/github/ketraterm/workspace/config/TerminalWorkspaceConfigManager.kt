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
package io.github.ketraterm.workspace.config

import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

private const val SUGGESTION_LEARNING_PERSISTENCE_KEY = "suggestion_learning_persistence_enabled"

/**
 * Manages loading and saving the [TerminalConfig] TOML file.
 *
 * It resolves standard OS-specific directories for config files unless overridden
 * by a system property or environment variable.
 *
 * @property configPath the path to the configuration TOML file on disk.
 */
class TerminalWorkspaceConfigManager(
    val configPath: Path,
) {
    /**
     * Loads the configuration from the TOML file.
     *
     * If the file does not exist, it creates a default configuration file with comments
     * and returns the default settings. If the file is invalid or unreadable, it falls back
     * gracefully to default settings.
     *
     * @return the loaded [TerminalConfig] instance.
     */
    fun load(): TerminalConfig {
        if (!Files.exists(configPath)) {
            val defaultConfig = TerminalConfig()
            saveDefaults(defaultConfig)
            return defaultConfig
        }

        return try {
            val content = Files.readString(configPath)
            val parsed = TomlParser.parse(content)

            val window = parsed["window"] ?: emptyMap()
            val font = parsed["font"] ?: emptyMap()
            val themeSection = parsed["theme"] ?: emptyMap()
            val behavior = parsed["behavior"] ?: emptyMap()
            val shell = parsed["shell"] ?: emptyMap()
            val security = parsed["security"] ?: emptyMap()

            val default = TerminalConfig()

            val theme = themeSection["name"] ?: default.theme
            val shellPath = shell["path"] ?: default.shellPath
            val startDirectory = shell["start_directory"] ?: default.startDirectory
            val treatAmbiguousAsWide =
                behavior["treat_ambiguous_as_wide"]?.toBooleanStrictOrNull()
                    ?: default.treatAmbiguousAsWide
            val fontFamily = font["family"] ?: default.fontFamily
            val fontSize =
                parseIntSetting(
                    raw = font["size"],
                    defaultValue = default.fontSize,
                    min = TerminalConfig.FONT_SIZE_MIN,
                    max = TerminalConfig.FONT_SIZE_MAX,
                )
            val lineHeight =
                parseFloatSetting(
                    raw = font["line_height"],
                    defaultValue = default.lineHeight,
                    min = TerminalConfig.LINE_HEIGHT_MIN,
                    max = TerminalConfig.LINE_HEIGHT_MAX,
                )
            val columns =
                parseIntSetting(
                    raw = window["columns"],
                    defaultValue = default.columns,
                    min = TerminalConfig.COLUMNS_MIN,
                    max = TerminalConfig.COLUMNS_MAX,
                )
            val rows =
                parseIntSetting(
                    raw = window["rows"],
                    defaultValue = default.rows,
                    min = TerminalConfig.ROWS_MIN,
                    max = TerminalConfig.ROWS_MAX,
                )
            val cursorBlinkMillis =
                parseIntSetting(
                    raw = behavior["cursor_blink_millis"],
                    defaultValue = default.cursorBlinkMillis,
                    min = TerminalConfig.CURSOR_BLINK_MIN,
                    max = TerminalConfig.CURSOR_BLINK_MAX,
                )
            val useSystemFallbackFonts =
                font["use_system_fallback_fonts"]?.toBooleanStrictOrNull()
                    ?: default.useSystemFallbackFonts
            val cursorShape = behavior["cursor_shape"] ?: default.cursorShape
            val audibleBell = behavior["audible_bell"]?.toBooleanStrictOrNull() ?: default.audibleBell
            val visualBell = behavior["visual_bell"]?.toBooleanStrictOrNull() ?: default.visualBell
            val pasteOnMiddleClick = behavior["paste_on_middle_click"]?.toBooleanStrictOrNull() ?: default.pasteOnMiddleClick
            val pasteSanitizationPolicy =
                parsePasteSanitizationPolicy(
                    behavior["paste_sanitization"],
                    default.pasteSanitizationPolicy,
                )
            val shellRequestResizeWindow =
                behavior["shell_request_resize_window"]?.toBooleanStrictOrNull() ?: default.shellRequestResizeWindow
            val shellRequestWindowManipulation =
                behavior["shell_request_window_manipulation"]?.toBooleanStrictOrNull() ?: default.shellRequestWindowManipulation
            val desktopNotificationsEnabled =
                behavior["desktop_notifications_enabled"]?.toBooleanStrictOrNull() ?: default.desktopNotificationsEnabled
            val shellSuggestionsEnabled =
                behavior["shell_suggestions_enabled"]?.toBooleanStrictOrNull() ?: default.shellSuggestionsEnabled
            val acceptSelectedSuggestionWithEnter =
                behavior["accept_selected_suggestion_with_enter"]?.toBooleanStrictOrNull()
                    ?: default.acceptSelectedSuggestionWithEnter
            val persistentSuggestionLearningEnabled =
                behavior[SUGGESTION_LEARNING_PERSISTENCE_KEY]?.toBooleanStrictOrNull()
                    ?: default.persistentSuggestionLearningEnabled
            val scrollOnOutput =
                behavior["scroll_on_output"]?.toBooleanStrictOrNull()
                    ?: default.scrollOnOutput
            val scrollbackLines =
                parseIntSetting(
                    raw = window["scrollback_lines"],
                    defaultValue = default.scrollbackLines,
                    min = TerminalConfig.SCROLLBACK_MIN,
                    max = TerminalConfig.SCROLLBACK_MAX,
                )
            val clipboardLocalWrite =
                parseClipboardPermission(
                    raw = security["clipboard_local_write"],
                    defaultValue = default.clipboardLocalWrite,
                )
            val clipboardRemoteWrite =
                parseClipboardPermission(
                    raw = security["clipboard_remote_write"],
                    defaultValue = default.clipboardRemoteWrite,
                )
            val clipboardRead =
                parseClipboardPermission(
                    raw = security["clipboard_read"],
                    defaultValue = default.clipboardRead,
                )
            val clipboardMaxDecodedBytes =
                parseIntSetting(
                    raw = security["clipboard_max_decoded_bytes"],
                    defaultValue = default.clipboardMaxDecodedBytes,
                    min = 0,
                    max = Int.MAX_VALUE,
                )
            val titleLocalPermission =
                parseTitlePermission(
                    raw = security["title_local_permission"],
                    defaultValue = default.titleLocalPermission,
                )
            val titleRemotePermission =
                parseTitlePermission(
                    raw = security["title_remote_permission"],
                    defaultValue = default.titleRemotePermission,
                )

            val cleanTheme = if (theme.isNotBlank()) theme else default.theme
            val cleanFontFamily = if (fontFamily.isNotBlank()) fontFamily else default.fontFamily
            val cleanCursorShape = if (cursorShape.isNotBlank()) cursorShape else default.cursorShape
            val cleanShellPath = if (shellPath.isNotBlank()) shellPath else default.shellPath

            TerminalConfig(
                theme = cleanTheme,
                treatAmbiguousAsWide = treatAmbiguousAsWide,
                fontFamily = cleanFontFamily,
                fontSize = fontSize,
                columns = columns,
                rows = rows,
                cursorBlinkMillis = cursorBlinkMillis,
                useSystemFallbackFonts = useSystemFallbackFonts,
                cursorShape = cleanCursorShape,
                shellPath = cleanShellPath,
                startDirectory = startDirectory,
                audibleBell = audibleBell,
                visualBell = visualBell,
                pasteOnMiddleClick = pasteOnMiddleClick,
                pasteSanitizationPolicy = pasteSanitizationPolicy,
                scrollbackLines = scrollbackLines,
                lineHeight = lineHeight,
                shellRequestResizeWindow = shellRequestResizeWindow,
                shellRequestWindowManipulation = shellRequestWindowManipulation,
                desktopNotificationsEnabled = desktopNotificationsEnabled,
                smartSuggestionsEnabled = behavior["smart_suggestions_enabled"]?.toBooleanStrictOrNull() ?: default.smartSuggestionsEnabled,
                shellSuggestionsEnabled = shellSuggestionsEnabled,
                acceptSelectedSuggestionWithEnter = acceptSelectedSuggestionWithEnter,
                persistentSuggestionLearningEnabled = persistentSuggestionLearningEnabled,
                clipboardLocalWrite = clipboardLocalWrite,
                clipboardRemoteWrite = clipboardRemoteWrite,
                clipboardRead = clipboardRead,
                clipboardMaxDecodedBytes = clipboardMaxDecodedBytes,
                titleLocalPermission = titleLocalPermission,
                titleRemotePermission = titleRemotePermission,
                scrollOnOutput = scrollOnOutput,
            )
        } catch (failure: IOException) {
            System.err.println("Using default configuration; could not read $configPath: ${failure.message}")
            TerminalConfig()
        } catch (_: IllegalArgumentException) {
            try {
                val backupPath = configPath.resolveSibling("${configPath.fileName}.broken")
                Files.move(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                System.err.println("Configuration file was malformed and has been backed up to $backupPath")
            } catch (ioe: IOException) {
                System.err.println("Failed to back up malformed configuration file: ${ioe.message}")
                return TerminalConfig()
            }
            val defaultConfig = TerminalConfig()
            saveDefaults(defaultConfig)
            defaultConfig
        }
    }

    /**
     * Atomically replaces the configuration file with one complete TOML snapshot.
     *
     * Creates missing parent directories and stages the replacement beside the destination.
     * If writing or replacing fails, the previous file stays intact and the failure propagates.
     * The filesystem must support atomic replacement; there is no destructive fallback.
     *
     * @throws IOException if the snapshot cannot be persisted.
     */
    fun save(config: TerminalConfig) {
        val destination = configPath.toAbsolutePath()
        val parent = destination.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${destination.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, generateToml(config))
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun saveDefaults(config: TerminalConfig) {
        try {
            save(config)
        } catch (failure: IOException) {
            System.err.println("Using default configuration; could not save $configPath: ${failure.message}")
        }
    }

    private fun generateToml(config: TerminalConfig): String =
        """
        # KetraTerm Terminal Emulator Configuration File
        # Power users can edit this file directly to customize behavior.
        # Changes will take effect on next application launch.

        [shell]
        # Command or path to the shell executable to run
        path = "${config.shellPath}"
        # Initial working directory when opening a new tab
        start_directory = "${config.startDirectory}"

        [window]
        # Preferred default terminal size in columns and rows
        columns = ${config.columns}
        rows = ${config.rows}
        # Maximum number of lines to retain in the scrollback buffer
        scrollback_lines = ${config.scrollbackLines}

        [font]
        # Primary monospace font family
        family = "${config.fontFamily}"
        # Font size in points
        size = ${config.fontSize}
        # Line height multiplier
        line_height = ${config.lineHeight}
        # Whether the complex-text renderer may use installed system fonts as fallback
        use_system_fallback_fonts = ${config.useSystemFallbackFonts}

        [theme]
        # Resolved terminal color palette theme.
        # Supported themes: campbell, one-dark, nord, tokyo-night, everforest
        name = "${config.theme}"

        [behavior]
        # Whether East Asian Ambiguous characters should occupy two terminal cells in width policy
        treat_ambiguous_as_wide = ${config.treatAmbiguousAsWide}
        # Cursor blink period in milliseconds
        cursor_blink_millis = ${config.cursorBlinkMillis}
        # Style of the text cursor (block, underline, beam)
        cursor_shape = "${config.cursorShape}"
        # Play a system beep when the terminal receives a BEL character
        audible_bell = ${config.audibleBell}
        # Show a visual edge pulse when the terminal receives a BEL character
        visual_bell = ${config.visualBell}
        # Automatically paste clipboard contents when the middle mouse button is clicked
        paste_on_middle_click = ${config.pasteOnMiddleClick}
        # Paste payload handling before host-bound emission: raw, strip-c0, normalize-line-endings
        paste_sanitization = "${pasteSanitizationId(config.pasteSanitizationPolicy)}"
        # Whether terminal window should resize when the shell requests a grid resize
        shell_request_resize_window = ${config.shellRequestResizeWindow}
        # Whether terminal window manipulation (move, minimize, maximize, raise, lower) is allowed from the shell
        shell_request_window_manipulation = ${config.shellRequestWindowManipulation}
        # Whether to enable desktop notifications when the terminal receives OSC 9 or OSC 777 sequences
        desktop_notifications_enabled = ${config.desktopNotificationsEnabled}
        # Whether host-provided shell suggestions may appear in the terminal UI
        smart_suggestions_enabled = ${config.smartSuggestionsEnabled}
        shell_suggestions_enabled = ${config.shellSuggestionsEnabled}
        # Whether Enter accepts an already-selected suggestion; with no selection Enter reaches the shell
        accept_selected_suggestion_with_enter = ${config.acceptSelectedSuggestionWithEnter}
        # Persist compact suggestion-learning metadata (never raw terminal output) across application restarts
        $SUGGESTION_LEARNING_PERSISTENCE_KEY = ${config.persistentSuggestionLearningEnabled}
        # Automatically scroll to bottom when new process output arrives
        scroll_on_output = ${config.scrollOnOutput}

        [security]
        # OSC 52 clipboard write permission for local sessions (allow, prompt, allowlist, deny)
        clipboard_local_write = "${config.clipboardLocalWrite.name.lowercase(Locale.ROOT)}"
        # OSC 52 clipboard write permission for remote sessions (allow, prompt, allowlist, deny)
        clipboard_remote_write = "${config.clipboardRemoteWrite.name.lowercase(Locale.ROOT)}"
        # OSC 52 clipboard read/query permission (allow, prompt, allowlist, deny)
        clipboard_read = "${config.clipboardRead.name.lowercase(Locale.ROOT)}"
        # Maximum decoded payload size in bytes for clipboard writes/reads
        clipboard_max_decoded_bytes = ${config.clipboardMaxDecodedBytes}
        # Tab/window title renaming permission for local sessions (allow, deny)
        title_local_permission = "${config.titleLocalPermission.name.lowercase(Locale.ROOT)}"
        # Tab/window title renaming permission for remote sessions (allow, deny)
        title_remote_permission = "${config.titleRemotePermission.name.lowercase(Locale.ROOT)}"
        """.trimIndent()

    private fun parseIntSetting(
        raw: String?,
        defaultValue: Int,
        min: Int,
        max: Int,
    ): Int {
        val text = raw?.trim() ?: return defaultValue
        val parsed = text.toLongOrNull()
        if (parsed != null) {
            return parsed.coerceIn(min.toLong(), max.toLong()).toInt()
        }
        if (isSignedIntegerText(text)) {
            return if (text.startsWith("-")) min else max
        }
        return defaultValue
    }

    private fun isSignedIntegerText(text: String): Boolean {
        val start = if (text.startsWith("-") || text.startsWith("+")) 1 else 0
        if (start == text.length) return false
        for (index in start until text.length) {
            if (!text[index].isDigit()) return false
        }
        return true
    }

    private fun parseFloatSetting(
        raw: String?,
        defaultValue: Float,
        min: Float,
        max: Float,
    ): Float {
        val text = raw?.trim() ?: return defaultValue
        val parsed = text.toFloatOrNull() ?: return defaultValue
        if (parsed.isNaN()) return defaultValue
        return parsed.coerceIn(min, max)
    }

    private fun parsePasteSanitizationPolicy(
        raw: String?,
        defaultValue: PasteSanitizationPolicy,
    ): PasteSanitizationPolicy =
        when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "raw" -> PasteSanitizationPolicy.RAW
            "strip-c0" -> PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF
            "normalize-line-endings" -> PasteSanitizationPolicy.NORMALIZE_LINE_ENDINGS
            else -> defaultValue
        }

    private fun pasteSanitizationId(policy: PasteSanitizationPolicy): String =
        when (policy) {
            PasteSanitizationPolicy.RAW -> "raw"
            PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF -> "strip-c0"
            PasteSanitizationPolicy.NORMALIZE_LINE_ENDINGS -> "normalize-line-endings"
        }

    private fun parseClipboardPermission(
        raw: String?,
        defaultValue: TerminalClipboardPermission,
    ): TerminalClipboardPermission =
        when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "deny" -> TerminalClipboardPermission.DENY
            "prompt" -> TerminalClipboardPermission.PROMPT
            "allowlist" -> TerminalClipboardPermission.ALLOWLIST
            "allow" -> TerminalClipboardPermission.ALLOW
            else -> defaultValue
        }

    private fun parseTitlePermission(
        raw: String?,
        defaultValue: TerminalTitlePermission,
    ): TerminalTitlePermission =
        when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "deny" -> TerminalTitlePermission.DENY
            "allow" -> TerminalTitlePermission.ALLOW
            else -> defaultValue
        }

    companion object {
        /**
         * Resolves the default configuration path on disk for this operating system.
         *
         * @param osName operating system name used for platform selection.
         * @param env environment variables map.
         * @param userHome current user's home directory path.
         * @return the resolved [Path] to the configuration file on disk.
         */
        fun getDefaultPath(
            osName: String = System.getProperty("os.name"),
            env: Map<String, String> = System.getenv(),
            userHome: String = System.getProperty("user.home"),
        ): Path {
            // 1. System property override
            val sysProp = System.getProperty("ketraterm.config.path")
            if (!sysProp.isNullOrBlank()) {
                return Path.of(sysProp)
            }

            // 2. Env variable override
            val envVar = env["KetraTerm_CONFIG_PATH"]
            if (!envVar.isNullOrBlank()) {
                return Path.of(envVar)
            }

            // 3. OS-specific default configuration directories
            val os = osName.lowercase(Locale.ROOT)
            return when {
                os.contains("windows") -> {
                    val appData = env["APPDATA"]
                    if (!appData.isNullOrBlank()) {
                        Path.of(appData, "KetraTerm", "config.toml")
                    } else {
                        Path.of(userHome, ".config", "ketraterm", "config.toml")
                    }
                }
                os.contains("mac") -> {
                    Path.of(userHome, "Library", "Application Support", "KetraTerm", "config.toml")
                }
                else -> {
                    val xdgConfig = env["XDG_CONFIG_HOME"]
                    if (!xdgConfig.isNullOrBlank()) {
                        Path.of(xdgConfig, "ketraterm", "config.toml")
                    } else {
                        Path.of(userHome, ".config", "ketraterm", "config.toml")
                    }
                }
            }
        }

        /**
         * Returns a manager configured with the OS-specific default configuration path.
         *
         * @return a default [TerminalWorkspaceConfigManager] instance.
         */
        fun getDefault(): TerminalWorkspaceConfigManager = TerminalWorkspaceConfigManager(getDefaultPath())
    }
}

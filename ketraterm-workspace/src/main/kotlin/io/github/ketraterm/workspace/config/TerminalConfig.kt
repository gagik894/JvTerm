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
import io.github.ketraterm.host.TerminalClipboardPolicy
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import java.util.*

private fun defaultShellPath(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return if (os.contains("windows")) {
        "powershell.exe"
    } else {
        System.getenv("SHELL").takeIf { !it.isNullOrBlank() } ?: "/bin/bash"
    }
}

private fun defaultFontFamily(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        os.contains("windows") -> "Cascadia Mono"
        os.contains("mac") -> "Menlo"
        else -> "Monospaced"
    }
}

/**
 * Host-neutral configuration settings for the terminal emulator.
 *
 * This data class is immutable. For updates, a new instance is created via [copy].
 * The settings are serialized to/from a TOML configuration file on disk.
 *
 * All numeric bounds are published in the companion object so that the UI
 * layer, the TOML config manager, and the data class validation all share one source
 * of truth instead of repeating magic numbers.
 *
 * @property theme built-in color theme id, such as `one-dark` or `nord`.
 * @property treatAmbiguousAsWide whether East Asian Ambiguous characters occupy
 * two cells in core width policy.
 * @property fontFamily primary terminal font family name.
 * @property fontSize primary terminal font size in points.
 * @property columns preferred initial terminal columns.
 * @property rows preferred initial terminal rows.
 * @property cursorBlinkMillis cursor blink period in milliseconds. Zero disables blinking.
 * @property useSystemFallbackFonts whether Swing rendering may scan installed
 * system fonts after configured fallback fonts fail.
 * @property cursorShape default cursor shape id: `block`, `underline`, or `beam`.
 * @property shellPath command or executable path used when opening a local shell.
 * @property startDirectory initial working directory for newly opened shells.
 * @property audibleBell whether host UI should play a system bell for BEL events.
 * @property visualBell whether host UI should show a visual indicator for BEL events.
 * @property pasteOnMiddleClick whether middle mouse click should paste clipboard text.
 * @property pasteSanitizationPolicy transformation applied to pasted text before
 * terminal-host emission.
 * @property scrollbackLines maximum retained scrollback lines.
 * @property lineHeight font metric line-height multiplier.
 * @property shellRequestResizeWindow whether shell application window/grid resize requests are honored.
 * @property desktopNotificationsEnabled whether desktop notifications are enabled.
 * @property smartSuggestionsEnabled whether completion, learning, and completion persistence are available.
 * @property shellSuggestionsEnabled whether host-provided shell suggestions may
 * appear automatically. Explicit requests require only [smartSuggestionsEnabled].
 * @property acceptSelectedSuggestionWithEnter whether Enter accepts an
 * already-selected shell suggestion while leaving unselected Enter presses to
 * the shell.
 * @property persistentSuggestionLearningEnabled whether supporting product hosts
 * persist compact command-completion learning metadata across application restarts.
 * @property clipboardLocalWrite OSC 52 write permission for local sessions.
 * @property clipboardRemoteWrite OSC 52 write permission for remote sessions.
 * @property clipboardRead OSC 52 read/query permission.
 * @property clipboardMaxDecodedBytes maximum decoded clipboard payload size.
 * @property titleLocalPermission window/tab renaming permission for local sessions.
 * @property titleRemotePermission window/tab renaming permission for remote sessions.
 */
data class TerminalConfig(
    val theme: String = DEFAULT_THEME,
    val treatAmbiguousAsWide: Boolean = DEFAULT_TREAT_AMBIGUOUS_AS_WIDE,
    val fontFamily: String = DEFAULT_FONT_FAMILY,
    val fontSize: Int = DEFAULT_FONT_SIZE,
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    val cursorBlinkMillis: Int = DEFAULT_CURSOR_BLINK_MILLIS,
    val useSystemFallbackFonts: Boolean = DEFAULT_USE_SYSTEM_FALLBACK_FONTS,
    val cursorShape: String = DEFAULT_CURSOR_SHAPE,
    val shellPath: String = DEFAULT_SHELL_PATH,
    val startDirectory: String = DEFAULT_START_DIRECTORY,
    val audibleBell: Boolean = DEFAULT_AUDIBLE_BELL,
    val visualBell: Boolean = DEFAULT_VISUAL_BELL,
    val pasteOnMiddleClick: Boolean = DEFAULT_PASTE_ON_MIDDLE_CLICK,
    val pasteSanitizationPolicy: PasteSanitizationPolicy = DEFAULT_PASTE_SANITIZATION_POLICY,
    val scrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
    val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    val shellRequestResizeWindow: Boolean = DEFAULT_SHELL_REQUEST_RESIZE_WINDOW,
    val shellRequestWindowManipulation: Boolean = DEFAULT_SHELL_REQUEST_WINDOW_MANIPULATION,
    val desktopNotificationsEnabled: Boolean = DEFAULT_DESKTOP_NOTIFICATIONS_ENABLED,
    val smartSuggestionsEnabled: Boolean = DEFAULT_SMART_SUGGESTIONS_ENABLED,
    val shellSuggestionsEnabled: Boolean = DEFAULT_SHELL_SUGGESTIONS_ENABLED,
    val acceptSelectedSuggestionWithEnter: Boolean = DEFAULT_ACCEPT_SELECTED_SUGGESTION_WITH_ENTER,
    val persistentSuggestionLearningEnabled: Boolean = DEFAULT_PERSISTENT_SUGGESTION_LEARNING_ENABLED,
    val clipboardLocalWrite: TerminalClipboardPermission = DEFAULT_CLIPBOARD_LOCAL_WRITE,
    val clipboardRemoteWrite: TerminalClipboardPermission = DEFAULT_CLIPBOARD_REMOTE_WRITE,
    val clipboardRead: TerminalClipboardPermission = DEFAULT_CLIPBOARD_READ,
    val clipboardMaxDecodedBytes: Int = DEFAULT_CLIPBOARD_MAX_DECODED_BYTES,
    val titleLocalPermission: TerminalTitlePermission = DEFAULT_TITLE_LOCAL_PERMISSION,
    val titleRemotePermission: TerminalTitlePermission = DEFAULT_TITLE_REMOTE_PERMISSION,
    val scrollOnOutput: Boolean = DEFAULT_SCROLL_ON_OUTPUT,
) {
    init {
        require(columns in COLUMNS_MIN..COLUMNS_MAX) {
            "columns must be in ${COLUMNS_MIN}..${COLUMNS_MAX}, was $columns"
        }
        require(rows in ROWS_MIN..ROWS_MAX) {
            "rows must be in ${ROWS_MIN}..${ROWS_MAX}, was $rows"
        }
        require(fontSize in FONT_SIZE_MIN..FONT_SIZE_MAX) {
            "fontSize must be in ${FONT_SIZE_MIN}..${FONT_SIZE_MAX}, was $fontSize"
        }
        require(cursorBlinkMillis in CURSOR_BLINK_MIN..CURSOR_BLINK_MAX) {
            "cursorBlinkMillis must be in ${CURSOR_BLINK_MIN}..${CURSOR_BLINK_MAX}, was $cursorBlinkMillis"
        }
        require(theme.isNotBlank()) { "theme must not be blank" }
        require(fontFamily.isNotBlank()) { "fontFamily must not be blank" }
        require(cursorShape.isNotBlank()) { "cursorShape must not be blank" }
        require(shellPath.isNotBlank()) { "shellPath must not be blank" }
        require(scrollbackLines in SCROLLBACK_MIN..SCROLLBACK_MAX) {
            "scrollbackLines must be in ${SCROLLBACK_MIN}..${SCROLLBACK_MAX}, was $scrollbackLines"
        }
        require(lineHeight in LINE_HEIGHT_MIN..LINE_HEIGHT_MAX) {
            "lineHeight must be in ${LINE_HEIGHT_MIN}..${LINE_HEIGHT_MAX}, was $lineHeight"
        }
        require(clipboardMaxDecodedBytes >= 0) {
            "clipboardMaxDecodedBytes must be non-negative, was $clipboardMaxDecodedBytes"
        }
    }

    /**
     * Published bounds and defaults for all terminal configuration fields.
     *
     * These constants are the single source of truth shared by:
     * - [TerminalConfig.init] validity assertions.
     * - [TerminalWorkspaceConfigManager] TOML clamping before constructing a config.
     * - The standalone settings dialog spinner min/max values.
     *
     * If you need to change a limit or default, change it here and it will be reflected
     * everywhere automatically.
     */
    companion object {
        // Defaults
        const val DEFAULT_THEME: String = "one-dark"
        const val DEFAULT_TREAT_AMBIGUOUS_AS_WIDE: Boolean = false
        const val DEFAULT_FONT_SIZE: Int = 16
        const val DEFAULT_COLUMNS: Int = 100
        const val DEFAULT_ROWS: Int = 30
        const val DEFAULT_CURSOR_BLINK_MILLIS: Int = 600
        const val DEFAULT_USE_SYSTEM_FALLBACK_FONTS: Boolean = true
        const val DEFAULT_CURSOR_SHAPE: String = "block"
        const val DEFAULT_AUDIBLE_BELL: Boolean = true
        const val DEFAULT_VISUAL_BELL: Boolean = true
        const val DEFAULT_PASTE_ON_MIDDLE_CLICK: Boolean = true
        val DEFAULT_PASTE_SANITIZATION_POLICY: PasteSanitizationPolicy = PasteSanitizationPolicy.RAW
        const val DEFAULT_SCROLLBACK_LINES: Int = 1000
        const val DEFAULT_LINE_HEIGHT: Float = 1.0f
        const val DEFAULT_SHELL_REQUEST_RESIZE_WINDOW: Boolean = false
        const val DEFAULT_SHELL_REQUEST_WINDOW_MANIPULATION: Boolean = false
        const val DEFAULT_DESKTOP_NOTIFICATIONS_ENABLED: Boolean = true
        const val DEFAULT_SMART_SUGGESTIONS_ENABLED: Boolean = false
        const val DEFAULT_SHELL_SUGGESTIONS_ENABLED: Boolean = true
        const val DEFAULT_ACCEPT_SELECTED_SUGGESTION_WITH_ENTER: Boolean = true
        const val DEFAULT_PERSISTENT_SUGGESTION_LEARNING_ENABLED: Boolean = false
        const val DEFAULT_SCROLL_ON_OUTPUT: Boolean = true

        val DEFAULT_CLIPBOARD_LOCAL_WRITE: TerminalClipboardPermission = TerminalClipboardPermission.PROMPT
        val DEFAULT_CLIPBOARD_REMOTE_WRITE: TerminalClipboardPermission = TerminalClipboardPermission.DENY
        val DEFAULT_CLIPBOARD_READ: TerminalClipboardPermission = TerminalClipboardPermission.DENY
        const val DEFAULT_CLIPBOARD_MAX_DECODED_BYTES: Int = TerminalClipboardPolicy.DEFAULT_MAX_DECODED_BYTES
        val DEFAULT_TITLE_LOCAL_PERMISSION: TerminalTitlePermission = TerminalTitlePermission.ALLOW
        val DEFAULT_TITLE_REMOTE_PERMISSION: TerminalTitlePermission = TerminalTitlePermission.DENY

        val DEFAULT_FONT_FAMILY: String get() = defaultFontFamily()
        val DEFAULT_SHELL_PATH: String get() = defaultShellPath()
        val DEFAULT_START_DIRECTORY: String get() = System.getProperty("user.home") ?: ""

        // Limits
        const val COLUMNS_MIN: Int = 10
        const val COLUMNS_MAX: Int = 1000

        const val ROWS_MIN: Int = 10
        const val ROWS_MAX: Int = 500

        const val FONT_SIZE_MIN: Int = 10
        const val FONT_SIZE_MAX: Int = 56

        /** Zero disables blinking. */
        const val CURSOR_BLINK_MIN: Int = 0
        const val CURSOR_BLINK_MAX: Int = 10_000

        const val SCROLLBACK_MIN: Int = 0
        const val SCROLLBACK_MAX: Int = 1_000_000

        const val LINE_HEIGHT_MIN: Float = 0.7f
        const val LINE_HEIGHT_MAX: Float = 1.5f
    }
}

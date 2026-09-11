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
package io.github.ketraterm.intellij.settings

import com.intellij.openapi.components.*
import com.intellij.util.ui.JBFont
import io.github.ketraterm.host.*
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.config.TerminalConfig
import kotlinx.collections.immutable.persistentListOf
import java.awt.Font
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application-level IntelliJ settings service for IDE-hosted KetraTerm terminals.
 *
 * IntelliJ persists this service through its native XML persistence model. The
 * standalone application keeps using TOML through `ketraterm-workspace`; this
 * class is the IntelliJ-only adapter that converts persisted IDE state into a
 * reusable [SwingSettings] snapshot.
 */
@Service(Service.Level.APP)
@State(
    name = "KetraTermIntellijSettings",
    storages = [Storage(value = "ketraterm.xml", roamingType = RoamingType.DEFAULT)],
    category = SettingsCategory.TOOLS,
)
class KetraTermIntellijSettings : SerializablePersistentStateComponent<KetraTermIntellijSettings.State>(State()) {
    private val stateLock = Any()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Returns the current immutable settings snapshot consumed by `SwingTerminal`.
     *
     * @return immutable Swing terminal settings.
     */
    fun current(): SwingSettings = KetraTermIntellijSettingsMapper.toSwingSettings(state)

    /**
     * Returns whether the IDE host should bind middle-click paste for terminal panes.
     *
     * @return `true` when middle mouse button paste is enabled.
     */
    fun pasteOnMiddleClick(): Boolean = state.pasteOnMiddleClick

    /**
     * Returns whether terminal panes should override IDE shortcuts.
     *
     * When enabled, focused terminal panes may claim IDE-style shortcuts such
     * as Find for host UI. When disabled, those key events are left for
     * terminal input encoding and can be sent to the PTY.
     *
     * @return `true` when terminal panes may override IDE shortcuts.
     */
    fun overrideIdeShortcuts(): Boolean = state.overrideIdeShortcuts

    /**
     * Returns whether learned completion statistics may be stored on disk.
     *
     * Disabling persistence does not disable product-lifetime in-memory
     * learning. It only prevents the IntelliJ host from reading or writing the
     * learned completion snapshot.
     *
     * @return `true` when completion learning may cross IDE restarts.
     */
    fun completionLearningPersistenceEnabled(): Boolean {
        val snapshot = state
        return snapshot.smartSuggestionsEnabled && snapshot.completionLearningPersistenceEnabled
    }

    /**
     * Replaces persisted IDE terminal settings with a normalized state.
     *
     * @param nextState new settings state produced by the IntelliJ settings UI.
     */
    fun replaceState(nextState: State) = publishState(nextState) { normalized -> updateState { normalized } }

    /** Loads platform state through the same normalization and notification boundary as UI changes. */
    override fun loadState(state: State) = publishState(state) { normalized -> super.loadState(normalized) }

    private fun publishState(
        nextState: State,
        publish: (State) -> Unit,
    ) {
        val normalized = KetraTermIntellijSettingsNormalizer.normalize(nextState)
        synchronized(stateLock) {
            if (normalized == state) return
            publish(normalized)
        }
        // Publication has committed: every consumer must see it even when another listener fails.
        var failure: Exception? = null
        for (listener in changeListeners) {
            try {
                listener()
            } catch (error: Exception) {
                val previous = failure
                if (previous == null) {
                    failure = error
                } else if (error is CancellationException) {
                    error.addSuppressed(previous)
                    failure = error
                } else {
                    previous.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    fun createHostPolicy(command: List<String>): HostPolicy {
        val s = state
        val isRemote = command.firstOrNull()?.let(::isSshExecutable) == true
        val clipboardOrigin = if (isRemote) TerminalClipboardOrigin.REMOTE else TerminalClipboardOrigin.LOCAL
        val titleOrigin = if (isRemote) TerminalTitleOrigin.REMOTE else TerminalTitleOrigin.LOCAL

        val localWrite = TerminalClipboardPermission.valueOf(s.clipboardLocalWrite.uppercase(Locale.ROOT))
        val remoteWrite = TerminalClipboardPermission.valueOf(s.clipboardRemoteWrite.uppercase(Locale.ROOT))
        val read = TerminalClipboardPermission.valueOf(s.clipboardRead.uppercase(Locale.ROOT))
        val maxBytes = s.clipboardMaxDecodedBytes

        val localTitle = TerminalTitlePermission.valueOf(s.titleLocalPermission.uppercase(Locale.ROOT))
        val remoteTitle = TerminalTitlePermission.valueOf(s.titleRemotePermission.uppercase(Locale.ROOT))

        return HostPolicy(
            titlePolicy =
                TerminalTitlePolicy(
                    origin = titleOrigin,
                    localPermission = localTitle,
                    remotePermission = remoteTitle,
                ),
            clipboardPolicy =
                TerminalClipboardPolicy(
                    origin = clipboardOrigin,
                    localWritePermission = localWrite,
                    remoteWritePermission = remoteWrite,
                    readPermission = read,
                    maxDecodedBytes = maxBytes,
                ),
            windowManipulationPolicy = HostControlPolicy.DENY,
        )
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

    /**
     * Registers a listener notified after settings are changed through this service.
     *
     * @param listener callback invoked on the caller thread after a changed snapshot is committed.
     * Every listener is notified before any listener failure is rethrown to the caller.
     */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    /**
     * Removes a previously registered settings listener.
     *
     * @param listener callback to remove.
     */
    fun removeChangeListener(listener: () -> Unit) {
        changeListeners -= listener
    }

    /**
     * Persistent XML state for IntelliJ-hosted terminal preferences.
     *
     * @property themeId `intellij` for IDE-derived colors, or a built-in theme id.
     * @property fontFamily terminal font family.
     * @property fallbackFontFamily preferred complex-text fallback font family.
     * @property fontSize terminal font size in points.
     * @property columns preferred initial terminal columns.
     * @property rows preferred initial terminal rows.
     * @property treatAmbiguousAsWide East Asian Ambiguous width policy.
     * @property cursorBlinkMillis cursor blink period; zero disables blinking.
     * @property useSystemFallbackFonts whether renderer may scan installed fonts.
     * @property cursorShape default cursor shape id.
     * @property visualBell whether BEL events show a visual terminal indicator.
     * @property pasteOnMiddleClick whether the middle click pastes clipboard text.
     * @property overrideIdeShortcuts whether focused terminal panes may claim
     * IDE-style shortcuts such as Find for host UI.
     * @property scrollbackLines maximum retained scrollback rows.
     * @property lineHeight font metric line-height multiplier.
     * @property shellPath command or executable path used for new local shells.
     * @property startDirectory initial working directory; blank means project root.
     * @property environmentVariables newline-separated `NAME=VALUE` environment entries.
     * @property defaultTabName user-visible name for newly opened tabs.
     * @property smartSuggestionsEnabled master switch for completion resources and requests.
     * @property shellSuggestionsEnabled whether host-provided shell suggestions
     * may appear automatically. Explicit requests require only [smartSuggestionsEnabled].
     * @property acceptSelectedSuggestionWithEnter whether Enter accepts an
     * already-selected terminal suggestion and otherwise reaches the shell.
     * @property completionLearningPersistenceEnabled whether sanitized learned
     * completion statistics may be read from and written to local disk.
     */
    data class State(
        @JvmField val themeId: String = DEFAULT_THEME_ID,
        @JvmField val fontFamily: String = DEFAULT_FONT_FAMILY,
        @JvmField val fallbackFontFamily: String = DEFAULT_FONT_FAMILY,
        @JvmField val fontSize: Int = DEFAULT_FONT_SIZE,
        @JvmField val columns: Int = TerminalConfig.DEFAULT_COLUMNS,
        @JvmField val rows: Int = TerminalConfig.DEFAULT_ROWS,
        @JvmField val treatAmbiguousAsWide: Boolean = TerminalConfig.DEFAULT_TREAT_AMBIGUOUS_AS_WIDE,
        @JvmField val cursorBlinkMillis: Int = TerminalConfig.DEFAULT_CURSOR_BLINK_MILLIS,
        @JvmField val useSystemFallbackFonts: Boolean = TerminalConfig.DEFAULT_USE_SYSTEM_FALLBACK_FONTS,
        @JvmField val cursorShape: String = TerminalConfig.DEFAULT_CURSOR_SHAPE,
        @JvmField val visualBell: Boolean = TerminalConfig.DEFAULT_VISUAL_BELL,
        @JvmField val pasteOnMiddleClick: Boolean = TerminalConfig.DEFAULT_PASTE_ON_MIDDLE_CLICK,
        @JvmField val overrideIdeShortcuts: Boolean = true,
        @JvmField val scrollbackLines: Int = TerminalConfig.DEFAULT_SCROLLBACK_LINES,
        @JvmField val lineHeight: Float = TerminalConfig.DEFAULT_LINE_HEIGHT,
        @JvmField val shellPath: String = TerminalConfig.DEFAULT_SHELL_PATH,
        @JvmField val startDirectory: String = "",
        @JvmField val environmentVariables: String = "",
        @JvmField val defaultTabName: String = "Local",
        @JvmField val smartSuggestionsEnabled: Boolean = TerminalConfig.DEFAULT_SMART_SUGGESTIONS_ENABLED,
        @JvmField val shellSuggestionsEnabled: Boolean = TerminalConfig.DEFAULT_SHELL_SUGGESTIONS_ENABLED,
        @JvmField val acceptSelectedSuggestionWithEnter: Boolean = TerminalConfig.DEFAULT_ACCEPT_SELECTED_SUGGESTION_WITH_ENTER,
        @JvmField val completionLearningPersistenceEnabled: Boolean = false,
        @JvmField val pasteSanitization: String = "raw",
        @JvmField val clipboardLocalWrite: String = TerminalConfig.DEFAULT_CLIPBOARD_LOCAL_WRITE.name.lowercase(Locale.ROOT),
        @JvmField val clipboardRemoteWrite: String = TerminalConfig.DEFAULT_CLIPBOARD_REMOTE_WRITE.name.lowercase(Locale.ROOT),
        @JvmField val clipboardRead: String = TerminalConfig.DEFAULT_CLIPBOARD_READ.name.lowercase(Locale.ROOT),
        @JvmField val clipboardMaxDecodedBytes: Int = TerminalConfig.DEFAULT_CLIPBOARD_MAX_DECODED_BYTES,
        @JvmField val titleLocalPermission: String = TerminalConfig.DEFAULT_TITLE_LOCAL_PERMISSION.name.lowercase(Locale.ROOT),
        @JvmField val titleRemotePermission: String = TerminalConfig.DEFAULT_TITLE_REMOTE_PERMISSION.name.lowercase(Locale.ROOT),
        @JvmField val scrollOnOutput: Boolean = true,
    )

    companion object {
        /**
         * Theme id that derives colors from the active IntelliJ editor scheme.
         */
        const val DEFAULT_THEME_ID: String = "intellij"

        /**
         * Default IDE terminal font size.
         */
        const val DEFAULT_FONT_SIZE: Int = 13

        /**
         * Default IDE terminal font family.
         */
        const val DEFAULT_FONT_FAMILY: String = "JetBrains Mono"

        /**
         * Returns the application-level KetraTerm settings service.
         *
         * @return IntelliJ settings service.
         */
        fun getInstance(): KetraTermIntellijSettings = service()

        /**
         * Returns the current Swing settings snapshot from the application service.
         *
         * @return immutable Swing terminal settings.
         */
        fun current(): SwingSettings = getInstance().current()

        internal fun normalizeThemeId(themeId: String): String {
            val normalized = themeId.trim().lowercase(Locale.ROOT)
            if (normalized == DEFAULT_THEME_ID) return DEFAULT_THEME_ID
            return TerminalTheme.fromId(normalized)?.id ?: DEFAULT_THEME_ID
        }
    }
}

/**
 * Normalizes settings at the platform-load and UI-publication boundary.
 */
internal object KetraTermIntellijSettingsNormalizer {
    /**
     * Returns a state with validated bounds and canonical identifiers.
     *
     * @param state persisted or UI-produced state.
     * @return normalized state safe to persist.
     */
    fun normalize(state: KetraTermIntellijSettings.State): KetraTermIntellijSettings.State =
        state.copy(
            themeId = KetraTermIntellijSettings.normalizeThemeId(state.themeId),
            fontFamily = normalizeFontFamily(state.fontFamily),
            fallbackFontFamily = normalizeFontFamily(state.fallbackFontFamily),
            fontSize =
                state.fontSize.coerceIn(TerminalConfig.FONT_SIZE_MIN, TerminalConfig.FONT_SIZE_MAX),
            columns = state.columns.coerceIn(TerminalConfig.COLUMNS_MIN, TerminalConfig.COLUMNS_MAX),
            rows = state.rows.coerceIn(TerminalConfig.ROWS_MIN, TerminalConfig.ROWS_MAX),
            cursorBlinkMillis =
                state.cursorBlinkMillis.coerceIn(
                    TerminalConfig.CURSOR_BLINK_MIN,
                    TerminalConfig.CURSOR_BLINK_MAX,
                ),
            cursorShape = normalizeCursorShape(state.cursorShape),
            scrollbackLines =
                state.scrollbackLines.coerceIn(
                    TerminalConfig.SCROLLBACK_MIN,
                    TerminalConfig.SCROLLBACK_MAX,
                ),
            lineHeight = coerceLineHeight(state.lineHeight),
            shellPath = state.shellPath.trim().ifBlank { TerminalConfig.DEFAULT_SHELL_PATH },
            startDirectory = state.startDirectory.trim(),
            environmentVariables = normalizeEnvironmentText(state.environmentVariables),
            defaultTabName = state.defaultTabName.trim().ifBlank { "Local" },
            pasteSanitization = normalizePasteSanitization(state.pasteSanitization),
            clipboardLocalWrite =
                normalizeClipboardPermission(
                    state.clipboardLocalWrite,
                    TerminalConfig.DEFAULT_CLIPBOARD_LOCAL_WRITE,
                ),
            clipboardRemoteWrite =
                normalizeClipboardPermission(
                    state.clipboardRemoteWrite,
                    TerminalConfig.DEFAULT_CLIPBOARD_REMOTE_WRITE,
                ),
            clipboardRead =
                normalizeClipboardPermission(
                    state.clipboardRead,
                    TerminalConfig.DEFAULT_CLIPBOARD_READ,
                ),
            clipboardMaxDecodedBytes = state.clipboardMaxDecodedBytes.coerceAtLeast(0),
            titleLocalPermission =
                normalizeTitlePermission(
                    state.titleLocalPermission,
                    TerminalConfig.DEFAULT_TITLE_LOCAL_PERMISSION,
                ),
            titleRemotePermission =
                normalizeTitlePermission(
                    state.titleRemotePermission,
                    TerminalConfig.DEFAULT_TITLE_REMOTE_PERMISSION,
                ),
        )

    /**
     * Parses newline-separated environment entries.
     *
     * @param text environment text using `NAME=VALUE` entries separated by newlines.
     * @return parsed environment map.
     */
    fun parseEnvironmentVariables(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val separator = trimmed.indexOf('=')
            if (separator <= 0) continue
            val name = trimmed.substring(0, separator).trim()
            if (name.isEmpty()) continue
            result[name] = trimmed.substring(separator + 1)
        }
        return result
    }

    private fun normalizeFontFamily(fontFamily: String): String =
        fontFamily.trim().ifBlank { KetraTermIntellijSettings.DEFAULT_FONT_FAMILY }

    private fun normalizeEnvironmentText(text: String): String =
        parseEnvironmentVariables(text)
            .entries
            .joinToString("\n") { (key, value) -> "$key=$value" }

    private fun normalizeCursorShape(shape: String): String =
        when (shape.lowercase(Locale.ROOT)) {
            "beam", "bar" -> "beam"
            "underline" -> "underline"
            else -> "block"
        }

    private fun coerceLineHeight(lineHeight: Float): Float =
        if (lineHeight.isFinite()) {
            lineHeight.coerceIn(TerminalConfig.LINE_HEIGHT_MIN, TerminalConfig.LINE_HEIGHT_MAX)
        } else {
            TerminalConfig.DEFAULT_LINE_HEIGHT
        }

    private fun normalizeClipboardPermission(
        perm: String,
        default: TerminalClipboardPermission,
    ): String =
        when (val normalized = perm.trim().lowercase(Locale.ROOT)) {
            "allow", "prompt", "allowlist", "deny" -> normalized
            else -> default.name.lowercase(Locale.ROOT)
        }

    private fun normalizeTitlePermission(
        perm: String,
        default: TerminalTitlePermission,
    ): String =
        when (val normalized = perm.trim().lowercase(Locale.ROOT)) {
            "allow", "deny" -> normalized
            else -> default.name.lowercase(Locale.ROOT)
        }

    private fun normalizePasteSanitization(policy: String): String =
        when (val normalized = policy.trim().lowercase(Locale.ROOT)) {
            "raw", "strip-c0", "normalize-line-endings" -> normalized
            else -> "raw"
        }
}

/**
 * Converts IntelliJ persisted state into host-neutral Swing terminal settings.
 */
internal object KetraTermIntellijSettingsMapper {
    /**
     * Creates a Swing settings snapshot from [state].
     *
     * @param state normalized IntelliJ settings state.
     * @return immutable Swing terminal settings.
     */
    fun toSwingSettings(state: KetraTermIntellijSettings.State): SwingSettings {
        val fontSize = state.fontSize
        val fontFamily = SwingSettings.resolveFontFamily(state.fontFamily)
        val fallbackFontFamily = SwingSettings.resolveFontFamily(state.fallbackFontFamily)

        val palette = paletteForThemeId(state.themeId)

        return SwingSettings(
            font = JBFont.create(Font(fontFamily, Font.PLAIN, fontSize)),
            fallbackFonts = persistentListOf(Font(fallbackFontFamily, Font.PLAIN, fontSize)),
            columns = state.columns,
            rows = state.rows,
            palette = palette,
            selectionBackground = palette.selectionBackground,
            treatAmbiguousAsWide = state.treatAmbiguousAsWide,
            cursorBlinkMillis = state.cursorBlinkMillis,
            useSystemFallbackFonts = state.useSystemFallbackFonts,
            visualBellEnabled = state.visualBell,
            pasteSanitizationPolicy = parsePasteSanitization(state.pasteSanitization),
            cursorShape = parseCursorShape(state.cursorShape),
            scrollbackLines = state.scrollbackLines,
            lineHeight = state.lineHeight,
            shellRequestResizeWindow = false,
            shellRequestWindowManipulation = false,
            smartSuggestionsEnabled = state.smartSuggestionsEnabled,
            shellSuggestionsEnabled = state.shellSuggestionsEnabled,
            acceptSelectedSuggestionWithEnter = state.acceptSelectedSuggestionWithEnter,
            scrollOnOutput = state.scrollOnOutput,
        )
    }

    private fun paletteForThemeId(themeId: String) =
        if (themeId == KetraTermIntellijSettings.DEFAULT_THEME_ID) {
            KetraTermIntellijThemePalette.currentIntellijPalette()
        } else {
            requireNotNull(TerminalTheme.fromId(themeId)).createPalette()
        }

    private fun parseCursorShape(shape: String): TerminalRenderCursorShape =
        when (shape) {
            "beam" -> TerminalRenderCursorShape.BAR
            "underline" -> TerminalRenderCursorShape.UNDERLINE
            else -> TerminalRenderCursorShape.BLOCK
        }

    private fun parsePasteSanitization(value: String): PasteSanitizationPolicy =
        when (value) {
            "strip-c0" -> PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF
            "normalize-line-endings" -> PasteSanitizationPolicy.NORMALIZE_LINE_ENDINGS
            else -> PasteSanitizationPolicy.RAW
        }
}

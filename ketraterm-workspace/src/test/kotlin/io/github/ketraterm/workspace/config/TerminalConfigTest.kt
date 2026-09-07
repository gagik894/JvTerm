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
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.*

class TerminalConfigTest {
    @Test
    fun `missing master flag stays off even when legacy preferences are on`() {
        val directory = Files.createTempDirectory("ketraterm-suggestions-default")
        val path = directory.resolve("config.toml")
        try {
            Files.writeString(path, "[behavior]\nshell_suggestions_enabled = true\npersistent_suggestion_learning_enabled = true\n")
            val manager = TerminalWorkspaceConfigManager(path)
            val config = manager.load()
            assertFalse(config.smartSuggestionsEnabled)
            manager.save(config.copy(smartSuggestionsEnabled = true))
            assertTrue(manager.load().smartSuggestionsEnabled)
            assertTrue(Files.readString(path).contains("smart_suggestions_enabled = true"))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `test TomlParser parses sections keys and values correctly`() {
        val toml =
            """
            # Global comment
            [window]
            columns = 120 # custom columns
            rows = 40

            [theme]
            name = "tokyo-night"

            [font]
            family = 'Fira Code' # single quotes
            size = 14
            """.trimIndent()

        val parsed = TomlParser.parse(toml)

        assertEquals("120", parsed["window"]?.get("columns"))
        assertEquals("40", parsed["window"]?.get("rows"))
        assertEquals("tokyo-night", parsed["theme"]?.get("name"))
        assertEquals("Fira Code", parsed["font"]?.get("family"))
        assertEquals("14", parsed["font"]?.get("size"))
    }

    @Test
    fun `test TomlParser ignores comments and preserves quotes inside strings`() {
        val toml =
            """
            [theme]
            color = "#ff0000" # hex value containing hash
            description = "This is a #comment text inside quotes"
            """.trimIndent()

        val parsed = TomlParser.parse(toml)
        assertEquals("#ff0000", parsed["theme"]?.get("color"))
        assertEquals("This is a #comment text inside quotes", parsed["theme"]?.get("description"))
    }

    @Test
    fun `test TerminalWorkspaceConfigManager path resolution`() {
        // Overrides
        // Clean default system property check (which might be set or not during tests, but we can verify our overrides work)
        System.setProperty("ketraterm.config.path", "/custom/sys/path.toml")
        assertEquals(Path.of("/custom/sys/path.toml"), TerminalWorkspaceConfigManager.getDefaultPath(osName = "Windows 11"))
        System.clearProperty("ketraterm.config.path")

        // Env override
        val env = mapOf("KetraTerm_CONFIG_PATH" to "/custom/env/path.toml")
        assertEquals(Path.of("/custom/env/path.toml"), TerminalWorkspaceConfigManager.getDefaultPath(osName = "Windows 11", env = env))

        // Windows resolution
        val winEnv = mapOf("APPDATA" to "C:\\Users\\User\\AppData\\Roaming")
        val winPath = TerminalWorkspaceConfigManager.getDefaultPath(osName = "Windows 11", env = winEnv)
        assertEquals(Path.of("C:\\Users\\User\\AppData\\Roaming", "KetraTerm", "config.toml"), winPath)

        // Mac resolution
        val macPath = TerminalWorkspaceConfigManager.getDefaultPath(osName = "macOS Big Sur", userHome = "/Users/username")
        assertEquals(Path.of("/Users/username/Library/Application Support/KetraTerm/config.toml"), macPath)

        // Linux resolution
        val linuxEnv = mapOf("XDG_CONFIG_HOME" to "/home/username/.custom_config")
        val linuxPath = TerminalWorkspaceConfigManager.getDefaultPath(osName = "Linux", env = linuxEnv)
        assertEquals(Path.of("/home/username/.custom_config/ketraterm/config.toml"), linuxPath)

        val linuxFallbackPath =
            TerminalWorkspaceConfigManager.getDefaultPath(
                osName = "Linux",
                env = emptyMap(),
                userHome = "/home/username",
            )
        assertEquals(Path.of("/home/username/.config/ketraterm/config.toml"), linuxFallbackPath)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager load default config if file does not exist`() {
        val tempDir = Files.createTempDirectory("ketraterm-config-test")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        assertFalse(Files.exists(configFile))
        val config = manager.load()

        assertTrue(Files.exists(configFile))
        assertEquals("one-dark", config.theme)
        assertEquals(100, config.columns)
        assertEquals(30, config.rows)
        val expectedFont =
            when {
                System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows") -> "Cascadia Mono"
                System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac") -> "Menlo"
                else -> "Monospaced"
            }
        assertEquals(expectedFont, config.fontFamily)
        assertEquals(16, config.fontSize)
        assertFalse(config.treatAmbiguousAsWide)
        assertEquals(600, config.cursorBlinkMillis)
        assertTrue(config.useSystemFallbackFonts)
        assertEquals("block", config.cursorShape)
        assertTrue(config.visualBell)
        assertEquals(PasteSanitizationPolicy.RAW, config.pasteSanitizationPolicy)
        assertFalse(config.shellRequestResizeWindow)
        assertFalse(config.shellRequestWindowManipulation)
        assertFalse(config.smartSuggestionsEnabled)
        assertTrue(config.shellSuggestionsEnabled)
        assertTrue(config.acceptSelectedSuggestionWithEnter)
        assertFalse(config.persistentSuggestionLearningEnabled)
        assertEquals(TerminalClipboardPermission.PROMPT, config.clipboardLocalWrite)
        assertEquals(TerminalClipboardPermission.DENY, config.clipboardRemoteWrite)
        assertEquals(TerminalClipboardPermission.DENY, config.clipboardRead)
        assertEquals(1024 * 1024, config.clipboardMaxDecodedBytes)
        assertEquals(TerminalTitlePermission.ALLOW, config.titleLocalPermission)
        assertEquals(TerminalTitlePermission.DENY, config.titleRemotePermission)

        // Clean up
        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager saves and loads custom config correctly`() {
        val tempDir = Files.createTempDirectory("ketraterm-config-test-custom")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        val customConfig =
            TerminalConfig(
                theme = "nord",
                smartSuggestionsEnabled = true,
                treatAmbiguousAsWide = true,
                fontFamily = "JetBrains Mono",
                fontSize = 18,
                columns = 110,
                rows = 35,
                cursorBlinkMillis = 500,
                useSystemFallbackFonts = true,
                cursorShape = "beam",
                audibleBell = false,
                visualBell = false,
                pasteSanitizationPolicy = PasteSanitizationPolicy.NORMALIZE_LINE_ENDINGS,
                shellRequestResizeWindow = true,
                shellRequestWindowManipulation = true,
                shellSuggestionsEnabled = false,
                acceptSelectedSuggestionWithEnter = false,
                persistentSuggestionLearningEnabled = true,
                desktopNotificationsEnabled = false,
                clipboardLocalWrite = TerminalClipboardPermission.ALLOW,
                clipboardRemoteWrite = TerminalClipboardPermission.ALLOWLIST,
                clipboardRead = TerminalClipboardPermission.PROMPT,
                clipboardMaxDecodedBytes = 500,
                titleLocalPermission = TerminalTitlePermission.DENY,
                titleRemotePermission = TerminalTitlePermission.ALLOW,
            )

        manager.save(customConfig)
        assertTrue(Files.exists(configFile))
        assertTrue(Files.readString(configFile).contains("""paste_sanitization = "normalize-line-endings""""))
        assertTrue(Files.readString(configFile).contains("""shell_suggestions_enabled = false"""))
        assertTrue(Files.readString(configFile).contains("""accept_selected_suggestion_with_enter = false"""))
        assertTrue(Files.readString(configFile).contains("""suggestion_learning_persistence_enabled = true"""))
        assertTrue(Files.readString(configFile).contains("""clipboard_local_write = "allow""""))
        assertTrue(Files.readString(configFile).contains("""clipboard_max_decoded_bytes = 500"""))

        val loaded = manager.load()
        assertEquals(customConfig, loaded)

        // Clean up
        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager loads new suggestion learning persistence key`() {
        val tempDir = Files.createTempDirectory("ketraterm-config-test-suggestion-learning-new")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        Files.writeString(
            configFile,
            """
            [behavior]
            suggestion_learning_persistence_enabled = true
            """.trimIndent(),
        )

        assertTrue(manager.load().persistentSuggestionLearningEnabled)

        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    fun `test TerminalWorkspaceConfigManager clamps hand edited numeric values`() {
        val tempDir = Files.createTempDirectory("ketraterm-config-test-clamped")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        Files.writeString(
            configFile,
            """
            [window]
            columns = 999999
            rows = -42
            scrollback_lines = 999999999999999999999999

            [font]
            family = "JetBrains Mono"
            size = -9
            line_height = 0.01

            [theme]
            name = "nord"

            [behavior]
            cursor_blink_millis = 999999999999999999999999
            cursor_shape = "beam"
            paste_sanitization = "strip-c0"
            """.trimIndent(),
        )

        val loaded = manager.load()

        assertEquals(TerminalConfig.COLUMNS_MAX, loaded.columns)
        assertEquals(TerminalConfig.ROWS_MIN, loaded.rows)
        assertEquals(TerminalConfig.SCROLLBACK_MAX, loaded.scrollbackLines)
        assertEquals(TerminalConfig.FONT_SIZE_MIN, loaded.fontSize)
        assertEquals(TerminalConfig.LINE_HEIGHT_MIN, loaded.lineHeight)
        assertEquals(TerminalConfig.CURSOR_BLINK_MAX, loaded.cursorBlinkMillis)
        assertEquals(PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF, loaded.pasteSanitizationPolicy)

        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager uses field specific security defaults for invalid values`() {
        val tempDir = Files.createTempDirectory("ketraterm-config-test-security-defaults")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        Files.writeString(
            configFile,
            """
            [security]
            clipboard_local_write = "invalid"
            clipboard_remote_write = "invalid"
            clipboard_read = "invalid"
            title_local_permission = "invalid"
            title_remote_permission = "invalid"
            """.trimIndent(),
        )

        val loaded = manager.load()

        assertEquals(TerminalClipboardPermission.PROMPT, loaded.clipboardLocalWrite)
        assertEquals(TerminalClipboardPermission.DENY, loaded.clipboardRemoteWrite)
        assertEquals(TerminalClipboardPermission.DENY, loaded.clipboardRead)
        assertEquals(TerminalTitlePermission.ALLOW, loaded.titleLocalPermission)
        assertEquals(TerminalTitlePermission.DENY, loaded.titleRemotePermission)

        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager clamps clipboard decoded byte boundaries`() {
        val tempDir = Files.createTempDirectory("ketraterm-config-test-clipboard-size")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        Files.writeString(
            configFile,
            """
            [security]
            clipboard_max_decoded_bytes = -1
            """.trimIndent(),
        )

        assertEquals(0, manager.load().clipboardMaxDecodedBytes)

        Files.writeString(
            configFile,
            """
            [security]
            clipboard_max_decoded_bytes = 999999999999999999999999
            """.trimIndent(),
        )

        assertEquals(Int.MAX_VALUE, manager.load().clipboardMaxDecodedBytes)

        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalConfig rejects direct out of bounds values`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(columns = TerminalConfig.COLUMNS_MIN - 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(rows = TerminalConfig.ROWS_MAX + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(scrollbackLines = TerminalConfig.SCROLLBACK_MAX + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(lineHeight = TerminalConfig.LINE_HEIGHT_MIN - 0.1f)
        }
    }

    @Test
    fun `test TerminalWorkspaceConfigManager fallback on invalid format`() {
        val tempDir = Files.createTempDirectory("ketraterm-config-test-invalid")
        val configFile = tempDir.resolve("config.toml")
        val brokenFile = tempDir.resolve("config.toml.broken")
        val manager = TerminalWorkspaceConfigManager(configFile)

        Files.writeString(configFile, "invalid toml syntax here [[[[")

        val loaded = manager.load()
        // Should return default config instead of crashing
        assertEquals("one-dark", loaded.theme)
        assertEquals(100, loaded.columns)

        // Verify it backed up the broken file and generated a new valid file
        assertTrue(Files.exists(brokenFile))
        assertEquals("invalid toml syntax here [[[[", Files.readString(brokenFile))
        assertTrue(Files.exists(configFile))
        assertNotEquals("invalid toml syntax here [[[[", Files.readString(configFile))

        // Clean up
        Files.deleteIfExists(configFile)
        Files.deleteIfExists(brokenFile)
        Files.deleteIfExists(tempDir)
    }
}

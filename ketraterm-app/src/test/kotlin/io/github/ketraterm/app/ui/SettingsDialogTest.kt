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
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.TerminalProfileRegistry
import io.github.ketraterm.workspace.config.TerminalConfig
import io.github.ketraterm.workspace.config.TerminalWorkspaceConfigManager
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsDialogTest {
    @Test
    fun `OK saves off EDT and waits for the single in-flight save before closing`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("ketraterm-settings-dialog")
        val path = directory.resolve("config.toml")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val savedOnEdt = AtomicBoolean(true)
        val manager = TerminalWorkspaceConfigManager(path)
        val settings =
            KetraTermSettings(manager) { snapshot ->
                savedOnEdt.set(SwingUtilities.isEventDispatchThread())
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                manager.save(snapshot)
            }
        val (frame, dialog) =
            onEdt {
                val frame = JFrame()
                val dialog = SettingsDialog(frame, settings, TerminalProfileRegistry(executableExists = { false }))
                dialog.addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosed(event: WindowEvent) {
                            closed.countDown()
                        }
                    },
                )
                dialog.pack()
                val fontSize =
                    components(dialog).filterIsInstance<JSpinner>().first {
                        val model = it.model as SpinnerNumberModel
                        model.minimum == TerminalConfig.FONT_SIZE_MIN && model.maximum == TerminalConfig.FONT_SIZE_MAX
                    }
                fontSize.value = 24
                button(dialog, "OK").doClick()
                frame to dialog
            }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            onEdt {
                assertFalse(button(dialog, "OK").isEnabled)
                assertFalse(button(dialog, "Apply").isEnabled)
                assertFalse(button(dialog, "Cancel").isEnabled)
                dialog.dispose()
                assertTrue(dialog.isDisplayable)
            }
            assertFalse(savedOnEdt.get())
            assertEquals(TerminalConfig.DEFAULT_FONT_SIZE, settings.config.fontSize)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(24, settings.config.fontSize)
            assertEquals(settings.config, manager.load())
        } finally {
            release.countDown()
            onEdt {
                dialog.dispose()
                frame.dispose()
            }
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `dialog preserves configured clipboard permissions and hidden preferences on unrelated save`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val theme = components(dialog).filterIsInstance<JComboBox<*>>().first { it.selectedItem is TerminalTheme }
                theme.selectedItem = TerminalTheme.NORD
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(TerminalTheme.NORD.id, settings.config.theme)
            assertEquals(TerminalClipboardPermission.ALLOWLIST, settings.config.clipboardLocalWrite)
            assertEquals(TerminalClipboardPermission.ALLOWLIST, settings.config.clipboardRemoteWrite)
            assertEquals(TerminalClipboardPermission.ALLOWLIST, settings.config.clipboardRead)
            assertTrue(settings.config.smartSuggestionsEnabled)
            assertFalse(settings.config.shellSuggestionsEnabled)
            assertFalse(settings.config.acceptSelectedSuggestionWithEnter)
            assertTrue(settings.config.persistentSuggestionLearningEnabled)
        }
    }

    @Test
    fun `reset selects the actual default theme and preserves hidden preferences`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                button(dialog, "Reset to Defaults").doClick()
                val theme = components(dialog).filterIsInstance<JComboBox<*>>().first { it.selectedItem is TerminalTheme }
                assertEquals(TerminalTheme.fromId(TerminalConfig.DEFAULT_THEME), theme.selectedItem)
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(TerminalConfig.DEFAULT_THEME, settings.config.theme)
            assertTrue(settings.config.smartSuggestionsEnabled)
            assertFalse(settings.config.shellSuggestionsEnabled)
            assertFalse(settings.config.acceptSelectedSuggestionWithEnter)
            assertTrue(settings.config.persistentSuggestionLearningEnabled)
        }
    }

    @Test
    fun `opening settings preserves shell command spelling instead of replacing it with a discovered path`() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows",
                environment = mapOf("SystemRoot" to "C:/Windows"),
                executableExists = { it.fileName.toString().equals("powershell.exe", ignoreCase = true) },
            )
        for (shellPath in listOf("powershell.exe", "PowerShell.EXE")) {
            withDialog(profileRegistry = registry, shellPath = shellPath) { settings, dialog, closed ->
                onEdt {
                    assertFalse(button(dialog, "Apply").isEnabled)
                    assertTrue(components(dialog).filterIsInstance<JTextField>().any { it.text == shellPath })
                    val theme = components(dialog).filterIsInstance<JComboBox<*>>().first { it.selectedItem is TerminalTheme }
                    theme.selectedItem = TerminalTheme.NORD
                    button(dialog, "OK").doClick()
                }
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertEquals(shellPath, settings.config.shellPath)
            }
        }
    }

    @Test
    fun `dialog preserves unavailable fonts and configured font name casing`() {
        val installed = SwingSettings.getMonospaceFontFamilies().first()
        val differentCase = installed.lowercase().takeIf { it != installed } ?: installed.uppercase()
        for (fontFamily in listOf("Uninstalled Terminal Font", differentCase)) {
            withDialog(fontFamily = fontFamily) { settings, dialog, closed ->
                onEdt {
                    assertFalse(button(dialog, "Apply").isEnabled)
                    val theme = components(dialog).filterIsInstance<JComboBox<*>>().first { it.selectedItem is TerminalTheme }
                    theme.selectedItem = TerminalTheme.NORD
                    button(dialog, "OK").doClick()
                }
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertEquals(fontFamily, settings.config.fontFamily)
            }
        }
    }

    private fun withDialog(
        profileRegistry: TerminalProfileRegistry = TerminalProfileRegistry(executableExists = { false }),
        shellPath: String = TerminalConfig.DEFAULT_SHELL_PATH,
        fontFamily: String = TerminalConfig.DEFAULT_FONT_FAMILY,
        block: (KetraTermSettings, SettingsDialog, CountDownLatch) -> Unit,
    ) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("ketraterm-settings-dialog")
        val path = directory.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(path)
        manager.save(
            TerminalConfig(
                shellPath = shellPath,
                fontFamily = fontFamily,
                theme = TerminalTheme.TOKYO_NIGHT.id,
                smartSuggestionsEnabled = true,
                shellSuggestionsEnabled = false,
                acceptSelectedSuggestionWithEnter = false,
                persistentSuggestionLearningEnabled = true,
                clipboardLocalWrite = TerminalClipboardPermission.ALLOWLIST,
                clipboardRemoteWrite = TerminalClipboardPermission.ALLOWLIST,
                clipboardRead = TerminalClipboardPermission.ALLOWLIST,
            ),
        )
        val settings = KetraTermSettings(manager)
        val closed = CountDownLatch(1)
        val (frame, dialog) =
            onEdt {
                val frame = JFrame()
                val dialog = SettingsDialog(frame, settings, profileRegistry)
                dialog.addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosed(event: WindowEvent) {
                            closed.countDown()
                        }
                    },
                )
                dialog.pack()
                frame to dialog
            }
        try {
            block(settings, dialog, closed)
        } finally {
            onEdt {
                dialog.dispose()
                frame.dispose()
            }
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun button(
        dialog: SettingsDialog,
        text: String,
    ): JButton = components(dialog).filterIsInstance<JButton>().first { it.text == text }

    private fun components(root: Component): Sequence<Component> =
        sequence {
            yield(root)
            if (root is Container) root.components.forEach { yieldAll(components(it)) }
        }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}

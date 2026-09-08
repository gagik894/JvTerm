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
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.host.*
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.TerminalProfileRegistry
import io.github.ketraterm.workspace.config.TerminalConfig
import io.github.ketraterm.workspace.config.TerminalWorkspaceConfigManager
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.*

class SettingsModelTest {
    private lateinit var tempFile: java.nio.file.Path
    private lateinit var settings: KetraTermSettings
    private lateinit var registry: TerminalProfileRegistry
    private lateinit var model: SettingsModel

    @BeforeTest
    fun setUp() {
        tempFile = Files.createTempFile("ketraterm-settings-test", ".toml")
        val manager = TerminalWorkspaceConfigManager(tempFile)
        settings = KetraTermSettings(manager)
        registry = TerminalProfileRegistry(executableExists = { false })
        model = SettingsModel(settings, registry)
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `unrelated settings changes preserve hidden suggestion preferences`() {
        settings.update(
            settings.config.copy(
                smartSuggestionsEnabled = true,
                shellSuggestionsEnabled = false,
                acceptSelectedSuggestionWithEnter = false,
                persistentSuggestionLearningEnabled = true,
            ),
        )
        model.applyChanges(settings.config.copy(fontSize = 24))
        assertTrue(settings.config.smartSuggestionsEnabled)
        assertFalse(settings.config.shellSuggestionsEnabled)
        assertFalse(settings.config.acceptSelectedSuggestionWithEnter)
        assertTrue(settings.config.persistentSuggestionLearningEnabled)
        assertTrue(settings.current().smartSuggestionsEnabled)
    }

    @Test
    fun `applying multiple changes saves and publishes one complete snapshot`() {
        val saved = mutableListOf<TerminalConfig>()
        val notifications = mutableListOf<TerminalConfig>()
        val manager = TerminalWorkspaceConfigManager(tempFile)
        settings =
            KetraTermSettings(manager) { snapshot ->
                assertEquals(TerminalConfig.DEFAULT_FONT_SIZE, settings.config.fontSize)
                saved += snapshot
                manager.save(snapshot)
            }
        model = SettingsModel(settings, registry)
        settings.addChangeListener {
            assertTrue(SwingUtilities.isEventDispatchThread())
            notifications += settings.config
        }
        val updated = settings.config.copy(fontSize = 24, columns = 150, theme = TerminalTheme.NORD.id)

        model.applyChanges(updated)
        SwingUtilities.invokeAndWait {}

        assertEquals(listOf(updated), saved)
        assertEquals(listOf(updated), notifications)
        assertEquals(updated, manager.load())
        assertEquals(updated, model.initialUiState)
        model.applyChanges(updated)
        SwingUtilities.invokeAndWait {}
        assertEquals(1, saved.size)
        assertEquals(1, notifications.size)
    }

    @Test
    fun `failed save preserves active state disk baseline and editable draft`() {
        val manager = TerminalWorkspaceConfigManager(tempFile)
        manager.save(TerminalConfig(fontSize = 18))
        settings = KetraTermSettings(manager) { throw IOException("Disk is full") }
        model = SettingsModel(settings, registry)
        val initial = settings.config
        val draft = initial.copy(fontSize = 24, visualBell = false)
        var notifications = 0
        settings.addChangeListener { notifications++ }

        assertFailsWith<IOException> { model.applyChanges(draft) }
        SwingUtilities.invokeAndWait {}

        assertEquals(initial, settings.config)
        assertEquals(initial, manager.load())
        assertEquals(initial, model.initialUiState)
        assertTrue(model.hasChanges(draft))
        assertEquals(0, notifications)
    }

    @Test
    fun `consumer failures do not skip later consumers or misreport a committed save`() {
        val observedFailures = LinkedBlockingQueue<Throwable>()
        val edtReference = AtomicReference<Thread>()
        SwingUtilities.invokeAndWait { edtReference.set(Thread.currentThread()) }
        val edt = edtReference.get()
        val previousHandler = edt.uncaughtExceptionHandler
        edt.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> observedFailures.add(failure) }
        try {
            val consumerFailure = IllegalStateException("Cannot refresh one pane")
            val otherConsumerFailure = IllegalArgumentException("Cannot refresh another pane")
            settings.addChangeListener { throw consumerFailure }
            settings.addChangeListener { throw consumerFailure }
            settings.addChangeListener { throw otherConsumerFailure }
            var notifications = 0
            settings.addChangeListener { notifications++ }
            val updated = settings.config.copy(fontSize = 24)

            model.applyChanges(updated)

            assertEquals(updated, settings.config)
            assertEquals(updated, model.initialUiState)
            assertFalse(model.hasChanges(updated))
            assertEquals(1, notifications)
            assertSame(consumerFailure, observedFailures.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(otherConsumerFailure), consumerFailure.suppressedExceptions)
        } finally {
            edt.uncaughtExceptionHandler = previousHandler
        }
    }

    @Test
    fun `removed listeners receive no notification`() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        settings.addChangeListener(listener)
        settings.removeChangeListener(listener)
        settings.update(settings.config.copy(fontSize = 24))
        SwingUtilities.invokeAndWait {}
        assertEquals(0, calls)
    }

    @Test
    fun `clipboard choices preserve configured allowlist without offering new allowlists`() {
        for (permission in TerminalClipboardPermission.entries) {
            val options = clipboardPermissionOptions(permission)
            assertTrue(permission in options)
            assertEquals(permission == TerminalClipboardPermission.ALLOWLIST, TerminalClipboardPermission.ALLOWLIST in options)
        }
    }

    @Test
    fun testInitialStateMatchesSettings() {
        val state = model.initialUiState
        assertEquals(settings.theme.id, state.theme)
        assertEquals(settings.config.fontSize, state.fontSize)
        assertEquals(settings.config.columns, state.columns)
        assertEquals(settings.config.shellPath, state.shellPath)
        assertEquals(settings.config.visualBell, state.visualBell)
        assertEquals(settings.config.pasteSanitizationPolicy, state.pasteSanitizationPolicy)
        assertEquals(settings.config.shellRequestResizeWindow, state.shellRequestResizeWindow)
        assertEquals(settings.config.shellRequestWindowManipulation, state.shellRequestWindowManipulation)
        assertEquals(settings.config.scrollOnOutput, state.scrollOnOutput)
        assertFalse(model.hasChanges(state))
    }

    @Test
    fun `command completion stats path uses codec-owned file name`() {
        assertEquals(
            tempFile.resolveSibling(TerminalCompletionLearningCoordinator.currentFileName()),
            settings.commandCompletionStatsPath,
        )
    }

    @Test
    fun testHasChangesWhenUiStateIsModified() {
        val state = settings.config
        assertFalse(model.hasChanges(state))

        // Modify a field
        val modifiedState = state.copy(fontSize = state.fontSize + 2)
        assertTrue(model.hasChanges(modifiedState))

        // Revert it back
        val revertedState = modifiedState.copy(fontSize = state.fontSize)
        assertFalse(model.hasChanges(revertedState))
    }

    @Test
    fun testApplyChangesSavesToSettingsAndUpdatesSnapshot() {
        val state = settings.config
        val modifiedState =
            state.copy(
                fontSize = 22,
                columns = 120,
                visualBell = false,
                pasteSanitizationPolicy = io.github.ketraterm.input.policy.PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                shellRequestResizeWindow = true,
                shellRequestWindowManipulation = true,
                clipboardLocalWrite = TerminalClipboardPermission.ALLOW,
                clipboardRemoteWrite = TerminalClipboardPermission.ALLOWLIST,
                clipboardRead = TerminalClipboardPermission.PROMPT,
                clipboardMaxDecodedBytes = 2048,
                titleLocalPermission = TerminalTitlePermission.DENY,
                titleRemotePermission = TerminalTitlePermission.ALLOW,
                scrollOnOutput = false,
            )

        assertTrue(model.hasChanges(modifiedState))

        model.applyChanges(modifiedState)
        assertEquals(22, settings.config.fontSize)
        assertEquals(120, settings.config.columns)
        assertFalse(settings.config.visualBell)
        assertEquals(
            io.github.ketraterm.input.policy.PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
            settings.config.pasteSanitizationPolicy,
        )
        assertTrue(settings.config.shellRequestResizeWindow)
        assertTrue(settings.config.shellRequestWindowManipulation)
        assertEquals(TerminalClipboardPermission.ALLOW, settings.config.clipboardLocalWrite)
        assertEquals(TerminalClipboardPermission.ALLOWLIST, settings.config.clipboardRemoteWrite)
        assertEquals(TerminalClipboardPermission.PROMPT, settings.config.clipboardRead)
        assertEquals(2048, settings.config.clipboardMaxDecodedBytes)
        assertEquals(TerminalTitlePermission.DENY, settings.config.titleLocalPermission)
        assertEquals(TerminalTitlePermission.ALLOW, settings.config.titleRemotePermission)
        assertFalse(settings.config.scrollOnOutput)

        // Snapshot should be updated, so it shouldn't show changes against modified state anymore
        assertFalse(model.hasChanges(modifiedState))
    }

    @Test
    fun testHostPolicyAllowsResizeControlWhenResizeSettingIsEnabled() {
        settings.update(settings.config.copy(shellRequestResizeWindow = true, shellRequestWindowManipulation = false))

        val policy = settings.createHostPolicy(listOf(settings.config.shellPath))

        assertEquals(HostControlPolicy.ALLOW, policy.windowManipulationPolicy)
    }

    @Test
    fun testHostPolicyMapsLocalAndRemoteTrustBoundaries() {
        settings.update(
            settings.config.copy(
                clipboardLocalWrite = TerminalClipboardPermission.PROMPT,
                clipboardRemoteWrite = TerminalClipboardPermission.DENY,
                clipboardRead = TerminalClipboardPermission.DENY,
                titleLocalPermission = TerminalTitlePermission.ALLOW,
                titleRemotePermission = TerminalTitlePermission.DENY,
            ),
        )

        val localPolicy = settings.createHostPolicy(listOf("powershell.exe"))
        val remotePolicy = settings.createHostPolicy(listOf("ssh", "example.com"))
        val remoteWindowsPathPolicy = settings.createHostPolicy(listOf("""C:\Windows\System32\OpenSSH\ssh.exe"""))
        val nonSshPrefixPolicy = settings.createHostPolicy(listOf("sshuttle"))

        assertEquals(TerminalClipboardOrigin.LOCAL, localPolicy.clipboardPolicy.origin)
        assertEquals(TerminalTitleOrigin.LOCAL, localPolicy.titlePolicy.origin)
        assertEquals(TerminalClipboardPermission.PROMPT, localPolicy.clipboardPolicy.localWritePermission)
        assertEquals(TerminalTitlePermission.ALLOW, localPolicy.titlePolicy.localPermission)

        assertEquals(TerminalClipboardOrigin.REMOTE, remotePolicy.clipboardPolicy.origin)
        assertEquals(TerminalTitleOrigin.REMOTE, remotePolicy.titlePolicy.origin)
        assertEquals(TerminalClipboardPermission.DENY, remotePolicy.clipboardPolicy.remoteWritePermission)
        assertEquals(TerminalTitlePermission.DENY, remotePolicy.titlePolicy.remotePermission)
        assertEquals(TerminalClipboardOrigin.REMOTE, remoteWindowsPathPolicy.clipboardPolicy.origin)
        assertEquals(TerminalTitleOrigin.REMOTE, remoteWindowsPathPolicy.titlePolicy.origin)
        assertEquals(TerminalClipboardOrigin.LOCAL, nonSshPrefixPolicy.clipboardPolicy.origin)
        assertEquals(TerminalTitleOrigin.LOCAL, nonSshPrefixPolicy.titlePolicy.origin)
    }
}

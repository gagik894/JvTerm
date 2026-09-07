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
import io.github.ketraterm.workspace.TerminalProfileRegistry
import io.github.ketraterm.workspace.config.TerminalWorkspaceConfigManager
import java.nio.file.Files
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
        settings.smartSuggestionsEnabled = true
        settings.shellSuggestionsEnabled = false
        settings.acceptSelectedSuggestionWithEnter = false
        settings.persistentSuggestionLearningEnabled = true
        model.applyChanges(model.getSettingsState().copy(fontSize = 24)) {}
        assertTrue(settings.smartSuggestionsEnabled)
        assertFalse(settings.shellSuggestionsEnabled)
        assertFalse(settings.acceptSelectedSuggestionWithEnter)
        assertTrue(settings.persistentSuggestionLearningEnabled)
        assertTrue(settings.current().smartSuggestionsEnabled)
    }

    @Test
    fun testInitialStateMatchesSettings() {
        val state = model.getSettingsState()
        assertEquals(settings.theme.name, state.theme)
        assertEquals(settings.fontSize, state.fontSize)
        assertEquals(settings.columns, state.columns)
        assertEquals(settings.shellPath, state.shellPath)
        assertEquals(settings.visualBell, state.visualBell)
        assertEquals(settings.pasteSanitizationPolicy, state.pasteSanitizationPolicy)
        assertEquals(settings.shellRequestResizeWindow, state.shellRequestResizeWindow)
        assertEquals(settings.shellRequestWindowManipulation, state.shellRequestWindowManipulation)
        assertEquals(settings.scrollOnOutput, state.scrollOnOutput)
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
        val state = model.getSettingsState()
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
        val state = model.getSettingsState()
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

        var applied = false
        model.applyChanges(modifiedState) {
            applied = true
        }

        assertTrue(applied)
        assertEquals(22, settings.fontSize)
        assertEquals(120, settings.columns)
        assertFalse(settings.visualBell)
        assertEquals(io.github.ketraterm.input.policy.PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF, settings.pasteSanitizationPolicy)
        assertTrue(settings.shellRequestResizeWindow)
        assertTrue(settings.shellRequestWindowManipulation)
        assertEquals(TerminalClipboardPermission.ALLOW, settings.clipboardLocalWrite)
        assertEquals(TerminalClipboardPermission.ALLOWLIST, settings.clipboardRemoteWrite)
        assertEquals(TerminalClipboardPermission.PROMPT, settings.clipboardRead)
        assertEquals(2048, settings.clipboardMaxDecodedBytes)
        assertEquals(TerminalTitlePermission.DENY, settings.titleLocalPermission)
        assertEquals(TerminalTitlePermission.ALLOW, settings.titleRemotePermission)
        assertFalse(settings.scrollOnOutput)

        // Snapshot should be updated, so it shouldn't show changes against modified state anymore
        assertFalse(model.hasChanges(modifiedState))
    }

    @Test
    fun testHostPolicyAllowsResizeControlWhenResizeSettingIsEnabled() {
        settings.shellRequestResizeWindow = true
        settings.shellRequestWindowManipulation = false

        val policy = settings.createHostPolicy(listOf(settings.shellPath))

        assertEquals(HostControlPolicy.ALLOW, policy.windowManipulationPolicy)
    }

    @Test
    fun testHostPolicyMapsLocalAndRemoteTrustBoundaries() {
        settings.clipboardLocalWrite = TerminalClipboardPermission.PROMPT
        settings.clipboardRemoteWrite = TerminalClipboardPermission.DENY
        settings.clipboardRead = TerminalClipboardPermission.DENY
        settings.titleLocalPermission = TerminalTitlePermission.ALLOW
        settings.titleRemotePermission = TerminalTitlePermission.DENY

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

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
package io.github.ketraterm.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.github.ketraterm.host.TerminalClipboardOrigin
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.ui.KetraTermTerminalPane
import io.github.ketraterm.intellij.ui.KetraTermTerminalPaneHostActions
import io.github.ketraterm.intellij.ui.KetraTermTerminalStartupView
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.workspace.*
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Project-level owner for IntelliJ-hosted KetraTerm tabs and sessions.
 *
 * The service adapts IntelliJ `Content` tabs to the host-neutral
 * [TerminalWorkspace]. Closing an IDE tab disposes the corresponding pane and
 * terminal session; closing the project disposes all remaining sessions.
 *
 * @property project IntelliJ project that owns this terminal workspace.
 */
@Service(Service.Level.PROJECT)
class KetraTermProjectTerminalService(
    private val project: Project,
) : Disposable {
    private val contentsByTabId = LinkedHashMap<String, Content>()
    private val pendingTabsById = LinkedHashMap<String, PendingTerminalTab>()
    private val panesByTabId = LinkedHashMap<String, KetraTermTerminalPane>()
    private val closeListenersByTabId = LinkedHashMap<String, ContentCloseQueryRegistration>()
    private val workspace = TerminalWorkspace(IntellijWorkspaceListener())
    private val workspaceLock = Any()
    private val nextPendingTabNumber = AtomicInteger(1)
    private val ptyRuntime = IntelliJPtyRuntime()
    private val settingsChangedListener = { reloadOpenTerminalSettings() }
    private var lastToolWindow: ToolWindow? = null
    private var disposed = false

    init {
        KetraTermIntellijSettings.getInstance().addChangeListener(settingsChangedListener)
    }

    /**
     * Returns true when this project already has an open terminal tab.
     */
    fun hasOpenTabs(): Boolean = contentsByTabId.isNotEmpty() || pendingTabsById.isNotEmpty()

    /**
     * Finds the terminal pane that owns [component].
     *
     * Registered IntelliJ actions use this to scope global keymap shortcuts to
     * the focused KetraTerm terminal pane without installing per-pane action
     * instances.
     *
     * @param component focused Swing component from the action event context.
     * @return owning terminal pane, or `null` when focus is outside KetraTerm.
     */
    internal fun paneForComponent(component: Component?): KetraTermTerminalPane? {
        if (component == null || disposed) return null
        for (pane in panesByTabId.values) {
            if (component === pane.component || SwingUtilities.isDescendingFrom(component, pane.component)) {
                return pane
            }
        }
        return null
    }

    /**
     * Opens the initial terminal tab if no terminal content exists yet.
     *
     * @param toolWindow target IntelliJ tool window.
     */
    fun ensureInitialTab(toolWindow: ToolWindow) {
        lastToolWindow = toolWindow
        if (hasOpenTabs()) return
        openDefaultTab(toolWindow)
    }

    /**
     * Opens one local terminal tab in [toolWindow].
     *
     * @param toolWindow target IntelliJ tool window.
     * @return created content tab containing either a pending, running, or failure state.
     */
    fun openDefaultTab(toolWindow: ToolWindow): Content {
        check(!disposed) { "KetraTerm project terminal service is disposed" }

        lastToolWindow = toolWindow
        val settingsService = KetraTermIntellijSettings.getInstance()
        val settingsState = settingsService.state
        val profile = KetraTermDefaultProfileFactory.defaultProfile(project, settingsState)
        val settings = settingsService.current()
        return openTab(toolWindow, profile, settings)
    }

    /**
     * Opens one local terminal tab for an explicitly selected profile.
     *
     * @param toolWindow target IntelliJ tool window.
     * @param profile selected shell profile discovered by the shared profile registry.
     * @return created content tab.
     */
    fun openProfileTab(
        toolWindow: ToolWindow,
        profile: TerminalProfile,
    ): Content {
        check(!disposed) { "KetraTerm project terminal service is disposed" }

        lastToolWindow = toolWindow
        val settingsState = KetraTermIntellijSettings.getInstance().state
        val configuredProfile =
            KetraTermDefaultProfileFactory.profileForSelectedShell(project, profile, settingsState)
        val settings = KetraTermIntellijSettings.getInstance().current()
        return openTab(toolWindow, configuredProfile, settings)
    }

    private fun openTab(
        toolWindow: ToolWindow,
        profile: TerminalProfile,
        settings: SwingSettings,
    ): Content {
        val pendingId = "pending-terminal-${nextPendingTabNumber.getAndIncrement()}"
        val container =
            JPanel(BorderLayout()).apply {
                border = null
                add(KetraTermTerminalStartupView.starting(profile.displayName), BorderLayout.CENTER)
            }
        val contentManager = toolWindow.contentManager
        val content =
            contentManager.factory.createContent(
                container,
                profile.displayName,
                false,
            )

        content.isCloseable = true
        @Suppress("UsePropertyAccessSyntax")
        content.setDisposer(PendingTerminalTabDisposable(pendingId))

        pendingTabsById[pendingId] = PendingTerminalTab(content, container)

        contentManager.addContent(content)
        contentManager.setSelectedContent(content, true)

        startTerminalTabInBackground(
            pendingId = pendingId,
            profileName = profile.displayName,
            start = {
                synchronized(workspaceLock) {
                    ptyRuntime.openWorkspaceTab(
                        workspace = workspace,
                        profile = profile,
                        options = openOptions(settings, profile),
                    )
                }
            },
        )
        return content
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        val panes = panesByTabId.values.toList()
        val closeRegistrations = closeListenersByTabId.values.toList()
        for (registration in closeRegistrations) {
            registration.manager.removeContentManagerListener(registration.listener)
        }

        panesByTabId.clear()
        contentsByTabId.clear()
        pendingTabsById.clear()
        closeListenersByTabId.clear()

        for (pane in panes) {
            pane.close()
        }
        KetraTermIntellijSettings.getInstance().removeChangeListener(settingsChangedListener)
        synchronized(workspaceLock) {
            workspace.close()
        }
    }

    private fun closeTabFromContent(tabId: String) {
        if (disposed) return

        val pane = panesByTabId.remove(tabId)
        val content = contentsByTabId.remove(tabId)
        removeCloseQueryListener(tabId)
        if (pane == null && content == null) return

        pane?.close()
        synchronized(workspaceLock) {
            workspace.closeTab(tabId)
        }
    }

    private fun closeTabAfterRemoteSessionExit(tab: TerminalWorkspaceTab) {
        if (disposed) return

        val pane = panesByTabId.remove(tab.id) ?: return
        val content = contentsByTabId.remove(tab.id)
        removeCloseQueryListener(tab.id)

        pane.close()
        synchronized(workspaceLock) {
            workspace.closeTab(tab.id)
        }

        content?.manager?.removeContent(content, true)

        if (!hasOpenTabs()) {
            lastToolWindow?.let(::openDefaultTab)
        }
    }

    private fun closePendingTab(pendingId: String) {
        pendingTabsById.remove(pendingId)
    }

    private fun startTerminalTabInBackground(
        pendingId: String,
        profileName: String,
        start: () -> TerminalWorkspaceTab,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result =
                try {
                    TerminalStartupResult.Started(start())
                } catch (exception: Exception) {
                    TerminalStartupResult.Failed(exception)
                } catch (error: LinkageError) {
                    TerminalStartupResult.Failed(error)
                }
            invokeLaterIfAlive {
                publishTerminalStartupResult(pendingId, profileName, result)
            }
        }
    }

    private fun publishTerminalStartupResult(
        pendingId: String,
        profileName: String,
        result: TerminalStartupResult,
    ) {
        val pendingTab = pendingTabsById[pendingId]
        if (pendingTab == null) {
            if (result is TerminalStartupResult.Started) {
                synchronized(workspaceLock) {
                    workspace.closeTab(result.tab.id)
                }
            }
            return
        }

        when (result) {
            is TerminalStartupResult.Started -> {
                pendingTabsById.remove(pendingId)
                bindStartedTab(pendingTab, result.tab)
            }
            is TerminalStartupResult.Failed -> showStartupFailure(pendingTab, profileName, result.error)
        }
    }

    private fun bindStartedTab(
        pendingTab: PendingTerminalTab,
        workspaceTab: TerminalWorkspaceTab,
    ) {
        val pane =
            KetraTermTerminalPane.create(
                project = project,
                tab = workspaceTab,
                hostActions =
                    KetraTermTerminalPaneHostActions(
                        openNewTabAction = ::openDefaultTabFromContextMenu,
                        canOpenTerminalHereAction = ::canOpenTerminalHere,
                        openTerminalHereAction = ::openTerminalHere,
                        closePaneAction = ::closePaneFromContextMenu,
                    ),
            )
        replaceContent(pendingTab.container, pane.component)
        pendingTab.content.displayName = workspaceTab.title
        pendingTab.content.preferredFocusableComponent = pane.terminal
        @Suppress("UsePropertyAccessSyntax")
        pendingTab.content.setDisposer(TerminalTabDisposable(workspaceTab.id))
        installCloseQueryListener(pendingTab.content, workspaceTab)
        contentsByTabId[workspaceTab.id] = pendingTab.content
        panesByTabId[workspaceTab.id] = pane
        pane.requestFocus()
    }

    private fun showStartupFailure(
        pendingTab: PendingTerminalTab,
        profileName: String,
        error: Throwable,
    ) {
        pendingTab.content.displayName = "Failed: $profileName"
        replaceContent(
            pendingTab.container,
            KetraTermTerminalStartupView.failure(profileName, error),
        )
    }

    private fun replaceContent(
        container: JPanel,
        component: Component,
    ) {
        container.removeAll()
        container.add(component, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
    }

    private fun openDefaultTabFromContextMenu(): Boolean {
        val toolWindow = lastToolWindow ?: return false
        openDefaultTab(toolWindow)
        return true
    }

    private fun canOpenTerminalHere(tab: TerminalWorkspaceTab): Boolean =
        lastToolWindow != null && IntellijWorkingDirectoryResolver.resolve(tab.currentWorkingDirectoryUri) != null

    private fun openTerminalHere(tab: TerminalWorkspaceTab): Boolean {
        val toolWindow = lastToolWindow ?: return false
        val workingDirectory = IntellijWorkingDirectoryResolver.resolve(tab.currentWorkingDirectoryUri) ?: return false
        openTab(
            toolWindow = toolWindow,
            profile = tab.profile.copy(workingDirectory = workingDirectory),
            settings = KetraTermIntellijSettings.getInstance().current(),
        )
        return true
    }

    private fun closePaneFromContextMenu(tab: TerminalWorkspaceTab) {
        val content = contentsByTabId[tab.id] ?: return
        content.manager?.removeContent(content, true)
    }

    private fun reloadOpenTerminalSettings() {
        invokeLaterIfAlive {
            for (pane in panesByTabId.values) {
                pane.reloadSettings()
            }
        }
    }

    private fun openOptions(
        settings: SwingSettings,
        profile: TerminalProfile,
    ): TerminalWorkspaceOpenOptions =
        TerminalWorkspaceOpenOptions(
            columns = settings.columns,
            rows = settings.rows,
            treatAmbiguousAsWide = settings.treatAmbiguousAsWide,
            maxHistory = settings.scrollbackLines,
            pasteSanitizationPolicy = settings.pasteSanitizationPolicy,
            hostPolicy = KetraTermIntellijSettings.getInstance().createHostPolicy(profile.command),
        )

    private fun installCloseQueryListener(
        content: Content,
        tab: TerminalWorkspaceTab,
    ) {
        val listener =
            object : ContentManagerListener {
                override fun contentRemoveQuery(event: ContentManagerEvent) {
                    if (event.content !== content) return
                    if (!confirmLiveProcessClose(tab)) {
                        event.consume()
                    }
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    if (event.content !== content) return
                    removeCloseQueryListener(tab.id)
                }
            }
        val manager = content.manager ?: return
        manager.addContentManagerListener(listener)
        closeListenersByTabId[tab.id] = ContentCloseQueryRegistration(manager, listener)
    }

    private fun removeCloseQueryListener(tabId: String) {
        val registration = closeListenersByTabId.remove(tabId) ?: return
        registration.manager.removeContentManagerListener(registration.listener)
    }

    private fun confirmLiveProcessClose(tab: TerminalWorkspaceTab): Boolean {
        if (!tab.session.shellIntegrationState.hasRunningCommand()) return true
        val answer =
            Messages.showYesNoDialog(
                project,
                "Closing \"${tab.title}\" will terminate its running process.",
                "Terminate Terminal Process?",
                "Terminate",
                "Cancel",
                Messages.getWarningIcon(),
            )
        return answer == Messages.YES
    }

    private inner class TerminalTabDisposable(
        private val tabId: String,
    ) : Disposable {
        override fun dispose() {
            closeTabFromContent(tabId)
        }
    }

    private inner class PendingTerminalTabDisposable(
        private val pendingId: String,
    ) : Disposable {
        override fun dispose() {
            closePendingTab(pendingId)
        }
    }

    private inner class IntellijWorkspaceListener : TerminalWorkspaceListener {
        override fun shellIntegrationMarker(
            tab: TerminalWorkspaceTab,
            event: ShellIntegrationEvent,
        ) {
            if (!KetraTermIntellijSettings.getInstance().state.smartSuggestionsEnabled ||
                event.marker != ShellIntegrationMarker.COMMAND_FINISHED
            ) {
                return
            }
            val state = tab.session.shellIntegrationState
            val metadata = state.commandMetadata(state.latestCommandRecordId()) ?: return
            KetraTermCompletionService.getInstanceIfCreated()?.recordFinishedCommand(tab, metadata)
        }

        override fun bell(tab: TerminalWorkspaceTab) {
            invokeLaterIfAlive {
                panesByTabId[tab.id]?.terminal?.showVisualBell()
            }
        }

        override fun titleChanged(
            tab: TerminalWorkspaceTab,
            title: String,
        ) {
            invokeLaterIfAlive {
                contentsByTabId[tab.id]?.displayName = title
            }
        }

        override fun showNotification(
            tab: TerminalWorkspaceTab,
            title: String,
            body: String,
            level: NotificationLevel,
        ) {
            invokeLaterIfAlive {
                KetraTermIntellijNotifier.showNotification(project, title, body, level)
            }
        }

        override fun terminalClipboardWrite(
            tab: TerminalWorkspaceTab,
            event: TerminalClipboardWriteEvent,
        ) {
            if (!IntellijOsc52ClipboardSelections.targetsIdeClipboard(event.selection)) return
            invokeLaterIfAlive {
                panesByTabId[tab.id]?.terminal?.copyTextToClipboard(event.text)
            }
        }

        override fun terminalClipboardPrompt(
            tab: TerminalWorkspaceTab,
            event: TerminalClipboardPromptEvent,
        ) {
            if (!IntellijOsc52ClipboardSelections.targetsIdeClipboard(event.selection)) return
            invokeLaterIfAlive {
                val pane = panesByTabId[tab.id] ?: return@invokeLaterIfAlive
                val answer =
                    Messages.showYesNoDialog(
                        project,
                        IntellijOsc52ClipboardPromptText.message(tab.profile.displayName, event),
                        IntellijOsc52ClipboardPromptText.title(),
                        Messages.getWarningIcon(),
                    )
                if (answer == Messages.YES) {
                    pane.terminal.copyTextToClipboard(event.text)
                }
            }
        }

        override fun tabClosed(tabId: String) {
            invokeLaterIfAlive {
                contentsByTabId.remove(tabId)
                panesByTabId.remove(tabId)
                removeCloseQueryListener(tabId)
            }
        }

        override fun sessionClosed(
            tab: TerminalWorkspaceTab,
            exitCode: Int?,
            failure: Throwable?,
        ) {
            invokeLaterIfAlive {
                closeTabAfterRemoteSessionExit(tab)
            }
        }
    }

    private fun invokeLaterIfAlive(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                action()
            }
        }
    }

    companion object {
        /**
         * Returns the terminal service for [project].
         *
         * @param project IntelliJ project.
         * @return project terminal service.
         */
        fun getInstance(project: Project): KetraTermProjectTerminalService = project.service()
    }

    private data class PendingTerminalTab(
        val content: Content,
        val container: JPanel,
    )

    private data class ContentCloseQueryRegistration(
        val manager: ContentManager,
        val listener: ContentManagerListener,
    )

    private sealed interface TerminalStartupResult {
        data class Started(
            val tab: TerminalWorkspaceTab,
        ) : TerminalStartupResult

        data class Failed(
            val error: Throwable,
        ) : TerminalStartupResult
    }
}

internal object IntellijOsc52ClipboardSelections {
    fun targetsIdeClipboard(selection: String): Boolean = selection.isEmpty() || selection.indexOf('c') >= 0
}

internal object IntellijOsc52ClipboardPromptText {
    fun title(): String = "Clipboard Access"

    fun message(
        profileName: String,
        event: TerminalClipboardPromptEvent,
    ): String = question(profileName, event) + "\n\n" + detail(event)

    private fun question(
        profileName: String,
        event: TerminalClipboardPromptEvent,
    ): String {
        val applicationName = profileName.trim().ifBlank { "this terminal" }
        if (event.text.isEmpty()) {
            return "Allow $applicationName to clear the IDE clipboard?"
        }
        val count = event.text.codePointCount(0, event.text.length)
        return "Allow $applicationName to write ${count.formatCount("character")} to the IDE clipboard?"
    }

    private fun detail(event: TerminalClipboardPromptEvent): String =
        when (event.audit.origin) {
            TerminalClipboardOrigin.LOCAL -> "Local terminal session"
            TerminalClipboardOrigin.REMOTE -> "Remote terminal session"
        }

    private fun Int.formatCount(unit: String): String =
        if (this == 1) {
            "1 $unit"
        } else {
            "$this ${unit}s"
        }
}

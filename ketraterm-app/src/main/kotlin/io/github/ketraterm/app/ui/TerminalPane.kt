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
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuHandler
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuRequest
import io.github.ketraterm.ui.swing.host.SwingCompletionBinding
import io.github.ketraterm.ui.swing.host.SwingCompletionResources
import io.github.ketraterm.ui.swing.host.SwingTerminalOverlayPane
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionKeymap
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

/**
 * Owns one terminal pane in the standalone host.
 *
 * A pane binds a workspace tab's session to one reusable Swing terminal
 * component.
 */
internal class TerminalPane private constructor(
    val tab: TerminalWorkspaceTab,
    val terminal: SwingTerminal,
    val component: JPanel,
    private val settings: KetraTermSettings,
    private var completionResources: SwingCompletionResources?,
    private val completionBinding: SwingCompletionBinding,
    private val searchBar: SwingTerminalSearchBar,
) : TerminalPaneActionTarget {
    private val closed = AtomicBoolean()
    private var shortcutController: TerminalPaneShortcutController? = null

    fun requestFocus() {
        terminal.requestFocusInWindow()
    }

    fun reloadSettings() {
        terminal.reloadSettings()
        component.background = terminal.background
        searchBar.refreshColors()
        tab.session.setHostPolicy(settings.createHostPolicy(tab.profile.command))
        completionBinding.update(completionResources.takeIf { settings.smartSuggestionsEnabled }, settings.shellSuggestionsEnabled)
    }

    fun setCompletionResources(resources: SwingCompletionResources?) {
        if (closed.get()) return
        completionResources = resources
        completionBinding.update(resources.takeIf { settings.smartSuggestionsEnabled }, settings.shellSuggestionsEnabled)
    }

    override fun suggestionsEnabled(): Boolean = settings.smartSuggestionsEnabled && completionBinding.isEnabled

    override fun hasSelection(): Boolean = terminal.currentSelection() != null

    override fun copySelectionToClipboard(): Boolean = terminal.copySelectionToClipboard()

    override fun pasteClipboardText(): Boolean = terminal.pasteClipboardText()

    override fun selectAll(): Boolean = terminal.selectAll()

    override fun clearScreen(): Boolean = terminal.clearScreen()

    override fun requestShellSuggestions() {
        if (suggestionsEnabled()) terminal.requestActiveShellSuggestions()
    }

    override fun openSearch() {
        searchBar.open()
    }

    override fun scrollPageUp() {
        terminal.scrollViewportBy(
            terminal
                .visibleGridSize()
                .height
                .coerceAtLeast(1)
                .toDouble(),
        )
    }

    override fun scrollPageDown() {
        terminal.scrollViewportBy(
            -terminal
                .visibleGridSize()
                .height
                .coerceAtLeast(1)
                .toDouble(),
        )
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val shortcut = shortcutController
        shortcutController = null
        var failure: Throwable? = null
        failure = captureCleanupFailure(failure, completionBinding::close)
        completionResources = null
        failure = captureCleanupFailure(failure, searchBar::close)
        failure = captureCleanupFailure(failure) { shortcut?.dispose() }
        failure = captureCleanupFailure(failure, terminal::dispose)
        failure?.let { throw it }
    }

    internal companion object {
        fun create(
            tab: TerminalWorkspaceTab,
            settings: KetraTermSettings,
            completionResources: SwingCompletionResources?,
            onContextMenu: (TerminalPane, SwingTerminalContextMenuRequest) -> Unit,
        ): TerminalPane {
            val shortcutControllerRef = arrayOfNulls<TerminalPaneShortcutController>(1)
            val paneRef = arrayOfNulls<TerminalPane>(1)
            val completionBinding = SwingCompletionBinding(tab.session) { tab.currentWorkingDirectoryUri }
            var ownedTerminal: SwingTerminal? = null
            var ownedSearchBar: SwingTerminalSearchBar? = null
            var ownedPane: TerminalPane? = null
            return try {
                val terminal =
                    SwingTerminal(
                        settingsProvider = { settings.current() },
                        hostServices =
                            SwingHostServices(
                                shellSuggestionProvider = completionBinding.provider,
                                shellSuggestionHandler = SwingShellSuggestionHandler.createDefault(tab.session),
                                shellSuggestionFeedbackHandler = completionBinding.feedbackHandler,
                                shellSuggestionKeymap = SwingShellSuggestionKeymap.STANDARD,
                                hostKeyHandler = { event -> shortcutControllerRef[0]?.handleKeyPressed(event) == true },
                                contextMenuHandler =
                                    SwingTerminalContextMenuHandler { request ->
                                        val pane = paneRef[0] ?: return@SwingTerminalContextMenuHandler false
                                        onContextMenu(pane, request)
                                        true
                                    },
                            ),
                    )

                ownedTerminal = terminal
                terminal.bind(tab.session)

                val searchBar = SwingTerminalSearchBar(terminal)
                ownedSearchBar = searchBar
                val component = terminalPanel(terminal, searchBar)
                val pane =
                    TerminalPane(
                        tab = tab,
                        terminal = terminal,
                        component = component,
                        settings = settings,
                        completionResources = completionResources,
                        completionBinding = completionBinding,
                        searchBar = searchBar,
                    )
                ownedPane = pane
                pane.shortcutController = TerminalPaneShortcutController(pane, settings)
                shortcutControllerRef[0] = pane.shortcutController
                paneRef[0] = pane
                tab.session.requestRender(scrollbackOffset = 0)
                completionBinding.attach(terminal)
                pane.setCompletionResources(completionResources)
                pane
            } catch (failure: Throwable) {
                var cleanupFailure: Throwable? = failure
                val pane = ownedPane
                if (pane != null) {
                    cleanupFailure = captureCleanupFailure(cleanupFailure, pane::close)
                } else {
                    cleanupFailure = captureCleanupFailure(cleanupFailure, completionBinding::close)
                    cleanupFailure = captureCleanupFailure(cleanupFailure) { ownedSearchBar?.close() }
                    cleanupFailure = captureCleanupFailure(cleanupFailure) { ownedTerminal?.dispose() }
                }
                throw requireNotNull(cleanupFailure)
            }
        }

        private fun terminalPanel(
            terminal: SwingTerminal,
            searchBar: SwingTerminalSearchBar,
        ): JPanel =
            SwingTerminalOverlayPane(terminal, searchBar.component).apply {
                background = terminal.background
                border = null
                terminal.border = null
            }
    }
}

internal inline fun captureCleanupFailure(
    previous: Throwable?,
    action: () -> Unit,
): Throwable? =
    try {
        action()
        previous
    } catch (failure: Throwable) {
        previous?.apply {
            if (this !== failure) addSuppressed(failure)
        } ?: failure
    }

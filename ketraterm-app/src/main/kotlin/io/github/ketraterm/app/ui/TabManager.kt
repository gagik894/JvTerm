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

import io.github.ketraterm.app.completion.StandaloneCompletionRegistry
import io.github.ketraterm.app.completion.completionShellCapabilities
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuRequest
import io.github.ketraterm.workspace.*
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns standalone terminal tabs and tab-scoped session lifecycle.
 *
 * The manager coordinates between [TabBar] (visual header), the
 * [tabContentPanel] (holds one [TerminalPane] per tab via [CardLayout]),
 * and the backing [TerminalWorkspace]. All session lifecycle decisions live
 * here; reusable UI components remain policy-free.
 *
 * @property defaultProfileProvider supplier invoked each time a new default tab
 *   or split pane is opened. Queried at open time — not cached — so that changes
 *   to the configured shell take effect immediately on the next new tab.
 */
internal class TabManager(
    private val frame: JFrame,
    val tabBar: TabBar,
    private val tabContentPanel: JPanel,
    private val settings: KetraTermSettings,
    private val defaultProfileProvider: () -> TerminalProfile,
    private val closeConfirmation: TerminalCloseConfirmation = SwingTerminalCloseConfirmation(frame),
) {
    private val panes = ArrayList<TerminalPane>(INITIAL_TAB_CAPACITY)
    private val workspace = TerminalWorkspace(StandaloneWorkspaceListener())
    private val tabRoots = HashMap<String, SplitNode>()
    private val tabContainers = HashMap<String, JPanel>()

    @Volatile private var completionRegistry: StandaloneCompletionRegistry? = null
    private val completionLifecycle = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var completionShutdown: Job? = null
    private val shutdownStarted = AtomicBoolean()
    val selectedPane: TerminalPane?
        get() = tabBar.selectedId()?.let { getActivePane(it) }

    private val isMac =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")

    private val keyEventDispatcher =
        KeyEventDispatcher { event ->
            if (event.id == KeyEvent.KEY_PRESSED) {
                val keyCode = event.keyCode
                val modifiers = event.modifiersEx
                val menuShortcutMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
                val isMenuShortcut = (modifiers and menuShortcutMask) == menuShortcutMask
                val isCtrlPressed = (modifiers and InputEvent.CTRL_DOWN_MASK) == InputEvent.CTRL_DOWN_MASK
                val isShiftPressed = (modifiers and InputEvent.SHIFT_DOWN_MASK) == InputEvent.SHIFT_DOWN_MASK
                val hasAlt = (modifiers and InputEvent.ALT_DOWN_MASK) != 0

                // 1. Tab cycling: Ctrl + Tab / Ctrl + Shift + Tab (on all platforms)
                if (keyCode == KeyEvent.VK_TAB && isCtrlPressed && !hasAlt) {
                    if (isShiftPressed) {
                        switchToPrevTab()
                    } else {
                        switchToNextTab()
                    }
                    return@KeyEventDispatcher true
                }

                // 2. Open/Close/Split tab/panes:
                if (isMac) {
                    if (isMenuShortcut && !hasAlt) {
                        when (keyCode) {
                            KeyEvent.VK_T -> {
                                if (!isShiftPressed) {
                                    openTab(defaultProfileProvider())
                                    return@KeyEventDispatcher true
                                }
                            }
                            KeyEvent.VK_W -> {
                                if (!isShiftPressed) {
                                    closeActivePane()
                                    return@KeyEventDispatcher true
                                }
                            }
                            KeyEvent.VK_E -> {
                                if (isShiftPressed) {
                                    selectedPane?.let { splitPane(it, isVertical = true) }
                                    return@KeyEventDispatcher true
                                }
                            }
                            KeyEvent.VK_O -> {
                                if (isShiftPressed) {
                                    selectedPane?.let { splitPane(it, isVertical = false) }
                                    return@KeyEventDispatcher true
                                }
                            }
                        }
                    }
                } else {
                    if (isCtrlPressed && isShiftPressed && !hasAlt) {
                        when (keyCode) {
                            KeyEvent.VK_T -> {
                                openTab(defaultProfileProvider())
                                return@KeyEventDispatcher true
                            }
                            KeyEvent.VK_W -> {
                                closeActivePane()
                                return@KeyEventDispatcher true
                            }
                            KeyEvent.VK_E -> {
                                selectedPane?.let { splitPane(it, isVertical = true) }
                                return@KeyEventDispatcher true
                            }
                            KeyEvent.VK_O -> {
                                selectedPane?.let { splitPane(it, isVertical = false) }
                                return@KeyEventDispatcher true
                            }
                        }
                    }
                }
            }
            false
        }

    init {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        try {
            focusManager.addKeyEventDispatcher(keyEventDispatcher)
        } catch (failure: Throwable) {
            var cleanupFailure: Throwable? = failure
            cleanupFailure = captureCleanupFailure(cleanupFailure) { focusManager.removeKeyEventDispatcher(keyEventDispatcher) }
            cleanupFailure = captureCleanupFailure(cleanupFailure, ::closeCompletionLearningWithinBudget)
            throw requireNotNull(cleanupFailure)
        }
    }

    fun selectTab(id: String) {
        tabBar.selectTab(id)
        onTabSelected(id)
    }

    private fun switchToNextTab() {
        val nextTabId = getNeighborTabId(forward = true) ?: return
        selectTab(nextTabId)
    }

    private fun switchToPrevTab() {
        val prevTabId = getNeighborTabId(forward = false) ?: return
        selectTab(prevTabId)
    }

    private fun getNeighborTabId(forward: Boolean): String? {
        val currentId = tabBar.selectedId() ?: return null
        val tabIds = tabRoots.keys.toList()
        if (tabIds.size <= 1) return null
        val currentIndex = tabIds.indexOf(currentId)
        if (currentIndex == -1) return null
        val step = if (forward) 1 else -1
        val nextIndex = (currentIndex + step + tabIds.size) % tabIds.size
        return tabIds[nextIndex]
    }

    /**
     * Opens a new terminal tab backed by [profile].
     *
     * Returns `true` on success, `false` if the PTY failed to start
     * (an error dialog is shown to the user in that case).
     */
    fun openTab(profile: TerminalProfile): Boolean {
        val workspaceTab =
            try {
                workspace.openTab(
                    profile = profile,
                    options =
                        settings.current().let { snapshot ->
                            TerminalWorkspaceOpenOptions(
                                columns = snapshot.columns,
                                rows = snapshot.rows,
                                treatAmbiguousAsWide = snapshot.treatAmbiguousAsWide,
                                maxHistory = snapshot.scrollbackLines,
                                pasteSanitizationPolicy = snapshot.pasteSanitizationPolicy,
                                hostPolicy = settings.createHostPolicy(profile.command),
                            )
                        },
                )
            } catch (exception: Exception) {
                showStartError(profile, exception)
                return false
            }

        val createdPane = createTerminalPane(workspaceTab)
        panes += createdPane

        val tabId = createdPane.tab.id
        val leaf = LeafNode(createdPane)
        tabRoots[tabId] = leaf

        val container =
            JPanel(BorderLayout()).apply {
                background = Chrome.terminalBackground
                isOpaque = true
                add(leaf.component, BorderLayout.CENTER)
            }
        tabContainers[tabId] = container

        tabContentPanel.add(container, tabId)
        tabBar.addTab(TabEntry(id = tabId, title = workspaceTab.title, profileKind = profile.kind))
        showPane(tabId)
        updateFrameTitle()
        createdPane.requestFocus()
        return true
    }

    /**
     * Closes the tab identified by [id].
     *
     * @return true when the tab is now closed or was already unknown; false
     * when the user rejected closing a live process.
     */
    fun closeTab(id: String): Boolean {
        val root = tabRoots[id] ?: return true
        if (!confirmClose(root)) return false
        closeTabWithoutConfirmation(id, root)
        return true
    }

    private fun closeTabWithoutConfirmation(
        id: String,
        root: SplitNode,
    ) {
        val tabPanes = root.allPanes()
        var failure: Throwable? = null
        for (pane in tabPanes) {
            panes.remove(pane)
            failure = captureCleanupFailure(failure, pane::close)
            failure = captureCleanupFailure(failure) { workspace.closeTab(pane.tab.id) }
        }
        tabRoots.remove(id)
        val container = tabContainers.remove(id)
        if (container != null) {
            failure = captureCleanupFailure(failure) { tabContentPanel.remove(container) }
        }
        failure = captureCleanupFailure(failure) { tabBar.removeTab(id) }
        failure = captureCleanupFailure(failure, ::updateFrameTitle)
        failure = captureCleanupFailure(failure) { selectedPane?.let { onTabSelected(it.tab.id) } }
        failure?.let { throw it }
    }

    /**
     * Closes every open tab and shuts down the workspace after one aggregated
     * confirmation for all live processes.
     *
     * @return true when shutdown completed; false when the user cancelled.
     */
    fun closeAllTabs(): Boolean {
        if (!confirmCloseAllTabs()) return false
        closeAllTabsWithoutConfirmation()
        return true
    }

    /** Closes every open tab and starts bounded completion persistence without blocking the Swing EDT. */
    fun closeAllTabsWithoutConfirmation() {
        if (!shutdownStarted.compareAndSet(false, true)) return
        var failure: Throwable? = null
        failure =
            captureCleanupFailure(failure) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)
            }
        val tabIds = tabRoots.keys.toList()
        for (tabId in tabIds) {
            tabRoots[tabId]?.let { root ->
                failure = captureCleanupFailure(failure) { closeTabWithoutConfirmation(tabId, root) }
            }
        }
        failure = captureCleanupFailure(failure, workspace::close)
        failure = captureCleanupFailure(failure, ::startCompletionLearningShutdown)
        failure?.let { throw it }
    }

    private fun startCompletionLearningShutdown() {
        if (completionRegistry == null && completionShutdown == null) {
            completionLifecycle.cancel()
            return
        }
        Thread
            .ofPlatform()
            .name("ketraterm-completion-shutdown")
            .daemon(false)
            .start {
                try {
                    closeCompletionLearningWithinBudget()
                } catch (failure: Throwable) {
                    LOGGER.log(Level.WARNING, "Final completion learning persistence failed during shutdown", failure)
                }
            }
    }

    private fun closeCompletionLearningWithinBudget() {
        val registry = completionRegistry
        completionRegistry = null
        val completed =
            runBlocking {
                withTimeoutOrNull(COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS.milliseconds) {
                    completionShutdown?.join()
                    if (registry != null) {
                        if (settings.smartSuggestionsEnabled) registry.closeAndFlush() else registry.closeWithoutFlush()
                    }
                    true
                } ?: false
            }
        completionLifecycle.cancel()
        if (!completed) {
            LOGGER.warning(
                "Completion learning persistence exceeded its " +
                    "$COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS ms shutdown budget; continuing shutdown",
            )
        }
    }

    /** Propagates a settings reload to all live panes and the workspace. */
    fun reloadAllPanes() {
        val snapshot = settings.current()
        Chrome.applyPalette(snapshot.palette)
        frame.rootPane.putClientProperty("JRootPane.titleBarBackground", Chrome.topBarBackground)
        frame.rootPane.putClientProperty("JRootPane.titleBarForeground", Chrome.textPrimary)
        SwingUtilities.updateComponentTreeUI(frame)
        panes.forEach { it.reloadSettings() }
        tabContentPanel.background = Chrome.terminalBackground
        workspace.applySettings(
            palette = snapshot.palette,
            treatAmbiguousAsWide = snapshot.treatAmbiguousAsWide,
        )
        reconcileCompletion()
        tabBar.repaint()
    }

    /**
     * Switches the active content pane and workspace selection to [id].
     * Called by [TabBar] when the user clicks a different tab.
     */
    fun onTabSelected(id: String) {
        val activePane = getActivePane(id) ?: return
        if (workspace.selectedTab()?.id != activePane.tab.id) {
            workspace.selectTab(activePane.tab.id)
        }
        showPane(id)
        tabBar.updateTitle(id, activePane.tab.title)
        tabBar.updateColor(id, activePane.tab.color?.let { Color.decode(it) })
        updateFrameTitle()
        activePane.requestFocus()
    }

    /**
     * Updates the custom color of the corresponding workspace tab.
     * Called by [TabBar] when a user picks a custom color.
     */
    fun onTabColorChanged(
        id: String,
        colorHex: String?,
    ) {
        val activePane = getActivePane(id) ?: return
        activePane.tab.color = colorHex
    }

    /**
     * Updates the custom title of the corresponding workspace tab.
     * Called by [TabBar] when a user renames a tab.
     */
    fun onTabRenameRequested(
        id: String,
        newName: String?,
    ) {
        val activePane = getActivePane(id) ?: return
        activePane.tab.customTitle = newName
    }

    fun splitPane(
        pane: TerminalPane,
        isVertical: Boolean,
    ) {
        val tabId = tabBar.selectedId() ?: return
        val root = tabRoots[tabId] ?: return

        val profile = defaultProfileProvider()
        val workspaceTab =
            try {
                workspace.openTab(
                    profile = profile,
                    options =
                        settings.current().let { snapshot ->
                            TerminalWorkspaceOpenOptions(
                                columns = snapshot.columns,
                                rows = snapshot.rows,
                                treatAmbiguousAsWide = snapshot.treatAmbiguousAsWide,
                                maxHistory = snapshot.scrollbackLines,
                                pasteSanitizationPolicy = snapshot.pasteSanitizationPolicy,
                                hostPolicy = settings.createHostPolicy(profile.command),
                            )
                        },
                )
            } catch (exception: Exception) {
                showStartError(profile, exception)
                return
            }
        val createdPane = createTerminalPane(workspaceTab)

        panes += createdPane

        val newRoot = splitNodeInTree(root, pane, createdPane, isVertical)
        tabRoots[tabId] = newRoot

        val container = tabContainers[tabId] ?: return
        container.removeAll()
        container.add(newRoot.component, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()

        createdPane.requestFocus()
        updateFrameTitle()
    }

    private fun createTerminalPane(workspaceTab: TerminalWorkspaceTab): TerminalPane =
        try {
            val registry = ensureCompletionRegistry()
            val completionResources =
                registry?.createResources(
                    profileId = workspaceTab.profile.id,
                    workingDirectoryUriProvider = { workspaceTab.currentWorkingDirectoryUri },
                    shellCapabilities = workspaceTab.profile.kind.completionShellCapabilities(),
                )
            TerminalPane.create(
                tab = workspaceTab,
                settings = settings,
                completionResources = completionResources,
            ) { pane, request ->
                showPaneContextMenu(pane, request)
            }
        } catch (failure: Throwable) {
            val cleanupFailure = captureCleanupFailure(failure) { workspace.closeTab(workspaceTab.id) }
            throw requireNotNull(cleanupFailure)
        }

    private fun splitNodeInTree(
        current: SplitNode,
        target: TerminalPane,
        newPane: TerminalPane,
        isVertical: Boolean,
    ): SplitNode =
        when (current) {
            is LeafNode -> {
                if (current.pane === target) {
                    val left = LeafNode(current.pane)
                    val right = LeafNode(newPane)
                    val splitPane = createStyledSplitPane(isVertical, left.component, right.component)
                    ParentNode(left, right, splitPane)
                } else {
                    current
                }
            }
            is ParentNode -> {
                current.left = splitNodeInTree(current.left, target, newPane, isVertical)
                current.right = splitNodeInTree(current.right, target, newPane, isVertical)
                current.splitPane.leftComponent = current.left.component
                current.splitPane.rightComponent = current.right.component
                current
            }
        }

    fun closePane(pane: TerminalPane) {
        closePane(pane, openReplacementWhenLastPane = false)
    }

    private fun closePane(
        pane: TerminalPane,
        openReplacementWhenLastPane: Boolean,
    ) {
        val tabId = visualTabIdForPane(pane) ?: return
        val root = tabRoots[tabId] ?: return
        val allPanes = root.allPanes()

        if (allPanes.size <= 1) {
            if (openReplacementWhenLastPane) {
                closeTabWithoutUserPrompt(tabId)
            } else if (!closeTab(tabId)) {
                return
            }
            if (openReplacementWhenLastPane && tabRoots.isEmpty()) {
                openTab(defaultProfileProvider())
            }
            return
        }

        if (!openReplacementWhenLastPane && !confirmClose(listOf(pane))) return

        var failure: Throwable? = null
        val newRoot = root.removePane(pane)
        if (newRoot != null) {
            tabRoots[tabId] = newRoot
            val container = tabContainers[tabId]
            if (container != null) {
                failure =
                    captureCleanupFailure(failure) {
                        container.removeAll()
                        container.add(newRoot.component, BorderLayout.CENTER)
                        container.revalidate()
                        container.repaint()
                    }
            }
        }

        panes.remove(pane)
        failure = captureCleanupFailure(failure, pane::close)
        failure = captureCleanupFailure(failure) { workspace.closeTab(pane.tab.id) }

        val newActive = getActivePane(tabId)
        failure = captureCleanupFailure(failure) { newActive?.requestFocus() }
        failure = captureCleanupFailure(failure, ::updateFrameTitle)
        failure?.let { throw it }
    }

    fun closeActivePane() {
        val tabId = tabBar.selectedId() ?: return
        val activePane = getActivePane(tabId) ?: return
        closePane(activePane)
    }

    fun getActivePane(tabId: String): TerminalPane? {
        val root = tabRoots[tabId] ?: return null
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        return root.findActivePane(focusOwner) ?: root.allPanes().firstOrNull()
    }

    private fun visualTabIdForPane(pane: TerminalPane): String? =
        tabRoots.entries.firstOrNull { (_, root) -> root.allPanes().any { it === pane } }?.key

    fun showPaneContextMenu(
        pane: TerminalPane,
        request: SwingTerminalContextMenuRequest,
    ) {
        val menu = JPopupMenu()

        val hyperlink = request.hyperlink
        if (hyperlink != null) {
            menu.add(
                JMenuItem("Open Link").apply {
                    addActionListener { hyperlink.open() }
                },
            )
            menu.add(
                JMenuItem("Copy Link").apply {
                    isEnabled = hyperlink.uri != null
                    addActionListener { hyperlink.copyUri() }
                },
            )
            menu.addSeparator()
        }

        val copyItem =
            JMenuItem("Copy").apply {
                isEnabled = request.hasSelection()
                addActionListener { request.copySelection() }
            }
        val pasteItem =
            JMenuItem("Paste").apply {
                addActionListener { request.pasteClipboard() }
            }
        val selectAllItem =
            JMenuItem("Select All").apply {
                addActionListener { request.selectAll() }
            }
        val searchItem =
            JMenuItem("Search").apply {
                addActionListener { pane.openSearch() }
            }
        val clearItem =
            JMenuItem("Clear").apply {
                addActionListener { request.clearScreen() }
            }

        menu.add(copyItem)
        menu.add(pasteItem)
        menu.add(selectAllItem)
        menu.add(searchItem)
        menu.add(clearItem)

        val commandRecordId = pane.terminal.commandRecordAt(request.x, request.y)
        if (commandRecordId != 0) {
            menu.addSeparator()
            menu.add(
                JMenuItem("Copy Command").apply {
                    isEnabled = pane.tab.session.shellIntegrationState
                        .commandText(commandRecordId) != null
                    addActionListener { pane.terminal.copyCommandTextToClipboard(commandRecordId) }
                },
            )
            menu.add(
                JMenuItem("Copy Command Output").apply {
                    addActionListener { pane.terminal.copyCommandOutputToClipboard(commandRecordId) }
                },
            )
            menu.add(
                JMenuItem("Export Command Output…").apply {
                    addActionListener { exportCommandOutput(pane, commandRecordId) }
                },
            )
        }
        menu.addSeparator()

        val workingDirectory = LocalWorkingDirectoryResolver.resolve(pane.tab.currentWorkingDirectoryUri)
        val openHereItem =
            JMenuItem("Open Terminal Here").apply {
                isEnabled = workingDirectory != null
                toolTipText = if (workingDirectory == null) "No local shell working directory is available" else null
                addActionListener {
                    val directory = workingDirectory ?: return@addActionListener
                    openTab(pane.tab.profile.copy(workingDirectory = directory))
                }
            }
        menu.add(openHereItem)
        menu.addSeparator()

        val splitVert =
            JMenuItem("Split Vertically").apply {
                addActionListener {
                    splitPane(pane, isVertical = true)
                }
            }
        val splitHor =
            JMenuItem("Split Horizontally").apply {
                addActionListener {
                    splitPane(pane, isVertical = false)
                }
            }

        menu.add(splitVert)
        menu.add(splitHor)

        val closeItem =
            JMenuItem("Close Pane").apply {
                addActionListener {
                    closePane(pane)
                }
            }
        menu.addSeparator()
        menu.add(closeItem)

        menu.show(request.terminal, request.x, request.y)
    }

    private fun exportCommandOutput(
        pane: TerminalPane,
        commandRecordId: Int,
    ) {
        val output = pane.terminal.commandOutputText(commandRecordId) ?: return
        val chooser = JFileChooser().apply { selectedFile = java.io.File("command-output.txt") }
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return
        runCatching {
            Files.writeString(chooser.selectedFile.toPath(), output, StandardCharsets.UTF_8)
        }.onFailure { exception ->
            JOptionPane.showMessageDialog(
                frame,
                exception.message ?: exception.javaClass.name,
                "Unable to export command output",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private fun createStyledSplitPane(
        isVertical: Boolean,
        left: Component,
        right: Component,
    ): JSplitPane {
        val orientation = if (isVertical) JSplitPane.HORIZONTAL_SPLIT else JSplitPane.VERTICAL_SPLIT
        return JSplitPane(orientation, left, right).apply {
            border = null
            dividerSize = 4
            isContinuousLayout = true
            resizeWeight = 0.5
            setUI(
                object : javax.swing.plaf.basic.BasicSplitPaneUI() {
                    override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider =
                        object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                            override fun paint(g: Graphics) {
                                g.color = Chrome.border
                                g.fillRect(0, 0, width, height)
                            }
                        }
                },
            )
            background = Chrome.terminalBackground
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun showPane(tabId: String) {
        (tabContentPanel.layout as CardLayout).show(tabContentPanel, tabId)
    }

    private fun closeTabWithoutUserPrompt(id: String) {
        val root = tabRoots[id] ?: return
        closeTabWithoutConfirmation(id, root)
    }

    private fun confirmClose(root: SplitNode): Boolean = confirmClose(root.allPanes())

    private fun confirmClose(panesToClose: List<TerminalPane>): Boolean {
        val liveProcessCount =
            panesToClose.count {
                it.tab.session.shellIntegrationState
                    .hasRunningCommand()
            }
        if (liveProcessCount == 0) return true
        val displayName = closeDisplayName(panesToClose)
        return closeConfirmation.confirmClose(TerminalCloseRequest(displayName, liveProcessCount))
    }

    private fun confirmCloseAllTabs(): Boolean {
        val tabIds = tabRoots.keys.toList()
        val liveProcessCount =
            tabIds.sumOf { tabId ->
                tabRoots[tabId]?.allPanes()?.count {
                    it.tab.session.shellIntegrationState
                        .hasRunningCommand()
                } ?: 0
            }
        return liveProcessCount == 0 ||
            closeConfirmation.confirmClose(
                TerminalCloseRequest(
                    displayName = Chrome.APP_TITLE,
                    liveProcessCount = liveProcessCount,
                ),
            )
    }

    private fun closeDisplayName(panesToClose: List<TerminalPane>): String {
        if (panesToClose.size == 1) return panesToClose[0].tab.title
        val active = tabBar.selectedId()?.let(::getActivePane)
        if (active != null && panesToClose.any { it === active }) return active.tab.title
        return panesToClose.firstOrNull()?.tab?.title ?: Chrome.APP_TITLE
    }

    private fun updateTabTitle(
        tabId: String,
        title: String,
    ) {
        val visualTabId = tabRoots.entries.find { (_, root) -> root.allPanes().any { it.tab.id == tabId } }?.key ?: tabId
        val activePane = getActivePane(visualTabId)
        if (activePane?.tab?.id == tabId) {
            tabBar.updateTitle(visualTabId, title)
            if (tabBar.selectedId() == visualTabId) {
                frame.title = title
            }
        }
    }

    private fun updateTabColor(
        tabId: String,
        colorHex: String?,
    ) {
        val visualTabId = tabRoots.entries.find { (_, root) -> root.allPanes().any { it.tab.id == tabId } }?.key ?: tabId
        val activePane = getActivePane(visualTabId)
        if (activePane?.tab?.id == tabId) {
            val color = colorHex?.let { Color.decode(it) }
            tabBar.updateColor(visualTabId, color)
        }
    }

    private fun updateFrameTitle() {
        frame.title = selectedPane?.tab?.title ?: Chrome.APP_TITLE
    }

    private fun showStartError(
        profile: TerminalProfile,
        exception: Exception,
    ) {
        JOptionPane.showMessageDialog(
            frame,
            exception.message ?: exception.javaClass.name,
            "Unable to start ${profile.displayName}",
            JOptionPane.ERROR_MESSAGE,
        )
    }

    private inner class StandaloneWorkspaceListener : TerminalWorkspaceListener {
        override fun shellIntegrationMarker(
            tab: TerminalWorkspaceTab,
            event: io.github.ketraterm.protocol.ShellIntegrationEvent,
        ) {
            if (event.marker != io.github.ketraterm.protocol.ShellIntegrationMarker.COMMAND_FINISHED) return
            if (!settings.smartSuggestionsEnabled) return
            val state = tab.session.shellIntegrationState
            val metadata = state.commandMetadata(state.latestCommandRecordId()) ?: return
            metadata.commandText?.let { command ->
                completionRegistry?.recordFinishedCommand(
                    commandLine = command,
                    successful = metadata.lifecycle == TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                    profileId = tab.profile.id,
                    workingDirectoryUri = metadata.workingDirectoryUri,
                    usedAtEpochMillis = metadata.finishedAtEpochMillis ?: System.currentTimeMillis(),
                )
            }
        }

        override fun bell(tab: TerminalWorkspaceTab) {
            if (settings.visualBell) {
                SwingUtilities.invokeLater {
                    panes.firstOrNull { it.tab == tab }?.terminal?.showVisualBell()
                }
            }
            if (settings.audibleBell) {
                SwingUtilities.invokeLater {
                    frame.toolkit.beep()
                }
            }

            val modes = tab.session.terminal.getModeSnapshot()
            if (modes.isBellIsUrgent) {
                SwingUtilities.invokeLater {
                    try {
                        if (Taskbar.isTaskbarSupported()) {
                            val taskbar = Taskbar.getTaskbar()
                            if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) {
                                taskbar.requestWindowUserAttention(frame)
                            } else if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION)) {
                                taskbar.requestUserAttention(true, true)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore taskbar access failure on unsupported systems.
                    }
                }
            }
            if (modes.isPopOnBell) {
                SwingUtilities.invokeLater {
                    frame.toFront()
                }
            }
        }

        override fun titleChanged(
            tab: TerminalWorkspaceTab,
            title: String,
        ) {
            SwingUtilities.invokeLater {
                updateTabTitle(tab.id, title)
            }
        }

        override fun colorChanged(
            tab: TerminalWorkspaceTab,
            color: String?,
        ) {
            SwingUtilities.invokeLater {
                updateTabColor(tab.id, color)
            }
        }

        override fun sessionClosed(
            tab: TerminalWorkspaceTab,
            exitCode: Int?,
            failure: Throwable?,
        ) {
            SwingUtilities.invokeLater {
                val pane = panes.firstOrNull { it.tab == tab } ?: return@invokeLater
                closePane(pane, openReplacementWhenLastPane = true)
            }
        }

        override fun showNotification(
            tab: TerminalWorkspaceTab,
            title: String,
            body: String,
            level: io.github.ketraterm.protocol.NotificationLevel,
        ) {
            if (settings.desktopNotificationsEnabled) {
                DesktopNotificationManager.showNotification(title, body, level)
            }
        }

        override fun terminalClipboardWrite(
            tab: TerminalWorkspaceTab,
            event: TerminalClipboardWriteEvent,
        ) {
            if (!targetsHostClipboard(event.selection)) return
            SwingUtilities.invokeLater {
                panes.firstOrNull { it.tab == tab }?.terminal?.copyTextToClipboard(event.text)
            }
        }

        override fun terminalClipboardPrompt(
            tab: TerminalWorkspaceTab,
            event: TerminalClipboardPromptEvent,
        ) {
            if (!targetsHostClipboard(event.selection)) return
            SwingUtilities.invokeLater {
                val pane = panes.firstOrNull { it.tab == tab } ?: return@invokeLater
                val answer =
                    JOptionPane.showConfirmDialog(
                        frame,
                        clipboardPromptComponent(tab.profile.displayName, event),
                        Osc52ClipboardPromptText.title(),
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                    )
                if (answer == JOptionPane.YES_OPTION) {
                    pane.terminal.copyTextToClipboard(event.text)
                }
            }
        }

        override fun resizeWindow(
            tab: TerminalWorkspaceTab,
            rows: Int,
            columns: Int,
        ) {
            if (settings.shellRequestResizeWindow) {
                // Resize the session synchronously so that subsequent query reports (e.g. vttest CSI 18 t)
                // return the updated size immediately.
                tab.session.resize(columns, rows)

                SwingUtilities.invokeLater {
                    val pane = panes.firstOrNull { it.tab == tab } ?: return@invokeLater
                    val terminal = pane.terminal
                    val targetSize = terminal.preferredGridSize(columns, rows)
                    val currentSize = terminal.size
                    val deltaWidth = targetSize.width - currentSize.width
                    val deltaHeight = targetSize.height - currentSize.height

                    terminal.preferredSize = targetSize
                    frame.setSize(frame.width + deltaWidth, frame.height + deltaHeight)
                    frame.revalidate()
                }
            }
        }

        override fun moveWindow(
            tab: TerminalWorkspaceTab,
            x: Int,
            y: Int,
        ) {
            if (settings.shellRequestWindowManipulation) {
                SwingUtilities.invokeLater {
                    frame.setLocation(x, y)
                }
            }
        }

        override fun minimizeWindow(tab: TerminalWorkspaceTab) {
            if (settings.shellRequestWindowManipulation) {
                SwingUtilities.invokeLater {
                    frame.state = Frame.ICONIFIED
                }
            }
        }

        override fun deminimizeWindow(tab: TerminalWorkspaceTab) {
            if (settings.shellRequestWindowManipulation) {
                SwingUtilities.invokeLater {
                    frame.state = Frame.NORMAL
                }
            }
        }

        override fun raiseWindow(tab: TerminalWorkspaceTab) {
            if (settings.shellRequestWindowManipulation) {
                SwingUtilities.invokeLater {
                    frame.toFront()
                }
            }
        }

        override fun lowerWindow(tab: TerminalWorkspaceTab) {
            if (settings.shellRequestWindowManipulation) {
                SwingUtilities.invokeLater {
                    frame.toBack()
                }
            }
        }

        override fun setMaximized(
            tab: TerminalWorkspaceTab,
            maximize: Boolean,
        ) {
            if (settings.shellRequestWindowManipulation) {
                SwingUtilities.invokeLater {
                    if (maximize) {
                        frame.extendedState = Frame.MAXIMIZED_BOTH
                    } else {
                        frame.extendedState = Frame.NORMAL
                    }
                }
            }
        }
    }

    private fun ensureCompletionRegistry(): StandaloneCompletionRegistry? {
        if (!settings.smartSuggestionsEnabled || shutdownStarted.get() || completionShutdown != null) return null
        return completionRegistry ?: StandaloneCompletionRegistry
            .create(
                persistencePath = settings.commandCompletionStatsPath,
                persistenceEnabled = settings.persistentSuggestionLearningEnabled,
                onPersistenceLoadFailure = { LOGGER.log(Level.WARNING, "Completion learning could not be loaded", it) },
            ).also { completionRegistry = it }
    }

    private fun reconcileCompletion() {
        if (!settings.smartSuggestionsEnabled) {
            panes.forEach { it.setCompletionResources(null) }
            val retiring = completionRegistry ?: return
            completionRegistry = null
            completionShutdown =
                completionLifecycle.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        retiring.closeWithoutFlush()
                    } finally {
                        SwingUtilities.invokeLater {
                            completionShutdown = null
                            if (!shutdownStarted.get()) reconcileCompletion()
                        }
                    }
                }
            return
        }
        if (panes.isEmpty()) return
        val previous = completionRegistry
        val registry = ensureCompletionRegistry() ?: return
        registry.setPersistenceEnabled(settings.persistentSuggestionLearningEnabled)
        if (previous === registry) return
        panes.forEach { pane ->
            pane.setCompletionResources(
                registry.createResources(
                    profileId = pane.tab.profile.id,
                    workingDirectoryUriProvider = { pane.tab.currentWorkingDirectoryUri },
                    shellCapabilities =
                        pane.tab.profile.kind
                            .completionShellCapabilities(),
                ),
            )
        }
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(TabManager::class.java.name)
        private const val INITIAL_TAB_CAPACITY = 4
        private const val COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS = 500L

        private fun targetsHostClipboard(selection: String): Boolean = selection.isEmpty() || selection.indexOf('c') >= 0

        private fun clipboardPromptComponent(
            profileName: String,
            event: TerminalClipboardPromptEvent,
        ): JComponent =
            JPanel(BorderLayout(0, 6)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
                add(JLabel(Osc52ClipboardPromptText.htmlQuestion(profileName, event)), BorderLayout.NORTH)
                add(JLabel(Osc52ClipboardPromptText.htmlDetail(event)), BorderLayout.CENTER)
            }
    }
}

internal sealed interface SplitNode {
    val component: Component

    fun findPane(id: String): TerminalPane?

    fun findActivePane(focusedComponent: Component?): TerminalPane?

    fun allPanes(): List<TerminalPane>

    fun removePane(pane: TerminalPane): SplitNode?
}

internal class LeafNode(
    val pane: TerminalPane,
) : SplitNode {
    override val component: Component get() = pane.component

    override fun findPane(id: String): TerminalPane? = if (pane.tab.id == id) pane else null

    override fun findActivePane(focusedComponent: Component?): TerminalPane? {
        if (focusedComponent != null &&
            (pane.component == focusedComponent || SwingUtilities.isDescendingFrom(focusedComponent, pane.component))
        ) {
            return pane
        }
        return null
    }

    override fun allPanes(): List<TerminalPane> = listOf(pane)

    override fun removePane(pane: TerminalPane): SplitNode? = if (this.pane === pane) null else this
}

internal class ParentNode(
    var left: SplitNode,
    var right: SplitNode,
    val splitPane: JSplitPane,
) : SplitNode {
    override val component: Component get() = splitPane

    override fun findPane(id: String): TerminalPane? = left.findPane(id) ?: right.findPane(id)

    override fun findActivePane(focusedComponent: Component?): TerminalPane? =
        left.findActivePane(focusedComponent) ?: right.findActivePane(focusedComponent)

    override fun allPanes(): List<TerminalPane> = left.allPanes() + right.allPanes()

    override fun removePane(pane: TerminalPane): SplitNode {
        val nextLeft = left.removePane(pane)
        val nextRight = right.removePane(pane)
        if (nextLeft == null) return right
        if (nextRight == null) return left
        left = nextLeft
        right = nextRight
        splitPane.leftComponent = left.component
        splitPane.rightComponent = right.component
        return this
    }
}

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

import com.formdev.flatlaf.extras.FlatSVGIcon
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileRegistry
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Creates and wires the standalone terminal window.
 *
 * FlatLaf merges [JMenuBar] into the custom title bar when
 * `useWindowDecorations=true` and `JFrame.setDefaultLookAndFeelDecorated(true)`
 * are active. We place [TabBar] at the left of the menu bar and the
 * action buttons at the right, with a horizontal glue in between.
 *
 * @property profileRegistry source of available shell profiles for the new-tab
 *   dropdown menu and the default-profile provider lambda.
 */
internal class WindowFactory(
    private val settings: KetraTermSettings,
    private val profileRegistry: TerminalProfileRegistry,
) {
    fun createWindow(): Window {
        val frame =
            JFrame(Chrome.APP_TITLE).apply {
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                minimumSize = Dimension(720, 420)
                iconImages =
                    listOf(
                        FlatSVGIcon("io/github/ketraterm/app/icons/logo.svg", 16, 16).image,
                        FlatSVGIcon("io/github/ketraterm/app/icons/logo.svg", 32, 32).image,
                        FlatSVGIcon("io/github/ketraterm/app/icons/logo.svg", 48, 48).image,
                        FlatSVGIcon("io/github/ketraterm/app/icons/logo.svg", 128, 128).image,
                    )
            }

        val tabContentPanel =
            JPanel(CardLayout()).apply {
                background = Chrome.terminalBackground
                isOpaque = true
            }

        // Tab bar callbacks reference tabManager; forward via lambda so the
        // lateinit is resolved at call time, not at construction time.
        lateinit var tabManager: TabManager
        lateinit var tabBar: TabBar

        val availableProfiles = profileRegistry.availableProfiles()

        // Builds a fresh configured profile from the current settings each time
        // a new default tab or split is opened. This means shell changes in the
        // Settings dialog take effect on the very next new tab without a restart.
        val defaultProfileProvider: () -> TerminalProfile = {
            profileRegistry.configuredProfile(
                shellPath = settings.shellPath,
                workingDirectory =
                    settings.startDirectory
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { Path.of(it) }.getOrNull() },
            )
        }

        tabBar =
            TabBar(
                onTabSelected = { id -> tabManager.onTabSelected(id) },
                onTabClose = { id -> tabManager.closeTab(id) },
                onNewTab = { tabManager.openTab(defaultProfileProvider()) },
                onMenuClick = { x, y -> showDropdownMenu(frame, tabBar, x, y, availableProfiles, tabManager, defaultProfileProvider) },
                onTabColorChanged = { id, color -> tabManager.onTabColorChanged(id, color) },
                onTabRenameRequested = { id, newName -> tabManager.onTabRenameRequested(id, newName) },
            )

        tabManager = TabManager(frame, tabBar, tabContentPanel, settings, defaultProfileProvider)

        installMenuBar(frame, tabBar)
        styleTitleBar(frame)
        frame.contentPane = tabContentPanel

        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) {
                    if (tabManager.closeAllTabs()) {
                        frame.dispose()
                    }
                }

                override fun windowClosed(event: WindowEvent) {
                    tabManager.closeAllTabsWithoutConfirmation()
                }
            },
        )

        return Window(frame, tabManager)
    }

    /**
     * Places [tabBar] and the action panel inside a [JMenuBar] that FlatLaf
     * renders in the title bar area.
     */
    private fun installMenuBar(
        frame: JFrame,
        tabBar: TabBar,
    ) {
        frame.jMenuBar =
            JMenuBar().apply {
                isOpaque = false
                border = EmptyBorder(0, 0, 0, 0)
                add(tabBar)
                add(Box.createHorizontalGlue())
            }
    }

    private fun showDropdownMenu(
        frame: JFrame,
        invoker: java.awt.Component,
        x: Int,
        y: Int,
        profiles: List<TerminalProfile>,
        tabManager: TabManager,
        defaultProfileProvider: () -> TerminalProfile,
    ) {
        val popup =
            JPopupMenu().apply {
                background = Chrome.popupBackground
                border = BorderFactory.createLineBorder(Chrome.border)
            }

        val profileIcons = ProfileIcons()
        profiles.forEach { profile ->
            val item =
                JMenuItem(profile.displayName, profileIcons.icon(profile.kind)).apply {
                    background = Chrome.popupBackground
                    foreground = Chrome.textPrimary
                    addActionListener {
                        // Stamp the configured startDirectory onto built-in profiles
                        // whose workingDirectory is null (i.e. they carry no explicit
                        // directory preference). Profiles with a profile-level directory
                        // are left untouched.
                        val configuredDirectory = defaultProfileProvider().workingDirectory
                        val effectiveProfile =
                            if (profile.workingDirectory == null && configuredDirectory != null) {
                                profile.copy(workingDirectory = configuredDirectory)
                            } else {
                                profile
                            }
                        tabManager.openTab(effectiveProfile)
                    }
                }
            popup.add(item)
        }

        popup.addSeparator()

        val settingsItem =
            JMenuItem("Settings").apply {
                icon =
                    FlatSVGIcon("io/github/ketraterm/app/icons/settings.svg", 16, 16).apply {
                        colorFilter =
                            FlatSVGIcon.ColorFilter().apply {
                                add(java.awt.Color.BLACK, Chrome.textPrimary)
                            }
                    }
                background = Chrome.popupBackground
                foreground = Chrome.textPrimary
                addActionListener {
                    SettingsDialog(
                        parent = frame,
                        settings = settings,
                        profileRegistry = profileRegistry,
                        onApply = tabManager::reloadAllPanes,
                    ).isVisible = true
                }
            }
        popup.add(settingsItem)

        val aboutItem =
            JMenuItem("About").apply {
                icon =
                    FlatSVGIcon("io/github/ketraterm/app/icons/about.svg", 16, 16).apply {
                        colorFilter =
                            FlatSVGIcon.ColorFilter().apply {
                                add(java.awt.Color.BLACK, Chrome.textPrimary)
                            }
                    }
                background = Chrome.popupBackground
                foreground = Chrome.textPrimary
                addActionListener {
                    AboutDialog(frame).isVisible = true
                }
            }
        popup.add(aboutItem)

        popup.show(invoker, x, y)
    }

    /**
     * Applies FlatLaf title bar styling: background colour, no icon, no title
     * text (the tab bar provides context instead).
     */
    private fun styleTitleBar(frame: JFrame) {
        frame.rootPane.apply {
            background = Chrome.surface
            putClientProperty("JRootPane.titleBarBackground", Chrome.topBarBackground)
            putClientProperty("JRootPane.titleBarForeground", Chrome.textPrimary)
            putClientProperty("JRootPane.titleBarShowIcon", false)
            putClientProperty("JRootPane.titleBarShowTitle", false)
        }
    }
}

/**
 * Standalone window and its terminal tab controller.
 */
internal data class Window(
    val frame: JFrame,
    val tabManager: TabManager,
)

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
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileKind
import io.github.ketraterm.workspace.TerminalProfileRegistry
import io.github.ketraterm.workspace.config.TerminalConfig
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * A highly polished, IDE-style settings dialog featuring a clean sidebar and flat form layouts.
 */
internal class SettingsDialog(
    parent: JFrame,
    private val settings: KetraTermSettings,
    private val profileRegistry: TerminalProfileRegistry,
    private val onApply: () -> Unit,
) : JDialog(parent, "Terminal Settings", true) {
    private val cardLayout = CardLayout()

    // Opaque panel is critical for CardLayout to clear previous artifacts correctly
    private val cardPanel =
        JPanel(cardLayout).apply {
            isOpaque = true
            background = Chrome.surface
        }
    private val sidebarPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = Chrome.surface
            border = EmptyBorder(8, 0, 8, 0)
            preferredSize = Dimension(180, -1)
        }

    private val categories = mutableListOf<CategoryLabel>()
    private val applyButton = JButton("Apply")
    private val model = SettingsModel(settings, profileRegistry)

    // Factory Helpers
    private fun createTextField(
        initialValue: String,
        width: Int,
    ) = JTextField(initialValue).apply { applySizing(this, width) }

    private fun createSpinner(
        initialValue: Int,
        min: Int,
        max: Int,
        step: Int,
        width: Int,
    ) = JSpinner(SpinnerNumberModel(initialValue, min, max, step)).apply {
        applySizing(this, width)
    }

    private fun createFloatSpinner(
        initialValue: Float,
        min: Double,
        max: Double,
        step: Double,
        width: Int,
    ) = JSpinner(SpinnerNumberModel(initialValue.toDouble(), min, max, step)).apply {
        applySizing(this, width)
    }

    private fun <T> createComboBox(
        items: Array<T>,
        initialValue: T,
        width: Int,
    ) = JComboBox(items).apply {
        selectedItem = initialValue
        applySizing(this, width)
    }

    private val availableProfiles = profileRegistry.availableProfiles()
    private val matchedProfile =
        availableProfiles.firstOrNull { profile ->
            val exec = profile.command.firstOrNull() ?: ""
            exec.equals(settings.shellPath, ignoreCase = true) ||
                runCatching { Path.of(exec).fileName?.toString() }
                    .getOrNull()
                    ?.equals(settings.shellPath, ignoreCase = true) == true
        }
    private val isCustomShell = matchedProfile == null

    // Form Controls - Application
    private val shellPathCombo =
        JComboBox<Any>().apply {
            availableProfiles.forEach { addItem(it) }
            addItem("Custom...")
            selectedItem = matchedProfile ?: "Custom..."
            renderer =
                object : DefaultListCellRenderer() {
                    private val profileIcons = ProfileIcons()

                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): Component {
                        val label =
                            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                        if (value is TerminalProfile) {
                            label.text = value.displayName
                            label.icon = profileIcons.icon(value.kind)
                        } else if (value is String) {
                            label.text = value
                            if (value == "Custom...") {
                                label.icon = profileIcons.icon(TerminalProfileKind.DEFAULT)
                            } else {
                                label.icon = null
                            }
                        }
                        return label
                    }
                }
            applySizing(this, 220)
        }
    private val customShellField = createTextField(if (isCustomShell) settings.shellPath else "", 140) // Will be wrapped with button
    private val startDirectoryField = createTextField(settings.startDirectory, 140) // Will be wrapped with button
    private val audibleBellCheckbox = JCheckBox("Audible bell", settings.audibleBell)
    private val visualBellCheckbox = JCheckBox("Visual bell", settings.visualBell)

    // Form Controls - Appearance
    private val fontFamilyCombo =
        run {
            val monospaceFamilies = SwingSettings.getMonospaceFontFamilies().toMutableList()
            val currentFamily = settings.fontFamily
            if (currentFamily.isNotEmpty() && monospaceFamilies.none { it.equals(currentFamily, ignoreCase = true) }) {
                monospaceFamilies.add(0, currentFamily)
            }
            createComboBox(monospaceFamilies.toTypedArray(), currentFamily, 220)
        }
    private val fontSizeSpinner =
        createSpinner(settings.fontSize, TerminalConfig.FONT_SIZE_MIN, TerminalConfig.FONT_SIZE_MAX, 1, 80)
    private val lineHeightSpinner =
        createFloatSpinner(
            settings.lineHeight,
            TerminalConfig.LINE_HEIGHT_MIN.toDouble(),
            TerminalConfig.LINE_HEIGHT_MAX.toDouble(),
            0.1,
            80,
        )
    private val columnsSpinner =
        createSpinner(settings.columns, TerminalConfig.COLUMNS_MIN, TerminalConfig.COLUMNS_MAX, 1, 80)
    private val rowsSpinner =
        createSpinner(settings.rows, TerminalConfig.ROWS_MIN, TerminalConfig.ROWS_MAX, 1, 80)
    private val scrollbackSpinner =
        createSpinner(settings.scrollbackLines, TerminalConfig.SCROLLBACK_MIN, TerminalConfig.SCROLLBACK_MAX, 100, 80)
    private val themeCombo = createComboBox(TerminalTheme.entries.map { it.name }.toTypedArray(), settings.theme.name, 220)

    // Form Controls - Behavior
    private val treatAmbiguousCheckbox = JCheckBox("Treat East Asian ambiguous characters as wide", settings.treatAmbiguousAsWide)
    private val useSystemFallbackCheckbox = JCheckBox("Use system font fallback for missing glyphs", settings.useSystemFallbackFonts)
    private val pasteOnMiddleClickCheckbox = JCheckBox("Paste on middle mouse button click", settings.pasteOnMiddleClick)
    private val pasteSanitizationCombo =
        createComboBox(
            PASTE_SANITIZATION_OPTIONS.toTypedArray(),
            PASTE_SANITIZATION_OPTIONS.first { it.policy == settings.pasteSanitizationPolicy },
            220,
        )
    private val shellRequestResizeWindowCheckbox = JCheckBox("Allow window resize from shell", settings.shellRequestResizeWindow)
    private val shellRequestWindowManipulationCheckbox =
        JCheckBox("Allow window manipulation from shell", settings.shellRequestWindowManipulation)
    private val scrollOnOutputCheckbox = JCheckBox("Scroll on output", settings.scrollOnOutput)
    private val cursorBlinkSpinner =
        createSpinner(settings.cursorBlinkMillis, TerminalConfig.CURSOR_BLINK_MIN, TerminalConfig.CURSOR_BLINK_MAX, 50, 70)
    private val cursorShapeCombo = createComboBox(arrayOf("block", "underline", "beam"), settings.cursorShape.lowercase(Locale.ROOT), 150)

    // Form Controls - Security
    private val clipboardLocalWriteCombo =
        createComboBox(
            CLIPBOARD_PERMISSION_OPTIONS,
            visibleClipboardPermission(settings.clipboardLocalWrite.name.lowercase(Locale.ROOT), "prompt"),
            150,
        )
    private val clipboardRemoteWriteCombo =
        createComboBox(
            CLIPBOARD_PERMISSION_OPTIONS,
            visibleClipboardPermission(settings.clipboardRemoteWrite.name.lowercase(Locale.ROOT), "deny"),
            150,
        )
    private val clipboardReadCombo =
        createComboBox(
            CLIPBOARD_PERMISSION_OPTIONS,
            visibleClipboardPermission(settings.clipboardRead.name.lowercase(Locale.ROOT), "deny"),
            150,
        )
    private val clipboardMaxDecodedBytesSpinner = createSpinner(settings.clipboardMaxDecodedBytes, 0, Int.MAX_VALUE, 1024, 150)
    private val titleLocalPermissionCheckbox =
        JCheckBox("Allow local sessions to rename window/tab", settings.titleLocalPermission == TerminalTitlePermission.ALLOW)
    private val titleRemotePermissionCheckbox =
        JCheckBox("Allow remote sessions to rename window/tab", settings.titleRemotePermission == TerminalTitlePermission.ALLOW)

    init {
        size = Dimension(820, 600)
        setLocationRelativeTo(parent)
        layout = BorderLayout()
        isUndecorated = false

        // Setup Main Container
        val splitPane =
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = Chrome.surface
                add(sidebarPanel, BorderLayout.WEST)
                add(
                    JScrollPane(cardPanel).apply {
                        isOpaque = true
                        viewport.isOpaque = true
                        viewport.background = Chrome.surface
                        border = BorderFactory.createMatteBorder(0, 1, 0, 0, Chrome.border)
                        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    },
                    BorderLayout.CENTER,
                )
            }

        add(splitPane, BorderLayout.CENTER)
        add(buildFooterPanel(), BorderLayout.SOUTH)

        // Add Pages
        addPage("General", buildGeneralPanel())
        addPage("Appearance", buildAppearancePanel())
        addPage("Behavior", buildBehaviorPanel())
        addPage("Security", buildSecurityPanel())

        selectCategory(categories.first().categoryName)

        updateApplyButtonState()

        val updateApplyState = {
            updateApplyButtonState()
        }

        registerChangeListener(shellPathCombo, updateApplyState)
        registerChangeListener(customShellField, updateApplyState)
        registerChangeListener(startDirectoryField, updateApplyState)
        registerChangeListener(audibleBellCheckbox, updateApplyState)
        registerChangeListener(visualBellCheckbox, updateApplyState)
        registerChangeListener(fontFamilyCombo, updateApplyState)
        registerChangeListener(fontSizeSpinner, updateApplyState)
        registerChangeListener(lineHeightSpinner, updateApplyState)
        registerChangeListener(columnsSpinner, updateApplyState)
        registerChangeListener(rowsSpinner, updateApplyState)
        registerChangeListener(scrollbackSpinner, updateApplyState)
        registerChangeListener(themeCombo, updateApplyState)
        registerChangeListener(treatAmbiguousCheckbox, updateApplyState)
        registerChangeListener(useSystemFallbackCheckbox, updateApplyState)
        registerChangeListener(clipboardLocalWriteCombo, updateApplyState)
        registerChangeListener(clipboardRemoteWriteCombo, updateApplyState)
        registerChangeListener(clipboardReadCombo, updateApplyState)
        registerChangeListener(clipboardMaxDecodedBytesSpinner, updateApplyState)
        registerChangeListener(titleLocalPermissionCheckbox, updateApplyState)
        registerChangeListener(titleRemotePermissionCheckbox, updateApplyState)
        registerChangeListener(pasteOnMiddleClickCheckbox, updateApplyState)
        registerChangeListener(pasteSanitizationCombo, updateApplyState)
        registerChangeListener(shellRequestResizeWindowCheckbox, updateApplyState)
        registerChangeListener(shellRequestWindowManipulationCheckbox, updateApplyState)
        registerChangeListener(scrollOnOutputCheckbox, updateApplyState)
        registerChangeListener(cursorBlinkSpinner, updateApplyState)
        registerChangeListener(cursorShapeCombo, updateApplyState)
    }

    private fun applySizing(
        component: JComponent,
        width: Int,
    ) {
        component.preferredSize = Dimension(width, 26)
    }

    private fun addPage(
        title: String,
        panel: JPanel,
    ) {
        val categoryLabel = CategoryLabel(title)
        categories.add(categoryLabel)
        sidebarPanel.add(categoryLabel)

        // Container with padding to hold the content panel
        val contentContainer =
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = Chrome.surface
                border = EmptyBorder(0, 16, 16, 16)
                add(panel, BorderLayout.NORTH)
            }

        cardPanel.add(contentContainer, title)

        categoryLabel.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    selectCategory(title)
                }
            },
        )
    }

    private fun selectCategory(title: String) {
        categories.forEach { it.updateState(it.categoryName == title) }
        cardLayout.show(cardPanel, title)
    }

    private fun buildGeneralPanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        panel.add(SectionHeader("Shell & Session"))
        val projectSection = createSectionPanel()
        addFormRow(projectSection, 0, "Default shell:", shellPathCombo)

        val customShellWrapper =
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(customShellField, BorderLayout.CENTER)
                val browseBtn =
                    JButton("Browse...").apply {
                        preferredSize = Dimension(72, 26)
                        addActionListener {
                            val chooser =
                                JFileChooser(customShellField.text).apply {
                                    fileSelectionMode = JFileChooser.FILES_ONLY
                                }
                            if (chooser.showOpenDialog(this@SettingsDialog) == JFileChooser.APPROVE_OPTION) {
                                customShellField.text = chooser.selectedFile.absolutePath
                            }
                        }
                    }
                add(browseBtn, BorderLayout.EAST)
            }
        val customShellLabel = addFormRow(projectSection, 1, "Custom path:", customShellWrapper)
        customShellLabel.isVisible = isCustomShell
        customShellWrapper.isVisible = isCustomShell

        shellPathCombo.addItemListener { e ->
            if (e.stateChange == java.awt.event.ItemEvent.SELECTED) {
                val isCustom = e.item == "Custom..."
                customShellLabel.isVisible = isCustom
                customShellWrapper.isVisible = isCustom
                projectSection.revalidate()
                projectSection.repaint()
            }
        }

        val startDirWrapper =
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(startDirectoryField, BorderLayout.CENTER)
                val browseBtn =
                    JButton("Browse...").apply {
                        preferredSize = Dimension(72, 26)
                        addActionListener {
                            val chooser =
                                JFileChooser(startDirectoryField.text).apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                }
                            if (chooser.showOpenDialog(this@SettingsDialog) == JFileChooser.APPROVE_OPTION) {
                                startDirectoryField.text = chooser.selectedFile.absolutePath
                            }
                        }
                    }
                add(browseBtn, BorderLayout.EAST)
            }
        addFormRow(projectSection, 2, "Start directory:", startDirWrapper)
        panel.add(projectSection)

        panel.add(SectionHeader("Terminal Bells"))
        val appSection = createSectionPanel()
        addCheckboxRow(
            appSection,
            0,
            audibleBellCheckbox,
            "Play an alert sound when the terminal triggers the bell sequence (ASCII BEL).",
        )
        addCheckboxRow(
            appSection,
            2,
            visualBellCheckbox,
            "Flash the screen or window when the terminal triggers the bell sequence.",
        )
        panel.add(appSection)

        return panel
    }

    private fun buildAppearancePanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        panel.add(SectionHeader("Typography & Theme"))
        val typoSection = createSectionPanel()
        addFormRow(typoSection, 0, "Font family:", fontFamilyCombo)

        val fontGridWrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(fontSizeSpinner)
                add(Box.createHorizontalStrut(24))
                add(
                    JLabel("Line height:").apply {
                        foreground = Chrome.textPrimary
                        font = font.deriveFont(Font.PLAIN, 13f)
                        border = EmptyBorder(0, 0, 0, 12)
                    },
                )
                add(lineHeightSpinner)
            }
        addFormRow(typoSection, 1, "Font size:", fontGridWrapper)

        addFormRow(typoSection, 2, "Color theme:", themeCombo)
        addCheckboxRow(
            typoSection,
            3,
            useSystemFallbackCheckbox,
            "Query the system catalog to resolve characters and symbols missing in the primary typeface.",
        )
        panel.add(typoSection)

        panel.add(SectionHeader("Cursor Settings"))
        val cursorSection = createSectionPanel()
        addFormRow(cursorSection, 0, "Cursor shape:", cursorShapeCombo)
        addFormRow(cursorSection, 1, "Blink period (ms):", cursorBlinkSpinner)
        panel.add(cursorSection)

        panel.add(SectionHeader("Layout & Scrollback"))
        val windowSection = createSectionPanel()

        val layoutGridWrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(columnsSpinner)
                add(Box.createHorizontalStrut(24))
                add(
                    JLabel("Rows:").apply {
                        foreground = Chrome.textPrimary
                        font = font.deriveFont(Font.PLAIN, 13f)
                        border = EmptyBorder(0, 0, 0, 12)
                    },
                )
                add(rowsSpinner)
            }
        addFormRow(windowSection, 0, "Columns:", layoutGridWrapper)
        addFormRow(windowSection, 1, "Scrollback lines:", scrollbackSpinner)
        panel.add(windowSection)

        return panel
    }

    private fun buildBehaviorPanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        panel.add(SectionHeader("Input & Keyboard"))
        val keyboardSection = createSectionPanel()
        addCheckboxRow(
            keyboardSection,
            0,
            treatAmbiguousCheckbox,
            "Render East Asian ambiguous characters (e.g. smart quotes, emojis) with double cell width.",
        )
        panel.add(keyboardSection)

        panel.add(SectionHeader("Mouse"))
        val mouseSection = createSectionPanel()
        addCheckboxRow(
            mouseSection,
            0,
            pasteOnMiddleClickCheckbox,
            "Insert the system clipboard content when clicking the middle mouse button.",
        )
        panel.add(mouseSection)

        panel.add(SectionHeader("Scrolling"))
        val scrollingSection = createSectionPanel()
        addCheckboxRow(
            scrollingSection,
            0,
            scrollOnOutputCheckbox,
            "Automatically scroll to the bottom when new process output is received.",
        )
        panel.add(scrollingSection)

        return panel
    }

    private fun createSectionPanel(): JPanel =
        JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = EmptyBorder(0, 16, 0, 0)
        }

    private fun addFormRow(
        panel: JPanel,
        row: Int,
        labelText: String,
        comp1: Component,
    ): JLabel {
        val label =
            JLabel(labelText).apply {
                foreground = Chrome.textPrimary
                font = font.deriveFont(Font.PLAIN, 13f)
                preferredSize = Dimension(190, 26)
            }

        val gbc =
            GridBagConstraints().apply {
                gridy = row
                fill = GridBagConstraints.NONE
                anchor = GridBagConstraints.WEST
            }

        // Column 0: Label
        gbc.gridx = 0
        gbc.weightx = 0.0
        gbc.gridwidth = 1
        gbc.insets = Insets(6, 0, 6, 12)
        panel.add(label, gbc)

        // Column 1: Component
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.gridwidth = 3
        gbc.insets = Insets(6, 0, 6, 0)
        panel.add(comp1, gbc)

        return label
    }

    private fun addCheckboxRow(
        panel: JPanel,
        row: Int,
        checkbox: JCheckBox,
        description: String? = null,
    ) {
        val gbc =
            GridBagConstraints().apply {
                gridy = row
                gridx = 0
                gridwidth = 2
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(4, 0, 0, 0)
            }
        panel.add(checkbox, gbc)

        if (description != null) {
            val descGbc =
                GridBagConstraints().apply {
                    gridy = row + 1
                    gridx = 0
                    gridwidth = 2
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(2, 22, 12, 0) // Indent to match checkbox text
                }
            val descLabel =
                JLabel("<html>$description</html>").apply {
                    foreground = Chrome.textSecondary
                    font = font.deriveFont(Font.PLAIN, 12f)
                }
            panel.add(descLabel, descGbc)
        }
    }

    private fun buildSecurityPanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        panel.add(SectionHeader("Clipboard Safety (OSC 52)"))
        val clipboardSection = createSectionPanel()
        addFormRow(clipboardSection, 0, "Local write permission:", clipboardLocalWriteCombo)
        addFormRow(clipboardSection, 1, "Remote write permission:", clipboardRemoteWriteCombo)
        addFormRow(clipboardSection, 2, "Read / Query permission:", clipboardReadCombo)
        addFormRow(clipboardSection, 3, "Max decoded size (bytes):", clipboardMaxDecodedBytesSpinner)
        panel.add(clipboardSection)

        panel.add(SectionHeader("Paste Safety"))
        val pasteSection = createSectionPanel()
        addFormRow(pasteSection, 0, "Paste handling:", pasteSanitizationCombo)
        panel.add(pasteSection)

        panel.add(SectionHeader("Window Security"))
        val windowSection = createSectionPanel()
        addCheckboxRow(
            windowSection,
            0,
            shellRequestResizeWindowCheckbox,
            "Allow the terminal window to resize itself when the shell requests a grid resize.",
        )
        addCheckboxRow(
            windowSection,
            2,
            shellRequestWindowManipulationCheckbox,
            "Allow the shell to move, minimize, maximize, raise, or lower the terminal window.",
        )
        panel.add(windowSection)

        panel.add(SectionHeader("Title Safety (OSC 0/1/2)"))
        val titleSection = createSectionPanel()
        addCheckboxRow(
            titleSection,
            0,
            titleLocalPermissionCheckbox,
            "Allow local processes to dynamically change the window or tab title via escape sequences.",
        )
        addCheckboxRow(
            titleSection,
            2,
            titleRemotePermissionCheckbox,
            "Allow remote processes (e.g. SSH sessions) to dynamically change the window or tab title via escape sequences.",
        )
        panel.add(titleSection)

        return panel
    }

    private fun buildFooterPanel(): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = Chrome.surface
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Chrome.border)

            val leftPanel =
                JPanel(FlowLayout(FlowLayout.LEFT, 12, 12)).apply {
                    isOpaque = false
                }
            val rightPanel =
                JPanel(FlowLayout(FlowLayout.RIGHT, 12, 12)).apply {
                    isOpaque = false
                }

            val resetButton =
                JButton("Reset to Defaults").apply {
                    addActionListener { resetToDefaults() }
                }
            leftPanel.add(resetButton)

            val okButton =
                JButton("OK").apply {
                    addActionListener {
                        applyChanges()
                        dispose()
                    }
                }

            val cancelButton =
                JButton("Cancel").apply {
                    addActionListener { dispose() }
                }

            applyButton.addActionListener { applyChanges() }

            rightPanel.add(cancelButton)
            rightPanel.add(applyButton)
            rightPanel.add(okButton)

            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
            this@SettingsDialog.rootPane.defaultButton = okButton
        }

    private fun resetToDefaults() {
        val defaultProfile =
            availableProfiles.firstOrNull { profile ->
                val exec = profile.command.firstOrNull() ?: ""
                exec.equals(TerminalConfig.DEFAULT_SHELL_PATH, ignoreCase = true) ||
                    runCatching { Path.of(exec).fileName?.toString() }
                        .getOrNull()
                        ?.equals(TerminalConfig.DEFAULT_SHELL_PATH, ignoreCase = true) == true
            }

        shellPathCombo.selectedItem = defaultProfile ?: "Custom..."
        customShellField.text = if (defaultProfile == null) TerminalConfig.DEFAULT_SHELL_PATH else ""
        startDirectoryField.text = TerminalConfig.DEFAULT_START_DIRECTORY
        audibleBellCheckbox.isSelected = TerminalConfig.DEFAULT_AUDIBLE_BELL
        visualBellCheckbox.isSelected = TerminalConfig.DEFAULT_VISUAL_BELL

        fontFamilyCombo.selectedItem = TerminalConfig.DEFAULT_FONT_FAMILY
        fontSizeSpinner.value = TerminalConfig.DEFAULT_FONT_SIZE
        lineHeightSpinner.value = TerminalConfig.DEFAULT_LINE_HEIGHT.toDouble()
        columnsSpinner.value = TerminalConfig.DEFAULT_COLUMNS
        rowsSpinner.value = TerminalConfig.DEFAULT_ROWS
        scrollbackSpinner.value = TerminalConfig.DEFAULT_SCROLLBACK_LINES
        themeCombo.selectedItem = TerminalConfig.DEFAULT_THEME

        treatAmbiguousCheckbox.isSelected = TerminalConfig.DEFAULT_TREAT_AMBIGUOUS_AS_WIDE
        useSystemFallbackCheckbox.isSelected = TerminalConfig.DEFAULT_USE_SYSTEM_FALLBACK_FONTS
        pasteOnMiddleClickCheckbox.isSelected = TerminalConfig.DEFAULT_PASTE_ON_MIDDLE_CLICK
        pasteSanitizationCombo.selectedItem =
            PASTE_SANITIZATION_OPTIONS.first {
                it.policy == TerminalConfig.DEFAULT_PASTE_SANITIZATION_POLICY
            }
        shellRequestResizeWindowCheckbox.isSelected = TerminalConfig.DEFAULT_SHELL_REQUEST_RESIZE_WINDOW
        shellRequestWindowManipulationCheckbox.isSelected = TerminalConfig.DEFAULT_SHELL_REQUEST_WINDOW_MANIPULATION
        scrollOnOutputCheckbox.isSelected = TerminalConfig.DEFAULT_SCROLL_ON_OUTPUT
        cursorBlinkSpinner.value = TerminalConfig.DEFAULT_CURSOR_BLINK_MILLIS
        cursorShapeCombo.selectedItem = TerminalConfig.DEFAULT_CURSOR_SHAPE

        clipboardLocalWriteCombo.selectedItem = TerminalConfig.DEFAULT_CLIPBOARD_LOCAL_WRITE.name.lowercase(Locale.ROOT)
        clipboardRemoteWriteCombo.selectedItem = TerminalConfig.DEFAULT_CLIPBOARD_REMOTE_WRITE.name.lowercase(Locale.ROOT)
        clipboardReadCombo.selectedItem = TerminalConfig.DEFAULT_CLIPBOARD_READ.name.lowercase(Locale.ROOT)
        clipboardMaxDecodedBytesSpinner.value = TerminalConfig.DEFAULT_CLIPBOARD_MAX_DECODED_BYTES
        titleLocalPermissionCheckbox.isSelected = TerminalConfig.DEFAULT_TITLE_LOCAL_PERMISSION == TerminalTitlePermission.ALLOW
        titleRemotePermissionCheckbox.isSelected = TerminalConfig.DEFAULT_TITLE_REMOTE_PERMISSION == TerminalTitlePermission.ALLOW
    }

    private fun applyChanges() {
        val uiState = getUiState()
        model.applyChanges(uiState) {
            updateApplyButtonState()
            onApply()
        }
    }

    private fun updateApplyButtonState() {
        val hasChanges = model.hasChanges(getUiState())
        applyButton.isEnabled = hasChanges
        if (hasChanges) {
            applyButton.putClientProperty("JButton.buttonType", "default")
            applyButton.background = UIManager.getColor("Button.default.background") ?: Chrome.accent
            applyButton.foreground = UIManager.getColor("Button.default.foreground") ?: Chrome.surface
        } else {
            applyButton.putClientProperty("JButton.buttonType", null)
            applyButton.background = null
            applyButton.foreground = null
        }
        applyButton.repaint()
    }

    private fun getUiState(): SettingsState {
        val selected = shellPathCombo.selectedItem
        val nextShellPath =
            if (selected is TerminalProfile) {
                selected.command.firstOrNull() ?: ""
            } else {
                customShellField.text
            }
        val finalShellPath =
            if (profileRegistry.isValidShellPath(nextShellPath)) {
                nextShellPath
            } else {
                TerminalConfig.DEFAULT_SHELL_PATH
            }
        return SettingsState(
            theme = themeCombo.selectedItem as? String ?: "",
            treatAmbiguousAsWide = treatAmbiguousCheckbox.isSelected,
            fontFamily = fontFamilyCombo.selectedItem as? String ?: "",
            fontSize = fontSizeSpinner.value as? Int ?: TerminalConfig.DEFAULT_FONT_SIZE,
            columns = columnsSpinner.value as? Int ?: TerminalConfig.DEFAULT_COLUMNS,
            rows = rowsSpinner.value as? Int ?: TerminalConfig.DEFAULT_ROWS,
            cursorBlinkMillis = cursorBlinkSpinner.value as? Int ?: TerminalConfig.DEFAULT_CURSOR_BLINK_MILLIS,
            useSystemFallbackFonts = useSystemFallbackCheckbox.isSelected,
            cursorShape = cursorShapeCombo.selectedItem as? String ?: "",
            shellPath = finalShellPath,
            startDirectory = startDirectoryField.text,
            audibleBell = audibleBellCheckbox.isSelected,
            visualBell = visualBellCheckbox.isSelected,
            pasteOnMiddleClick = pasteOnMiddleClickCheckbox.isSelected,
            pasteSanitizationPolicy =
                (pasteSanitizationCombo.selectedItem as? PasteSanitizationOption)?.policy
                    ?: TerminalConfig.DEFAULT_PASTE_SANITIZATION_POLICY,
            scrollbackLines = scrollbackSpinner.value as? Int ?: TerminalConfig.DEFAULT_SCROLLBACK_LINES,
            lineHeight = lineHeightSpinner.value as? Double ?: TerminalConfig.DEFAULT_LINE_HEIGHT.toDouble(),
            shellRequestResizeWindow = shellRequestResizeWindowCheckbox.isSelected,
            shellRequestWindowManipulation = shellRequestWindowManipulationCheckbox.isSelected,
            clipboardLocalWrite =
                TerminalClipboardPermission.valueOf(
                    (clipboardLocalWriteCombo.selectedItem as String).uppercase(Locale.ROOT),
                ),
            clipboardRemoteWrite =
                TerminalClipboardPermission.valueOf(
                    (clipboardRemoteWriteCombo.selectedItem as String).uppercase(Locale.ROOT),
                ),
            clipboardRead =
                TerminalClipboardPermission.valueOf(
                    (clipboardReadCombo.selectedItem as String).uppercase(Locale.ROOT),
                ),
            clipboardMaxDecodedBytes =
                clipboardMaxDecodedBytesSpinner.value as? Int
                    ?: TerminalConfig.DEFAULT_CLIPBOARD_MAX_DECODED_BYTES,
            titleLocalPermission =
                if (titleLocalPermissionCheckbox.isSelected) {
                    TerminalTitlePermission.ALLOW
                } else {
                    TerminalTitlePermission.DENY
                },
            titleRemotePermission =
                if (titleRemotePermissionCheckbox.isSelected) {
                    TerminalTitlePermission.ALLOW
                } else {
                    TerminalTitlePermission.DENY
                },
            scrollOnOutput = scrollOnOutputCheckbox.isSelected,
        )
    }

    private fun registerChangeListener(
        comp: Component,
        callback: () -> Unit,
    ) {
        when (comp) {
            is JTextField ->
                comp.document.addDocumentListener(
                    object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = callback()

                        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = callback()

                        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = callback()
                    },
                )
            is JComboBox<*> -> comp.addActionListener { callback() }
            is JCheckBox -> comp.addActionListener { callback() }
            is JSpinner -> comp.addChangeListener { callback() }
        }
    }

    private inner class CategoryLabel(
        val categoryName: String,
    ) : JPanel() {
        private var selected = false
        private var hovered = false

        private val nameLabel =
            JLabel(categoryName).apply {
                font = font.deriveFont(Font.PLAIN, 13f)
                foreground = Chrome.textPrimary
                border = EmptyBorder(0, 12, 0, 0)
            }

        init {
            layout = BorderLayout()
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            preferredSize = Dimension(Int.MAX_VALUE, 32)

            add(nameLabel, BorderLayout.CENTER)

            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        hovered = false
                        repaint()
                    }
                },
            )
        }

        fun updateState(isSelected: Boolean) {
            this.selected = isSelected
            nameLabel.font = nameLabel.font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN)
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            if (selected) {
                g.color = Chrome.controlHover
                g.fillRect(8, 0, width - 16, height)
            } else if (hovered) {
                g.color = Chrome.tabHoverBackground
                g.fillRect(8, 0, width - 16, height)
            }
            super.paintComponent(g)
        }
    }
}

private data class PasteSanitizationOption(
    val label: String,
    val policy: io.github.ketraterm.input.policy.PasteSanitizationPolicy,
) {
    override fun toString(): String = label
}

// TODO(policy): Re-enable "allowlist" after product-host allowlist management
// can persist entries and set TerminalClipboardPolicy.allowlisted for sessions.
private val CLIPBOARD_PERMISSION_OPTIONS =
    arrayOf(
        "allow",
        "prompt",
        // "allowlist",
        "deny",
    )

private fun visibleClipboardPermission(
    value: String,
    fallback: String,
): String =
    if (CLIPBOARD_PERMISSION_OPTIONS.any { it == value }) {
        value
    } else {
        fallback
    }

private val PASTE_SANITIZATION_OPTIONS =
    listOf(
        PasteSanitizationOption("Raw paste", io.github.ketraterm.input.policy.PasteSanitizationPolicy.RAW),
        PasteSanitizationOption(
            "Strip control characters",
            io.github.ketraterm.input.policy.PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
        ),
        PasteSanitizationOption("Normalize line endings", io.github.ketraterm.input.policy.PasteSanitizationPolicy.NORMALIZE_LINE_ENDINGS),
    )

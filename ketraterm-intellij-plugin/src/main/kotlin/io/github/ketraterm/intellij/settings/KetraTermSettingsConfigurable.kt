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

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileRegistry
import io.github.ketraterm.workspace.config.TerminalConfig
import java.awt.Component
import java.util.*
import javax.swing.*

private const val KETRATERM_SETTINGS_CONFIGURABLE_ID = "io.github.ketraterm.terminal.settings"

/**
 * IntelliJ-native settings page for IDE-hosted KetraTerm terminals.
 *
 * The page intentionally exposes only settings the plugin host can honor.
 * Standalone window-manipulation policy remains outside the IntelliJ UI.
 */
class KetraTermSettingsConfigurable internal constructor(
    shellProfiles: List<TerminalProfile>,
) : SearchableConfigurable {
    constructor() : this(TerminalProfileRegistry().availableProfiles())

    private val settings: KetraTermIntellijSettings
        get() = KetraTermIntellijSettings.getInstance()

    private val themeCombo = ComboBox(themeOptions())
    private val fontFamilyCombo = ComboBox(fontFamilyOptions()).apply { isEditable = false }
    private val fallbackFontFamilyCombo = ComboBox(fontFamilyOptions()).apply { isEditable = false }
    private val fontSizeSpinner =
        spinner(KetraTermIntellijSettings.DEFAULT_FONT_SIZE, TerminalConfig.FONT_SIZE_MIN, TerminalConfig.FONT_SIZE_MAX)
    private val columnsSpinner = spinner(TerminalConfig.DEFAULT_COLUMNS, TerminalConfig.COLUMNS_MIN, TerminalConfig.COLUMNS_MAX)
    private val rowsSpinner = spinner(TerminalConfig.DEFAULT_ROWS, TerminalConfig.ROWS_MIN, TerminalConfig.ROWS_MAX)
    private val scrollbackSpinner =
        spinner(TerminalConfig.DEFAULT_SCROLLBACK_LINES, TerminalConfig.SCROLLBACK_MIN, TerminalConfig.SCROLLBACK_MAX)
    private val cursorBlinkSpinner =
        spinner(TerminalConfig.DEFAULT_CURSOR_BLINK_MILLIS, TerminalConfig.CURSOR_BLINK_MIN, TerminalConfig.CURSOR_BLINK_MAX)
    private val lineHeightSpinner =
        JSpinner(
            SpinnerNumberModel(
                TerminalConfig.DEFAULT_LINE_HEIGHT.toDouble(),
                TerminalConfig.LINE_HEIGHT_MIN.toDouble(),
                TerminalConfig.LINE_HEIGHT_MAX.toDouble(),
                0.1,
            ),
        ).apply {
            editor = JSpinner.NumberEditor(this, "0.0")
        }
    private val shellPathCombo =
        ComboBox<Any>(shellProfiles.map(::ShellPathOption).toTypedArray()).apply {
            isEditable = true
            renderer = ShellPathOptionRenderer()
            prototypeDisplayValue = "Windows PowerShell"
        }
    private val startDirectoryField = TextFieldWithBrowseButton()
    private val environmentVariablesField = JBTextField()
    private val defaultTabNameField = JBTextField()
    private val cursorShapeCombo = ComboBox(cursorShapeOptions())
    private val ambiguousWidthCheckBox = JBCheckBox(KetraTermBundle.message("settings.ketraterm.ambiguousWidth"))
    private val systemFallbackFontsCheckBox = JBCheckBox(KetraTermBundle.message("settings.ketraterm.systemFallbackFonts"))
    private val visualBellCheckBox = JBCheckBox(KetraTermBundle.message("settings.ketraterm.visualBell"))
    private val pasteOnMiddleClickCheckBox = JBCheckBox(KetraTermBundle.message("settings.ketraterm.pasteOnMiddleClick"))
    private val overrideIdeShortcutsCheckBox = JBCheckBox(KetraTermBundle.message("settings.ketraterm.overrideIdeShortcuts"))
    private val scrollOnOutputCheckBox = JBCheckBox(KetraTermBundle.message("settings.ketraterm.scrollOnOutput"))

    private val pasteSanitizationCombo = ComboBox(pasteSanitizationOptions())
    private val clipboardLocalWriteCombo = ComboBox(permissionOptions())
    private val clipboardRemoteWriteCombo = ComboBox(permissionOptions())
    private val clipboardReadCombo = ComboBox(permissionOptions())
    private val clipboardMaxDecodedBytesSpinner = spinner(TerminalConfig.DEFAULT_CLIPBOARD_MAX_DECODED_BYTES, 0, Int.MAX_VALUE)
    private val titleLocalPermissionCheckBox = JBCheckBox(KetraTermBundle.message("settings.ketraterm.titleLocalPermission"))
    private val titleRemotePermissionCheckBox = JBCheckBox(KetraTermBundle.message("settings.ketraterm.titleRemotePermission"))

    private var panel: JComponent? = null

    init {
        startDirectoryField.addActionListener {
            val chosen =
                FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                    null,
                    null,
                )
            if (chosen != null) {
                startDirectoryField.text = chosen.path
            }
        }
    }

    override fun getId(): String = KETRATERM_SETTINGS_CONFIGURABLE_ID

    override fun getDisplayName(): String = KetraTermBundle.message("settings.ketraterm.displayName")

    override fun createComponent(): JComponent {
        val created =
            panel {
                group(KetraTermBundle.message("settings.ketraterm.group.session")) {
                    row(KetraTermBundle.message("settings.ketraterm.shellPath")) {
                        cell(shellPathCombo)
                            .align(AlignX.FILL)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.startDirectory")) {
                        cell(startDirectoryField)
                            .align(AlignX.FILL)
                            .comment(KetraTermBundle.message("settings.ketraterm.startDirectory.comment"))
                    }
                    row(KetraTermBundle.message("settings.ketraterm.environmentVariables")) {
                        cell(environmentVariablesField)
                            .align(AlignX.FILL)
                            .comment(KetraTermBundle.message("settings.ketraterm.environmentVariables.comment"))
                    }
                    row(KetraTermBundle.message("settings.ketraterm.defaultTabName")) {
                        cell(defaultTabNameField)
                            .align(AlignX.FILL)
                    }
                }

                group(KetraTermBundle.message("settings.ketraterm.group.font")) {
                    row {
                        label(KetraTermBundle.message("settings.ketraterm.fontFamily"))
                        cell(fontFamilyCombo)
                            .align(AlignX.FILL)
                        label(KetraTermBundle.message("settings.ketraterm.fallbackFontFamily"))
                        cell(fallbackFontFamilyCombo)
                            .align(AlignX.FILL)
                    }
                    row {
                        label(KetraTermBundle.message("settings.ketraterm.fontSize"))
                        cell(fontSizeSpinner)
                        label(KetraTermBundle.message("settings.ketraterm.lineHeight"))
                        cell(lineHeightSpinner)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.theme")) {
                        cell(themeCombo)
                            .align(AlignX.LEFT)
                            .comment(KetraTermBundle.message("settings.ketraterm.theme.comment"))
                    }
                    row {
                        cell(systemFallbackFontsCheckBox)
                    }
                }

                group(KetraTermBundle.message("settings.ketraterm.group.application")) {
                    row(KetraTermBundle.message("settings.ketraterm.initialColumns")) {
                        cell(columnsSpinner)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.initialRows")) {
                        cell(rowsSpinner)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.scrollback")) {
                        cell(scrollbackSpinner)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.cursorShape")) {
                        cell(cursorShapeCombo).align(AlignX.LEFT)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.cursorBlink")) {
                        cell(cursorBlinkSpinner)
                            .comment(KetraTermBundle.message("settings.ketraterm.cursorBlink.comment"))
                    }
                }

                group(KetraTermBundle.message("settings.ketraterm.group.behavior")) {
                    row {
                        cell(ambiguousWidthCheckBox)
                    }
                    row {
                        cell(visualBellCheckBox)
                    }
                    row {
                        cell(pasteOnMiddleClickCheckBox)
                    }
                    row {
                        cell(overrideIdeShortcutsCheckBox)
                    }
                    row {
                        cell(scrollOnOutputCheckBox)
                    }
                }

                group(KetraTermBundle.message("settings.ketraterm.group.security")) {
                    row(KetraTermBundle.message("settings.ketraterm.pasteSanitization")) {
                        cell(pasteSanitizationCombo).align(AlignX.LEFT)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.clipboardLocalWrite")) {
                        cell(clipboardLocalWriteCombo).align(AlignX.LEFT)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.clipboardRemoteWrite")) {
                        cell(clipboardRemoteWriteCombo).align(AlignX.LEFT)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.clipboardRead")) {
                        cell(clipboardReadCombo).align(AlignX.LEFT)
                    }
                    row(KetraTermBundle.message("settings.ketraterm.clipboardMaxDecodedBytes")) {
                        cell(clipboardMaxDecodedBytesSpinner)
                    }
                    row {
                        cell(titleLocalPermissionCheckBox)
                    }
                    row {
                        cell(titleRemotePermissionCheckBox)
                    }
                }
            }
        panel = created
        reset()
        return created
    }

    override fun isModified(): Boolean = KetraTermIntellijSettingsNormalizer.normalize(uiState()) != settings.state

    override fun apply() {
        settings.replaceState(uiState())
    }

    override fun reset() {
        applyState(settings.state)
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun applyState(state: KetraTermIntellijSettings.State) {
        themeCombo.selectedItem = themeOptions().first { it.id == state.themeId }
        fontFamilyCombo.selectConfiguredFont(state.fontFamily)
        fallbackFontFamilyCombo.selectConfiguredFont(state.fallbackFontFamily)
        fontSizeSpinner.value = state.fontSize
        columnsSpinner.value = state.columns
        rowsSpinner.value = state.rows
        scrollbackSpinner.value = state.scrollbackLines
        cursorBlinkSpinner.value = state.cursorBlinkMillis
        lineHeightSpinner.value = state.lineHeight.toDouble()
        shellPathCombo.selectedItem = shellPathOptionFor(state.shellPath) ?: state.shellPath
        startDirectoryField.text = state.startDirectory
        environmentVariablesField.text = state.environmentVariables
        defaultTabNameField.text = state.defaultTabName
        cursorShapeCombo.selectedItem = cursorShapeOptions().first { it.id == state.cursorShape }
        ambiguousWidthCheckBox.isSelected = state.treatAmbiguousAsWide
        systemFallbackFontsCheckBox.isSelected = state.useSystemFallbackFonts
        visualBellCheckBox.isSelected = state.visualBell
        pasteOnMiddleClickCheckBox.isSelected = state.pasteOnMiddleClick
        overrideIdeShortcutsCheckBox.isSelected = state.overrideIdeShortcuts
        scrollOnOutputCheckBox.isSelected = state.scrollOnOutput
        pasteSanitizationCombo.selectedItem = pasteSanitizationOptions().firstOrNull { it.id == state.pasteSanitization }
        clipboardLocalWriteCombo.selectPermission(state.clipboardLocalWrite)
        clipboardRemoteWriteCombo.selectPermission(state.clipboardRemoteWrite)
        clipboardReadCombo.selectPermission(state.clipboardRead)
        clipboardMaxDecodedBytesSpinner.value = state.clipboardMaxDecodedBytes
        titleLocalPermissionCheckBox.isSelected = state.titleLocalPermission == "allow"
        titleRemotePermissionCheckBox.isSelected = state.titleRemotePermission == "allow"
    }

    private fun uiState(): KetraTermIntellijSettings.State =
        settings.state.copy(
            themeId = selectedThemeId(),
            fontFamily = selectedString(fontFamilyCombo),
            fallbackFontFamily = selectedString(fallbackFontFamilyCombo),
            fontSize = spinnerValue(fontSizeSpinner),
            columns = spinnerValue(columnsSpinner),
            rows = spinnerValue(rowsSpinner),
            treatAmbiguousAsWide = ambiguousWidthCheckBox.isSelected,
            cursorBlinkMillis = spinnerValue(cursorBlinkSpinner),
            useSystemFallbackFonts = systemFallbackFontsCheckBox.isSelected,
            cursorShape = selectedCursorShapeId(),
            visualBell = visualBellCheckBox.isSelected,
            pasteOnMiddleClick = pasteOnMiddleClickCheckBox.isSelected,
            overrideIdeShortcuts = overrideIdeShortcutsCheckBox.isSelected,
            scrollbackLines = spinnerValue(scrollbackSpinner),
            lineHeight = spinnerDoubleValue(lineHeightSpinner).toFloat(),
            shellPath = selectedShellPath(),
            startDirectory = startDirectoryField.text.trim(),
            environmentVariables = environmentVariablesField.text.trim(),
            defaultTabName = defaultTabNameField.text.trim(),
            pasteSanitization = (pasteSanitizationCombo.selectedItem as? PasteSanitizationOption)?.id ?: "raw",
            clipboardLocalWrite = (clipboardLocalWriteCombo.selectedItem as? PermissionOption)?.id ?: "prompt",
            clipboardRemoteWrite = (clipboardRemoteWriteCombo.selectedItem as? PermissionOption)?.id ?: "deny",
            clipboardRead = (clipboardReadCombo.selectedItem as? PermissionOption)?.id ?: "deny",
            clipboardMaxDecodedBytes = spinnerValue(clipboardMaxDecodedBytesSpinner),
            titleLocalPermission = if (titleLocalPermissionCheckBox.isSelected) "allow" else "deny",
            titleRemotePermission = if (titleRemotePermissionCheckBox.isSelected) "allow" else "deny",
            scrollOnOutput = scrollOnOutputCheckBox.isSelected,
        )

    private fun selectedThemeId(): String = (themeCombo.selectedItem as? ThemeOption)?.id ?: KetraTermIntellijSettings.DEFAULT_THEME_ID

    private fun selectedCursorShapeId(): String = (cursorShapeCombo.selectedItem as? CursorShapeOption)?.id ?: "block"

    private fun selectedString(comboBox: ComboBox<String>): String =
        comboBox.selectedItem
            ?.toString()
            ?.trim()
            .orEmpty()

    private fun shellPathOptionFor(shellPath: String): ShellPathOption? =
        (0 until shellPathCombo.itemCount)
            .asSequence()
            .map { shellPathCombo.getItemAt(it) }
            .filterIsInstance<ShellPathOption>()
            .firstOrNull { it.profile.command.first() == shellPath }

    private fun selectedShellPath(): String {
        val selected = shellPathCombo.selectedItem
        if (selected is ShellPathOption) return selected.profile.command.first()
        return shellPathCombo.editor.item
            ?.toString()
            ?.trim()
            .orEmpty()
    }

    private fun spinnerValue(spinner: JSpinner): Int = (spinner.value as Number).toInt()

    private fun spinnerDoubleValue(spinner: JSpinner): Double = (spinner.value as Number).toDouble()

    private fun spinner(
        value: Int,
        minimum: Int,
        maximum: Int,
    ): JSpinner =
        JSpinner(SpinnerNumberModel(value, minimum, maximum, 1)).apply {
            editor = JSpinner.NumberEditor(this, "#")
        }
}

private data class ThemeOption(
    val id: String,
    private val label: String,
) {
    override fun toString(): String = label
}

private data class CursorShapeOption(
    val id: String,
    private val label: String,
) {
    override fun toString(): String = label
}

private data class ShellPathOption(
    val profile: TerminalProfile,
) {
    override fun toString(): String = profile.displayName
}

private class ShellPathOptionRenderer : DefaultListCellRenderer() {
    private val profileIcons = KetraTermIntellijProfileIcons()

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val label =
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        when (value) {
            is ShellPathOption -> {
                label.text = value.profile.displayName
                label.icon = profileIcons.icon(value.profile.kind)
            }
            else -> {
                label.text = value?.toString().orEmpty()
                label.icon = null
            }
        }
        return label
    }
}

private fun fontFamilyOptions(): Array<String> =
    LinkedHashSet<String>()
        .apply {
            add(KetraTermIntellijSettings.DEFAULT_FONT_FAMILY)
            addAll(SwingSettings.getMonospaceFontFamilies())
        }.toTypedArray()

private fun ComboBox<String>.selectConfiguredFont(family: String) {
    if ((0 until itemCount).none { getItemAt(it) == family }) addItem(family)
    selectedItem = family
}

private fun themeOptions(): Array<ThemeOption> =
    arrayOf(
        ThemeOption(
            KetraTermIntellijSettings.DEFAULT_THEME_ID,
            KetraTermBundle.message("settings.ketraterm.theme.intellij"),
        ),
        *TerminalTheme.entries
            .map { theme ->
                ThemeOption(
                    theme.id,
                    theme.name
                        .lowercase(Locale.ROOT)
                        .split('_')
                        .joinToString(" ") { part -> part.replaceFirstChar(Char::titlecase) },
                )
            }.toTypedArray(),
    )

private fun cursorShapeOptions(): Array<CursorShapeOption> =
    arrayOf(
        CursorShapeOption("block", KetraTermBundle.message("settings.ketraterm.cursorShape.block")),
        CursorShapeOption("beam", KetraTermBundle.message("settings.ketraterm.cursorShape.beam")),
        CursorShapeOption("underline", KetraTermBundle.message("settings.ketraterm.cursorShape.underline")),
    )

private data class PermissionOption(
    val id: String,
    private val label: String,
) {
    override fun toString(): String = label
}

private fun permissionOptions(): Array<PermissionOption> =
    arrayOf(
        PermissionOption("allow", KetraTermBundle.message("settings.ketraterm.permission.allow")),
        PermissionOption("prompt", KetraTermBundle.message("settings.ketraterm.permission.prompt")),
        // TODO(policy): Re-enable after product-host allowlist management can
        // persist entries and set TerminalClipboardPolicy.allowlisted.
        // PermissionOption("allowlist", KetraTermBundle.message("settings.ketraterm.permission.allowlist")),
        PermissionOption("deny", KetraTermBundle.message("settings.ketraterm.permission.deny")),
    )

private fun ComboBox<PermissionOption>.selectPermission(id: String) {
    val options = permissionOptions().toMutableList()
    if (id == "allowlist") {
        options += PermissionOption(id, KetraTermBundle.message("settings.ketraterm.permission.allowlist.configured"))
    }
    model = DefaultComboBoxModel(options.toTypedArray())
    selectedItem = options.first { it.id == id }
}

private data class PasteSanitizationOption(
    val id: String,
    private val label: String,
) {
    override fun toString(): String = label
}

private fun pasteSanitizationOptions(): Array<PasteSanitizationOption> =
    arrayOf(
        PasteSanitizationOption("raw", KetraTermBundle.message("settings.ketraterm.pasteSanitization.raw")),
        PasteSanitizationOption("strip-c0", KetraTermBundle.message("settings.ketraterm.pasteSanitization.stripC0")),
        PasteSanitizationOption("normalize-line-endings", KetraTermBundle.message("settings.ketraterm.pasteSanitization.normalize")),
    )

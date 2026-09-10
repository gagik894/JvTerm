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
package io.github.ketraterm.core.buffer.impl

import io.github.ketraterm.core.api.TerminalModeController
import io.github.ketraterm.core.engine.CursorEngine
import io.github.ketraterm.core.state.TerminalState
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCursorShape

internal class TerminalModeControllerImpl(
    private val state: TerminalState,
    private val cursorEngine: CursorEngine,
) : TerminalModeController {
    private inline fun mutateMode(block: () -> Unit) {
        block()
    }

    override fun setInsertMode(enabled: Boolean) {
        mutateMode { state.modes.isInsertMode = enabled }
    }

    override fun setAutoWrap(enabled: Boolean) {
        state.cancelPendingWrap()
        state.modes.isAutoWrap = enabled
    }

    override fun setOriginMode(enabled: Boolean) {
        mutateMode {
            state.modes.isOriginMode = enabled
            cursorEngine.homeCursor()
        }
    }

    override fun setApplicationCursorKeys(enabled: Boolean) {
        mutateMode { state.modes.isApplicationCursorKeys = enabled }
    }

    override fun setApplicationKeypad(enabled: Boolean) {
        mutateMode { state.modes.isApplicationKeypad = enabled }
    }

    override fun setBackarrowKeyMode(sendsBackspace: Boolean) {
        mutateMode { state.modes.setBackarrowKeyMode(sendsBackspace) }
    }

    override fun setLeftRightMarginMode(enabled: Boolean) {
        state.cancelPendingWrap()
        if (state.modes.isLeftRightMarginMode == enabled) return
        state.modes.isLeftRightMarginMode = enabled
        state.activeBuffer.resetLeftRightMargins(state.dimensions.width)
        cursorEngine.homeCursor()
    }

    override fun setNewLineMode(enabled: Boolean) {
        mutateMode { state.modes.isNewLineMode = enabled }
    }

    override fun setMouseTrackingMode(mode: io.github.ketraterm.protocol.MouseTrackingMode) {
        mutateMode { state.modes.mouseTrackingMode = mode }
    }

    override fun setMouseEncodingMode(mode: io.github.ketraterm.protocol.MouseEncodingMode) {
        mutateMode { state.modes.mouseEncodingMode = mode }
    }

    override fun setBracketedPasteEnabled(enabled: Boolean) {
        mutateMode { state.modes.isBracketedPasteEnabled = enabled }
    }

    override fun setFocusReportingEnabled(enabled: Boolean) {
        mutateMode { state.modes.isFocusReportingEnabled = enabled }
    }

    override fun setModifyOtherKeysMode(mode: Int) {
        mutateMode { state.modes.modifyOtherKeysMode = mode }
    }

    override fun setFormatOtherKeysMode(mode: Int) {
        mutateMode { state.modes.formatOtherKeysMode = mode }
    }

    override fun setKittyKeyboardFlags(flags: Int) {
        mutateMode {
            state.modes.kittyKeyboardFlags = flags
            state.activeBuffer.kittyKeyboardFlags = flags
        }
    }

    override fun pushKittyKeyboardFlags(flags: Int) {
        mutateMode {
            state.modes.kittyKeyboardFlags = state.activeBuffer.pushKittyKeyboardFlags(flags, state.modes.kittyKeyboardFlags)
        }
    }

    override fun popKittyKeyboardFlags(count: Int) {
        mutateMode {
            state.modes.kittyKeyboardFlags = state.activeBuffer.popKittyKeyboardFlags(count, state.modes.kittyKeyboardFlags)
        }
    }

    override fun setReverseVideo(enabled: Boolean) {
        mutateMode {
            if (state.modes.isReverseVideo == enabled) return@mutateMode
            state.modes.isReverseVideo = enabled
            state.markGlobalAttributesChanged()
        }
    }

    override fun setCursorVisible(enabled: Boolean) {
        mutateMode {
            if (state.modes.isCursorVisible == enabled) return@mutateMode
            state.modes.isCursorVisible = enabled
            state.markCursorChanged()
        }
    }

    override fun setCursorBlinking(enabled: Boolean) {
        mutateMode {
            if (state.modes.isCursorBlinking == enabled) return@mutateMode
            state.modes.isCursorBlinking = enabled
            state.markCursorChanged()
        }
    }

    override fun setCursorShape(shape: TerminalRenderCursorShape) {
        mutateMode {
            state.cursorShape = shape
        }
    }

    override fun setDefaultCursorShape(shape: TerminalRenderCursorShape) {
        mutateMode {
            state.defaultCursorShape = shape
        }
    }

    override fun setTreatAmbiguousAsWide(enabled: Boolean) {
        mutateMode { state.modes.treatAmbiguousAsWide = enabled }
    }

    override fun setSynchronizedOutput(enabled: Boolean) {
        mutateMode { state.modes.isSynchronizedOutput = enabled }
    }

    override fun setBellIsUrgent(enabled: Boolean) {
        mutateMode { state.modes.isBellIsUrgent = enabled }
    }

    override fun setPopOnBell(enabled: Boolean) {
        mutateMode { state.modes.isPopOnBell = enabled }
    }

    override fun enterAltBufferWithoutCursorSave(clearBeforeEnter: Boolean) {
        if (state.isAltScreenActive) return

        state.enterAltScreen(clearBeforeEnter)
        state.markStructureChanged()
        state.markCursorChanged()
    }

    override fun exitAltBufferWithoutCursorRestore() {
        if (!state.isAltScreenActive) return

        state.exitAltScreen()
        state.markStructureChanged()
        state.markCursorChanged()
    }

    override fun enterAltBuffer() {
        if (state.isAltScreenActive) return

        cursorEngine.saveCursor()
        state.enterAltScreen(clearBeforeEnter = true)
        state.markStructureChanged()
        state.markCursorChanged()
    }

    override fun exitAltBuffer() {
        if (!state.isAltScreenActive) return

        state.exitAltScreen()
        cursorEngine.restoreCursor()
        state.markStructureChanged()
    }

    override fun setThemePalette(palette: TerminalColorPalette) {
        mutateMode {
            state.themePalette = palette
            state.palette = palette
            state.markVisibleLinesChanged()
        }
    }

    override fun setPaletteColor(
        index: Int,
        color: Int,
    ) {
        if (index !in 0..255) return
        mutateMode {
            val newIndexed = state.palette.toIndexedColorsArray()
            newIndexed[index] = color
            state.palette = state.palette.copy(indexedColors = newIndexed)
            state.markVisibleLinesChanged()
        }
    }

    override fun setDynamicColor(
        target: Int,
        color: Int,
    ) {
        mutateMode {
            state.palette =
                when (target) {
                    10 -> state.palette.copy(defaultForeground = color)
                    11 -> state.palette.copy(defaultBackground = color)
                    12 -> state.palette.copy(cursorBackground = color)
                    else -> state.palette
                }
            state.markVisibleLinesChanged()
        }
    }
}

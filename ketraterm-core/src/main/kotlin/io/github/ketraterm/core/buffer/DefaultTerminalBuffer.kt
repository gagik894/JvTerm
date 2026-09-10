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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.api.*
import io.github.ketraterm.core.buffer.impl.*
import io.github.ketraterm.core.engine.CursorEngine
import io.github.ketraterm.core.engine.MutationEngine
import io.github.ketraterm.core.engine.TerminalResizer
import io.github.ketraterm.core.model.SavedCursorState
import io.github.ketraterm.core.render.CoreTerminalRenderFrame
import io.github.ketraterm.core.state.TerminalState

/**
 * Concrete facade for the terminal-buffer core.
 *
 * The facade stays intentionally thin: focused adapters implement the public
 * roles while the hot mutation and cursor logic remain in dedicated engines.
 *
 * Cross-cutting responsibilities owned here:
 * - resize orchestration across both screen buffers
 * - full terminal reset (RIS)
 * - soft terminal reset (DECSTR)
 */
internal class DefaultTerminalBuffer private constructor(
    private val components: Components,
) : TerminalBuffer,
    io.github.ketraterm.render.api.TerminalRenderFrameReader,
    TerminalReader by TerminalReaderImpl(components.state),
    TerminalWriter by BufferWriter(components.state, components.mutationEngine, components.cursorEngine),
    TerminalCursor by TerminalCursorImpl(components.state, components.cursorEngine),
    TerminalModeController by TerminalModeControllerImpl(components.state, components.cursorEngine),
    TerminalModeReader by TerminalModeReaderImpl(components.state),
    TerminalResponseChannel by BufferResponseChannel(components.state),
    TerminalInspector by BufferInspector(components.state) {
    private val state: TerminalState
        get() = components.state
    private val renderFrame = CoreTerminalRenderFrame(components.state)

    constructor(initialWidth: Int, initialHeight: Int, maxHistory: Int = 1000) : this(
        createComponents(initialWidth, initialHeight, maxHistory),
    )

    /**
     * Provides a low-level render frame without taking a lock.
     *
     * Callers that may race with terminal mutation must synchronize externally.
     * `terminal-session` is the intended synchronization point for UI code.
     */
    override fun readRenderFrame(consumer: io.github.ketraterm.render.api.TerminalRenderFrameConsumer) {
        readRenderFrame(scrollbackOffset = 0, consumer = consumer)
    }

    override fun readRenderFrame(
        scrollbackOffset: Int,
        consumer: io.github.ketraterm.render.api.TerminalRenderFrameConsumer,
    ) {
        renderFrame.use(scrollbackOffset) {
            consumer.accept(renderFrame)
        }
    }

    override fun readRenderFrame(
        scrollbackOffset: Int,
        viewportRows: Int,
        consumer: io.github.ketraterm.render.api.TerminalRenderFrameConsumer,
    ) {
        renderFrame.use(scrollbackOffset, viewportRows) {
            consumer.accept(renderFrame)
        }
    }

    /**
     * Reflows the primary screen, recreates the alternate screen, updates global
     * dimensions and tab stops, then restores invariants that must hold even for
     * the currently inactive buffer.
     *
     * @return A [Pair] of (newScrollbackOffset, newHistorySize), allowing the caller to
     *   re-anchor a scrollback viewport that was active at [oldScrollbackOffset] before
     *   the reflow. The offset is 0 when the caller was not scrolled back.
     */
    override fun resize(
        newWidth: Int,
        newHeight: Int,
        oldScrollbackOffset: Int,
    ): Pair<Int, Int> {
        require(newWidth > 0) { "newWidth must be > 0, was $newWidth" }
        require(newHeight > 0) { "newHeight must be > 0, was $newHeight" }

        val oldWidth = state.dimensions.width
        val oldHeight = state.dimensions.height
        val oldCursorCol = state.cursor.col
        val oldCursorRow = state.cursor.row

        if (newWidth == oldWidth && newHeight == oldHeight) {
            return Pair(oldScrollbackOffset.coerceIn(0, state.historySize), state.historySize)
        }

        val newScrollbackOffset =
            TerminalResizer.resizeBuffer(
                buffer = state.primaryBuffer,
                oldWidth = oldWidth,
                oldHeight = oldHeight,
                newWidth = newWidth,
                newHeight = newHeight,
                lineIdProvider = state::allocateLineId,
                oldScrollbackOffset = oldScrollbackOffset,
            )
        state.altBuffer.replaceStorage(newWidth, newHeight, state.pen.blankAttr, state.pen.blankExtendedAttr)

        state.dimensions.width = newWidth
        state.dimensions.height = newHeight
        state.tabStops.resize(newWidth)

        state.primaryBuffer.resetScrollRegion(newHeight)
        state.altBuffer.resetScrollRegion(newHeight)
        state.primaryBuffer.resetLeftRightMargins(newWidth)
        state.altBuffer.resetLeftRightMargins(newWidth)
        state.primaryBuffer.clampSavedCursorToBounds(newWidth, newHeight)
        state.altBuffer.clampSavedCursorToBounds(newWidth, newHeight)
        state.cancelPendingWrap()
        for (row in 0 until newHeight) {
            state.markLineChanged(state.ring[state.resolveRingIndex(row)])
        }
        state.markStructureChanged()
        if (state.cursor.col != oldCursorCol || state.cursor.row != oldCursorRow) {
            state.markCursorChanged()
        }
        return Pair(newScrollbackOffset, state.historySize)
    }

    override fun reset() {
        val wasReverseVideo = state.modes.isReverseVideo
        if (state.isAltScreenActive) {
            this.exitAltBuffer()
        }
        clearAll()
        state.activeBuffer.resetScrollRegion(state.dimensions.height)
        state.primaryBuffer.resetLeftRightMargins(state.dimensions.width)
        state.altBuffer.resetLeftRightMargins(state.dimensions.width)
        state.primaryBuffer.clearKittyKeyboardStack()
        state.altBuffer.clearKittyKeyboardStack()
        state.isAttributeChangeExtentRectangle = false
        state.modes.reset()
        if (wasReverseVideo != state.modes.isReverseVideo) {
            state.markGlobalAttributesChanged()
        }
        state.tabStops.resetToDefault()
        state.cursorShape = state.defaultCursorShape
        state.palette = state.themePalette
        state.markStructureChanged()
        state.markCursorChanged()
    }

    override fun softReset() {
        val wasReverseVideo = state.modes.isReverseVideo
        state.pen.reset()
        state.isAttributeChangeExtentRectangle = false

        state.modes.softReset()

        state.primaryBuffer.resetScrollRegion(state.dimensions.height)
        state.altBuffer.resetScrollRegion(state.dimensions.height)
        state.primaryBuffer.resetLeftRightMargins(state.dimensions.width)
        state.altBuffer.resetLeftRightMargins(state.dimensions.width)
        state.primaryBuffer.clearKittyKeyboardStack()
        state.altBuffer.clearKittyKeyboardStack()
        state.primaryBuffer.cursor.pendingWrap = false
        state.altBuffer.cursor.pendingWrap = false
        resetSavedCursorToHome(state.primaryBuffer.savedCursor)
        resetSavedCursorToHome(state.altBuffer.savedCursor)
        state.cursorShape = state.defaultCursorShape
        if (wasReverseVideo != state.modes.isReverseVideo) {
            state.markGlobalAttributesChanged()
        }
        state.markCursorChanged()
    }

    override fun executeDeccolm(newWidth: Int) {
        if (newWidth != 80 && newWidth != 132) return

        val primarySaved = SavedCursorSnapshot.from(state.primaryBuffer.savedCursor)
        val altSaved = SavedCursorSnapshot.from(state.altBuffer.savedCursor)

        resize(newWidth, state.dimensions.height) // oldScrollbackOffset=0: DECCOLM wipes the buffer
        components.mutationEngine.deccolmReset(newWidth)

        primarySaved.restoreInto(state.primaryBuffer.savedCursor)
        altSaved.restoreInto(state.altBuffer.savedCursor)
    }

    private fun resetSavedCursorToHome(target: SavedCursorState) {
        target.col = 0
        target.row = 0
        target.attr = state.pen.currentAttr
        target.extendedAttr = state.pen.currentExtendedAttr
        target.pendingWrap = false
        target.isOriginMode = false
        target.isSaved = true
    }

    private data class Components(
        val state: TerminalState,
        val mutationEngine: MutationEngine,
        val cursorEngine: CursorEngine,
    )

    private data class SavedCursorSnapshot(
        val col: Int,
        val row: Int,
        val attr: Long,
        val extendedAttr: Long,
        val pendingWrap: Boolean,
        val isOriginMode: Boolean,
        val isSaved: Boolean,
    ) {
        fun restoreInto(target: SavedCursorState) {
            target.col = col
            target.row = row
            target.attr = attr
            target.extendedAttr = extendedAttr
            target.pendingWrap = pendingWrap
            target.isOriginMode = isOriginMode
            target.isSaved = isSaved
        }

        companion object {
            fun from(source: SavedCursorState): SavedCursorSnapshot =
                SavedCursorSnapshot(
                    col = source.col,
                    row = source.row,
                    attr = source.attr,
                    extendedAttr = source.extendedAttr,
                    pendingWrap = source.pendingWrap,
                    isOriginMode = source.isOriginMode,
                    isSaved = source.isSaved,
                )
        }
    }

    private companion object {
        fun createComponents(
            initialWidth: Int,
            initialHeight: Int,
            maxHistory: Int,
        ): Components {
            val state = TerminalState(initialWidth, initialHeight, maxHistory)
            return Components(
                state = state,
                mutationEngine = MutationEngine(state),
                cursorEngine = CursorEngine(state),
            )
        }
    }
}

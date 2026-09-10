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
package io.github.ketraterm.core.render

import io.github.ketraterm.core.state.TerminalState

/**
 * Adapter from core state to the stable public render frame ABI.
 */
internal class CoreTerminalRenderFrame(
    @PublishedApi internal val state: TerminalState,
) : io.github.ketraterm.render.api.TerminalRenderFrame {
    private val attrTranslator = RenderAttrTranslator()
    private val clusterScratch = RenderClusterScratch()

    @PublishedApi internal var isValid: Boolean = false

    @PublishedApi internal var resolvedScrollbackOffset: Int = 0

    @PublishedApi internal var resolvedRows: Int = 0

    internal inline fun <T> use(
        scrollbackOffset: Int,
        block: () -> T,
    ): T = use(scrollbackOffset, state.dimensions.height, block)

    internal inline fun <T> use(
        scrollbackOffset: Int,
        viewportRows: Int,
        block: () -> T,
    ): T {
        require(viewportRows > 0) { "viewportRows must be > 0, was $viewportRows" }
        resolvedScrollbackOffset = state.clampScrollbackOffset(scrollbackOffset)
        resolvedRows = viewportRows.coerceAtMost(state.dimensions.height + resolvedScrollbackOffset)
        isValid = true
        try {
            return block()
        } finally {
            isValid = false
            resolvedScrollbackOffset = 0
            resolvedRows = 0
        }
    }

    private fun checkValid() {
        check(isValid) { "TerminalRenderFrame is only valid inside the readRenderFrame callback" }
    }

    override val columns: Int
        get() {
            checkValid()
            return state.dimensions.width
        }

    override val rows: Int
        get() {
            checkValid()
            return resolvedRows
        }

    override val historySize: Int
        get() {
            checkValid()
            return state.historySize
        }

    override val scrollbackOffset: Int
        get() {
            checkValid()
            return resolvedScrollbackOffset
        }

    override val discardedCount: Long
        get() {
            checkValid()
            return state.ring.discardedCount
        }

    override val frameGeneration: Long
        get() {
            checkValid()
            return state.frameGeneration
        }

    override val contentGeneration: Long
        get() {
            checkValid()
            return state.contentGeneration
        }

    override val structureGeneration: Long
        get() {
            checkValid()
            return state.structureGeneration
        }

    override val activeBuffer: io.github.ketraterm.render.api.TerminalRenderBufferKind
        get() {
            checkValid()
            return if (state.isAltScreenActive) {
                io.github.ketraterm.render.api.TerminalRenderBufferKind.ALTERNATE
            } else {
                io.github.ketraterm.render.api.TerminalRenderBufferKind.PRIMARY
            }
        }

    override val palette: io.github.ketraterm.render.api.TerminalColorPalette
        get() {
            checkValid()
            return state.palette
        }

    override val cursor: io.github.ketraterm.render.api.TerminalRenderCursor
        get() {
            checkValid()
            val row = state.cursor.row + resolvedScrollbackOffset
            return io.github.ketraterm.render.api.TerminalRenderCursor(
                column = state.cursor.col,
                row = row,
                visible = state.modes.isCursorVisible && row in 0 until resolvedRows,
                blinking = state.modes.isCursorBlinking,
                shape = state.cursorShape,
                generation = state.cursorGeneration,
            )
        }

    override fun copyCursor(sink: io.github.ketraterm.render.api.TerminalRenderCursorSink) {
        checkValid()
        val row = state.cursor.row + resolvedScrollbackOffset
        sink.onCursor(
            column = state.cursor.col,
            row = row,
            visible = state.modes.isCursorVisible && row in 0 until resolvedRows,
            blinking = state.modes.isCursorBlinking,
            shape = state.cursorShape,
            generation = state.cursorGeneration,
        )
    }

    override fun lineGeneration(row: Int): Long {
        checkValid()
        checkRow(row)
        return visibleLineAt(row).renderGeneration
    }

    override fun lineId(row: Int): Long {
        checkValid()
        checkRow(row)
        return visibleLineAt(row).lineId
    }

    override fun lineWrapped(row: Int): Boolean {
        checkValid()
        checkRow(row)
        return visibleLineAt(row).wrapped
    }

    override fun copyLine(
        row: Int,
        codeWords: IntArray,
        codeOffset: Int,
        attrWords: LongArray,
        attrOffset: Int,
        flags: IntArray,
        flagOffset: Int,
        extraAttrWords: LongArray?,
        extraAttrOffset: Int,
        hyperlinkIds: IntArray?,
        hyperlinkOffset: Int,
        clusterSink: io.github.ketraterm.render.api.TerminalRenderClusterSink?,
        clusterDataSink: io.github.ketraterm.render.api.TerminalRenderClusterDataSink?,
    ) {
        checkValid()
        checkRow(row)
        checkCapacity(codeWords, codeOffset, columns, "codeWords")
        checkCapacity(attrWords, attrOffset, columns, "attrWords")
        checkCapacity(flags, flagOffset, columns, "flags")
        if (extraAttrWords != null) {
            checkCapacity(extraAttrWords, extraAttrOffset, columns, "extraAttrWords")
        }
        if (hyperlinkIds != null) {
            checkCapacity(hyperlinkIds, hyperlinkOffset, columns, "hyperlinkIds")
        }

        visibleLineAt(row).copyToRenderAbi(
            width = columns,
            codeWords = codeWords,
            codeOffset = codeOffset,
            attrWords = attrWords,
            attrOffset = attrOffset,
            flags = flags,
            flagOffset = flagOffset,
            extraAttrWords = extraAttrWords,
            extraAttrOffset = extraAttrOffset,
            hyperlinkIds = hyperlinkIds,
            hyperlinkOffset = hyperlinkOffset,
            clusterSink = clusterSink,
            clusterDataSink = clusterDataSink,
            attrTranslator = attrTranslator,
            clusterScratch = clusterScratch,
            reverseVideo = state.modes.isReverseVideo,
        )
    }

    private fun visibleLineAt(row: Int) = state.ring[state.resolveScrollbackRingIndex(row, resolvedScrollbackOffset)]

    private fun checkRow(row: Int) {
        require(row in 0 until rows) {
            "row out of bounds: $row, rows=$rows"
        }
    }

    private fun checkCapacity(
        array: IntArray,
        offset: Int,
        length: Int,
        name: String,
    ) {
        require(offset >= 0 && array.size - offset >= length) {
            "$name has insufficient capacity: size=${array.size}, offset=$offset, required=$length"
        }
    }

    private fun checkCapacity(
        array: LongArray,
        offset: Int,
        length: Int,
        name: String,
    ) {
        require(offset >= 0 && array.size - offset >= length) {
            "$name has insufficient capacity: size=${array.size}, offset=$offset, required=$length"
        }
    }
}

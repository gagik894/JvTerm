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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout

/**
 * Extracts selected visible text from primitive render-cache rows in logical text order.
 * Rectangular selections project their visual interval independently on each row.
 */
internal class TerminalSelectionTextExtractor {
    private val rowScratch = StringBuilder(INITIAL_ROW_CAPACITY)

    fun selectedText(
        cache: TerminalRenderCache,
        selection: CellSelection,
        joinSoftWrappedRows: Boolean = false,
    ): String {
        if (selection.isEmpty) return ""

        // A copy operation may own a temporary history snapshot; do not retain it afterward.
        val bidiLayout = if (selection.isBlock) TerminalBidiLayout() else null
        val result = StringBuilder()
        var row = selection.startRow.coerceAtLeast(0)
        val lastRow = selection.endRow.coerceAtMost(cache.rows - 1)
        var hasPreviousSelectedRow = false
        while (row <= lastRow) {
            val bidi = bidiLayout?.row(cache, row)
            val range = selection.packedColumnRange(row, cache, bidi)
            if (range != CellSelection.NO_RANGE) {
                if (
                    hasPreviousSelectedRow &&
                    (!joinSoftWrappedRows || selection.isBlock || row == 0 || !cache.lineWrapped[row - 1])
                ) {
                    result.append('\n')
                }
                appendTrimmedRow(
                    destination = result,
                    cache = cache,
                    row = row,
                    startColumn = CellSelection.rangeStart(range),
                    endColumn = CellSelection.rangeEnd(range),
                    bidi = bidi,
                )
                hasPreviousSelectedRow = true
            }
            row++
        }
        return result.toString()
    }

    fun wordSelectionAt(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
    ): CellSelection? {
        if (row !in 0 until cache.rows || column !in 0 until cache.columns) return null

        val rowOffset = cache.rowOffset(row)
        val flagsPlane = cache.flags
        val codeWords = cache.codeWords

        // 1. Expand with the wide path/URL character set
        var pathStart = column
        while (pathStart > 0) {
            val idx = rowOffset + pathStart - 1
            if (!isPathChar(flagsPlane[idx], codeWords[idx])) break
            pathStart--
        }

        var pathEnd = column + 1
        while (pathEnd < cache.columns) {
            val idx = rowOffset + pathEnd
            if (!isPathChar(flagsPlane[idx], codeWords[idx])) break
            pathEnd++
        }

        // Check if the expanded path contains any path-defining characters
        var hasPathIndicator = false
        var col = pathStart
        while (col < pathEnd) {
            val charVal = codeWords[rowOffset + col].toChar()
            if (charVal == '/' || charVal == '\\' || charVal == '.' || charVal == ':') {
                hasPathIndicator = true
                break
            }
            col++
        }

        if (hasPathIndicator) {
            return CellSelection(pathStart, row, pathEnd, row)
        }

        // 2. Otherwise, fall back to standard word selection (letters, digits, underscore)
        val clickedIndex = rowOffset + column
        val clickedKind = wordKind(flagsPlane[clickedIndex], codeWords[clickedIndex])

        var start = column
        while (start > 0) {
            val previousIndex = rowOffset + start - 1
            if (wordKind(flagsPlane[previousIndex], codeWords[previousIndex]) != clickedKind) break
            start--
        }

        var end = column + 1
        while (end < cache.columns) {
            val nextIndex = rowOffset + end
            if (wordKind(flagsPlane[nextIndex], codeWords[nextIndex]) != clickedKind) break
            end++
        }

        return CellSelection(start, row, end, row)
    }

    private fun appendTrimmedRow(
        destination: StringBuilder,
        cache: TerminalRenderCache,
        row: Int,
        startColumn: Int,
        endColumn: Int,
        bidi: TerminalBidiLayout.Row?,
    ) {
        rowScratch.setLength(0)
        // A visual interval may select disjoint logical spans in mixed-direction text.
        // Visit logical cells in order so copying preserves text and cluster ordering.
        var column = if (bidi == null) startColumn else 0
        val limit = if (bidi == null) endColumn else cache.columns
        while (column < limit) {
            val visualColumn = bidi?.visualColumn(column) ?: column
            column =
                if (visualColumn >= startColumn && visualColumn < endColumn) {
                    appendCell(rowScratch, cache, row, column)
                } else {
                    column + 1
                }
        }

        var trimmedEnd = rowScratch.length
        while (trimmedEnd > 0 && rowScratch[trimmedEnd - 1] == ' ') {
            trimmedEnd--
        }
        destination.append(rowScratch, 0, trimmedEnd)
    }

    private fun appendCell(
        destination: StringBuilder,
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
    ): Int {
        val index = cache.rowOffset(row) + column
        val flags = cache.flags[index]
        if (flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
            return column + 1
        }

        when {
            flags and TerminalRenderCellFlags.CLUSTER != 0 -> {
                val ref = cache.clusterRefs[index]
                if (ref != 0L) {
                    val offset = cache.clusterOffset(ref)
                    val length = cache.clusterLength(ref)
                    destination.appendCodePoints(cache.clusterCodepoints, offset, length)
                }
            }

            flags and TerminalRenderCellFlags.CODEPOINT != 0 -> {
                destination.appendCodePoint(cache.codeWords[index])
            }

            else -> destination.append(' ')
        }

        return column + if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1
    }

    private fun StringBuilder.appendCodePoints(
        codepoints: IntArray,
        offset: Int,
        length: Int,
    ) {
        var index = offset
        val end = offset + length
        while (index < end) {
            appendCodePoint(codepoints[index])
            index++
        }
    }

    private fun isPathChar(
        flags: Int,
        codeWord: Int,
    ): Boolean {
        if (flags and (TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER) == 0) return false
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) return true
        if (Character.isLetterOrDigit(codeWord)) return true
        return when (codeWord.toChar()) {
            '_', '-', '/', '\\', '.', ':', '~', '?', '&', '=', '%', '+', '@', '$', '*' -> true
            else -> false
        }
    }

    private fun wordKind(
        flags: Int,
        codeWord: Int,
    ): Int {
        if (flags and (TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER) == 0) return WORD_KIND_SPACE
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) return WORD_KIND_WORD
        if (Character.isLetterOrDigit(codeWord) || codeWord == '_'.code) {
            return WORD_KIND_WORD
        }
        if (Character.isWhitespace(codeWord)) return WORD_KIND_SPACE
        return WORD_KIND_PUNCTUATION_BASE + codeWord
    }

    private companion object {
        private const val INITIAL_ROW_CAPACITY = 256
        private const val WORD_KIND_SPACE = 0
        private const val WORD_KIND_WORD = 1
        private const val WORD_KIND_PUNCTUATION_BASE = 2
    }
}

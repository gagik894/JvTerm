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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import java.text.Bidi

/**
 * Shared logical/visual cell mapping for painting, overlays, repainting and pointer input.
 *
 * Rows are cached by cache identity, column count, buffer, line identity and generation. LTR
 * rows use the identity mapping without allocating a row object. Bidi analysis runs only
 * for changed directional text; repainting and querying an unchanged row allocate nothing.
 * Row storage follows retained cache capacity, so overscan height changes preserve both
 * storage and unchanged permutations. Column scratch grows independently of row capacity.
 * Rows without stable line identities invalidate when structure or viewport mapping changes.
 * Grapheme clusters and wide-cell pairs remain atomic when applying UBA level reordering.
 */
internal class TerminalBidiLayout {
    private var source: TerminalRenderCache? = null
    private var columns = 0
    private var buffer = -1
    private var structureGeneration = Long.MIN_VALUE
    private var viewportTop = 0L
    private var layouts = arrayOfNulls<Row>(0)
    private var generations = LongArray(0)
    private var lineIds = LongArray(0)
    private var chars = CharArray(256)
    private var unitColumns = IntArray(0)
    private var unitCharOffsets = IntArray(0)
    private var unitOrder = IntArray(0)
    private var unitLevels = ByteArray(0)

    fun reset() {
        source = null
    }

    fun row(
        cache: TerminalRenderCache,
        row: Int,
    ): Row? {
        if (row !in 0 until cache.rows) return null
        if (layouts.size < cache.lineIds.size) {
            val previousCapacity = layouts.size
            layouts = layouts.copyOf(cache.lineIds.size)
            generations = generations.copyOf(cache.lineIds.size)
            generations.fill(Long.MIN_VALUE, previousCapacity)
            lineIds = lineIds.copyOf(cache.lineIds.size)
        }
        val nextViewportTop = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        if (source !== cache || columns != cache.columns || buffer != cache.activeBuffer.ordinal) {
            source = cache
            columns = cache.columns
            buffer = cache.activeBuffer.ordinal
            layouts.fill(null)
            generations.fill(Long.MIN_VALUE)
        } else if (structureGeneration != cache.structureGeneration || viewportTop != nextViewportTop) {
            var index = 0
            while (index < layouts.size) {
                if (lineIds[index] == 0L) {
                    layouts[index] = null
                    generations[index] = Long.MIN_VALUE
                }
                index++
            }
        }
        structureGeneration = cache.structureGeneration
        viewportTop = nextViewportTop
        if (unitOrder.size < columns) {
            unitColumns = IntArray(columns + 1)
            unitCharOffsets = IntArray(columns)
            unitOrder = IntArray(columns)
            unitLevels = ByteArray(columns)
        }
        val generation = cache.lineGenerations[row]
        val lineId = cache.lineIds[row]
        if (generations[row] == generation && lineIds[row] == lineId) return layouts[row]
        val layout = if (containsDirectionalText(cache, row)) buildRow(cache, row) else null
        layouts[row] = layout
        generations[row] = generation
        lineIds[row] = lineId
        return layout
    }

    private fun containsDirectionalText(
        cache: TerminalRenderCache,
        row: Int,
    ): Boolean {
        val rowOffset = cache.rowOffset(row)
        var column = 0
        while (column < columns) {
            val index = rowOffset + column
            if (cache.flags[index] and TerminalRenderCellFlags.CLUSTER != 0) {
                val ref = cache.clusterRefs[index]
                if (ref != 0L) {
                    var cp = cache.clusterOffset(ref)
                    val end = cp + cache.clusterLength(ref)
                    while (cp < end) {
                        if (requiresBidi(cache.clusterCodepoints[cp++])) return true
                    }
                }
            } else if (hasDrawableText(cache.flags[index]) && requiresBidi(cache.codeWords[index])) {
                return true
            }
            column++
        }
        return false
    }

    private fun buildRow(
        cache: TerminalRenderCache,
        row: Int,
    ): Row {
        val rowOffset = cache.rowOffset(row)
        var units = 0
        var length = 0
        var column = 0
        while (column < columns) {
            val index = rowOffset + column
            val flags = cache.flags[index]
            unitColumns[units] = column
            unitCharOffsets[units] = length
            unitOrder[units] = units
            if (flags and TerminalRenderCellFlags.CLUSTER != 0 && cache.clusterRefs[index] != 0L) {
                val ref = cache.clusterRefs[index]
                var cp = cache.clusterOffset(ref)
                val end = cp + cache.clusterLength(ref)
                while (cp < end) length = appendCodePoint(cache.clusterCodepoints[cp++], length)
            }
            if (length == unitCharOffsets[units]) {
                length = appendCodePoint(if (hasDrawableText(flags)) cache.codeWords[index] else 0x20, length)
            }
            column += minOf(cellSpan(flags), columns - column)
            units++
        }
        unitColumns[units] = columns
        val bidi = Bidi(chars, 0, null, 0, length, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        var maxLevel = 0
        var minOddLevel = Int.MAX_VALUE
        var unit = 0
        while (unit < units) {
            val level = bidi.getLevelAt(unitCharOffsets[unit])
            unitLevels[unit] = level.toByte()
            maxLevel = maxOf(maxLevel, level)
            if (level and 1 != 0) minOddLevel = minOf(minOddLevel, level)
            unit++
        }
        // UBA L2: reverse contiguous sequences from the highest level through the lowest odd level.
        var level = maxLevel
        while (level >= minOddLevel) {
            unit = 0
            while (unit < units) {
                if (unitLevels[unitOrder[unit]].toInt() < level) {
                    unit++
                    continue
                }
                val start = unit
                while (unit < units && unitLevels[unitOrder[unit]].toInt() >= level) unit++
                var left = start
                var right = unit - 1
                while (left < right) {
                    val saved = unitOrder[left]
                    unitOrder[left++] = unitOrder[right]
                    unitOrder[right--] = saved
                }
            }
            level--
        }

        val logicalToVisual = IntArray(columns)
        val visualToLogical = IntArray(columns)
        val levels = ByteArray(columns)
        var visual = 0
        unit = 0
        while (unit < units) {
            val logicalUnit = unitOrder[unit++]
            var logical = unitColumns[logicalUnit]
            val limit = unitColumns[logicalUnit + 1]
            while (logical < limit) {
                logicalToVisual[logical] = visual
                visualToLogical[visual++] = logical
                levels[logical++] = unitLevels[logicalUnit]
            }
        }
        return Row(logicalToVisual, visualToLogical, levels)
    }

    private fun appendCodePoint(
        codePoint: Int,
        offset: Int,
    ): Int {
        if (offset + 2 > chars.size) chars = chars.copyOf(maxOf(offset + 2, chars.size * 2))
        val safeCodePoint = if (Character.isValidCodePoint(codePoint)) codePoint else 0xFFFD
        return offset + Character.toChars(safeCodePoint, chars, offset)
    }

    /** Immutable cell permutation for one analyzed row; core/cache columns remain logical. */
    class Row(
        private val logicalToVisual: IntArray,
        private val visualToLogical: IntArray,
        private val levels: ByteArray,
    ) {
        fun visualColumn(logicalColumn: Int): Int = logicalToVisual[logicalColumn]

        fun logicalColumn(visualColumn: Int): Int = visualToLogical[visualColumn]

        fun isRtl(column: Int): Boolean = levels[column].toInt() and 1 != 0

        fun runLimit(start: Int): Int {
            var end = start + 1
            while (end < levels.size && levels[end] == levels[start]) end++
            return end
        }
    }

    private companion object {
        private fun requiresBidi(codePoint: Int): Boolean =
            codePoint >= 0x590 &&
                when (Character.getDirectionality(codePoint)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE,
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE,
                    Character.DIRECTIONALITY_FIRST_STRONG_ISOLATE,
                    -> true
                    else -> false
                }
    }
}

/** Projects a logical range into contiguous visual spans without filling gaps or allocating ranges. */
internal inline fun forEachVisualCellSpan(
    layout: TerminalBidiLayout.Row?,
    startColumn: Int,
    endColumn: Int,
    action: (Int, Int) -> Unit,
) {
    if (startColumn >= endColumn) return
    if (layout == null) {
        action(startColumn, endColumn)
        return
    }
    var start = layout.visualColumn(startColumn)
    var end = start + 1
    var logical = startColumn + 1
    while (logical < endColumn) {
        when (val visual = layout.visualColumn(logical++)) {
            end -> end++
            start - 1 -> start--
            else -> {
                action(start, end)
                start = visual
                end = visual + 1
            }
        }
    }
    action(start, end)
}

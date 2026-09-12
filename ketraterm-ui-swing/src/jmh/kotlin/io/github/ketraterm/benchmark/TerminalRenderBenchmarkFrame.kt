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
package io.github.ketraterm.benchmark

import io.github.ketraterm.render.api.*

/** Frozen scalar-cell viewport; copying uses retained primitive planes. */
internal class TerminalRenderBenchmarkFrame(
    lines: List<String>,
    attributes: LongArray? = null,
) : TerminalRenderFrame {
    override val columns: Int = lines.first().codePointCount(0, lines.first().length)
    override val rows: Int = lines.size
    override val historySize: Int = 0
    override val scrollbackOffset: Int = 0
    override val discardedCount: Long = 0L
    override val frameGeneration: Long = 1
    override val contentGeneration: Long = 1
    override val structureGeneration: Long = 1
    override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
    override val palette = TerminalColorPalette()
    override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1)
    private val codes = IntArray(columns * rows)
    private val attrs = attributes?.copyOf() ?: LongArray(codes.size) { TerminalRenderAttrs.DEFAULT }

    init {
        require(columns > 0 && attrs.size == codes.size)
        for ((row, text) in lines.withIndex()) {
            require(text.codePointCount(0, text.length) == columns)
            var index = 0
            var column = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                codes[row * columns + column++] = codePoint
                index += Character.charCount(codePoint)
            }
        }
    }

    override fun lineGeneration(row: Int): Long = 1

    override fun lineId(row: Int): Long = row + 1L

    override fun lineWrapped(row: Int): Boolean = false

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
        clusterSink: TerminalRenderClusterSink?,
        clusterDataSink: TerminalRenderClusterDataSink?,
    ) {
        val start = row * columns
        codes.copyInto(codeWords, codeOffset, start, start + columns)
        attrs.copyInto(attrWords, attrOffset, start, start + columns)
        flags.fill(TerminalRenderCellFlags.CODEPOINT, flagOffset, flagOffset + columns)
        extraAttrWords?.fill(TerminalRenderExtraAttrs.DEFAULT, extraAttrOffset, extraAttrOffset + columns)
        hyperlinkIds?.fill(0, hyperlinkOffset, hyperlinkOffset + columns)
    }
}

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

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache

/** Immutable text and UTF-16-to-cell mapping for one soft-wrapped logical line. */
internal class TerminalHyperlinkLineSnapshot(
    val text: String,
    val columns: Int,
    val activeBuffer: TerminalRenderBufferKind,
    val cellStarts: IntArray,
    val cellEnds: IntArray,
    private val lineIds: LongArray,
    private val generations: LongArray,
    private val fingerprints: LongArray,
    private val wrapped: BooleanArray,
) {
    val firstLineId: Long get() = lineIds[0]

    fun matchesRows(
        cache: TerminalRenderCache,
        start: Int,
        end: Int,
    ): Boolean {
        if (end - start != lineIds.size || cache.columns != columns || cache.activeBuffer != activeBuffer) return false
        for (offset in lineIds.indices) {
            val row = start + offset
            if (cache.lineIds[row] != lineIds[offset] || cache.lineWrapped[row] != wrapped[offset]) return false
            if (lineIds[offset] != 0L) {
                if (cache.lineGenerations[row] != generations[offset]) return false
            } else if (rowFingerprint(cache, row) != fingerprints[offset]) {
                return false
            }
        }
        return true
    }

    fun sameIdentity(other: TerminalHyperlinkLineSnapshot): Boolean =
        columns == other.columns &&
            activeBuffer == other.activeBuffer &&
            lineIds.contentEquals(other.lineIds) &&
            fingerprints.contentEquals(other.fingerprints) &&
            wrapped.contentEquals(other.wrapped)
}

/** Reuses extraction scratch storage; snapshots are created only for changed logical lines. */
internal class TerminalHyperlinkLineSnapshotBuilder {
    private val text = StringBuilder(256)
    private var starts = IntArray(256)
    private var ends = IntArray(256)

    fun snapshot(
        cache: TerminalRenderCache,
        startRow: Int,
        endRow: Int,
    ): TerminalHyperlinkLineSnapshot {
        text.setLength(0)
        for (row in startRow until endRow) {
            val rowOffset = cache.rowOffset(row)
            var column = 0
            while (column < cache.columns) {
                val index = rowOffset + column
                val flags = cache.flags[index]
                if (flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
                    column++
                    continue
                }
                val span = if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1
                val start = (row - startRow) * cache.columns + column
                val end = (row - startRow) * cache.columns + minOf(cache.columns, column + span)
                when {
                    flags and TerminalRenderCellFlags.CLUSTER != 0 -> {
                        val ref = cache.clusterRefs[index]
                        if (ref == 0L) {
                            appendCodePoint(0x20, start, end)
                        } else {
                            val clusterStart = cache.clusterOffset(ref)
                            val clusterEnd = clusterStart + cache.clusterLength(ref)
                            for (offset in clusterStart until clusterEnd) appendCodePoint(cache.clusterCodepoints[offset], start, end)
                        }
                    }
                    flags and TerminalRenderCellFlags.CODEPOINT != 0 -> appendCodePoint(cache.codeWords[index], start, end)
                    else -> appendCodePoint(0x20, start, end)
                }
                column += span
            }
        }
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
        val length = text.length
        text.append('\n')
        val rowCount = endRow - startRow
        return TerminalHyperlinkLineSnapshot(
            text.toString(),
            cache.columns,
            cache.activeBuffer,
            starts.copyOf(length),
            ends.copyOf(length),
            cache.lineIds.copyOfRange(startRow, endRow),
            cache.lineGenerations.copyOfRange(startRow, endRow),
            LongArray(rowCount) { rowFingerprint(cache, startRow + it) },
            cache.lineWrapped.copyOfRange(startRow, endRow),
        )
    }

    private fun appendCodePoint(
        codePoint: Int,
        start: Int,
        end: Int,
    ) {
        val before = text.length
        text.appendCodePoint(if (Character.isValidCodePoint(codePoint)) codePoint else 0xFFFD)
        if (text.length > starts.size) {
            val capacity = maxOf(text.length, starts.size * 2)
            starts = starts.copyOf(capacity)
            ends = ends.copyOf(capacity)
        }
        for (offset in before until text.length) {
            starts[offset] = start
            ends[offset] = end
        }
    }
}

private fun rowFingerprint(
    cache: TerminalRenderCache,
    row: Int,
): Long {
    var hash = -3750763034362895579L
    val rowOffset = cache.rowOffset(row)
    for (column in 0 until cache.columns) {
        val index = rowOffset + column
        val flags = cache.flags[index]
        val textFlags =
            flags and (
                TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER or
                    TerminalRenderCellFlags.WIDE_LEADING or TerminalRenderCellFlags.WIDE_TRAILING
            )
        hash = (hash xor textFlags.toLong()) * 1099511628211L
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val ref = cache.clusterRefs[index]
            if (ref != 0L) {
                val start = cache.clusterOffset(ref)
                val end = start + cache.clusterLength(ref)
                for (offset in start until end) hash = (hash xor cache.clusterCodepoints[offset].toLong()) * 1099511628211L
            }
        } else {
            hash = (hash xor cache.codeWords[index].toLong()) * 1099511628211L
        }
    }
    return hash
}

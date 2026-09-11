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
package io.github.ketraterm.render.api

/**
 * Short-lived primitive view of the terminal's render state for one viewport.
 *
 * A frame exposes stable public render encodings, not core internal storage.
 * Consumers should copy rows into caller-owned primitive arrays during the
 * enclosing [TerminalRenderFrameReader.readRenderFrame] callback.
 */
interface TerminalRenderFrame {
    /**
     * Number of visible columns in each render row.
     */
    val columns: Int

    /**
     * Number of visible render rows.
     */
    val rows: Int

    /**
     * Number of retained off-screen history lines available in this frame.
     */
    val historySize: Int
        get() = 0

    /**
     * Clamped scrollback offset used by this frame, in lines from the live
     * bottom viewport. Zero means the frame is pinned to the newest output.
     */
    val scrollbackOffset: Int
        get() = 0

    /**
     * Number of history lines discarded due to ring buffer capacity wrapping.
     */
    val discardedCount: Long
        get() = 0L

    /**
     * Monotonic generation that changes on any visually relevant mutation.
     *
     * Consumers can use this as a cheap "anything changed?" check. Callers must
     * compare for equality or inequality rather than assuming the value remains
     * positive forever.
     */
    val frameGeneration: Long

    /**
     * Generation for retained cell content and terminal-owned row mapping, including
     * off-screen history. Cell, cluster, stored attribute, wrap, resize, scroll, reset, and
     * buffer changes advance this value; cursor and caller-requested viewport changes
     * do not need to. Compare for equality, since generations can wrap.
     *
     * The default conservatively includes all visual changes. Implementations with
     * separate content tracking should override this to let consumers reuse work
     * across cursor-only frames. Generations are local to one content source.
     * Global presentation changes such as reverse video may preserve this value,
     * but must change [lineGeneration] for every row whose rendered attributes change.
     */
    val contentGeneration: Long
        get() = frameGeneration

    /**
     * Generation that changes when terminal-owned row mapping or shape changes.
     *
     * Resize, full reset, scrolling, buffer switches, and reflow should advance
     * this value. Caller-requested scrollback offset changes are reported via
     * [scrollbackOffset]; render caches should include that value in their own
     * row-copy invalidation key.
     */
    val structureGeneration: Long

    /**
     * Currently active terminal screen buffer.
     */
    val activeBuffer: TerminalRenderBufferKind

    /**
     * Currently active resolved color palette.
     */
    val palette: TerminalColorPalette
        get() = TerminalColorPalette()

    /**
     * Current render cursor overlay state.
     *
     * Prefer [copyCursor] in frame-copy hot paths so implementations can expose
     * cursor state without allocating this value object.
     */
    val cursor: TerminalRenderCursor

    /**
     * Returns the generation for visible [row].
     *
     * The generation changes when visual cell content, attributes, hyperlink
     * identifiers, cluster text, or the row wrap flag changes.
     *
     * @param row zero-based visible row index.
     * @return row visual generation.
     */
    fun lineGeneration(row: Int): Long

    /**
     * Returns the stable logical line identity for visible [row].
     *
     * The identity is owned by core and moves with content through scrolling
     * and resize reflow. It is intended for renderer-side metadata projection
     * such as shell integration decorations; renderers must treat `0` as
     * "identity unavailable".
     *
     * @param row zero-based visible row index.
     * @return stable logical line id, or `0` when the frame implementation does
     * not expose one.
     */
    fun lineId(row: Int): Long = 0L

    /**
     * Reports whether visible [row] soft-wraps into the next row.
     *
     * @param row zero-based visible row index.
     * @return `true` when the row wraps into the following row.
     */
    fun lineWrapped(row: Int): Boolean

    /**
     * Copies one visible row into caller-owned primitive arrays in logical terminal-column order.
     *
     * All destination arrays must have enough space for [columns] entries from
     * their respective offsets. [extraAttrWords] and [hyperlinkIds] are optional
     * because not every renderer needs those channels.
     *
     * [clusterDataSink] is the preferred primitive handoff for cells marked
     * [TerminalRenderCellFlags.CLUSTER]. [clusterSink] is retained for callers
     * that need text directly; implementations may avoid constructing cluster
     * strings when only [clusterDataSink] is supplied.
     *
     * @param row zero-based visible row index.
     * @param codeWords destination for Unicode scalar values or zero for empty,
     * cluster, and wide trailing cells.
     * @param codeOffset first destination index in [codeWords].
     * @param attrWords destination for stable public render attribute words.
     * @param attrOffset first destination index in [attrWords].
     * @param flags destination for [TerminalRenderCellFlags] bit sets.
     * @param flagOffset first destination index in [flags].
     * @param extraAttrWords optional destination for less common attribute data.
     * @param extraAttrOffset first destination index in [extraAttrWords].
     * @param hyperlinkIds optional destination for public hyperlink identifiers,
     * where zero means no hyperlink.
     * @param hyperlinkOffset first destination index in [hyperlinkIds].
     * @param clusterSink optional receiver for cluster text on cluster cells.
     * @param clusterDataSink optional receiver for primitive cluster code points
     * on cluster cells.
     */
    fun copyLine(
        row: Int,
        codeWords: IntArray,
        codeOffset: Int = 0,
        attrWords: LongArray,
        attrOffset: Int = 0,
        flags: IntArray,
        flagOffset: Int = 0,
        extraAttrWords: LongArray? = null,
        extraAttrOffset: Int = 0,
        hyperlinkIds: IntArray? = null,
        hyperlinkOffset: Int = 0,
        clusterSink: TerminalRenderClusterSink? = null,
        clusterDataSink: TerminalRenderClusterDataSink? = null,
    )

    /**
     * Copies the current cursor overlay state into [sink].
     *
     * The default implementation adapts [cursor] for simple frame
     * implementations. Allocation-sensitive implementations should override
     * this method and send primitive cursor fields directly.
     *
     * @param sink primitive cursor receiver.
     */
    fun copyCursor(sink: TerminalRenderCursorSink) {
        val cursor = cursor
        sink.onCursor(
            column = cursor.column,
            row = cursor.row,
            visible = cursor.visible,
            blinking = cursor.blinking,
            shape = cursor.shape,
            generation = cursor.generation,
        )
    }
}

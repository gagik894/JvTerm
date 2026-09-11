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
package io.github.ketraterm.ui.swing.viewport

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * EDT-confined smooth scrollback viewport model.
 *
 * The model owns the precise position, animation timeline and history bounds as
 * one state transition. The controller supplies input and clock ticks. The
 * line-addressed render reader receives a whole-row anchor plus one overscan
 * row; fractional motion is a renderer translation only. Renderer decorations
 * do not contribute scrollable height.
 */
internal class SwingScrollModel {
    private val animation = SmoothRowScrollAnimation()
    private var cellHeight: Int = 1
    private var discardedCount: Long = 0L
    private var preciseOffset: Double = 0.0

    /** Number of retained rows available above the live viewport. */
    var historySize: Int = 0
        private set

    /**
     * Precise visual scrollback offset in terminal rows.
     *
     * `0.0` is the live viewport. Larger values move farther back into
     * scrollback history. Fractional values exist only during animation.
     */
    val preciseScrollbackOffset: Double
        get() = preciseOffset

    /**
     * Scrollback offset that should be requested from the render reader.
     */
    val requestedOffset: Int
        get() = ceil(preciseOffset).toInt()

    /** Whether clock ticks are still moving toward a whole-row destination. */
    val isAnimating: Boolean
        get() = animation.isActive

    /** Active destination, or the nearest row to the current idle position. */
    val targetRow: Int
        get() = if (animation.isActive) animation.targetRow else preciseOffset.roundToInt()

    /**
     * Current visual pixel offset from the live bottom.
     */
    val visualScrollOffsetPixels: Double
        get() = preciseOffset * cellHeight

    /**
     * Maximum visual pixel offset from the live bottom.
     */
    val visualScrollRangePixels: Int
        get() = (historySize.toLong() * cellHeight).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Current fixed terminal row height used by visual controls. */
    val cellHeightPixels: Int
        get() = cellHeight

    /** Whether the current viewport needs one row of smooth-scroll overscan. */
    val needsOverscan: Boolean
        get() = requestedOffset.toDouble() != preciseOffset

    /**
     * Clears scrollback state back to the live viewport.
     */
    fun reset() {
        animation.cancel()
        cellHeight = 1
        historySize = 0
        discardedCount = 0L
        preciseOffset = 0.0
    }

    /**
     * Clamps the current offset after history size changes.
     *
     * Active timelines follow the same content translation as their visual
     * position. Shrinking or resetting history ends the old timeline.
     *
     * @return true when the requested render offset or overscan requirement changed.
     */
    fun clamp(
        historySize: Int,
        discardedCount: Long,
        scrollOnOutput: Boolean,
    ): Boolean {
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        require(discardedCount >= 0L) { "discardedCount must be >= 0, was $discardedCount" }
        val oldRequestedOffset = requestedOffset
        val oldNeedsOverscan = needsOverscan
        val historyShrank = historySize < this.historySize || discardedCount < this.discardedCount
        // A larger shift already exceeds every addressable history row. Bound the
        // discarded delta before addition so long-lived counters cannot overflow.
        val maximumHistoryDelta = Int.MAX_VALUE.toLong() + 1L
        val deltaBottom =
            (historySize.toLong() - this.historySize) +
                (discardedCount - this.discardedCount).coerceIn(-maximumHistoryDelta, maximumHistoryDelta)
        this.historySize = historySize
        this.discardedCount = discardedCount

        when {
            deltaBottom > 0L && scrollOnOutput -> {
                animation.cancel()
                preciseOffset = 0.0
            }
            historyShrank -> {
                if (animation.isActive) preciseOffset = preciseOffset.roundToInt().toDouble()
                animation.cancel()
                preciseOffset = preciseOffset.coerceIn(0.0, historySize.toDouble())
            }
            deltaBottom > 0L && (preciseOffset > 0.0 || animation.isActive) -> {
                preciseOffset = (preciseOffset + deltaBottom).coerceIn(0.0, historySize.toDouble())
                animation.rebase(preciseOffset, deltaBottom, historySize)
            }
            else -> preciseOffset = preciseOffset.coerceIn(0.0, historySize.toDouble())
        }
        return oldRequestedOffset != requestedOffset || oldNeedsOverscan != needsOverscan
    }

    /** Updates pixel metrics without changing history or the animation timeline. */
    fun updateCellHeight(cellHeight: Int) {
        require(cellHeight > 0) { "cellHeight must be > 0, was $cellHeight" }
        this.cellHeight = cellHeight
    }

    /**
     * Moves to [offsetLines], clamped to the available scrollback history.
     *
     * @return true when the precise offset changed.
     */
    fun scrollTo(
        offsetLines: Double,
        historySize: Int,
    ): Boolean {
        require(offsetLines.isFinite()) { "offsetLines must be finite, was $offsetLines" }
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }

        animation.cancel()
        this.historySize = historySize
        return applyOffset(offsetLines)
    }

    /** Installs the session's resize anchor and history baseline from the same mutation. */
    fun anchorAfterResize(
        offset: Int,
        historySize: Int,
        discardedCount: Long,
    ) {
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        require(discardedCount >= 0L) { "discardedCount must be >= 0, was $discardedCount" }
        animation.cancel()
        this.historySize = historySize
        this.discardedCount = discardedCount
        applyOffset(offset.toDouble())
    }

    /** Advances to [nowNanos], then accumulates input from the previous destination. */
    fun animateBy(
        deltaRows: Int,
        nowNanos: Long,
    ): Boolean {
        val moved = advance(nowNanos)
        return animation.retargetBy(preciseOffset, deltaRows, historySize, nowNanos) || moved
    }

    /** Advances to [nowNanos], then targets a bounded row without restarting an unchanged target. */
    fun animateTo(
        targetRow: Int,
        nowNanos: Long,
    ): Boolean {
        val moved = advance(nowNanos)
        return animation.retargetTo(preciseOffset, targetRow, historySize, nowNanos) || moved
    }

    /** Applies a clock tick and returns whether the precise visual position changed. */
    fun advance(nowNanos: Long): Boolean {
        return animation.isActive && applyOffset(animation.positionAt(nowNanos))
    }

    /** Settles an active animation on its destination and reports visual movement. */
    fun finish(): Boolean {
        if (!animation.isActive) return false
        val destination = animation.targetRow
        animation.cancel()
        return applyOffset(destination.toDouble())
    }

    /** Stops the timeline while preserving the current precise position. */
    fun cancelAnimation() {
        animation.cancel()
    }

    /** Returns the render-cache row count needed for the current viewport. */
    fun requestedRows(renderRows: Int): Int {
        require(renderRows > 0) { "renderRows must be > 0, was $renderRows" }
        return if (needsOverscan) renderRows + 1 else renderRows
    }

    private fun applyOffset(offset: Double): Boolean {
        val nextOffset = offset.coerceIn(0.0, historySize.toDouble())
        if (nextOffset == preciseOffset) return false
        preciseOffset = nextOffset
        return true
    }
}

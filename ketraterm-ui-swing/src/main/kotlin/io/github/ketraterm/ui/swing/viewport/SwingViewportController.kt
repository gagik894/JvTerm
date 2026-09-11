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

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.api.TerminalViewportListener
import io.github.ketraterm.ui.swing.api.TerminalViewportState
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Timer

/**
 * EDT-owned viewport and scrollback controller with off-EDT snapshots.
 *
 * Owns scroll input and timer lifecycle. [SwingScrollModel] reconciles the
 * animation and viewport against history in one operation. [onScroll] reports
 * whether the integer render window changed and whether scrolling completed;
 * the component owns render requests and painting. Frame reconciliation does
 * not call [onScroll], because its caller already owns frame invalidation.
 */
internal class SwingViewportController(
    private val listener: TerminalViewportListener,
    private val onScroll: (renderMappingChanged: Boolean, scrollComplete: Boolean) -> Unit,
) {
    private val scrollModel = SwingScrollModel()
    private val accumulator = ScrollDeltaAccumulator()
    private val scrollTimer =
        Timer(SCROLL_FRAME_DELAY_MILLIS) { advanceScroll(System.nanoTime()) }.apply {
            isCoalesce = true
            initialDelay = 0
        }
    private val visibleGridSizeSnapshot = AtomicLong(packVisibleGridSize(1, 1))

    // The EDT is the sole writer. Worker snapshots share this monitor with publication;
    // EDT paint getters read directly because no other thread can modify these fields.
    private val viewportSnapshotLock = Any()
    private var publishedHistorySize = 0
    private var publishedScrollbackOffset = 0.0
    private var publishedRenderOffset = 0
    private var publishedVisibleRows = 1
    private var publishedRequestedRows = 1
    private var publishedVisualOffsetPixels = 0.0
    private var publishedVisualRangePixels = 0
    private var publishedViewportHeightPixels = 0
    private var publishedContentHeightPixels = 0
    private var publishedCellHeightPixels = 1

    val requestedOffset: Int
        get() = scrollModel.requestedOffset

    val preciseOffset: Double
        get() = scrollModel.preciseScrollbackOffset

    /** Published scrollbar metrics for EDT painting; other threads use [viewportStateSnapshot]. */
    val historySize: Int
        get() = publishedHistorySize

    val visualScrollOffsetPixels: Double
        get() = publishedVisualOffsetPixels

    val visualScrollRangePixels: Int
        get() = publishedVisualRangePixels

    val viewportHeightPixels: Int
        get() = publishedViewportHeightPixels

    fun reset() {
        cancelScroll()
        scrollModel.reset()
    }

    /** Accumulates precise device input into whole-row animation destinations. */
    fun scrollByPreciseRows(deltaRows: Double): Boolean {
        require(deltaRows.isFinite()) { "deltaRows must be finite, was $deltaRows" }
        if (deltaRows == 0.0) return false
        val destination = scrollModel.targetRow
        if ((deltaRows < 0.0 && destination <= 0) || (deltaRows > 0.0 && destination >= scrollModel.historySize)) {
            accumulator.reset()
            return scrollModel.isAnimating
        }
        val wholeRows = accumulator.accumulate(deltaRows)
        if (wholeRows == 0) return true
        val changed = animateBy(wholeRows)
        if (!changed) accumulator.reset()
        return changed
    }

    /** Animates a signed whole-row delta from the current destination. */
    fun scrollByRows(deltaRows: Int): Boolean {
        accumulator.reset()
        return deltaRows != 0 && animateBy(deltaRows)
    }

    /** Animates to an absolute integer row; repeated targets keep their deadline. */
    fun scrollToRow(targetRow: Int): Boolean {
        accumulator.reset()
        val changed = updateScroll(notifyUnchanged = true) { scrollModel.animateTo(targetRow, System.nanoTime()) }
        return changed || scrollModel.isAnimating
    }

    /** Direct manipulation maps immediately to an integer row without easing. */
    fun jumpToRow(targetRow: Int): Boolean {
        cancelScroll()
        return updateScroll(notifyCompletion = false) {
            scrollModel.scrollTo(targetRow.toDouble(), scrollModel.historySize)
        }
    }

    /** Stops timer and input accumulation before a source or position is replaced. */
    fun cancelScroll() {
        scrollTimer.stop()
        scrollModel.cancelAnimation()
        accumulator.reset()
    }

    /** Settles on the destination before resizing or changing cell metrics. */
    fun finishScroll() {
        accumulator.reset()
        if (!scrollModel.isAnimating) return
        updateScroll { scrollModel.finish() }
    }

    internal fun advanceScroll(nowNanos: Long) {
        if (!scrollModel.isAnimating) {
            scrollTimer.stop()
            return
        }
        updateScroll { scrollModel.advance(nowNanos) }
    }

    private fun animateBy(deltaRows: Int): Boolean {
        val changed = updateScroll { scrollModel.animateBy(deltaRows, System.nanoTime()) }
        return changed || scrollModel.isAnimating
    }

    private inline fun updateScroll(
        notifyCompletion: Boolean = true,
        notifyUnchanged: Boolean = false,
        update: () -> Unit,
    ): Boolean {
        val previousOffset = preciseOffset
        val previousAnchor = requestedOffset
        val previousOverscan = scrollModel.needsOverscan
        val wasAnimating = scrollModel.isAnimating
        update()
        val complete = !scrollModel.isAnimating
        if (complete) {
            scrollTimer.stop()
        } else if (!scrollTimer.isRunning) {
            scrollTimer.start()
        }
        val changed = previousOffset != preciseOffset
        if (changed || notifyCompletion && complete && (wasAnimating || notifyUnchanged)) {
            onScroll(
                previousAnchor != requestedOffset || previousOverscan != scrollModel.needsOverscan,
                notifyCompletion && complete,
            )
        }
        return changed
    }

    fun visibleGridSizeSnapshot(): Dimension {
        val packed = visibleGridSizeSnapshot.get()
        return Dimension(unpackVisibleColumns(packed), unpackVisibleRows(packed))
    }

    fun visibleGridSizeOnEdt(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
    ): Dimension {
        val packed = updateVisibleGridSize(settings, metrics, componentWidth, componentHeight, activeBuffer)
        return Dimension(unpackVisibleColumns(packed), unpackVisibleRows(packed))
    }

    fun updateVisibleGridSize(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
    ): Long {
        val columns = visibleGridColumns(settings, metrics, componentWidth, activeBuffer)
        val rows = visibleGridRows(settings, metrics, componentHeight, activeBuffer)
        val packed = packVisibleGridSize(columns, rows)
        visibleGridSizeSnapshot.set(packed)
        return packed
    }

    fun visibleGridRows(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentHeight: Int,
        activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
    ): Int =
        maxOf(
            1,
            (componentHeight - SwingTerminalChrome.verticalInset(settings, activeBuffer)) / metrics.cellHeight,
        )

    fun visibleRenderRows(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentHeight: Int,
        activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
    ): Int {
        val availableHeight = componentHeight - SwingTerminalChrome.verticalInset(settings, activeBuffer)
        if (availableHeight <= 0) return 1
        return ceilDiv(availableHeight, metrics.cellHeight)
    }

    fun viewportPixelHeight(
        settings: SwingSettings,
        componentHeight: Int,
        activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
    ): Int = maxOf(0, componentHeight - SwingTerminalChrome.verticalInset(settings, activeBuffer))

    fun requestedRows(renderRows: Int): Int = scrollModel.requestedRows(renderRows)

    fun scrollTo(
        offsetLines: Double,
        historySize: Int,
    ): Boolean {
        cancelScroll()
        return updateScroll { scrollModel.scrollTo(offsetLines, historySize) }
    }

    fun clamp(
        historySize: Int,
        discardedCount: Long,
        scrollOnOutput: Boolean,
    ): Boolean {
        val previousHistorySize = scrollModel.historySize
        val wasAnimating = scrollModel.isAnimating
        val changed = scrollModel.clamp(historySize, discardedCount, scrollOnOutput)
        if (!scrollModel.isAnimating) {
            scrollTimer.stop()
            if (wasAnimating || historySize < previousHistorySize) accumulator.reset()
        }
        return changed
    }

    fun updateCellHeight(cellHeight: Int) = scrollModel.updateCellHeight(cellHeight)

    fun anchorAfterResize(
        newOffset: Int,
        newHistorySize: Int,
        newDiscardedCount: Long,
    ) {
        cancelScroll()
        scrollModel.anchorAfterResize(newOffset, newHistorySize, newDiscardedCount)
    }

    /**
     * Translates the installed cache toward the precise position within its available coverage.
     *
     * An outstanding render request does not change which rows are safe to expose. At a
     * cache edge, translation waits for its replacement. Near live output, the fractional
     * space below the terminal grid remains empty until scrolling supplies history there.
     */
    fun contentOriginY(
        cacheScrollbackOffset: Int,
        cacheRows: Int,
        cellHeight: Int,
        viewportHeightPixels: Int,
        visibleGridRows: Int,
    ): Double {
        require(cacheScrollbackOffset >= 0) {
            "cacheScrollbackOffset must be >= 0, was $cacheScrollbackOffset"
        }
        require(cacheRows >= 0) { "cacheRows must be >= 0, was $cacheRows" }
        require(cellHeight > 0) { "cellHeight must be > 0, was $cellHeight" }
        require(viewportHeightPixels >= 0) { "viewportHeightPixels must be >= 0, was $viewportHeightPixels" }
        require(visibleGridRows > 0) { "visibleGridRows must be > 0, was $visibleGridRows" }

        val requiredBottom = minOf(viewportHeightPixels.toDouble(), (visibleGridRows.toDouble() + preciseOffset) * cellHeight)
        val minimumOrigin = minOf(0.0, requiredBottom - cacheRows.toDouble() * cellHeight)
        val desiredOrigin = (preciseOffset - cacheScrollbackOffset) * cellHeight
        return desiredOrigin.coerceIn(minimumOrigin, 0.0)
    }

    /**
     * Copies one completed publication under the same short monitor used by its writer.
     * Only an explicit snapshot request allocates the result. Neither snapshot copying
     * nor publication dispatches to the EDT or invokes listeners while holding the monitor.
     */
    fun viewportStateSnapshot(): TerminalViewportState =
        synchronized(viewportSnapshotLock) {
            TerminalViewportState(
                historySize = publishedHistorySize,
                scrollbackOffset = publishedScrollbackOffset,
                renderOffset = publishedRenderOffset,
                visibleRows = publishedVisibleRows,
                requestedRows = publishedRequestedRows,
                visualScrollOffsetPixels = publishedVisualOffsetPixels,
                visualScrollRangePixels = publishedVisualRangePixels,
                viewportHeightPixels = publishedViewportHeightPixels,
                contentHeightPixels = publishedContentHeightPixels,
                cellHeightPixels = publishedCellHeightPixels,
            )
        }

    fun publishViewportState(
        historySize: Int,
        visibleRows: Int,
        renderRows: Int,
        viewportHeightPixels: Int,
        contentHeightPixels: Int,
        notifyListener: Boolean = true,
        notifyPrimitiveListener: Boolean = false,
    ) {
        val requestedRows = scrollModel.requestedRows(renderRows)
        val scrollbackOffset = scrollModel.preciseScrollbackOffset
        val renderOffset = scrollModel.requestedOffset
        val visualScrollOffsetPixels = scrollModel.visualScrollOffsetPixels
        val visualScrollRangePixels = scrollModel.visualScrollRangePixels
        val cellHeightPixels = scrollModel.cellHeightPixels

        synchronized(viewportSnapshotLock) {
            publishedHistorySize = historySize
            publishedScrollbackOffset = scrollbackOffset
            publishedRenderOffset = renderOffset
            publishedVisibleRows = visibleRows
            publishedRequestedRows = requestedRows
            publishedVisualOffsetPixels = visualScrollOffsetPixels
            publishedVisualRangePixels = visualScrollRangePixels
            publishedViewportHeightPixels = viewportHeightPixels
            publishedContentHeightPixels = contentHeightPixels
            publishedCellHeightPixels = cellHeightPixels
        }
        if (!notifyListener) {
            if (notifyPrimitiveListener) {
                listener.viewportChanged(
                    historySize = historySize,
                    scrollbackOffset = scrollbackOffset,
                    renderOffset = renderOffset,
                    visibleRows = visibleRows,
                    requestedRows = requestedRows,
                )
            }
            return
        }

        listener.viewportStateChanged(
            TerminalViewportState(
                historySize = historySize,
                scrollbackOffset = scrollbackOffset,
                renderOffset = renderOffset,
                visibleRows = visibleRows,
                requestedRows = requestedRows,
                visualScrollOffsetPixels = visualScrollOffsetPixels,
                visualScrollRangePixels = visualScrollRangePixels,
                viewportHeightPixels = viewportHeightPixels,
                contentHeightPixels = contentHeightPixels,
                cellHeightPixels = cellHeightPixels,
            ),
        )
    }

    private companion object {
        private const val SCROLL_FRAME_DELAY_MILLIS = 8

        private fun visibleGridColumns(
            settings: SwingSettings,
            metrics: SwingMetrics,
            componentWidth: Int,
            activeBuffer: TerminalRenderBufferKind,
        ): Int =
            maxOf(
                1,
                (
                    componentWidth -
                        SwingTerminalChrome.horizontalInset(
                            settings,
                            activeBuffer,
                        )
                ) / metrics.cellWidth,
            )

        private fun packVisibleGridSize(
            columns: Int,
            rows: Int,
        ): Long = (columns.toLong() shl 32) or (rows.toLong() and 0xffff_ffffL)

        fun unpackVisibleColumns(packed: Long): Int = (packed ushr 32).toInt()

        fun unpackVisibleRows(packed: Long): Int = packed.toInt()

        private fun ceilDiv(
            value: Int,
            divisor: Int,
        ): Int {
            require(divisor > 0) { "divisor must be > 0, was $divisor" }
            return (value - 1) / divisor + 1
        }
    }
}

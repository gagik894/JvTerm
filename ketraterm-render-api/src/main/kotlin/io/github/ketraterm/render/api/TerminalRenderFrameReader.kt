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
 * Provides a short-lived render frame view.
 *
 * The frame passed to the consumer is valid only during the callback.
 * Implementations may hold a terminal mutation lock while invoking the consumer.
 * Consumers must copy anything they need before returning.
 *
 * A reader instance identifies one source's generation namespace for incremental
 * consumers. Use a new reader for unrelated terminal content, or explicitly reset
 * consumer caches before replacing the content behind an existing reader.
 */
interface TerminalRenderFrameReader {
    /**
     * Invokes [consumer] with a render frame view whose lifetime is limited to
     * this call.
     *
     * Implementations may reuse the same frame object across calls, so consumers
     * must not retain the frame after [consumer] returns.
     *
     * @param consumer receiver that copies any render data it needs.
     */
    fun readRenderFrame(consumer: TerminalRenderFrameConsumer)

    /**
     * Invokes [consumer] with a render frame for a caller-owned scrollback
     * viewport.
     *
     * [scrollbackOffset] is measured in lines from the live bottom viewport.
     * Implementations clamp it to available history and expose the resolved
     * value through [TerminalRenderFrame.scrollbackOffset]. This method does not
     * mutate terminal state; it only changes how rows are mapped for this read.
     *
     * Implementations that do not support scrollback views may fall back to the
     * bottom-pinned frame by implementing only [readRenderFrame].
     *
     * @param scrollbackOffset requested lines above the live bottom viewport.
     * @param consumer receiver that copies any render data it needs.
     */
    fun readRenderFrame(
        scrollbackOffset: Int,
        consumer: TerminalRenderFrameConsumer,
    ) {
        readRenderFrame(consumer)
    }

    /**
     * Invokes [consumer] with a render frame for a caller-owned scrollback
     * viewport that may contain more rows than the live terminal grid.
     *
     * This is intended for UI overscan such as smooth pixel scrolling. The
     * requested [viewportRows] is render-only state: it must not resize the
     * terminal, mutate scrollback, or change host-visible dimensions.
     * Implementations clamp the exposed row count to valid backing storage and
     * may fall back to [readRenderFrame] when overscan is unsupported.
     *
     * @param scrollbackOffset requested lines above the live bottom viewport.
     * @param viewportRows requested number of rows in the render viewport.
     * @param consumer receiver that copies any render data it needs.
     */
    fun readRenderFrame(
        scrollbackOffset: Int,
        viewportRows: Int,
        consumer: TerminalRenderFrameConsumer,
    ) {
        readRenderFrame(scrollbackOffset, consumer)
    }

    /**
     * Invokes [consumer] with a frame containing the retained intersection of
     * the requested absolute row range.
     *
     * Absolute rows start at zero for the first terminal line and continue
     * increasing when old history is discarded. Implementations that serialize
     * terminal mutation should resolve the range and expose the frame in one
     * critical section so concurrent output cannot shift the requested rows.
     * The returned frame may start before [startAbsoluteRow] when the requested
     * range begins inside the live grid, because scrollback viewports are
     * anchored at the live grid's first row. Callers must intersect their range
     * with the retained bounds reported by the returned frame.
     *
     * The default implementation snapshots current frame metadata and then
     * issues a relative viewport read. Stateful callers must serialize this
     * entire method against mutation, or readers may override it when they can
     * resolve and expose the range atomically themselves.
     *
     * @param startAbsoluteRow inclusive first requested absolute row.
     * @param endAbsoluteRow inclusive last requested absolute row.
     * @param consumer receiver that copies any render data it needs.
     */
    fun readRenderFrameForAbsoluteRange(
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        consumer: TerminalRenderFrameConsumer,
    ) {
        require(startAbsoluteRow >= 0L) { "startAbsoluteRow must be >= 0, was $startAbsoluteRow" }
        require(endAbsoluteRow >= startAbsoluteRow) {
            "endAbsoluteRow must be >= startAbsoluteRow, was start=$startAbsoluteRow end=$endAbsoluteRow"
        }

        var scrollbackOffset = 0
        var viewportRows = 1
        readRenderFrame { frame ->
            val liveTopAbsoluteRow = frame.discardedCount + frame.historySize
            val retainedLastAbsoluteRow = liveTopAbsoluteRow + frame.rows - 1L
            val resolvedStart = startAbsoluteRow.coerceIn(frame.discardedCount, retainedLastAbsoluteRow)
            val resolvedEnd = endAbsoluteRow.coerceIn(resolvedStart, retainedLastAbsoluteRow)
            scrollbackOffset = (liveTopAbsoluteRow - resolvedStart).coerceAtLeast(0L).toInt()
            val frameTopAbsoluteRow = liveTopAbsoluteRow - scrollbackOffset
            viewportRows = (resolvedEnd - frameTopAbsoluteRow + 1L).toInt()
        }
        readRenderFrame(scrollbackOffset, viewportRows, consumer)
    }
}

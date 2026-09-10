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

/**
 * Host-facing snapshot of the terminal viewport's scrollback position.
 *
 * The reusable Swing component uses terminal-native scrollback coordinates:
 * `0.0` means the live viewport, and larger values move farther back into
 * scrollback history. Hosts that use top-origin scrollbars can invert this
 * value in their adapter without changing terminal rendering policy.
 *
 * @property historySize number of rows available above the live viewport.
 * @property scrollbackOffset precise visual offset from the live viewport.
 * Fractional values exist only during smooth animation between integer row
 * destinations; completed viewports are row-aligned.
 * @property renderOffset whole-row offset requested from the render cache.
 * This is the integer overscan anchor for [scrollbackOffset].
 * @property visibleRows number of terminal rows that fit in the component.
 * @property requestedRows number of rows requested from the render cache,
 * including smooth-scroll overscan when required.
 * @property visualScrollOffsetPixels precise pixel offset from the live bottom.
 * @property visualScrollRangePixels maximum row-native pixel offset from the
 * live bottom.
 * @property viewportHeightPixels visual viewport height in pixels.
 * @property contentHeightPixels visual content height for the current render
 * cache in pixels, or zero before the bound session publishes its first frame.
 * @property cellHeightPixels fixed terminal row height in pixels.
 */
data class TerminalViewportState(
    val historySize: Int,
    val scrollbackOffset: Double,
    val renderOffset: Int,
    val visibleRows: Int,
    val requestedRows: Int,
    val visualScrollOffsetPixels: Double = scrollbackOffset,
    val visualScrollRangePixels: Int = historySize,
    val viewportHeightPixels: Int = visibleRows,
    val contentHeightPixels: Int = requestedRows,
    val cellHeightPixels: Int = 1,
) {
    init {
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        require(scrollbackOffset >= 0.0) { "scrollbackOffset must be >= 0, was $scrollbackOffset" }
        require(renderOffset >= 0) { "renderOffset must be >= 0, was $renderOffset" }
        require(visibleRows > 0) { "visibleRows must be > 0, was $visibleRows" }
        require(requestedRows > 0) { "requestedRows must be > 0, was $requestedRows" }
        require(visualScrollOffsetPixels >= 0.0) {
            "visualScrollOffsetPixels must be >= 0, was $visualScrollOffsetPixels"
        }
        require(visualScrollRangePixels >= 0) {
            "visualScrollRangePixels must be >= 0, was $visualScrollRangePixels"
        }
        require(viewportHeightPixels >= 0) { "viewportHeightPixels must be >= 0, was $viewportHeightPixels" }
        require(contentHeightPixels >= 0) { "contentHeightPixels must be >= 0, was $contentHeightPixels" }
        require(cellHeightPixels > 0) { "cellHeightPixels must be > 0, was $cellHeightPixels" }
    }

    /**
     * Whether the viewport is following live terminal output.
     */
    val isAtLiveViewport: Boolean
        get() = visualScrollOffsetPixels == 0.0
}

/**
 * Allocation-free callback for host scrollbar adapters.
 *
 * The callback is invoked on the Swing Event Dispatch Thread after the
 * component's internal viewport state changes. Implementations should keep work
 * lightweight and defer expensive host updates outside this callback.
 */
fun interface TerminalViewportListener {
    /**
     * Reports the latest terminal-native viewport coordinates.
     *
     * @param historySize rows available above the live viewport.
     * @param scrollbackOffset precise visual offset from live output;
     * fractional only while an integer-destination animation is in flight.
     * @param renderOffset whole-row render-cache offset.
     * @param visibleRows rows visible in the Swing component.
     * @param requestedRows render-cache rows requested, including overscan.
     */
    fun viewportChanged(
        historySize: Int,
        scrollbackOffset: Double,
        renderOffset: Int,
        visibleRows: Int,
        requestedRows: Int,
    )

    /**
     * Reports the full viewport snapshot, including pixel scroll range.
     *
     * Implementations that only need the historical row-based contract can keep
     * overriding [viewportChanged].
     */
    fun viewportStateChanged(state: TerminalViewportState) {
        viewportChanged(
            historySize = state.historySize,
            scrollbackOffset = state.scrollbackOffset,
            renderOffset = state.renderOffset,
            visibleRows = state.visibleRows,
            requestedRows = state.requestedRows,
        )
    }

    companion object {
        /**
         * Listener used when no host scrollbar adapter is installed.
         */
        @JvmField
        val NONE: TerminalViewportListener =
            TerminalViewportListener { _, _, _, _, _ -> }
    }
}

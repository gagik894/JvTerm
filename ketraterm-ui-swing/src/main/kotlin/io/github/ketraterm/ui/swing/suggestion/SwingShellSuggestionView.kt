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
package io.github.ketraterm.ui.swing.suggestion

import javax.swing.JComponent

/**
 * Immutable viewport published to one shell-suggestion view.
 *
 * The controller retains the complete ranking and sends each renderer only a
 * bounded window. Absolute viewport metadata lets custom-painted and native
 * list implementations expose overflow consistently without duplicating
 * controller state.
 *
 * @property visibleSuggestions ordered suggestions in the current viewport.
 * @property selectedIndex selected index relative to [visibleSuggestions], or
 * `-1` when the popup is passive or selection is outside this viewport.
 * @property viewportStartIndex absolute rank of the first visible suggestion.
 * @property totalSuggestionCount total suggestions retained by the controller.
 */
class SwingShellSuggestionViewSnapshot private constructor(
    visibleSuggestions: List<SwingShellSuggestion>,
    val selectedIndex: Int,
    val viewportStartIndex: Int,
    val totalSuggestionCount: Int,
) {
    /** Defensively copied visible suggestion window. */
    val visibleSuggestions: List<SwingShellSuggestion> = visibleSuggestions.toList()

    /** Whether ranked suggestions precede this viewport. */
    val hasSuggestionsBefore: Boolean
        get() = viewportStartIndex > 0

    /** Whether ranked suggestions follow this viewport. */
    val hasSuggestionsAfter: Boolean
        get() = viewportStartIndex + visibleSuggestions.size < totalSuggestionCount

    /** Absolute rank of the selected suggestion, or `-1` when none is selected. */
    val absoluteSelectedIndex: Int
        get() = if (selectedIndex < 0) -1 else viewportStartIndex + selectedIndex

    /** Selected visible suggestion, or `null` when the viewport is passive. */
    val selectedSuggestion: SwingShellSuggestion?
        get() = visibleSuggestions.getOrNull(selectedIndex)

    init {
        require(this.visibleSuggestions.size <= MAX_VISIBLE_SUGGESTIONS) {
            "visibleSuggestions must contain at most $MAX_VISIBLE_SUGGESTIONS items, was ${this.visibleSuggestions.size}"
        }
        require(selectedIndex == NO_SELECTION || selectedIndex in this.visibleSuggestions.indices) {
            "selectedIndex must be -1 or address visibleSuggestions, was $selectedIndex"
        }
        require(viewportStartIndex >= 0) { "viewportStartIndex must be >= 0, was $viewportStartIndex" }
        require(totalSuggestionCount >= 0) {
            "totalSuggestionCount must be >= 0, was $totalSuggestionCount"
        }
        require(viewportStartIndex + this.visibleSuggestions.size <= totalSuggestionCount) {
            "visible viewport [$viewportStartIndex, ${viewportStartIndex + this.visibleSuggestions.size}) " +
                "exceeds totalSuggestionCount $totalSuggestionCount"
        }
        if (this.visibleSuggestions.isEmpty()) {
            require(viewportStartIndex == 0 && totalSuggestionCount == 0) {
                "an empty viewport must use zero start and total counts"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SwingShellSuggestionViewSnapshot &&
            visibleSuggestions == other.visibleSuggestions &&
            selectedIndex == other.selectedIndex &&
            viewportStartIndex == other.viewportStartIndex &&
            totalSuggestionCount == other.totalSuggestionCount

    override fun hashCode(): Int {
        var result = visibleSuggestions.hashCode()
        result = 31 * result + selectedIndex
        result = 31 * result + viewportStartIndex
        result = 31 * result + totalSuggestionCount
        return result
    }

    override fun toString(): String =
        "SwingShellSuggestionViewSnapshot(" +
            "visibleSuggestions=$visibleSuggestions, " +
            "selectedIndex=$selectedIndex, " +
            "viewportStartIndex=$viewportStartIndex, " +
            "totalSuggestionCount=$totalSuggestionCount)"

    companion object {
        /** Maximum number of completion rows rendered at once by any host view. */
        const val MAX_VISIBLE_SUGGESTIONS: Int = 8

        /** Shared empty hidden-view snapshot. */
        @JvmField
        val EMPTY: SwingShellSuggestionViewSnapshot =
            SwingShellSuggestionViewSnapshot(
                visibleSuggestions = emptyList(),
                selectedIndex = NO_SELECTION,
                viewportStartIndex = 0,
                totalSuggestionCount = 0,
            )

        /**
         * Creates a validated immutable suggestion viewport.
         *
         * @param visibleSuggestions bounded ordered suggestion window.
         * @param selectedIndex selected local index, or `-1`.
         * @param viewportStartIndex absolute rank of the first visible item.
         * @param totalSuggestionCount total retained ranked suggestions.
         * @return a defensive immutable viewport snapshot.
         */
        @JvmStatic
        fun create(
            visibleSuggestions: List<SwingShellSuggestion>,
            selectedIndex: Int,
            viewportStartIndex: Int,
            totalSuggestionCount: Int,
        ): SwingShellSuggestionViewSnapshot =
            SwingShellSuggestionViewSnapshot(
                visibleSuggestions = visibleSuggestions,
                selectedIndex = selectedIndex,
                viewportStartIndex = viewportStartIndex,
                totalSuggestionCount = totalSuggestionCount,
            )

        private const val NO_SELECTION = -1
    }
}

/**
 * Host-pluggable visual surface for shell suggestions.
 *
 * The reusable suggestion controller owns navigation, acceptance, dismissal,
 * feedback, and popup state. A view only renders the supplied immutable items
 * and reports pointer interaction through the listener used to create it.
 * Implementations must update Swing component state only on the Event Dispatch
 * Thread and must not invoke completion providers or mutate command lines.
 */
interface SwingShellSuggestionView {
    /**
     * Component embedded and positioned by the owning Swing terminal.
     */
    val component: JComponent

    /**
     * Replaces the complete immutable visual viewport on the Swing Event
     * Dispatch Thread.
     *
     * Implementations may retain [snapshot] because it owns a defensive copy
     * of its visible suggestions. They must not reinterpret provider ids or
     * candidate kinds; all renderer semantics are already resolved.
     *
     * @param snapshot authoritative bounded presentation state.
     */
    fun update(snapshot: SwingShellSuggestionViewSnapshot)

    /**
     * Releases host-specific presentation resources.
     *
     * The default implementation is resource-free. Hosts that own platform
     * editors, popups, listeners, or disposable scopes must release them here.
     */
    fun close() = Unit
}

/**
 * Pointer-interaction callback used by a [SwingShellSuggestionView].
 *
 * Selection and acceptance remain controller semantics; the view reports only
 * the item index under the relevant pointer gesture.
 */
interface SwingShellSuggestionViewListener {
    /**
     * Reports that the pointer moved over an item.
     *
     * @param index zero-based index in the current visual snapshot.
     */
    fun onSuggestionHovered(index: Int)

    /**
     * Reports an explicit primary-button click on an item.
     *
     * @param index zero-based index in the current visual snapshot.
     */
    fun onSuggestionClicked(index: Int)

    /**
     * Requests relative keyboard-style navigation after a pointer-wheel gesture.
     *
     * @param delta negative for earlier suggestions and positive for later
     * suggestions. A zero delta is ignored.
     */
    fun onSuggestionScrollRequested(delta: Int) = Unit
}

/**
 * Creates one suggestion view for a reusable Swing terminal instance.
 *
 * Standalone hosts normally use [DEFAULT]. Platform hosts can supply a native
 * Swing implementation without replacing completion behavior.
 */
fun interface SwingShellSuggestionViewFactory {
    /**
     * Creates a view that reports pointer interaction to [listener].
     *
     * @param listener controller-owned interaction callback.
     * @return a new view owned by one terminal component.
     */
    fun create(listener: SwingShellSuggestionViewListener): SwingShellSuggestionView

    companion object {
        /**
         * Factory for KetraTerm's adaptive standalone suggestion surface.
         */
        @JvmField
        val DEFAULT: SwingShellSuggestionViewFactory =
            SwingShellSuggestionViewFactory { listener -> SwingCompletionPopupView(listener) }
    }
}

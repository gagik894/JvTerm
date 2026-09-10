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

import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.event.KeyEvent

internal class SwingShellSuggestionController(
    private val host: SwingShellSuggestionHost,
    viewFactory: SwingShellSuggestionViewFactory = SwingShellSuggestionViewFactory.DEFAULT,
) {
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private var viewportStartIndex: Int = 0
    private var request: SwingShellSuggestionRequest = SwingShellSuggestionRequest.EMPTY

    private val view: SwingShellSuggestionView =
        viewFactory.create(
            object : SwingShellSuggestionViewListener {
                override fun onSuggestionHovered(index: Int) {
                    select(viewportStartIndex + index)
                }

                override fun onSuggestionClicked(index: Int) {
                    select(viewportStartIndex + index)
                    acceptSelected()
                }

                override fun onSuggestionScrollRequested(delta: Int) {
                    scrollRelative(delta)
                }
            },
        )

    val popup get() = view.component

    fun show(
        request: SwingShellSuggestionRequest,
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
    ): Boolean = showInternal(request, suggestions, selectedIndex, preserveSelectedOutcome = false)

    fun showPreservingSelectedOutcome(
        request: SwingShellSuggestionRequest,
        suggestions: List<SwingShellSuggestion>,
        fallbackSelectedIndex: Int = NO_SELECTION,
    ): Boolean = showInternal(request, suggestions, fallbackSelectedIndex, preserveSelectedOutcome = true)

    private fun showInternal(
        request: SwingShellSuggestionRequest,
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
        preserveSelectedOutcome: Boolean,
    ): Boolean {
        if (!host.settings.smartSuggestionsEnabled || suggestions.isEmpty()) {
            hide()
            return false
        }
        val sameRequest = this.request == request
        val selectedOutcome =
            this.suggestions
                .getOrNull(this.selectedIndex)
                ?.outcomeKey()
                ?.takeIf { preserveSelectedOutcome && sameRequest }
        this.suggestions = suggestions.toList()
        this.request = request
        if (!sameRequest) viewportStartIndex = 0
        this.selectedIndex =
            selectedOutcome
                ?.let { outcome -> this.suggestions.indexOfFirst { it.outcomeKey() == outcome } }
                ?.takeIf { it >= 0 }
                ?: selectedIndex.takeIf { it in this.suggestions.indices }
                ?: NO_SELECTION
        updateViewport()
        view.component.isVisible = true
        host.revalidate()
        host.repaint()
        return true
    }

    fun hide(): Boolean {
        if (!view.component.isVisible && suggestions.isEmpty()) return false
        suggestions = emptyList()
        selectedIndex = NO_SELECTION
        viewportStartIndex = 0
        request = SwingShellSuggestionRequest.EMPTY
        view.update(SwingShellSuggestionViewSnapshot.EMPTY)
        view.component.isVisible = false
        host.revalidate()
        host.repaint()
        return true
    }

    fun close() {
        hide()
        view.close()
    }

    fun handleKeyPressed(event: KeyEvent): Boolean {
        if (!host.settings.smartSuggestionsEnabled || !view.component.isVisible || suggestions.isEmpty()) return false
        val action = host.suggestionKeymap.actionFor(event) ?: return false
        val enterAcceptanceDisabled =
            action == SwingShellSuggestionAction.ACCEPT_SELECTED &&
                event.keyCode == KeyEvent.VK_ENTER &&
                !host.settings.acceptSelectedSuggestionWithEnter
        val handled = !enterAcceptanceDisabled && handleAction(action)
        if (handled) event.consume()
        return handled
    }

    private fun handleAction(action: SwingShellSuggestionAction): Boolean =
        when (action) {
            SwingShellSuggestionAction.SELECT_NEXT -> selectAdjacent(1)
            SwingShellSuggestionAction.SELECT_PREVIOUS -> selectAdjacent(-1)
            SwingShellSuggestionAction.SELECT_FIRST -> select(0)
            SwingShellSuggestionAction.SELECT_LAST -> select(suggestions.lastIndex)
            SwingShellSuggestionAction.SELECT_NEXT_PAGE -> selectRelative(SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS)
            SwingShellSuggestionAction.SELECT_PREVIOUS_PAGE ->
                selectRelative(-SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS)
            SwingShellSuggestionAction.ACCEPT -> selectFirstOrAccept()
            SwingShellSuggestionAction.ACCEPT_SELECTED -> acceptSelected()
            SwingShellSuggestionAction.DISMISS -> dismissSelected()
        }

    fun state(): SwingShellSuggestionState =
        if (!view.component.isVisible || suggestions.isEmpty()) {
            SwingShellSuggestionState.EMPTY
        } else {
            SwingShellSuggestionState(
                visible = true,
                count = suggestions.size,
                selectedIndex = selectedIndex,
                anchorColumn = request.anchorColumn,
                anchorRow = request.anchorRow,
                selectedSuggestion = suggestions.getOrNull(selectedIndex),
            )
        }

    private fun selectAdjacent(delta: Int): Boolean {
        if (suggestions.isEmpty()) return false
        val next =
            when {
                selectedIndex !in suggestions.indices -> if (delta > 0) 0 else suggestions.lastIndex
                delta > 0 && selectedIndex == suggestions.lastIndex -> 0
                delta < 0 && selectedIndex == 0 -> suggestions.lastIndex
                else -> selectedIndex + delta
            }
        return select(next)
    }

    private fun selectRelative(delta: Int): Boolean {
        if (suggestions.isEmpty()) return false
        val current =
            when {
                selectedIndex in suggestions.indices -> selectedIndex
                delta > 0 -> -1
                else -> suggestions.size
            }
        val next = (current + delta).coerceIn(0, suggestions.lastIndex)
        return select(next)
    }

    private fun scrollRelative(delta: Int): Boolean {
        if (suggestions.isEmpty() || delta == 0) return false
        val boundedDelta = delta.coerceIn(-MAX_POINTER_SCROLL_DELTA, MAX_POINTER_SCROLL_DELTA)
        val current =
            when {
                selectedIndex in suggestions.indices -> selectedIndex
                boundedDelta > 0 -> viewportStartIndex - 1
                else -> viewportStartIndex
            }
        return select((current + boundedDelta).coerceIn(0, suggestions.lastIndex))
    }

    private fun selectFirstOrAccept(): Boolean =
        if (selectedIndex in suggestions.indices) {
            acceptSelected()
        } else if (suggestions.size == 1) {
            select(0)
            acceptSelected()
        } else {
            select(0)
        }

    private fun select(index: Int): Boolean {
        if (index !in suggestions.indices) return false
        if (selectedIndex == index) return true
        selectedIndex = index
        updateViewport()
        return true
    }

    private fun acceptSelected(): Boolean {
        if (!host.settings.smartSuggestionsEnabled || selectedIndex !in suggestions.indices) return false
        val suggestion = suggestions[selectedIndex]
        val index = selectedIndex
        val acceptedRequest = request
        hide()
        host.invalidateSuggestions()
        host.suggestionHandler.onSuggestionAccepted(
            SwingShellSuggestionAcceptance(
                suggestion = suggestion,
                index = index,
                request = acceptedRequest,
            ),
        )
        host.suggestionFeedbackHandler.onSuggestionFeedback(
            SwingShellSuggestionFeedback(
                kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                suggestion = suggestion,
                index = index,
                request = acceptedRequest,
            ),
        )
        host.requestFocusInWindow()
        return true
    }

    private fun dismissSelected(): Boolean {
        if (selectedIndex !in suggestions.indices) return hide()
        val suggestion = suggestions[selectedIndex]
        val index = selectedIndex
        val dismissedRequest = request
        hide()
        host.invalidateSuggestions()
        host.suggestionFeedbackHandler.onSuggestionFeedback(
            SwingShellSuggestionFeedback(
                kind = SwingShellSuggestionFeedbackKind.DISMISSED,
                suggestion = suggestion,
                index = index,
                request = dismissedRequest,
            ),
        )
        host.requestFocusInWindow()
        return true
    }

    private fun updateViewport() {
        viewportStartIndex =
            when {
                suggestions.size <= SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS -> 0
                selectedIndex < 0 ->
                    viewportStartIndex.coerceIn(
                        0,
                        suggestions.size - SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS,
                    )
                selectedIndex < viewportStartIndex -> selectedIndex
                selectedIndex >= viewportStartIndex + SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS ->
                    selectedIndex - SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS + 1
                else -> viewportStartIndex
            }
        val viewportEnd =
            minOf(
                suggestions.size,
                viewportStartIndex + SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS,
            )
        val visible = suggestions.subList(viewportStartIndex, viewportEnd)
        val localSelection =
            selectedIndex
                .takeIf { it in viewportStartIndex until viewportEnd }
                ?.minus(viewportStartIndex)
                ?: NO_SELECTION
        view.update(
            SwingShellSuggestionViewSnapshot.create(
                visibleSuggestions = visible,
                selectedIndex = localSelection,
                viewportStartIndex = viewportStartIndex,
                totalSuggestionCount = suggestions.size,
            ),
        )
    }

    private fun SwingShellSuggestion.outcomeKey(): SuggestionOutcomeKey =
        SuggestionOutcomeKey(replacementStartOffset, replacementEndOffset, replacementText)

    private companion object {
        private const val NO_SELECTION = -1
        private const val MAX_POINTER_SCROLL_DELTA = 3
    }

    private data class SuggestionOutcomeKey(
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
        val replacementText: String,
    )
}

internal interface SwingShellSuggestionHost {
    val settings: SwingSettings
    val suggestionKeymap: SwingShellSuggestionKeymap
    val suggestionHandler: SwingShellSuggestionHandler
    val suggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler

    fun revalidate()

    fun repaint()

    fun requestFocusInWindow(): Boolean

    fun invalidateSuggestions()
}

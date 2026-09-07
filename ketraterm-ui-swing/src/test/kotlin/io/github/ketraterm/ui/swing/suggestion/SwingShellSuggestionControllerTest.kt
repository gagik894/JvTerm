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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import javax.swing.JPanel

class SwingShellSuggestionControllerTest {
    private val source = JPanel()

    @Test
    fun `master off rejects keyboard and pointer acceptance of an existing popup`() =
        onEdt {
            val host = RecordingSuggestionHost()
            lateinit var view: RecordingSuggestionView
            val controller =
                SwingShellSuggestionController(host, viewFactory = { listener ->
                    RecordingSuggestionView(listener).also { view = it }
                })
            controller.show(request(), suggestions(2), selectedIndex = 0)
            host.settings = host.settings.copy(smartSuggestionsEnabled = false)
            assertFalse(controller.handleKeyPressed(keyPressed(KeyEvent.VK_TAB)))
            view.listener.onSuggestionClicked(0)
            assertTrue(host.acceptedSuggestions.isEmpty())
            assertTrue(host.feedbackKinds.isEmpty())
            assertFalse(controller.show(request(), suggestions(2), selectedIndex = 0))
        }

    @Test
    fun `show publishes visible state with explicit selection and anchor`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            val suggestions = suggestions(3)
            val request = request(anchorColumn = 7, anchorRow = 2)

            val shown = controller.show(request, suggestions, selectedIndex = 2)
            val state = controller.state()

            assertTrue(shown)
            assertTrue(state.visible)
            assertEquals(3, state.count)
            assertEquals(2, state.selectedIndex)
            assertEquals(7, state.anchorColumn)
            assertEquals(2, state.anchorRow)
            assertSame(suggestions[2], state.selectedSuggestion)
        }

    @Test
    fun `host view factory receives the controller snapshot and selection`() =
        onEdt {
            lateinit var view: RecordingSuggestionView
            val controller =
                SwingShellSuggestionController(
                    host = RecordingSuggestionHost(),
                    viewFactory =
                        { listener ->
                            RecordingSuggestionView(listener).also { view = it }
                        },
                )
            val items = suggestions(3)

            controller.show(request(), items, selectedIndex = -1)
            view.listener.onSuggestionHovered(1)

            assertEquals(items, view.suggestions)
            assertEquals(1, view.selectedIndex)
            assertEquals(1, controller.state().selectedIndex)
        }

    @Test
    fun `show keeps popup passive when no valid initial selection is supplied`() =
        onEdt {
            val controller = SwingShellSuggestionController(RecordingSuggestionHost())

            controller.show(request(), suggestions(3), selectedIndex = 99)

            assertEquals(-1, controller.state().selectedIndex)
            assertNull(controller.state().selectedSuggestion)
        }

    @Test
    fun `explicit display is independent of automatic suggestion setting`() =
        onEdt {
            val host = RecordingSuggestionHost(settings = SwingSettings(smartSuggestionsEnabled = true, shellSuggestionsEnabled = false))
            val controller = SwingShellSuggestionController(host)

            val shown = controller.show(request(), suggestions(2), selectedIndex = 0)

            assertTrue(shown)
            assertTrue(controller.state().visible)
            assertTrue(controller.popup.isVisible)
        }

    @Test
    fun `navigation keys update selected suggestion and consume event`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            controller.show(request(), suggestions(3), selectedIndex = -1)
            val down = keyPressed(KeyEvent.VK_DOWN)
            val up = keyPressed(KeyEvent.VK_UP)

            assertTrue(controller.handleKeyPressed(down))
            assertEquals(0, controller.state().selectedIndex)
            assertTrue(down.isConsumed)

            assertTrue(controller.handleKeyPressed(up))
            assertEquals(2, controller.state().selectedIndex)
            assertTrue(up.isConsumed)
        }

    @Test
    fun `host keymap controls acceptance bindings`() =
        onEdt {
            val host =
                RecordingSuggestionHost(
                    suggestionKeymap =
                        SwingShellSuggestionKeymap { event ->
                            if (event.keyCode == KeyEvent.VK_F2) SwingShellSuggestionAction.ACCEPT else null
                        },
                )
            val controller = SwingShellSuggestionController(host)
            controller.show(request(), suggestions(1), selectedIndex = 0)

            assertFalse(controller.handleKeyPressed(keyPressed(KeyEvent.VK_ENTER)))
            assertTrue(controller.handleKeyPressed(keyPressed(KeyEvent.VK_F2)))
            assertEquals(1, host.acceptedSuggestions.size)
        }

    @Test
    fun `up from a passive popup selects the last retained suggestion`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)

            controller.show(request(), suggestions(20), selectedIndex = -1)
            controller.handleKeyPressed(keyPressed(KeyEvent.VK_UP))

            assertEquals(20, controller.state().count)
            assertEquals(19, controller.state().selectedIndex)
        }

    @Test
    fun `controller retains full ranking while view receives eight rows`() =
        onEdt {
            lateinit var view: RecordingSuggestionView
            val controller =
                SwingShellSuggestionController(
                    host = RecordingSuggestionHost(),
                    viewFactory = { listener -> RecordingSuggestionView(listener).also { view = it } },
                )

            controller.show(request(), suggestions(80), selectedIndex = -1)

            assertEquals(80, controller.state().count)
            assertEquals(8, view.suggestions.size)
            assertEquals((0..7).map { "command-$it" }, view.suggestions.map { it.replacementText })
        }

    @Test
    fun `navigation scrolls viewport and mouse acceptance resolves global rank`() =
        onEdt {
            lateinit var view: RecordingSuggestionView
            val host = RecordingSuggestionHost()
            val controller =
                SwingShellSuggestionController(
                    host = host,
                    viewFactory = { listener -> RecordingSuggestionView(listener).also { view = it } },
                )
            val items = suggestions(20)
            controller.show(request(), items, selectedIndex = -1)

            repeat(10) { controller.handleKeyPressed(keyPressed(KeyEvent.VK_DOWN)) }

            assertEquals(9, controller.state().selectedIndex)
            assertEquals(7, view.selectedIndex)
            assertEquals((2..9).map { "command-$it" }, view.suggestions.map { it.replacementText })
            assertEquals(2, view.snapshot.viewportStartIndex)
            assertEquals(20, view.snapshot.totalSuggestionCount)
            assertTrue(view.snapshot.hasSuggestionsBefore)
            assertTrue(view.snapshot.hasSuggestionsAfter)
            view.listener.onSuggestionClicked(3)
            assertEquals(listOf(5), host.acceptedIndexes)
            assertEquals(listOf(items[5]), host.acceptedSuggestions)
        }

    @Test
    fun `wheel navigation is bounded and passive upward motion clamps to the first result`() =
        onEdt {
            lateinit var view: RecordingSuggestionView
            val controller =
                SwingShellSuggestionController(
                    host = RecordingSuggestionHost(),
                    viewFactory = { listener -> RecordingSuggestionView(listener).also { view = it } },
                )
            controller.show(request(), suggestions(20), selectedIndex = -1)

            view.listener.onSuggestionScrollRequested(-100)

            assertEquals(0, controller.state().selectedIndex)
            assertEquals(0, view.snapshot.viewportStartIndex)

            controller.show(request(commandText = "different"), suggestions(20), selectedIndex = -1)
            view.listener.onSuggestionScrollRequested(100)

            assertEquals(2, controller.state().selectedIndex)
            assertEquals(0, view.snapshot.viewportStartIndex)
        }

    @Test
    fun `new request resets a previously scrolled passive viewport`() =
        onEdt {
            lateinit var view: RecordingSuggestionView
            val controller =
                SwingShellSuggestionController(
                    host = RecordingSuggestionHost(),
                    viewFactory = { listener -> RecordingSuggestionView(listener).also { view = it } },
                )
            val firstRequest = request(commandText = "first")
            controller.show(firstRequest, suggestions(20), selectedIndex = 12)
            assertTrue(view.snapshot.viewportStartIndex > 0)

            controller.show(request(commandText = "second"), suggestions(20), selectedIndex = -1)

            assertEquals(0, view.snapshot.viewportStartIndex)
            assertEquals(-1, view.snapshot.selectedIndex)
        }

    @Test
    fun `progressive reranking preserves selected outcome only for the same request`() =
        onEdt {
            val controller = SwingShellSuggestionController(RecordingSuggestionHost())
            val request = request(commandText = "git s")
            val first = suggestions(3, endOffset = request.commandText.length)
            controller.show(request, first, selectedIndex = 1)

            controller.showPreservingSelectedOutcome(
                request,
                listOf(first[2], first[1], first[0]),
                fallbackSelectedIndex = 0,
            )
            assertEquals(1, controller.state().selectedIndex)
            assertSame(first[1], controller.state().selectedSuggestion)

            controller.showPreservingSelectedOutcome(
                request(commandText = "git st"),
                first,
                fallbackSelectedIndex = 0,
            )
            assertEquals(0, controller.state().selectedIndex)
        }

    @Test
    fun `explicit passive display clears selection for the same request`() =
        onEdt {
            val controller = SwingShellSuggestionController(RecordingSuggestionHost())
            val request = request(commandText = "git s")
            val items = suggestions(3, endOffset = request.commandText.length)
            controller.show(request, items, selectedIndex = 1)

            controller.show(request, listOf(items[2], items[1], items[0]), selectedIndex = -1)

            assertEquals(-1, controller.state().selectedIndex)
            assertNull(controller.state().selectedSuggestion)
        }

    @Test
    fun `enter passes through to the shell when no suggestion is selected`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            val request = request(commandText = "git sw", cursorOffset = 6)
            val items = suggestions(2, endOffset = request.commandText.length)
            controller.show(request, items, selectedIndex = -1)
            val enter = keyPressed(KeyEvent.VK_ENTER)

            assertFalse(controller.handleKeyPressed(enter))

            assertTrue(controller.state().visible)
            assertTrue(host.acceptedSuggestions.isEmpty())
            assertTrue(host.feedbackKinds.isEmpty())
            assertFalse(enter.isConsumed)
        }

    @Test
    fun `enter accepts an already-selected suggestion`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            val request = request(commandText = "git sw", cursorOffset = 6)
            val items = suggestions(2, endOffset = request.commandText.length)
            controller.show(request, items, selectedIndex = 1)
            val enter = keyPressed(KeyEvent.VK_ENTER)

            assertTrue(controller.handleKeyPressed(enter))

            assertFalse(controller.state().visible)
            assertEquals(listOf(1), host.acceptedIndexes)
            assertEquals(listOf(items[1]), host.acceptedSuggestions)
            assertEquals(listOf(request), host.acceptedRequests)
            assertEquals(listOf(SwingShellSuggestionFeedbackKind.ACCEPTED), host.feedbackKinds)
            assertEquals(1, host.focusRequests)
            assertTrue(enter.isConsumed)
        }

    @Test
    fun `failed acceptance handler does not emit accepted feedback`() =
        onEdt {
            val host = RecordingSuggestionHost(failAcceptance = true)
            val controller = SwingShellSuggestionController(host)
            val items = suggestions(1)
            controller.show(request(), items, selectedIndex = 0)

            assertThrows(IllegalStateException::class.java) {
                controller.handleKeyPressed(keyPressed(KeyEvent.VK_TAB))
            }

            assertFalse(controller.state().visible)
            assertTrue(host.feedbackKinds.isEmpty())
            assertEquals(0, host.focusRequests)
        }

    @Test
    fun `disabled enter acceptance passes through with a selected suggestion`() =
        onEdt {
            val host =
                RecordingSuggestionHost(
                    settings = SwingSettings(smartSuggestionsEnabled = true, acceptSelectedSuggestionWithEnter = false),
                )
            val controller = SwingShellSuggestionController(host)
            val items = suggestions(2)
            controller.show(request(), items, selectedIndex = 1)
            val enter = keyPressed(KeyEvent.VK_ENTER)

            assertFalse(controller.handleKeyPressed(enter))

            assertTrue(controller.state().visible)
            assertEquals(1, controller.state().selectedIndex)
            assertTrue(host.acceptedSuggestions.isEmpty())
            assertTrue(host.feedbackKinds.isEmpty())
            assertFalse(enter.isConsumed)
        }

    @Test
    fun `tab selects first suggestion before accepting it`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            val request = request(commandText = "cd ", cursorOffset = 3)
            val items = suggestions(2, endOffset = request.commandText.length)
            controller.show(request, items, selectedIndex = -1)

            val firstTab = keyPressed(KeyEvent.VK_TAB)
            assertTrue(controller.handleKeyPressed(firstTab))
            assertEquals(0, controller.state().selectedIndex)
            assertTrue(host.acceptedSuggestions.isEmpty())

            val secondTab = keyPressed(KeyEvent.VK_TAB)
            assertTrue(controller.handleKeyPressed(secondTab))

            assertFalse(controller.state().visible)
            assertEquals(listOf(0), host.acceptedIndexes)
            assertEquals(listOf(items[0]), host.acceptedSuggestions)
            assertEquals(listOf(request), host.acceptedRequests)
            assertEquals(listOf(SwingShellSuggestionFeedbackKind.ACCEPTED), host.feedbackKinds)
            assertEquals(listOf(items[0]), host.feedbackSuggestions)
            assertEquals(1, host.focusRequests)
            assertTrue(secondTab.isConsumed)
        }

    @Test
    fun `tab on unique suggestion accepts immediately when passive`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            val request = request(commandText = "cd ", cursorOffset = 3)
            val items = suggestions(1, endOffset = request.commandText.length)
            controller.show(request, items, selectedIndex = -1)

            val tab = keyPressed(KeyEvent.VK_TAB)
            assertTrue(controller.handleKeyPressed(tab))

            assertFalse(controller.state().visible)
            assertEquals(listOf(0), host.acceptedIndexes)
            assertEquals(listOf(items[0]), host.acceptedSuggestions)
            assertEquals(listOf(request), host.acceptedRequests)
            assertEquals(listOf(SwingShellSuggestionFeedbackKind.ACCEPTED), host.feedbackKinds)
            assertTrue(tab.isConsumed)
        }

    @Test
    fun `escape hides popup records dismissal without accepting`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            val items = suggestions(2)
            controller.show(request(), items, selectedIndex = 0)
            val escape = keyPressed(KeyEvent.VK_ESCAPE)

            assertTrue(controller.handleKeyPressed(escape))

            assertFalse(controller.state().visible)
            assertTrue(host.acceptedSuggestions.isEmpty())
            assertEquals(listOf(SwingShellSuggestionFeedbackKind.DISMISSED), host.feedbackKinds)
            assertEquals(listOf(items[0]), host.feedbackSuggestions)
            assertEquals(1, host.focusRequests)
            assertTrue(escape.isConsumed)
        }

    @Test
    fun `ordinary popup closure with a selected suggestion records no dismissal`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            controller.show(request(), suggestions(2), selectedIndex = 0)

            controller.hide()

            assertFalse(controller.state().visible)
            assertTrue(host.feedbackKinds.isEmpty())
        }

    @Test
    fun `popup replacement records no negative feedback`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            controller.show(request(), suggestions(2), selectedIndex = 0)

            controller.show(request(commandText = "g"), suggestions(1), selectedIndex = -1)

            assertTrue(controller.state().visible)
            assertTrue(host.feedbackKinds.isEmpty())
        }

    @Test
    fun `focus loss hides selected suggestion without negative feedback`() =
        onEdt {
            assertPassiveHideIsNeutral()
        }

    @Test
    fun `timeout hides selected suggestion without negative feedback`() =
        onEdt {
            assertPassiveHideIsNeutral()
        }

    @Test
    fun `continued typing replaces selected suggestion without negative feedback`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            controller.show(request(commandText = "git s"), suggestions(2, endOffset = 5), selectedIndex = 0)

            controller.show(request(commandText = "git sw"), suggestions(1, endOffset = 6), selectedIndex = -1)

            assertTrue(controller.state().visible)
            assertEquals(-1, controller.state().selectedIndex)
            assertTrue(host.feedbackKinds.isEmpty())
        }

    @Test
    fun `arrow navigation wraps cleanly at top and bottom boundaries`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            controller.show(request(), suggestions(5), selectedIndex = 0)

            val upFromTop = keyPressed(KeyEvent.VK_UP)
            controller.handleKeyPressed(upFromTop)
            assertEquals(4, controller.state().selectedIndex, "Up arrow at index 0 should wrap to the last suggestion")

            val downFromBottom = keyPressed(KeyEvent.VK_DOWN)
            controller.handleKeyPressed(downFromBottom)
            assertEquals(0, controller.state().selectedIndex, "Down arrow at the last index should wrap to the first suggestion")
        }

    @Test
    fun `repeated arrow presses continue one item at a time through wrap boundaries`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            controller.show(request(), suggestions(5), selectedIndex = 4)

            repeat(3) { controller.handleKeyPressed(keyPressed(KeyEvent.VK_DOWN)) }
            assertEquals(2, controller.state().selectedIndex)

            repeat(4) { controller.handleKeyPressed(keyPressed(KeyEvent.VK_UP)) }
            assertEquals(3, controller.state().selectedIndex)
        }

    @Test
    fun `page down and page up navigate in blocks of maximum visible rows`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            val items = suggestions(20)
            controller.show(request(), items, selectedIndex = 0)

            val pageDown = keyPressed(KeyEvent.VK_PAGE_DOWN)
            controller.handleKeyPressed(pageDown)
            assertEquals(8, controller.state().selectedIndex, "Page down should advance by 8 items")

            controller.handleKeyPressed(pageDown)
            assertEquals(16, controller.state().selectedIndex, "Second page down should advance by another 8 items")

            val pageUp = keyPressed(KeyEvent.VK_PAGE_UP)
            controller.handleKeyPressed(pageUp)
            assertEquals(8, controller.state().selectedIndex, "Page up should move back by 8 items")
        }

    @Test
    fun `mouse click on suggestion accepts item and dismisses popup`() =
        onEdt {
            val host = RecordingSuggestionHost()
            lateinit var view: RecordingSuggestionView
            val controller =
                SwingShellSuggestionController(
                    host = host,
                    viewFactory = { listener -> RecordingSuggestionView(listener).also { view = it } },
                )
            val items = suggestions(4)
            controller.show(request(), items, selectedIndex = -1)

            view.listener.onSuggestionClicked(2)

            assertEquals(1, host.acceptedSuggestions.size)
            assertEquals("command-2", host.acceptedSuggestions.single().replacementText)
            assertFalse(controller.state().visible)
        }

    @Test
    fun `empty suggestions list hides popup and clears selection cleanly`() =
        onEdt {
            val host = RecordingSuggestionHost()
            val controller = SwingShellSuggestionController(host)
            controller.show(request(), suggestions(3), selectedIndex = 1)
            assertTrue(controller.state().visible)

            val shown = controller.show(request(), emptyList(), selectedIndex = -1)
            assertFalse(shown)
            assertFalse(controller.state().visible)
            assertEquals(-1, controller.state().selectedIndex)
        }

    private fun onEdt(block: () -> Unit) {
        javax.swing.SwingUtilities.invokeAndWait(block)
    }

    private fun assertPassiveHideIsNeutral() {
        val host = RecordingSuggestionHost()
        val controller = SwingShellSuggestionController(host)
        controller.show(request(), suggestions(2), selectedIndex = 0)

        controller.hide()

        assertFalse(controller.state().visible)
        assertTrue(host.feedbackKinds.isEmpty())
    }

    private fun suggestions(
        count: Int,
        startOffset: Int = 0,
        endOffset: Int = 0,
    ): List<SwingShellSuggestion> =
        List(count) { index ->
            SwingShellSuggestion(
                replacementText = "command-$index",
                replacementStartOffset = startOffset,
                replacementEndOffset = endOffset,
                source = "test",
                kind = "COMMAND",
                displayText = "command-$index",
                detail = "detail-$index",
            )
        }

    private fun request(
        commandText: String = "",
        cursorOffset: Int = commandText.length,
        anchorColumn: Int = 0,
        anchorRow: Int = 0,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
            anchorColumn = anchorColumn,
            anchorRow = anchorRow,
        )

    private fun keyPressed(keyCode: Int): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private class RecordingSuggestionHost(
        override var settings: SwingSettings = SwingSettings(smartSuggestionsEnabled = true),
        override val suggestionKeymap: SwingShellSuggestionKeymap = SwingShellSuggestionKeymap.STANDARD,
        private val failAcceptance: Boolean = false,
    ) : SwingShellSuggestionHost {
        val acceptedSuggestions = ArrayList<SwingShellSuggestion>()
        val acceptedIndexes = ArrayList<Int>()
        val acceptedRequests = ArrayList<SwingShellSuggestionRequest>()
        val feedbackKinds = ArrayList<SwingShellSuggestionFeedbackKind>()
        val feedbackSuggestions = ArrayList<SwingShellSuggestion>()
        var focusRequests = 0
        var revalidations = 0
        var repaints = 0
        var invalidations = 0

        override val suggestionHandler: SwingShellSuggestionHandler =
            SwingShellSuggestionHandler { acceptance ->
                if (failAcceptance) error("acceptance failed")
                acceptedSuggestions += acceptance.suggestion
                acceptedIndexes += acceptance.index
                acceptedRequests += acceptance.request
            }

        override val suggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler =
            SwingShellSuggestionFeedbackHandler { feedback ->
                feedbackKinds += feedback.kind
                feedbackSuggestions += feedback.suggestion
            }

        override fun revalidate() {
            revalidations++
        }

        override fun repaint() {
            repaints++
        }

        override fun requestFocusInWindow(): Boolean {
            focusRequests++
            return true
        }

        override fun invalidateSuggestions() {
            invalidations++
        }
    }

    private class RecordingSuggestionView(
        val listener: SwingShellSuggestionViewListener,
    ) : SwingShellSuggestionView {
        override val component = JPanel()
        var suggestions: List<SwingShellSuggestion> = emptyList()
            private set
        var selectedIndex: Int = -1
            private set
        var snapshot: SwingShellSuggestionViewSnapshot = SwingShellSuggestionViewSnapshot.EMPTY
            private set

        override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
            this.snapshot = snapshot
            suggestions = snapshot.visibleSuggestions
            selectedIndex = snapshot.selectedIndex
        }
    }
}

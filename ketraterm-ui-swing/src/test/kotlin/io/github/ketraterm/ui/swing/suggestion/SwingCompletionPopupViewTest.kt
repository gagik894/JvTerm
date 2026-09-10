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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleSelection
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SwingCompletionPopupViewTest {
    @Test
    fun `view exposes list semantics and selected item to accessibility`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val suggestions = List(3) { index -> suggestion("candidate-$index", detail = "detail-$index") }
            view.update(snapshot(suggestions, selectedIndex = 1, total = 12, start = 4))

            val context = view.list.accessibleContext
            assertEquals(AccessibleRole.LIST, context.accessibleRole)
            assertEquals("Command completions", context.accessibleName)
            assertEquals(3, context.accessibleChildrenCount)
            assertTrue(context.accessibleDescription.contains("12 suggestions"))
            val selection = context.accessibleSelection as AccessibleSelection
            assertEquals(1, selection.accessibleSelectionCount)
            assertEquals("candidate-1", selection.getAccessibleSelection(0).accessibleContext.accessibleName)
            assertEquals("candidate-1", context.getAccessibleChild(1).accessibleContext.accessibleName)
        }

    @Test
    fun `clipped popup paints selected tail row and maps pointer to snapshot index`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val suggestions = List(8) { index -> suggestion("candidate-$index") }
            view.update(snapshot(suggestions, selectedIndex = 7, total = 20, start = 8))
            val rowHeight = view.list.getCellBounds(0, 0).height
            view.setSize(440, rowHeight * 2 + 25)
            view.doLayout()

            val image = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_ARGB)
            view.paint(image.createGraphics())
            val event =
                MouseEvent(
                    view.list,
                    MouseEvent.MOUSE_MOVED,
                    0L,
                    0,
                    40,
                    view.list.getCellBounds(7, 7).let { it.y + it.height / 2 },
                    0,
                    false,
                )
            view.list.dispatchEvent(event)

            assertEquals(7, listener.hoveredIndex)
            assertTrue(view.list.visibleRect.intersects(view.list.getCellBounds(7, 7)))
            assertTrue(imageContainsNonTransparentPixel(image))
        }

    @Test
    fun `mouse click and wheel remain controller-owned interactions`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            view.update(snapshot(List(2) { suggestion("candidate-$it") }, selectedIndex = 0, total = 2, start = 0))
            view.size = view.preferredSize
            view.doLayout()

            view.list.dispatchEvent(
                MouseEvent(
                    view.list,
                    MouseEvent.MOUSE_PRESSED,
                    0L,
                    0,
                    40,
                    view.list.visibleRect.y + 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
            val wheel =
                MouseWheelEvent(
                    view.list,
                    MouseEvent.MOUSE_WHEEL,
                    0L,
                    0,
                    40,
                    10,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    2,
                )
            view.list.dispatchEvent(wheel)

            assertEquals(0, listener.clickedIndex)
            assertEquals(2, listener.scrollDelta)
            assertTrue(wheel.isConsumed)
        }

    @Test
    fun `tooltips retain bounded full row context`() =
        onEdt {
            val view = createView(RecordingListener())
            view.update(
                snapshot(
                    listOf(suggestion("git checkout feature/terminal", detail = "Switch branch", sourceDisplayText = "Git")),
                    selectedIndex = 0,
                    total = 1,
                    start = 0,
                ),
            )
            view.size = view.preferredSize
            view.doLayout()
            val event =
                MouseEvent(
                    view.list,
                    MouseEvent.MOUSE_MOVED,
                    0L,
                    0,
                    40,
                    view.list.visibleRect.y + 2,
                    0,
                    false,
                )

            val tooltip = view.list.getToolTipText(event)
            assertNotNull(tooltip)
            assertTrue(tooltip!!.contains("git&nbsp;checkout&nbsp;feature/terminal"))
            assertTrue(tooltip.contains("Switch&nbsp;branch"))
            assertTrue(tooltip.contains("Git"))
        }

    @Test
    fun `selection-only updates retain the list model`() =
        onEdt {
            val view = createView(RecordingListener())
            val suggestions = List(3) { suggestion("candidate-$it") }
            view.update(snapshot(suggestions, selectedIndex = 0, total = 3, start = 0))
            val model = view.list.model

            view.update(snapshot(suggestions, selectedIndex = 1, total = 3, start = 0))

            assertSame(model, view.list.model)
        }

    @Test
    fun `list font changes recompute row metrics`() =
        onEdt {
            val view = createView(RecordingListener())
            view.update(snapshot(listOf(suggestion("candidate")), selectedIndex = 0, total = 1, start = 0))
            val initialHeight = view.preferredSize.height

            view.list.font = Font(Font.MONOSPACED, Font.PLAIN, 32)

            assertTrue(view.preferredSize.height > initialHeight)
        }

    @Test
    fun `renderer escapes markup neutralizes controls and bounds hostile graphemes`() =
        onEdt {
            val view = createView(RecordingListener())
            val candidate = suggestion("<html><img src='file:/secret'>\u202e\n" + "x".repeat(8000), detail = "<script>&")
            view.update(snapshot(listOf(candidate), 0, 1, 0))
            val renderer = view.list.cellRenderer.getListCellRendererComponent(view.list, candidate, 0, false, false) as javax.swing.JLabel
            assertTrue(renderer.text.contains("&lt;html&gt;"))
            assertFalse(renderer.text.contains("<img"))
            assertFalse(renderer.text.contains('\u202e'))
            assertFalse(renderer.text.contains('\n'))
            assertTrue(renderer.text.length < 5000)
            val cluster = suggestion("a" + "\u0301".repeat(8000))
            val clusterRenderer =
                view.list.cellRenderer.getListCellRendererComponent(view.list, cluster, 0, false, false) as javax.swing.JLabel
            assertEquals("\u2026", clusterRenderer.accessibleContext.accessibleName)
            view.close()
        }

    @Test
    fun `look and feel colors and accessible selection use the standard list`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val candidates = List(2) { suggestion("candidate-$it") }
            view.update(snapshot(candidates, -1, 2, 0))
            assertEquals(-1, view.list.selectedIndex)
            view.list.selectionBackground = Color.MAGENTA
            view.list.selectionForeground = Color.YELLOW
            val renderer = view.list.cellRenderer.getListCellRendererComponent(view.list, candidates[1], 1, true, false)
            assertEquals(Color.MAGENTA, renderer.background)
            assertEquals(Color.YELLOW, renderer.foreground)
            view.list.accessibleContext.accessibleSelection
                .addAccessibleSelection(1)
            assertEquals(1, listener.hoveredIndex)
            view.close()
            assertEquals(0, view.list.model.size)
        }

    @Test
    fun `renderer retains matched fragments and bounded preferred width`() =
        onEdt {
            val view = createView(RecordingListener())
            val text = "git status"
            val candidate =
                suggestion(text).copy(
                    matchedRanges = SwingShellSuggestionMatchRanges.fromPackedOffsets(text, intArrayOf(0, 3, 4, 6)),
                )
            view.update(snapshot(listOf(candidate), 0, 1, 0))
            val renderer = view.list.cellRenderer.getListCellRendererComponent(view.list, candidate, 0, false, false) as javax.swing.JLabel
            assertTrue(renderer.text.contains("<b>git</b>&nbsp;<b>st</b>atus"))
            view.update(snapshot(listOf(suggestion("x".repeat(10000))), 0, 1, 0))
            assertTrue(view.preferredSize.width in 320..640)
            view.update(SwingShellSuggestionViewSnapshot.EMPTY)
            assertEquals(java.awt.Dimension(0, 0), view.preferredSize)
            view.close()
        }

    @Test
    fun `renderer and tooltip preserve leading repeated and trailing spaces`() =
        onEdt {
            val view = createView(RecordingListener())
            try {
                fun label(text: String): javax.swing.JLabel =
                    view.list.cellRenderer.getListCellRendererComponent(
                        view.list,
                        suggestion(text),
                        0,
                        false,
                        false,
                    ) as javax.swing.JLabel
                val singleSpaceWidth = label("folder name").preferredSize.width
                assertTrue(label("folder  name").preferredSize.width > singleSpaceWidth)
                val renderer = label(" folder  name ")
                assertTrue(renderer.text.contains("&nbsp;folder&nbsp;&nbsp;name&nbsp;"))
                assertTrue(renderer.toolTipText.contains("&nbsp;folder&nbsp;&nbsp;name&nbsp;"))
                assertEquals(" folder  name ", renderer.accessibleContext.accessibleName)
            } finally {
                view.close()
            }
        }

    private fun createView(listener: RecordingListener): SwingCompletionPopupView {
        val parent =
            JPanel(null).apply {
                setSize(800, 600)
                font = Font(Font.MONOSPACED, Font.PLAIN, 13)
                background = Color(0x10, 0x14, 0x18)
                foreground = Color(0xE5, 0xE7, 0xEB)
            }
        return SwingCompletionPopupView(listener).also {
            it.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            parent.add(it)
        }
    }

    private fun snapshot(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
        total: Int,
        start: Int,
    ): SwingShellSuggestionViewSnapshot =
        SwingShellSuggestionViewSnapshot.create(
            visibleSuggestions = suggestions,
            selectedIndex = selectedIndex,
            viewportStartIndex = start,
            totalSuggestionCount = total,
        )

    private fun suggestion(
        displayText: String,
        detail: String = "",
        sourceDisplayText: String = "Built-in",
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = displayText,
            replacementStartOffset = 0,
            replacementEndOffset = 0,
            source = "spec",
            kind = "COMMAND",
            displayText = displayText,
            detail = detail,
            sourceDisplayText = sourceDisplayText,
        )

    private fun imageContainsNonTransparentPixel(image: BufferedImage): Boolean {
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                if (image.getRGB(x, y) ushr 24 != 0) return true
                x++
            }
            y++
        }
        return false
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }

    private class RecordingListener : SwingShellSuggestionViewListener {
        var hoveredIndex = -1
        var clickedIndex = -1
        var scrollDelta = 0

        override fun onSuggestionHovered(index: Int) {
            hoveredIndex = index
        }

        override fun onSuggestionClicked(index: Int) {
            clickedIndex = index
        }

        override fun onSuggestionScrollRequested(delta: Int) {
            scrollDelta = delta
        }
    }
}

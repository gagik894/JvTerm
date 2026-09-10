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
package io.github.ketraterm.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.ui.swing.suggestion.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel

class IntellijCompletionListViewTest : BasePlatformTestCase() {
    fun testPrecomputesMatchFragmentsAndUsesSemanticIcons() {
        val suggestion =
            suggestion(
                displayText = "status",
                accentRole = SwingShellSuggestionAccentRole.PATH,
                matchedRanges = SwingShellSuggestionMatchRanges.fromPackedOffsets("status", intArrayOf(0, 2, 4, 6)),
                sourceDisplayText = "IntelliJ project files with a deliberately long source name",
            )

        val item = prepareCompletionListItem(suggestion)

        assertEquals(
            listOf(
                IntellijCompletionTextFragment("st", matched = true),
                IntellijCompletionTextFragment("at", matched = false),
                IntellijCompletionTextFragment("us", matched = true),
            ),
            item.fragments,
        )
        assertSame(AllIcons.Nodes.Folder, item.icon)
        assertSame(AllIcons.Actions.Execute, completionIconFor(SwingShellSuggestionAccentRole.COMMAND))
        assertSame(AllIcons.Nodes.Parameter, completionIconFor(SwingShellSuggestionAccentRole.OPTION))
        assertSame(AllIcons.Vcs.History, completionIconFor(SwingShellSuggestionAccentRole.HISTORY))
        assertSame(AllIcons.Nodes.Property, completionIconFor(SwingShellSuggestionAccentRole.OTHER))
        assertTrue(INTELLIJ_COMPLETION_MATCH_ATTRIBUTES.style and SimpleTextAttributes.STYLE_BOLD != 0)
        assertNotNull(INTELLIJ_COMPLETION_MATCH_ATTRIBUTES.fgColor)
        assertTrue(item.sourceDisplayText.endsWith("…"))
        assertTrue(item.sourceDisplayText.codePointCount(0, item.sourceDisplayText.length) <= 24)
    }

    fun testBoundsSourceLabelsWithoutSplittingExtendedGraphemeClusters() {
        val cluster = "👩🏽‍💻"

        assertEquals(
            cluster.repeat(23) + "…",
            boundedCompletionSourceDisplayText(cluster.repeat(30)),
        )
    }

    fun testBoundsPresentationTextAndCropsMatchFragmentsAtTheRetainedPrefix() {
        val displayText = "a".repeat(4090) + "b".repeat(1000)
        val detail = "d".repeat(1500)
        val item =
            prepareCompletionListItem(
                suggestion(
                    displayText = displayText,
                    detail = detail,
                    accentRole = SwingShellSuggestionAccentRole.COMMAND,
                    matchedRanges =
                        SwingShellSuggestionMatchRanges.fromPackedOffsets(
                            displayText,
                            intArrayOf(4088, displayText.length),
                        ),
                    sourceDisplayText = "Built-in completion source with a long presentation label",
                ),
            )

        assertEquals("a".repeat(4090) + "b".repeat(5) + "…", item.fragments.joinToString("") { it.text })
        assertEquals("aa" + "b".repeat(5), item.fragments.single { it.matched }.text)
        assertEquals("d".repeat(1023) + "…", item.detailDisplayText)
        assertTrue(item.accessibleText.length <= 5300)
    }

    fun testSingleHostileGraphemeCannotBypassRawPresentationBounds() {
        val hostileCluster = "a" + "\u0301".repeat(20_000)

        val item =
            prepareCompletionListItem(
                suggestion(
                    displayText = hostileCluster,
                    detail = hostileCluster,
                    accentRole = SwingShellSuggestionAccentRole.COMMAND,
                    sourceDisplayText = hostileCluster,
                ),
            )

        assertEquals("…", item.fragments.joinToString("") { it.text })
        assertEquals("…", item.detailDisplayText)
        assertEquals("…", item.sourceDisplayText)
    }

    fun testNeutralizesUnsafeLineAndBidiControls() {
        val item =
            prepareCompletionListItem(
                suggestion(
                    displayText = "git\u0000status\u202E",
                    detail = "show\nstatus\u2066now",
                    accentRole = SwingShellSuggestionAccentRole.COMMAND,
                    sourceDisplayText = "Built\tin\u200F",
                ),
            )

        assertEquals("git�status�", item.fragments.joinToString("") { it.text })
        assertEquals("show status now", item.detailDisplayText)
        assertEquals("Built in", item.sourceDisplayText)
        assertFalse(item.accessibleText.contains('\n'))
        assertFalse(item.accessibleText.contains('\u202E'))
    }

    fun testShowsLocalizedVisibleRangeAndNavigationFooterForOverflow() {
        val view = IntellijCompletionListView(RecordingListener())
        val visible = suggestions(8)
        view.update(
            snapshot(
                visibleSuggestions = visible,
                selectedIndex = 2,
                viewportStartIndex = 8,
                totalSuggestionCount = 20,
            ),
        )

        try {
            assertTrue(view.rangeLabel.isVisible)
            assertEquals(
                "↑ ${KetraTermBundle.message("completion.list.range", 9, 16, 20)} ↓",
                view.rangeLabel.text,
            )
            assertEquals(KetraTermBundle.message("completion.list.hints"), view.hintsLabel.text)
            assertEquals(2, view.suggestionList.selectedIndex)
            assertEquals(8, view.suggestionList.model.size)
        } finally {
            view.close()
        }
    }

    fun testTracksTerminalPaletteAndDerivesCellHeightFromFontMetrics() {
        val view = IntellijCompletionListView(RecordingListener())
        val parent = terminalParent()
        parent.add(view.component, BorderLayout.CENTER)
        view.update(snapshot(suggestions(2)))

        try {
            val initialHeight = view.suggestionList.fixedCellHeight
            val initialBackground = view.suggestionList.background
            val replacementFont = Font(Font.MONOSPACED, Font.PLAIN, 24)
            parent.background = Color(245, 246, 248)
            parent.foreground = Color(25, 27, 31)
            parent.font = replacementFont

            assertFalse(initialBackground == view.suggestionList.background)
            assertEquals(parent.background, view.suggestionList.background)
            assertEquals(parent.foreground, view.suggestionList.foreground)
            assertEquals(replacementFont, view.suggestionList.font)
            assertTrue(view.suggestionList.fixedCellHeight > initialHeight)
            assertTrue(view.suggestionList.fixedCellHeight > view.suggestionList.getFontMetrics(replacementFont).height)
        } finally {
            view.close()
        }
    }

    fun testKeepsSelectedTailVisibleWhenTerminalClampsPopupHeight() {
        val view = IntellijCompletionListView(RecordingListener())
        val parent = terminalParent().apply { font = Font(Font.MONOSPACED, Font.PLAIN, 32) }
        parent.add(view.component, BorderLayout.CENTER)
        view.update(snapshot(suggestions(8), selectedIndex = 0))

        try {
            view.component.setSize(480, view.suggestionList.fixedCellHeight * 2 + 50)
            view.component.doLayout()
            view.suggestionScrollPane.doLayout()
            view.suggestionScrollPane.viewport.doLayout()

            view.update(snapshot(suggestions(8), selectedIndex = 7))

            val selectedBounds = checkNotNull(view.suggestionList.getCellBounds(7, 7))
            val viewport = view.suggestionScrollPane.viewport
            val visibleTop = viewport.viewPosition.y
            val visibleBottom = visibleTop + viewport.extentSize.height
            assertTrue(viewport.extentSize.height < view.suggestionList.preferredSize.height)
            assertTrue(visibleTop > 0)
            assertTrue(selectedBounds.y >= visibleTop)
            assertTrue(selectedBounds.y + selectedBounds.height <= visibleBottom)
            assertFalse(view.suggestionScrollPane.verticalScrollBar.isVisible)
            assertTrue(view.rangeLabel.isVisible)
            val firstVisibleRank = visibleTop / view.suggestionList.fixedCellHeight + 1
            val lastVisibleRank =
                ((visibleBottom - 1) / view.suggestionList.fixedCellHeight + 1)
                    .coerceAtMost(8)
            assertEquals(
                "↑ ${KetraTermBundle.message("completion.list.range", firstVisibleRank, lastVisibleRank, 8)}",
                view.rangeLabel.text,
            )
        } finally {
            view.close()
        }
    }

    fun testRejectsInvalidLocalSelectionBeforeRendering() {
        try {
            snapshot(suggestions(4), selectedIndex = 99)
            fail("invalid local selection produced a renderable snapshot")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid local indices must not cross the shared view boundary.
        }
    }

    fun testPointerWheelTooltipAndAccessibilityReportLocalRows() {
        val listener = RecordingListener()
        val view = IntellijCompletionListView(listener)
        view.update(snapshot(suggestions(3)))
        val list = view.suggestionList
        list.setSize(480, list.fixedCellHeight * 3)
        list.doLayout()
        val row = checkNotNull(list.getCellBounds(1, 1))
        val x = row.x + row.width / 2
        val y = row.y + row.height / 2

        try {
            val moved = mouseEvent(list, MouseEvent.MOUSE_MOVED, x, y, MouseEvent.NOBUTTON)
            list.dispatchEvent(moved)
            list.dispatchEvent(mouseEvent(list, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1))
            list.dispatchEvent(mouseWheelEvent(list, x, y, rotation = 1))

            assertEquals(1, listener.hoveredIndex)
            assertEquals(1, listener.clickedIndex)
            assertEquals(listOf(1), listener.scrollDeltas)
            assertNotNull(list.getToolTipText(moved))
            assertEquals(KetraTermBundle.message("completion.list.accessibleName"), list.accessibleContext.accessibleName)
            val item = list.model.getElementAt(1)
            val rendered = list.cellRenderer.getListCellRendererComponent(list, item, 1, false, false)
            assertEquals(item.accessibleText, rendered.accessibleContext.accessibleDescription)
        } finally {
            view.close()
        }
    }

    fun testCloseIsIdempotentAndReleasesPresentationResources() {
        val view = IntellijCompletionListView(RecordingListener())
        view.update(snapshot(suggestions(2)))
        val list = view.suggestionList

        view.close()
        view.close()

        assertTrue(view.isClosed)
        assertEquals(0, list.model.size)
        assertEquals(0, view.component.componentCount)
        assertNull(list.toolTipText)
        try {
            view.update(snapshot(suggestions(1)))
            fail("closed completion list accepted an update")
        } catch (_: IllegalStateException) {
            // Expected: a closed view must never regain listeners or models.
        }
    }

    fun testUsesNativeJBListWithoutIntellijLookupComponents() {
        val view = IntellijCompletionListView(RecordingListener())
        val parent = terminalParent()
        parent.add(view.component, BorderLayout.CENTER)
        view.update(snapshot(suggestions(3), selectedIndex = 1))

        try {
            assertTrue(JBList::class.java.isAssignableFrom(view.suggestionList.javaClass))
            assertFalse(descendants(view.component).any { it.javaClass.name.contains("codeInsight.lookup") })
            assertTrue(view.component.preferredSize.width in 320..620)
            assertTrue(view.component.preferredSize.height > 0)
        } finally {
            view.close()
        }
    }

    private fun snapshot(
        visibleSuggestions: List<SwingShellSuggestion>,
        selectedIndex: Int = -1,
        viewportStartIndex: Int = 0,
        totalSuggestionCount: Int = visibleSuggestions.size,
    ): SwingShellSuggestionViewSnapshot =
        SwingShellSuggestionViewSnapshot.create(
            visibleSuggestions = visibleSuggestions,
            selectedIndex = selectedIndex,
            viewportStartIndex = viewportStartIndex,
            totalSuggestionCount = totalSuggestionCount,
        )

    private fun suggestions(count: Int): List<SwingShellSuggestion> =
        List(count) { index ->
            suggestion(
                displayText = "candidate-$index",
                accentRole = if (index % 2 == 0) SwingShellSuggestionAccentRole.COMMAND else SwingShellSuggestionAccentRole.PATH,
                sourceDisplayText = if (index % 2 == 0) "Built-in" else "Project",
            )
        }

    private fun suggestion(
        displayText: String,
        accentRole: SwingShellSuggestionAccentRole,
        detail: String = "detail for $displayText",
        matchedRanges: SwingShellSuggestionMatchRanges = SwingShellSuggestionMatchRanges.EMPTY,
        sourceDisplayText: String,
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = displayText,
            replacementStartOffset = 0,
            replacementEndOffset = 0,
            source = "test-source",
            kind = "TEST",
            displayText = displayText,
            detail = detail,
            accentRole = accentRole,
            matchedRanges = matchedRanges,
            sourceDisplayText = sourceDisplayText,
        )

    private fun terminalParent(): JPanel =
        JPanel(BorderLayout()).apply {
            background = Color(24, 26, 29)
            foreground = Color(220, 223, 228)
            font = Font(Font.MONOSPACED, Font.PLAIN, 15)
            setSize(900, 500)
        }

    private fun descendants(root: Container): Sequence<java.awt.Component> =
        sequence {
            root.components.forEach { child ->
                yield(child)
                if (child is Container) yieldAll(descendants(child))
            }
        }

    private fun mouseEvent(
        source: java.awt.Component,
        id: Int,
        x: Int,
        y: Int,
        button: Int,
    ): MouseEvent =
        MouseEvent(
            source,
            id,
            System.currentTimeMillis(),
            0,
            x,
            y,
            1,
            false,
            button,
        )

    private fun mouseWheelEvent(
        source: java.awt.Component,
        x: Int,
        y: Int,
        rotation: Int,
    ): MouseWheelEvent =
        MouseWheelEvent(
            source,
            MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            0,
            x,
            y,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            rotation,
        )

    private class RecordingListener : SwingShellSuggestionViewListener {
        var hoveredIndex: Int = -1
            private set
        var clickedIndex: Int = -1
            private set
        val scrollDeltas = ArrayList<Int>()

        override fun onSuggestionHovered(index: Int) {
            hoveredIndex = index
        }

        override fun onSuggestionClicked(index: Int) {
            clickedIndex = index
        }

        override fun onSuggestionScrollRequested(delta: Int) {
            scrollDeltas += delta
        }
    }
}

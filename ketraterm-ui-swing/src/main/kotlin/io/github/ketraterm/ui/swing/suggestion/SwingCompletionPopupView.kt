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

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.*
import java.util.regex.Pattern
import javax.swing.*

/** Standard Swing list presentation; the controller retains selection and acceptance ownership. */
internal class SwingCompletionPopupView(
    private val listener: SwingShellSuggestionViewListener,
) : JPanel(BorderLayout()),
    SwingShellSuggestionView {
    override val component: JComponent get() = this
    private var snapshot = SwingShellSuggestionViewSnapshot.EMPTY
    private var updating = false
    private var closed = false
    internal val list = JList<SwingShellSuggestion>()
    private val scrollPane = JScrollPane(list)
    private val position = JLabel()
    private val selectionListener =
        javax.swing.event.ListSelectionListener {
            if (!updating && !closed && !it.valueIsAdjusting && list.selectedIndex >= 0) {
                listener.onSuggestionHovered(list.selectedIndex)
            }
        }
    private val pointerHandler =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!closed && SwingUtilities.isLeftMouseButton(event)) {
                    rowAt(event)?.let(listener::onSuggestionClicked)
                }
            }

            override fun mouseMoved(event: MouseEvent) {
                if (!closed) rowAt(event)?.let(listener::onSuggestionHovered)
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                if (!closed && event.wheelRotation != 0) {
                    listener.onSuggestionScrollRequested(event.wheelRotation.coerceIn(-3, 3))
                    event.consume()
                }
            }
        }

    init {
        isFocusable = false
        list.isFocusable = false
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = CompletionRenderer()
        list.visibleRowCount = SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS
        list.accessibleContext.accessibleName = "Command completions"
        list.addListSelectionListener(selectionListener)
        list.addMouseListener(pointerHandler)
        list.addMouseMotionListener(pointerHandler)
        list.addMouseWheelListener(pointerHandler)
        ToolTipManager.sharedInstance().registerComponent(list)
        scrollPane.isWheelScrollingEnabled = false
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        scrollPane.addMouseWheelListener(pointerHandler)
        scrollPane.viewport.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) = revealSelection()
            },
        )
        position.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        add(scrollPane, BorderLayout.CENTER)
        add(position, BorderLayout.SOUTH)
    }

    override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
        check(SwingUtilities.isEventDispatchThread()) { "completion view must update on the EDT" }
        check(!closed) { "completion view is closed" }
        updating = true
        try {
            if (this.snapshot.visibleSuggestions != snapshot.visibleSuggestions) {
                list.setListData(snapshot.visibleSuggestions.toTypedArray())
            }
            this.snapshot = snapshot
            list.selectedIndex = snapshot.selectedIndex
            list.visibleRowCount = snapshot.visibleSuggestions.size
            position.isVisible = snapshot.hasSuggestionsBefore || snapshot.hasSuggestionsAfter
            position.text =
                "${snapshot.viewportStartIndex + 1}–${snapshot.viewportStartIndex + snapshot.visibleSuggestions.size} of ${snapshot.totalSuggestionCount}"
            list.accessibleContext.accessibleDescription = "${snapshot.totalSuggestionCount} suggestions, ${position.text}"
            revealSelection()
        } finally {
            updating = false
        }
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension =
        if (snapshot.visibleSuggestions.isEmpty()) {
            Dimension(0, 0)
        } else {
            super.getPreferredSize().apply { width = width.coerceIn(320, 640) }
        }

    override fun doLayout() {
        super.doLayout()
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        revealSelection()
    }

    private fun revealSelection() {
        if (list.selectedIndex >= 0) list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun rowAt(event: MouseEvent): Int? =
        list.locationToIndex(event.point).takeIf {
            it >= 0 && list.getCellBounds(it, it)?.contains(event.point) == true
        }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread()) { "completion view must close on the EDT" }
        if (closed) return
        closed = true
        list.removeListSelectionListener(selectionListener)
        list.removeMouseListener(pointerHandler)
        list.removeMouseMotionListener(pointerHandler)
        list.removeMouseWheelListener(pointerHandler)
        scrollPane.removeMouseWheelListener(pointerHandler)
        ToolTipManager.sharedInstance().unregisterComponent(list)
        list.setListData(emptyArray<SwingShellSuggestion>())
        snapshot = SwingShellSuggestionViewSnapshot.EMPTY
    }
}

private class CompletionRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus)
        val suggestion = value as? SwingShellSuggestion ?: return this
        val primary = displayText(suggestion.displayText, 4096)
        val detail = displayText(suggestion.detail, 1024)
        val source = displayText(suggestion.sourceDisplayText, 128)
        text =
            buildString {
                append("<html>")
                var offset = 0
                for (range in 0 until suggestion.matchedRanges.rangeCount) {
                    val start = suggestion.matchedRanges.startOffset(range)
                    if (start >= primary.length) break
                    val end = suggestion.matchedRanges.endOffset(range).coerceAtMost(primary.length)
                    append(escapeHtml(primary.substring(offset, start)))
                    append("<b>").append(escapeHtml(primary.substring(start, end))).append("</b>")
                    offset = end
                }
                append(escapeHtml(primary.substring(offset)))
                if (detail.isNotEmpty()) append(" &nbsp; ").append(escapeHtml(detail))
                append(" &nbsp; <small>").append(escapeHtml(source)).append("</small></html>")
            }
        icon =
            UIManager.getIcon(
                when (suggestion.accentRole) {
                    SwingShellSuggestionAccentRole.PATH -> "FileView.directoryIcon"
                    SwingShellSuggestionAccentRole.COMMAND -> "FileView.computerIcon"
                    else -> "Tree.leafIcon"
                },
            )
        border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        toolTipText = "<html>" +
            listOf(displayText(primary, 1024), detail, source)
                .filter { it.isNotEmpty() }
                .joinToString(" — ", transform = ::escapeHtml) + "</html>"
        getAccessibleContext().accessibleName = primary.ifEmpty { "Completion suggestion" }
        getAccessibleContext().accessibleDescription = listOf(detail, source, suggestion.accentRole.name).joinToString(", ")
        return this
    }
}

/** Bound hostile provider text without splitting an extended grapheme or exposing display controls. */
private fun displayText(
    text: String,
    limit: Int,
): String {
    var end = text.length
    if (end > limit) {
        val matcher = GRAPHEME.matcher(text).region(0, minOf(text.length, limit + 1))
        end = 0
        while (matcher.find() && matcher.end() <= limit) end = matcher.end()
    }
    return buildString {
        for (index in 0 until end) {
            val char = text[index]
            append(
                if (Character.isISOControl(char) ||
                    char == '\u061c' ||
                    char in '\u200e'..'\u200f' ||
                    char in '\u202a'..'\u202e' ||
                    char in '\u2066'..'\u2069'
                ) {
                    ' '
                } else {
                    char
                },
            )
        }
        if (end < text.length) append('…')
    }
}

private fun escapeHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace(" ", "&nbsp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private val GRAPHEME = Pattern.compile("\\X")

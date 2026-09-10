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
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.ui.swing.suggestion.*
import java.awt.*
import java.awt.event.*
import java.beans.PropertyChangeListener
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.MatteBorder
import javax.swing.event.ChangeListener
import kotlin.math.min

/** Creates one IntelliJ-native completion list for a Swing terminal. */
internal object IntellijCompletionListViewFactory : SwingShellSuggestionViewFactory {
    override fun create(listener: SwingShellSuggestionViewListener): SwingShellSuggestionView = IntellijCompletionListView(listener)
}

/**
 * IntelliJ-native presentation of a shared terminal-completion snapshot.
 *
 * The reusable Swing controller retains navigation, acceptance, dismissal, and
 * feedback semantics. This view owns only IDE-themed list rendering and reports
 * pointer or wheel intent through [listener].
 */
internal class IntellijCompletionListView(
    private val listener: SwingShellSuggestionViewListener,
) : SwingShellSuggestionView {
    private val model = DefaultListModel<IntellijCompletionListItem>()
    internal val suggestionList: JBList<IntellijCompletionListItem> = CompletionSuggestionList(model)
    internal val rangeLabel = JBLabel()
    internal val hintsLabel = JBLabel(KetraTermBundle.message("completion.list.hints"))
    private val footer = completionFooter(rangeLabel, hintsLabel)
    internal val suggestionScrollPane = completionScrollPane(suggestionList)
    private val host = CompletionListSurface(suggestionList, suggestionScrollPane, footer, ::updateTypography)
    private var visibleSuggestions: List<SwingShellSuggestion> = emptyList()
    private var currentSnapshot: SwingShellSuggestionViewSnapshot = SwingShellSuggestionViewSnapshot.EMPTY
    private val viewportChangeListener = ChangeListener { updateFooter(currentSnapshot) }

    internal var isClosed: Boolean = false
        private set

    private val pointerAdapter =
        object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                suggestionList.indexAt(event.point)?.let(listener::onSuggestionHovered)
            }

            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                suggestionList.indexAt(event.point)?.let(listener::onSuggestionClicked)
                event.consume()
            }
        }

    private val wheelListener =
        MouseWheelListener { event ->
            if (model.isEmpty) return@MouseWheelListener
            val delta = event.wheelRotation
            if (delta == 0) return@MouseWheelListener
            listener.onSuggestionScrollRequested(delta)
            event.consume()
        }

    init {
        suggestionList.apply {
            cellRenderer = IntellijCompletionCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            isFocusable = false
            isOpaque = false
            border = JBUI.Borders.empty(0, JBUI.scale(ROW_HORIZONTAL_INSET))
            accessibleContext.accessibleName = KetraTermBundle.message("completion.list.accessibleName")
            accessibleContext.accessibleDescription = KetraTermBundle.message("completion.list.accessibleDescription")
            toolTipText = ""
            addMouseListener(pointerAdapter)
            addMouseMotionListener(pointerAdapter)
            addMouseWheelListener(wheelListener)
        }
        footer.addMouseWheelListener(wheelListener)
        suggestionScrollPane.addMouseWheelListener(wheelListener)
        suggestionScrollPane.viewport.addChangeListener(viewportChangeListener)
        host.addMouseWheelListener(wheelListener)
        updateTypography()
    }

    override val component: JComponent get() = host

    override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
        check(!isClosed) { "Completion list view is closed" }
        currentSnapshot = snapshot
        host.synchronizeAppearance()

        if (visibleSuggestions != snapshot.visibleSuggestions) {
            visibleSuggestions = snapshot.visibleSuggestions.toList()
            val preparedItems = visibleSuggestions.map(::prepareCompletionListItem)
            model.removeAllElements()
            model.addAll(preparedItems)
        }

        suggestionList.selectedIndex = snapshot.selectedIndex.takeIf { it in visibleSuggestions.indices } ?: NO_SELECTION
        if (suggestionList.selectedIndex >= 0) {
            suggestionList.ensureIndexIsVisible(suggestionList.selectedIndex)
        }

        updateFooter(snapshot)
        updatePreferredSize()
        host.revalidate()
        host.repaint()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        suggestionList.removeMouseListener(pointerAdapter)
        suggestionList.removeMouseMotionListener(pointerAdapter)
        suggestionList.removeMouseWheelListener(wheelListener)
        suggestionList.toolTipText = null
        footer.removeMouseWheelListener(wheelListener)
        suggestionScrollPane.removeMouseWheelListener(wheelListener)
        suggestionScrollPane.viewport.removeChangeListener(viewportChangeListener)
        host.removeMouseWheelListener(wheelListener)
        host.close()
        visibleSuggestions = emptyList()
        currentSnapshot = SwingShellSuggestionViewSnapshot.EMPTY
        model.removeAllElements()
        rangeLabel.text = ""
        hintsLabel.text = ""
    }

    private fun updateTypography() {
        val listFont = suggestionList.font ?: UIUtil.getLabelFont()
        val listMetrics = suggestionList.getFontMetrics(listFont)
        suggestionList.fixedCellHeight =
            (listMetrics.height + JBUI.scale(CELL_VERTICAL_PADDING)).coerceAtLeast(JBUI.scale(MINIMUM_CELL_HEIGHT))
        suggestionScrollPane.verticalScrollBar.unitIncrement = suggestionList.fixedCellHeight

        val footerFont = listFont.deriveFont(Font.PLAIN, (listFont.size2D - FOOTER_FONT_REDUCTION).coerceAtLeast(MINIMUM_FOOTER_FONT_SIZE))
        rangeLabel.font = footerFont
        hintsLabel.font = footerFont
        val footerForeground = JBColor.namedColor("Label.infoForeground", UIUtil.getContextHelpForeground())
        rangeLabel.foreground = footerForeground
        hintsLabel.foreground = footerForeground
        updatePreferredSize()
    }

    private fun updateFooter(snapshot: SwingShellSuggestionViewSnapshot) {
        if (snapshot.visibleSuggestions.isEmpty()) {
            rangeLabel.isVisible = false
            rangeLabel.text = ""
            footer.isVisible = false
            return
        }
        val viewport = suggestionScrollPane.viewport
        val cellHeight = suggestionList.fixedCellHeight
        val extentHeight = viewport.extentSize.height
        val firstVisibleLocalIndex =
            if (cellHeight > 0 && extentHeight > 0) {
                (viewport.viewPosition.y / cellHeight).coerceIn(0, snapshot.visibleSuggestions.lastIndex)
            } else {
                0
            }
        val lastVisibleLocalIndex =
            if (cellHeight > 0 && extentHeight > 0) {
                ((viewport.viewPosition.y + extentHeight - 1) / cellHeight)
                    .coerceIn(firstVisibleLocalIndex, snapshot.visibleSuggestions.lastIndex)
            } else {
                snapshot.visibleSuggestions.lastIndex
            }
        val hasSuggestionsBefore = snapshot.hasSuggestionsBefore || firstVisibleLocalIndex > 0
        val hasSuggestionsAfter = snapshot.hasSuggestionsAfter || lastVisibleLocalIndex < snapshot.visibleSuggestions.lastIndex
        val hasOverflow = hasSuggestionsBefore || hasSuggestionsAfter
        rangeLabel.isVisible = hasOverflow
        rangeLabel.text =
            if (hasOverflow) {
                val first = snapshot.viewportStartIndex + firstVisibleLocalIndex + 1
                val last = snapshot.viewportStartIndex + lastVisibleLocalIndex + 1
                buildString {
                    if (hasSuggestionsBefore) append(UP_ARROW).append(' ')
                    append(KetraTermBundle.message("completion.list.range", first, last, snapshot.totalSuggestionCount))
                    if (hasSuggestionsAfter) append(' ').append(DOWN_ARROW)
                }
            } else {
                ""
            }
        footer.isVisible = snapshot.visibleSuggestions.isNotEmpty()
    }

    private fun updatePreferredSize() {
        if (model.isEmpty) {
            host.preferredSize = Dimension(0, 0)
            return
        }

        val insets = host.insets
        val parentWidth = host.parent?.width?.minus(JBUI.scale(PARENT_HORIZONTAL_MARGIN))
        val maximumWidth = min(JBUI.scale(MAXIMUM_WIDTH), parentWidth?.takeIf { it > 0 } ?: JBUI.scale(MAXIMUM_WIDTH)).coerceAtLeast(1)
        val minimumWidth = min(JBUI.scale(MINIMUM_WIDTH), maximumWidth)
        val naturalWidth = maxOf(suggestionList.preferredSize.width, footer.preferredSize.width) + insets.left + insets.right
        val width = naturalWidth.coerceIn(minimumWidth, maximumWidth)
        val height =
            suggestionList.fixedCellHeight * model.size +
                footer.preferredSize.height +
                insets.top +
                insets.bottom
        host.preferredSize = Dimension(width, height)
    }

    private fun JList<*>.indexAt(point: Point): Int? {
        val index = locationToIndex(point)
        if (index !in 0 until model.size) return null
        return index.takeIf { getCellBounds(index, index)?.contains(point) == true }
    }

    private companion object {
        private const val NO_SELECTION = -1
        private const val CELL_VERTICAL_PADDING = 10
        private const val MINIMUM_CELL_HEIGHT = 24
        private const val ROW_HORIZONTAL_INSET = 4
        private const val MINIMUM_WIDTH = 320
        private const val MAXIMUM_WIDTH = 620
        private const val PARENT_HORIZONTAL_MARGIN = 24
        private const val FOOTER_FONT_REDUCTION = 1.0f
        private const val MINIMUM_FOOTER_FONT_SIZE = 10.0f
        private const val UP_ARROW = "↑"
        private const val DOWN_ARROW = "↓"
    }
}

/** Immutable rendering fragment prepared before the Swing cell renderer runs. */
internal data class IntellijCompletionTextFragment(
    val text: String,
    val matched: Boolean,
)

/** Immutable row model consumed by [IntellijCompletionCellRenderer]. */
internal data class IntellijCompletionListItem(
    val fragments: List<IntellijCompletionTextFragment>,
    val detailDisplayText: String,
    val sourceDisplayText: String,
    val icon: Icon,
    val accessibleText: String,
)

internal fun prepareCompletionListItem(suggestion: SwingShellSuggestion): IntellijCompletionListItem {
    val boundedDisplayText =
        boundedCompletionText(
            suggestion.displayText,
            maximumCodeUnits = MAXIMUM_DISPLAY_CODE_UNITS,
            maximumGraphemeCount = MAXIMUM_DISPLAY_GRAPHEMES,
        )
    val detailDisplayText =
        boundedCompletionSecondaryText(
            suggestion.detail,
            maximumCodeUnits = MAXIMUM_DETAIL_CODE_UNITS,
            maximumGraphemeCount = MAXIMUM_DETAIL_GRAPHEMES,
        )
    val sourceDisplayText = boundedCompletionSourceDisplayText(suggestion.sourceDisplayText)
    val accessibleText =
        if (detailDisplayText.isEmpty()) {
            KetraTermBundle.message("completion.list.item.accessible.noDetail", boundedDisplayText.text, sourceDisplayText)
        } else {
            KetraTermBundle.message(
                "completion.list.item.accessible",
                boundedDisplayText.text,
                detailDisplayText,
                sourceDisplayText,
            )
        }
    return IntellijCompletionListItem(
        fragments = completionMatchFragments(suggestion.matchedRanges, boundedDisplayText),
        detailDisplayText = detailDisplayText,
        sourceDisplayText = sourceDisplayText,
        icon = completionIconFor(suggestion.accentRole),
        accessibleText = accessibleText,
    )
}

private fun completionMatchFragments(
    matchedRanges: SwingShellSuggestionMatchRanges,
    boundedDisplayText: BoundedCompletionText,
): List<IntellijCompletionTextFragment> {
    if (matchedRanges.isEmpty()) {
        return listOf(IntellijCompletionTextFragment(boundedDisplayText.text, matched = false))
    }

    val fragments =
        ArrayList<IntellijCompletionTextFragment>(
            minOf(matchedRanges.rangeCount, boundedDisplayText.retainedPrefixLength) * 2 + 1,
        )
    val retainedPrefixLength = boundedDisplayText.retainedPrefixLength
    val displayPrefix = boundedDisplayText.text.substring(0, retainedPrefixLength)
    var cursor = 0
    for (rangeIndex in 0 until matchedRanges.rangeCount) {
        val start = matchedRanges.startOffset(rangeIndex)
        if (start >= retainedPrefixLength) break
        val end = minOf(matchedRanges.endOffset(rangeIndex), retainedPrefixLength)
        if (cursor < start) {
            fragments += IntellijCompletionTextFragment(displayPrefix.substring(cursor, start), matched = false)
        }
        fragments += IntellijCompletionTextFragment(displayPrefix.substring(start, end), matched = true)
        cursor = end
    }
    if (cursor < retainedPrefixLength) {
        fragments += IntellijCompletionTextFragment(displayPrefix.substring(cursor, retainedPrefixLength), matched = false)
    }
    if (boundedDisplayText.isTruncated) {
        fragments += IntellijCompletionTextFragment(ELLIPSIS, matched = false)
    }
    return fragments
}

internal fun completionIconFor(role: SwingShellSuggestionAccentRole): Icon =
    when (role) {
        SwingShellSuggestionAccentRole.COMMAND -> AllIcons.Actions.Execute
        SwingShellSuggestionAccentRole.PATH -> AllIcons.Nodes.Folder
        SwingShellSuggestionAccentRole.OPTION -> AllIcons.Nodes.Parameter
        SwingShellSuggestionAccentRole.HISTORY -> AllIcons.Vcs.History
        SwingShellSuggestionAccentRole.OTHER -> AllIcons.Nodes.Property
    }

internal fun boundedCompletionSourceDisplayText(sourceDisplayText: String): String =
    boundedCompletionSecondaryText(
        sourceDisplayText,
        maximumCodeUnits = MAXIMUM_SOURCE_CODE_UNITS,
        maximumGraphemeCount = MAXIMUM_SOURCE_GRAPHEMES,
    )

private data class BoundedCompletionText(
    val text: String,
    val retainedPrefixLength: Int,
    val isTruncated: Boolean,
)

private fun boundedCompletionText(
    text: String,
    maximumCodeUnits: Int,
    maximumGraphemeCount: Int,
): BoundedCompletionText {
    val prefix = boundedCompletionPrefix(text, maximumCodeUnits, maximumGraphemeCount)
    val sanitizedPrefix = sanitizeCompletionPrimaryText(text, prefix.retainedPrefixLength)
    return BoundedCompletionText(
        text = if (prefix.isTruncated) sanitizedPrefix + ELLIPSIS else sanitizedPrefix,
        retainedPrefixLength = prefix.retainedPrefixLength,
        isTruncated = prefix.isTruncated,
    )
}

private fun boundedCompletionSecondaryText(
    text: String,
    maximumCodeUnits: Int,
    maximumGraphemeCount: Int,
): String {
    val prefix = boundedCompletionPrefix(text, maximumCodeUnits, maximumGraphemeCount)
    val result = StringBuilder(prefix.retainedPrefixLength.coerceAtMost(maximumCodeUnits))
    var whitespacePending = false
    var index = 0
    while (index < prefix.retainedPrefixLength) {
        val codePoint = text.codePointAt(index)
        if (Character.isWhitespace(codePoint) || codePoint.isUnsafeCompletionDisplayControl()) {
            whitespacePending = result.isNotEmpty()
        } else {
            if (whitespacePending) result.append(' ')
            result.appendCodePoint(codePoint)
            whitespacePending = false
        }
        index += Character.charCount(codePoint)
    }
    if (prefix.isTruncated) {
        if (result.isEmpty()) return ELLIPSIS
        result.append(ELLIPSIS)
    }
    return result.toString()
}

private fun boundedCompletionPrefix(
    text: String,
    maximumCodeUnits: Int,
    maximumGraphemeCount: Int,
): BoundedCompletionPrefix {
    require(maximumCodeUnits > 0) { "maximumCodeUnits must be > 0" }
    require(maximumGraphemeCount > 0) { "maximumGraphemeCount must be > 0" }
    val rawPrefixLength = boundedRawGraphemePrefixLength(text, maximumCodeUnits)
    val rawPrefix = text.substring(0, rawPrefixLength)
    val matcher = EXTENDED_GRAPHEME_CLUSTER.matcher(rawPrefix)
    var graphemeCount = 0
    var retainedEnd = 0
    while (matcher.find()) {
        graphemeCount++
        if (graphemeCount < maximumGraphemeCount) retainedEnd = matcher.end()
        if (graphemeCount > maximumGraphemeCount) {
            return BoundedCompletionPrefix(
                retainedPrefixLength = retainedEnd,
                isTruncated = true,
            )
        }
    }
    return BoundedCompletionPrefix(
        retainedPrefixLength = rawPrefixLength,
        isTruncated = rawPrefixLength < text.length,
    )
}

private fun boundedRawGraphemePrefixLength(
    text: String,
    maximumCodeUnits: Int,
): Int {
    if (text.length <= maximumCodeUnits) return text.length
    var rawLimit = maximumCodeUnits
    if (
        Character.isHighSurrogate(text[rawLimit - 1]) &&
        Character.isLowSurrogate(text[rawLimit])
    ) {
        rawLimit--
    }
    if (rawLimit == 0) return 0
    val rawPrefix = text.substring(0, rawLimit)
    val matcher = EXTENDED_GRAPHEME_CLUSTER.matcher(rawPrefix)
    var previousBoundary = 0
    var lastBoundary = 0
    while (matcher.find()) {
        previousBoundary = lastBoundary
        lastBoundary = matcher.end()
    }
    return previousBoundary
}

private fun sanitizeCompletionPrimaryText(
    text: String,
    retainedPrefixLength: Int,
): String {
    var index = 0
    var firstUnsafeIndex = -1
    while (index < retainedPrefixLength) {
        if (text[index].code.isUnsafeCompletionDisplayControl()) {
            firstUnsafeIndex = index
            break
        }
        index++
    }
    if (firstUnsafeIndex < 0) return text.substring(0, retainedPrefixLength)
    return StringBuilder(retainedPrefixLength)
        .apply {
            append(text, 0, firstUnsafeIndex)
            index = firstUnsafeIndex
            while (index < retainedPrefixLength) {
                val character = text[index]
                append(if (character.code.isUnsafeCompletionDisplayControl()) REPLACEMENT_CHARACTER else character)
                index++
            }
        }.toString()
}

private fun Int.isUnsafeCompletionDisplayControl(): Boolean =
    Character.isISOControl(this) ||
        this == ARABIC_LETTER_MARK ||
        this == LEFT_TO_RIGHT_MARK ||
        this == RIGHT_TO_LEFT_MARK ||
        this in BIDI_EMBEDDING_START..BIDI_EMBEDDING_END ||
        this in BIDI_ISOLATE_START..BIDI_ISOLATE_END

private data class BoundedCompletionPrefix(
    val retainedPrefixLength: Int,
    val isTruncated: Boolean,
)

private class CompletionSuggestionList(
    model: DefaultListModel<IntellijCompletionListItem>,
) : JBList<IntellijCompletionListItem>(model) {
    override fun getToolTipText(event: MouseEvent): String? {
        val index = locationToIndex(event.point)
        if (index !in 0 until model.size) return null
        if (getCellBounds(index, index)?.contains(event.point) != true) return null
        return model.getElementAt(index).accessibleText
    }
}

private class IntellijCompletionCellRenderer : ColoredListCellRenderer<IntellijCompletionListItem>() {
    init {
        isOpaque = true
        ipad = JBUI.insets(0, CELL_HORIZONTAL_INSET)
        iconTextGap = JBUI.scale(ICON_TEXT_GAP)
    }

    override fun customizeCellRenderer(
        list: JList<out IntellijCompletionListItem>,
        value: IntellijCompletionListItem,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        font = list.font ?: UIUtil.getLabelFont()
        icon = value.icon
        value.fragments.forEach { fragment ->
            append(
                fragment.text,
                if (fragment.matched) INTELLIJ_COMPLETION_MATCH_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                true,
            )
        }

        if (value.detailDisplayText.isNotEmpty()) {
            append("  ${value.detailDisplayText}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        val sourceWidth = getFontMetrics(font).stringWidth(value.sourceDisplayText)
        val sourceStart = list.width - sourceWidth - JBUI.scale(SOURCE_RIGHT_INSET)
        if (sourceStart > 0) appendTextPadding(sourceStart) else append("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(value.sourceDisplayText, SimpleTextAttributes.GRAYED_ATTRIBUTES)

        toolTipText = value.accessibleText
        getAccessibleContext().accessibleDescription = value.accessibleText
    }

    private companion object {
        private const val CELL_HORIZONTAL_INSET = 8
        private const val ICON_TEXT_GAP = 6
        private const val SOURCE_RIGHT_INSET = 12
    }
}

internal val INTELLIJ_COMPLETION_MATCH_ATTRIBUTES =
    SimpleTextAttributes(
        SimpleTextAttributes.STYLE_BOLD,
        JBColor.namedColor("CompletionPopup.matchForeground", JBColor(0x0B57D0, 0x78A9FF)),
    )

private class CompletionListSurface(
    private val list: JList<IntellijCompletionListItem>,
    private val scrollPane: JBScrollPane,
    private val footer: JPanel,
    private val appearanceChanged: () -> Unit,
) : JPanel(BorderLayout()) {
    private var appearanceParent: Component? = null
    private val appearanceListener =
        PropertyChangeListener { event ->
            if (event.propertyName == "background" || event.propertyName == "foreground" || event.propertyName == "font") {
                applyParentAppearance()
            }
        }
    private val hierarchyListener =
        HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L) synchronizeAppearance()
        }

    init {
        isOpaque = false
        isFocusable = false
        border = JBUI.Borders.empty(JBUI.scale(SURFACE_INSET))
        add(scrollPane, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        addHierarchyListener(hierarchyListener)
        getAccessibleContext().accessibleName = KetraTermBundle.message("completion.list.accessibleName")
    }

    fun synchronizeAppearance() {
        val currentParent = parent
        if (appearanceParent !== currentParent) {
            appearanceParent?.removePropertyChangeListener(appearanceListener)
            appearanceParent = currentParent
            currentParent?.addPropertyChangeListener(appearanceListener)
        }
        applyParentAppearance()
    }

    fun close() {
        appearanceParent?.removePropertyChangeListener(appearanceListener)
        appearanceParent = null
        removeHierarchyListener(hierarchyListener)
        removeAll()
    }

    private fun applyParentAppearance() {
        val source = appearanceParent ?: return
        background = source.background
        foreground = source.foreground
        font = source.font
        list.background = source.background
        list.foreground = source.foreground
        list.font = source.font
        scrollPane.background = source.background
        scrollPane.foreground = source.foreground
        scrollPane.viewport.background = source.background
        footer.background = source.background
        footer.foreground = source.foreground
        appearanceChanged()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val graphics2D = graphics.create() as Graphics2D
        try {
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (width <= 0 || height <= 0) return
            val arc = JBUI.scale(CORNER_ARC)
            graphics2D.color = background ?: JBColor.namedColor("CompletionPopup.background", UIUtil.getListBackground())
            graphics2D.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            graphics2D.color = JBColor.border()
            graphics2D.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            graphics2D.dispose()
        }
    }

    private companion object {
        private const val SURFACE_INSET = 4
        private const val CORNER_ARC = 10
    }
}

private fun completionFooter(
    rangeLabel: JBLabel,
    hintsLabel: JBLabel,
): JPanel =
    JPanel(BorderLayout(JBUI.scale(FOOTER_HORIZONTAL_GAP), 0)).apply {
        isOpaque = false
        isFocusable = false
        border =
            CompoundBorder(
                MatteBorder(JBUI.scale(1), 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(
                    JBUI.scale(FOOTER_VERTICAL_INSET),
                    JBUI.scale(FOOTER_HORIZONTAL_INSET),
                ),
            )
        rangeLabel.isVisible = false
        rangeLabel.accessibleContext.accessibleName = KetraTermBundle.message("completion.list.range.accessibleName")
        hintsLabel.accessibleContext.accessibleName = KetraTermBundle.message("completion.list.hints.accessibleName")
        add(rangeLabel, BorderLayout.WEST)
        add(hintsLabel, BorderLayout.EAST)
        accessibleContext.accessibleName = KetraTermBundle.message("completion.list.footer.accessibleName")
    }

private fun completionScrollPane(list: JList<IntellijCompletionListItem>): JBScrollPane =
    JBScrollPane(list).apply {
        isOpaque = false
        isFocusable = false
        isWheelScrollingEnabled = false
        border = JBUI.Borders.empty()
        viewportBorder = JBUI.Borders.empty()
        viewport.isOpaque = false
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
    }

private const val MAXIMUM_DISPLAY_CODE_UNITS = 4096
private const val MAXIMUM_DISPLAY_GRAPHEMES = 4096
private const val MAXIMUM_DETAIL_CODE_UNITS = 1024
private const val MAXIMUM_DETAIL_GRAPHEMES = 1024
private const val MAXIMUM_SOURCE_CODE_UNITS = 512
private const val MAXIMUM_SOURCE_GRAPHEMES = 24
private const val ELLIPSIS = "…"
private const val REPLACEMENT_CHARACTER = '\uFFFD'
private const val ARABIC_LETTER_MARK = 0x061C
private const val LEFT_TO_RIGHT_MARK = 0x200E
private const val RIGHT_TO_LEFT_MARK = 0x200F
private const val BIDI_EMBEDDING_START = 0x202A
private const val BIDI_EMBEDDING_END = 0x202E
private const val BIDI_ISOLATE_START = 0x2066
private const val BIDI_ISOLATE_END = 0x2069
private const val FOOTER_HORIZONTAL_GAP = 12
private const val FOOTER_HORIZONTAL_INSET = 6
private const val FOOTER_VERTICAL_INSET = 3
private val EXTENDED_GRAPHEME_CLUSTER: Pattern = Pattern.compile("\\X")

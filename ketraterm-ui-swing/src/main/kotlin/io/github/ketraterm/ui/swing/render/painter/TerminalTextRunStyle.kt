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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.SwingColors
import io.github.ketraterm.ui.swing.render.isTextHidden
import io.github.ketraterm.ui.swing.render.terminalFontStyle

/**
 * Reusable resolved style for one text run, shared by ASCII, complex-cell and shaped painters.
 *
 * The owning text painter configures the hover interval and blink phase once per row, then
 * calls [begin] before scanning or painting each run. [matches] leaves that run's style intact.
 * All state is painter-local; no style objects or hover ranges are allocated during painting.
 * Like the painter's other scratch buffers, this instance is confined to one paint invocation
 * at a time.
 */
internal class TerminalTextRunStyle {
    var attr: Long = 0L
        private set
    var extraAttr: Long = 0L
        private set
    var hyperlinkId: Int = 0
        private set
    var hovered: Boolean = false
        private set
    var foreground: Int = 0
        private set
    var fontStyle: Int = 0
        private set
    var textHidden: Boolean = false
        private set

    private var decoration = 0L
    private var textBlinkVisible = true
    private var hyperlinkIds = IntArray(0)
    private var hoveredHyperlinkId = 0
    private var hoverStartColumn = 0
    private var hoverEndColumn = 0
    private var activationHover = false
    private var activationForeground = 0

    fun configureRow(
        row: Int,
        textBlinkVisible: Boolean,
        hyperlinkIds: IntArray,
        hoveredHyperlinkId: Int,
        hoveredHyperlinkStartRow: Int,
        hoveredHyperlinkStartColumn: Int,
        hoveredHyperlinkEndRow: Int,
        hoveredHyperlinkEndColumn: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ) {
        this.textBlinkVisible = textBlinkVisible
        this.hyperlinkIds = hyperlinkIds
        this.hoveredHyperlinkId =
            if (row >= hoveredHyperlinkStartRow && row <= hoveredHyperlinkEndRow) hoveredHyperlinkId else 0
        hoverStartColumn = if (row == hoveredHyperlinkStartRow) hoveredHyperlinkStartColumn else 0
        hoverEndColumn = if (row == hoveredHyperlinkEndRow) hoveredHyperlinkEndColumn else Int.MAX_VALUE
        activationHover = hyperlinkActivationHover
        activationForeground = hyperlinkActivationForeground
    }

    fun begin(
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        rowOffset: Int,
        column: Int,
    ) {
        val index = rowOffset + column
        attr = cache.attrWords[index]
        extraAttr = cache.extraAttrWords[index]
        hyperlinkId = hyperlinkIds[index]
        hovered = isHovered(hyperlinkId, column)
        foreground = effectiveForeground(palette, attr, cache.codeWords[index], hovered)
        fontStyle = terminalFontStyle(attr)
        decoration = decorationKey(attr, extraAttr)
        textHidden = isTextHidden(attr, textBlinkVisible)
    }

    fun matches(
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        rowOffset: Int,
        column: Int,
    ): Boolean {
        val index = rowOffset + column
        val candidateAttr = cache.attrWords[index]
        if (
            isTextHidden(candidateAttr, textBlinkVisible) != textHidden ||
            terminalFontStyle(candidateAttr) != fontStyle ||
            decorationKey(candidateAttr, cache.extraAttrWords[index]) != decoration ||
            hyperlinkIds[index] != hyperlinkId
        ) {
            return false
        }

        val candidateHovered = isHovered(hyperlinkId, column)
        return candidateHovered == hovered &&
            effectiveForeground(palette, candidateAttr, cache.codeWords[index], candidateHovered) == foreground
    }

    private fun isHovered(
        hyperlinkId: Int,
        column: Int,
    ): Boolean =
        hyperlinkId != 0 &&
            hyperlinkId == hoveredHyperlinkId &&
            column >= hoverStartColumn &&
            column < hoverEndColumn

    private fun effectiveForeground(
        palette: TerminalColorPalette,
        attr: Long,
        codePoint: Int,
        hovered: Boolean,
    ): Int = if (hovered && activationHover) activationForeground else SwingColors.foreground(palette, attr, codePoint)

    private fun decorationKey(
        attr: Long,
        extraAttr: Long,
    ): Long =
        TerminalRenderAttrs.underlineStyle(attr).toLong() or
            (if (TerminalRenderAttrs.isStrikethrough(attr)) STRIKETHROUGH_KEY else 0L) or
            (extraAttr shl EXTRA_ATTR_KEY_SHIFT)

    private companion object {
        private const val STRIKETHROUGH_KEY = 1L shl 8
        private const val EXTRA_ATTR_KEY_SHIFT = 9
    }
}

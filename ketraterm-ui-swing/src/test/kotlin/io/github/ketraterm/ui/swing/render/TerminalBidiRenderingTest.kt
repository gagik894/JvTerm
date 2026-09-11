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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.painter.TerminalSelectionPainter
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalBidiRenderingTest {
    private val metrics = SwingMetrics(12, 24, 16, 18, 10, 0, 1)
    private val settings =
        defaultTestSettings(
            foreground = TEST_WHITE,
            background = TEST_BLACK,
        ).copy(shellIntegrationDecorationGutterWidth = 0)
    private val colors = intArrayOf(TEST_RED, TEST_GREEN, TEST_BLUE)

    @Test
    fun `rtl background follows its glyph and underline`() {
        val image = paint()
        for (visual in 0..2) {
            val color = colors[2 - visual]
            assertEquals(color, image.getRGB(visual * metrics.cellWidth + 1, metrics.underlineY))
            assertEquals(color, image.getRGB(visual * metrics.cellWidth + 1, 1))
        }
    }

    @ParameterizedTest
    @CsvSource("BLOCK, false", "BLOCK, true", "UNDERLINE, false", "UNDERLINE, true", "BAR, false", "BAR, true")
    fun `cursor stays over its logical rtl cell`(
        shape: TerminalRenderCursorShape,
        antialiased: Boolean,
    ) {
        val image =
            paint(
                cursor = TerminalRenderCursor(0, 0, true, false, shape, 1),
                textAntialiasing =
                    if (antialiased) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
            )
        val x = 2 * metrics.cellWidth
        val y = if (shape == TerminalRenderCursorShape.UNDERLINE) metrics.cellHeight - 1 else 0
        assertEquals(settings.palette.cursorBackground, image.getRGB(x, y))
        assertEquals(TEST_BLUE, image.getRGB(0, y))
        if (shape == TerminalRenderCursorShape.BLOCK) {
            // The opaque cursor fill replaces the row's text and underline before its glyph is repainted.
            val containsCursorGlyph =
                (0 until metrics.cellHeight).any { row ->
                    (x until x + metrics.cellWidth).any { column ->
                        image.getRGB(column, row) != settings.palette.cursorBackground
                    }
                }
            assertTrue(containsCursorGlyph, "The block cursor must repaint visible glyph coverage inside its logical cell")
        }
    }

    @Test
    fun `selection overlay follows logical rtl cells`() {
        val image = paint(selection = CellSelection(0, 0, 1, 0))
        assertEquals(TEST_BLUE, image.getRGB(1, 1))
        assertTrue(image.getRGB(metrics.cellWidth * 2 + 1, 1) != TEST_RED)
    }

    @Test
    fun `block selection overlay stays at its visual columns`() {
        val image = paint(selection = CellSelection(0, 0, 1, 0, isBlock = true))

        assertTrue(image.getRGB(1, 1) != TEST_BLUE)
        assertEquals(TEST_GREEN, image.getRGB(metrics.cellWidth + 1, 1))
        assertEquals(TEST_RED, image.getRGB(metrics.cellWidth * 2 + 1, 1))
    }

    @Test
    fun `mixed bidi block overlay is contiguous while linear overlay follows logical cells`() {
        val cache = renderCache(TestRenderFrame.text("AB אבג"))
        val selection = CellSelection(1, 0, 4, 0, isBlock = true)

        assertContentEquals(booleanArrayOf(false, true, true, true, false, false), paintedSelectionColumns(cache, selection))
        assertContentEquals(
            booleanArrayOf(false, true, true, false, false, true),
            paintedSelectionColumns(cache, selection.copy(isBlock = false)),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `block overlay includes both visual halves of an intersected wide cell`(selectedColumn: Int) {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'א'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(
                                codeWord = '字'.code,
                                flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                            ),
                            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                            TestCell(codeWord = 'ב'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                        ),
                    ),
                ),
            )
        val selection = CellSelection(selectedColumn, 0, selectedColumn + 1, 0, isBlock = true)

        assertContentEquals(booleanArrayOf(false, true, true, false), paintedSelectionColumns(cache, selection))
    }

    @Test
    fun `search overlay follows logical rtl cells`() {
        val highlights = TerminalSearchViewportHighlights()
        highlights.reset(1)
        highlights.add(0, 0, 1, active = true)
        highlights.finish()
        val image = paint(highlights = highlights)
        assertEquals(TEST_BLUE, image.getRGB(1, 1))
        assertTrue(image.getRGB(metrics.cellWidth * 2 + 1, 1) != TEST_RED)
    }

    private fun paintedSelectionColumns(
        cache: TerminalRenderCache,
        selection: CellSelection,
    ): BooleanArray {
        val image = BufferedImage(metrics.cellWidth * cache.columns, metrics.cellHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            TerminalSelectionPainter(AwtColorCache()).paint(
                g,
                cache,
                metrics,
                row = 0,
                selection,
                selectionBackground = TEST_WHITE,
                palette = settings.palette,
                bidi = TerminalBidiLayout().row(cache, 0),
            )
        } finally {
            g.dispose()
        }
        return BooleanArray(cache.columns) { column -> image.getRGB(column * metrics.cellWidth + 1, 1) != 0 }
    }

    private fun paint(
        cursor: TerminalRenderCursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1),
        selection: CellSelection? = null,
        highlights: TerminalSearchViewportHighlights? = null,
        textAntialiasing: Any = settings.textAntialiasing,
    ): BufferedImage {
        val cache =
            renderCache(
                object : TestRenderFrame(
                    arrayOf(
                        Array(3) { column ->
                            TestCell(
                                codeWord = 0x05D0 + column,
                                flags = TerminalRenderCellFlags.CODEPOINT,
                                attr =
                                    TerminalRenderAttrs.pack(
                                        backgroundKind = TerminalRenderColorKind.RGB,
                                        backgroundValue = colors[column] and 0xFFFFFF,
                                        underlineStyle = TerminalRenderUnderline.SINGLE,
                                    ),
                                extraAttr =
                                    TerminalRenderExtraAttrs.pack(
                                        underlineColorKind = TerminalRenderColorKind.RGB,
                                        underlineColorValue = colors[column] and 0xFFFFFF,
                                    ),
                            )
                        },
                    ),
                    cursorValue = cursor,
                ) {
                    override val palette = settings.palette
                },
            )
        val image = BufferedImage(metrics.cellWidth * 3, metrics.cellHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            GridPainter().paint(
                g,
                cache,
                settings.copy(textAntialiasing = textAntialiasing),
                metrics,
                image.width,
                image.height,
                cursorBlinkVisible = true,
                selection = selection,
                searchHighlights = highlights,
            )
        } finally {
            g.dispose()
        }
        return image
    }
}

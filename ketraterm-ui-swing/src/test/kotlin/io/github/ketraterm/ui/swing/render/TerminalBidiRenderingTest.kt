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
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.awt.image.BufferedImage
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
    @EnumSource(TerminalRenderCursorShape::class)
    fun `cursor stays over its logical rtl cell`(shape: TerminalRenderCursorShape) {
        val image = paint(cursor = TerminalRenderCursor(0, 0, true, false, shape, 1))
        val x = 2 * metrics.cellWidth
        val y = if (shape == TerminalRenderCursorShape.UNDERLINE) metrics.cellHeight - 1 else 0
        assertEquals(settings.palette.cursorBackground, image.getRGB(x, y))
        assertEquals(TEST_BLUE, image.getRGB(0, y))
        if (shape == TerminalRenderCursorShape.BLOCK) {
            assertTrue(image.containsColorInRange(settings.palette.cursorForeground, x, x + metrics.cellWidth))
        }
    }

    @Test
    fun `selection overlay follows logical rtl cells`() {
        val image = paint(selection = CellSelection(0, 0, 1, 0))
        assertEquals(TEST_BLUE, image.getRGB(1, 1))
        assertTrue(image.getRGB(metrics.cellWidth * 2 + 1, 1) != TEST_RED)
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

    private fun paint(
        cursor: TerminalRenderCursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1),
        selection: CellSelection? = null,
        highlights: TerminalSearchViewportHighlights? = null,
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
                settings,
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

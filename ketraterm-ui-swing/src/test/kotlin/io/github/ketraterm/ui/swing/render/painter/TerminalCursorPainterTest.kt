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

import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderCursor
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.render.primitives.TerminalPlatformEmojiPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalCursorPainterTest {
    @Nested
    inner class Visibility {
        @Test
        fun `does not paint invisible cursor`() {
            val fixture = fixture(cursor(visible = false))

            fixture.paint()

            assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }

        @Test
        fun `does not paint blinking cursor when blink is hidden`() {
            val fixture = fixture(cursor(blinking = true))

            fixture.paint(cursorBlinkVisible = false)

            assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }

        @Test
        fun `does not paint cursor when presentation is disabled`() {
            val fixture = fixture(cursor(blinking = false))

            fixture.paint(cursorVisible = false)

            assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }

        @Test
        fun `ignores cursor outside cache bounds`() {
            val fixture = fixture(cursor(column = 2))

            fixture.paint()

            assertTrue(!fixture.image.containsColor(TEST_BLUE, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }
    }

    @Nested
    inner class Shapes {
        @ParameterizedTest
        @ValueSource(ints = [0x41, 0xE9, 0x2588])
        fun `concealed block cursor paints only its background in either blink phase`(codePoint: Int) {
            for (textBlinkVisible in listOf(false, true)) {
                val fixture = fixture(cursor(shape = TerminalRenderCursorShape.BLOCK))
                fixture.cache.codeWords[0] = codePoint
                fixture.cache.attrWords[0] = TerminalRenderAttrs.pack(invisible = true, blink = true, inverse = true)

                try {
                    fixture.paint(textBlinkVisible = textBlinkVisible)

                    for (y in 0 until fixture.metrics.cellHeight) {
                        for (x in 0 until fixture.metrics.cellWidth) {
                            assertEquals(TEST_BLUE, fixture.image.getRGB(x, y), "Concealed cursor glyph at ($x, $y)")
                        }
                    }
                    assertEquals(TEST_BLACK, fixture.image.getRGB(fixture.metrics.cellWidth, 0))
                } finally {
                    fixture.g.dispose()
                }
            }
        }

        @Test
        fun `block cursor fills full cell and redraws foreground`() {
            val fixture = fixture(cursor(shape = TerminalRenderCursorShape.BLOCK))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, 1))
            assertTrue(fixture.image.containsColor(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellHeight))
        }

        @Test
        fun `block cursor over wide leader fills both cells`() {
            val fixture = wideFixture(cursor(column = 0, shape = TerminalRenderCursorShape.BLOCK))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, 1))
            assertEquals(TEST_BLUE, fixture.image.getRGB(fixture.metrics.cellWidth + 1, 1))
        }

        @Test
        fun `block cursor over wide trailing spacer fills owner pair`() {
            val fixture = wideFixture(cursor(column = 1, shape = TerminalRenderCursorShape.BLOCK))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, 1))
            assertEquals(TEST_BLUE, fixture.image.getRGB(fixture.metrics.cellWidth + 1, 1))
        }

        @Test
        fun `block cursor redraws wide emoji foreground across both cells`() {
            val fixture = wideEmojiFixture(cursor(column = 0, shape = TerminalRenderCursorShape.BLOCK))

            fixture.paint()

            assertTrue(
                fixture.image.containsColorInRange(
                    TEST_RED,
                    xStart = fixture.metrics.cellWidth,
                    xEnd = fixture.metrics.cellWidth * 2,
                ),
            )
        }

        @Test
        fun `underline cursor fills bottom stroke only`() {
            val fixture = fixture(cursor(shape = TerminalRenderCursorShape.UNDERLINE))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, fixture.metrics.cellHeight - 1))
            assertEquals(TEST_BLACK, fixture.image.getRGB(1, 1))
        }

        @Test
        fun `underline cursor over wide leader spans both cells`() {
            val fixture = wideFixture(cursor(column = 0, shape = TerminalRenderCursorShape.UNDERLINE))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(1, fixture.metrics.cellHeight - 1))
            assertEquals(TEST_BLUE, fixture.image.getRGB(fixture.metrics.cellWidth + 1, fixture.metrics.cellHeight - 1))
            assertEquals(TEST_BLACK, fixture.image.getRGB(fixture.metrics.cellWidth + 1, 1))
        }

        @Test
        fun `bar cursor fills leading stroke`() {
            val fixture = fixture(cursor(shape = TerminalRenderCursorShape.BAR))

            fixture.paint()

            assertEquals(TEST_BLUE, fixture.image.getRGB(0, 1))
            assertEquals(TEST_BLACK, fixture.image.getRGB(fixture.metrics.cellWidth - 1, 1))
        }
    }

    private data class Fixture(
        val image: BufferedImage,
        val g: Graphics2D,
        val settings: SwingSettings,
        val metrics: SwingMetrics,
        val cache: TerminalRenderCache,
        val painter: TerminalCursorPainter,
        val textPainter: TerminalTextPainter,
    ) {
        fun paint(
            cursorBlinkVisible: Boolean = true,
            textBlinkVisible: Boolean = true,
            cursorVisible: Boolean = true,
        ) {
            textPainter.updateSettings(settings)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
            painter.paint(
                g,
                cache,
                settings.palette,
                metrics,
                cursorBlinkVisible,
                textBlinkVisible,
                g.fontRenderContext,
                cursorVisible = cursorVisible,
            )
        }
    }

    private fun fixture(cursor: TerminalRenderCursor): Fixture {
        val image = BufferedImage(50, 30, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings(foreground = TEST_WHITE, background = TEST_BLACK)
        val metrics = testMetrics(image, settings)
        val colorCache = AwtColorCache()
        val textPainter = TerminalTextPainter(colorCache, TerminalDecorationPainter(colorCache))
        val cache = renderCache(copyWithCursor(cursor))
        return Fixture(
            image = image,
            g =
                image.createGraphics().also {
                    it.color = colorCache.color(TEST_BLACK)
                    it.fillRect(0, 0, image.width, image.height)
                },
            settings = settings,
            metrics = metrics,
            cache = cache,
            painter = TerminalCursorPainter(colorCache, textPainter),
            textPainter = textPainter,
        )
    }

    private fun wideFixture(cursor: TerminalRenderCursor): Fixture {
        val image = BufferedImage(50, 30, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings(foreground = TEST_WHITE, background = TEST_BLACK)
        val metrics = testMetrics(image, settings)
        val colorCache = AwtColorCache()
        val textPainter = TerminalTextPainter(colorCache, TerminalDecorationPainter(colorCache))
        val frame =
            TestRenderFrame(
                cells =
                    arrayOf(
                        arrayOf(
                            TestCell(
                                codeWord = 0x4F60,
                                flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                            ),
                            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                        ),
                    ),
                cursorValue = cursor,
            )
        val cache = renderCache(frame)
        return Fixture(
            image = image,
            g =
                image.createGraphics().also {
                    it.color = colorCache.color(TEST_BLACK)
                    it.fillRect(0, 0, image.width, image.height)
                },
            settings = settings,
            metrics = metrics,
            cache = cache,
            painter = TerminalCursorPainter(colorCache, textPainter),
            textPainter = textPainter,
        )
    }

    private fun wideEmojiFixture(cursor: TerminalRenderCursor): Fixture {
        val metrics =
            SwingMetrics(
                cellWidth = 10,
                cellHeight = 20,
                baseline = 14,
                underlineY = 15,
                strikethroughY = 8,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val image = BufferedImage(metrics.cellWidth * 3, metrics.cellHeight, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings(foreground = TEST_WHITE, background = TEST_BLACK)
        val colorCache = AwtColorCache()
        val textPainter =
            TerminalTextPainter(
                colorCache = colorCache,
                decorationPainter = TerminalDecorationPainter(colorCache),
                platformEmojiPainter = TerminalPlatformEmojiPainter(FakeEmojiRasterizer),
            )
        val frame =
            TestRenderFrame(
                cells =
                    arrayOf(
                        arrayOf(
                            TestCell(
                                codeWord = 0x1F600,
                                flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                            ),
                            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                        ),
                    ),
                cursorValue = cursor,
            )
        val cache = renderCache(frame)
        return Fixture(
            image = image,
            g =
                image.createGraphics().also {
                    it.color = colorCache.color(TEST_BLACK)
                    it.fillRect(0, 0, image.width, image.height)
                },
            settings = settings,
            metrics = metrics,
            cache = cache,
            painter = TerminalCursorPainter(colorCache, textPainter),
            textPainter = textPainter,
        )
    }

    private object FakeEmojiRasterizer : TerminalPlatformEmojiRasterizer {
        override val available: Boolean = true

        override fun rasterize(
            text: String,
            pixelSize: Int,
        ): BufferedImage {
            val image = BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB)
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    image.setRGB(x, y, TEST_RED)
                    x++
                }
                y++
            }
            return image
        }
    }

    private fun cursor(
        column: Int = 0,
        row: Int = 0,
        visible: Boolean = true,
        blinking: Boolean = false,
        shape: TerminalRenderCursorShape = TerminalRenderCursorShape.BLOCK,
    ): TerminalRenderCursor =
        TerminalRenderCursor(
            column = column,
            row = row,
            visible = visible,
            blinking = blinking,
            shape = shape,
            generation = 1,
        )

    private fun copyWithCursor(cursor: TerminalRenderCursor): TestRenderFrame =
        TestRenderFrame(
            cells = arrayOf(arrayOf(TestCell(codeWord = 'A'.code, flags = TerminalRenderCellFlags.CODEPOINT))),
            cursorValue = cursor,
        )
}

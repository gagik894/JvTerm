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
import io.github.ketraterm.render.api.TerminalRenderColorKind
import io.github.ketraterm.render.api.TerminalRenderExtraAttrs
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalDecorationPainterTest {
    @ParameterizedTest
    @CsvSource("7, false", "7, true", "-1, false", "-1, true")
    fun `only OSC8 links underline at rest while all links underline on hover`(
        id: Int,
        hovered: Boolean,
    ) {
        val fixture = fixture()
        try {
            val cache = renderCache(TestRenderFrame.text(" "))
            cache.hyperlinkIds[0] = id
            val style = TerminalTextRunStyle()
            style.configureRow(0, true, cache.hyperlinkIds, if (hovered) id else 0, 0, 0, 0, 1, false, TEST_BLUE)
            style.begin(cache, cache.palette, 0, 0)
            fixture.painter.paintTextRun(fixture.g, cache.palette, style, 0, 1, 0, fixture.metrics)
            val expectedColor = if (id > 0 || hovered) cache.palette.defaultForeground else 0
            assertEquals(expectedColor, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertEquals(if (hovered) expectedColor else 0, fixture.image.getRGB(1, fixture.metrics.underlineY))
        } finally {
            fixture.g.dispose()
        }
    }

    @Test
    fun `hyperlink styling preserves an application supplied colored double underline`() {
        val fixture = fixture()
        try {
            val cache = renderCache(TestRenderFrame.text(" "))
            cache.hyperlinkIds[0] = 7
            cache.attrWords[0] = TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.DOUBLE)
            cache.extraAttrWords[0] =
                TerminalRenderExtraAttrs.pack(
                    underlineColorKind = TerminalRenderColorKind.RGB,
                    underlineColorValue = 0x00FF00,
                )
            val style = TerminalTextRunStyle()
            style.configureRow(0, true, cache.hyperlinkIds, 7, 0, 0, 0, 1, true, TEST_BLUE)
            style.begin(cache, cache.palette, 0, 0)
            fixture.painter.paintTextRun(fixture.g, cache.palette, style, 0, 1, 0, fixture.metrics)
            assertEquals(TEST_GREEN, fixture.image.getRGB(1, fixture.metrics.underlineY))
            assertEquals(0, fixture.image.getRGB(1, fixture.metrics.underlineY + 1))
            assertEquals(TEST_GREEN, fixture.image.getRGB(1, fixture.metrics.underlineY + 2))
        } finally {
            fixture.g.dispose()
        }
    }

    @Test
    fun `dotted underline stays continuous across style run splits and preserves the graphics stroke`() {
        val whole = fixture(100)
        val split = fixture(100)
        try {
            val previousStroke = split.g.stroke
            whole.painter.paintHyperlink(whole.g, TEST_RED, 0, 5, 0, whole.metrics, false)
            for (column in 0 until 5) {
                split.painter.paintHyperlink(split.g, TEST_RED, column, column + 1, 0, split.metrics, false)
            }
            assertEquals(previousStroke, split.g.stroke)
            for (x in 0 until whole.image.width) {
                assertEquals(whole.image.getRGB(x, whole.metrics.underlineY), split.image.getRGB(x, split.metrics.underlineY), "pixel $x")
            }
        } finally {
            whole.g.dispose()
            split.g.dispose()
        }
    }

    @Nested
    inner class Underline {
        @Test
        fun `single underline uses foreground by default`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                TerminalRenderExtraAttrs.DEFAULT,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY))
        }

        @Test
        fun `underline color can come from extra attributes`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                TerminalRenderExtraAttrs.pack(
                    underlineColorKind = TerminalRenderColorKind.RGB,
                    underlineColorValue = 0x00FF00,
                ),
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_GREEN, fixture.image.getRGB(1, fixture.metrics.underlineY))
        }

        @Test
        fun `double underline paints two rows`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.DOUBLE),
                TerminalRenderExtraAttrs.DEFAULT,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(1, minOf(fixture.metrics.cellHeight - 1, fixture.metrics.underlineY + 2)))
        }
    }

    @Nested
    inner class OtherDecorations {
        @Test
        fun `strikethrough paints at strikethrough metric`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(strikethrough = true),
                TerminalRenderExtraAttrs.DEFAULT,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.strikethroughY))
        }

        @Test
        fun `overline paints at overline metric`() {
            val fixture = fixture()

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.DEFAULT,
                TerminalRenderExtraAttrs.pack(overline = true),
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.overlineY))
        }

        @Test
        fun `wide spans paint through every covered cell`() {
            val fixture = fixture(width = 80)

            fixture.painter.paint(
                fixture.g,
                fixture.settings.palette,
                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                TerminalRenderExtraAttrs.DEFAULT,
                TEST_RED,
                startColumn = 0,
                endColumn = 2,
                row = 0,
                metrics = fixture.metrics,
            )

            assertTrue(
                fixture.image.containsColorInRange(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellWidth * 2),
            )
        }
    }

    @Nested
    inner class HyperlinkDecorations {
        @Test
        fun `OSC8 underline is dotted when not hovered`() {
            val fixture = fixture()

            fixture.painter.paintHyperlink(
                fixture.g,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
                hovered = false,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertEquals(0, fixture.image.getRGB(1, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(3, fixture.metrics.underlineY))
        }

        @Test
        fun `hovered hyperlink underline is thicker`() {
            val fixture = fixture()

            fixture.painter.paintHyperlink(
                fixture.g,
                TEST_RED,
                startColumn = 0,
                endColumn = 1,
                row = 0,
                metrics = fixture.metrics,
                hovered = true,
            )

            assertEquals(TEST_RED, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(0, fixture.metrics.underlineY + 1))
        }
    }

    private data class Fixture(
        val image: BufferedImage,
        val g: Graphics2D,
        val settings: SwingSettings,
        val metrics: SwingMetrics,
        val painter: TerminalDecorationPainter,
    )

    private fun fixture(width: Int = 40): Fixture {
        val image = BufferedImage(width, 30, BufferedImage.TYPE_INT_ARGB)
        val settings = defaultTestSettings()
        return Fixture(
            image = image,
            g = image.createGraphics(),
            settings = settings,
            metrics = testMetrics(image, settings),
            painter = TerminalDecorationPainter(AwtColorCache()),
        )
    }
}

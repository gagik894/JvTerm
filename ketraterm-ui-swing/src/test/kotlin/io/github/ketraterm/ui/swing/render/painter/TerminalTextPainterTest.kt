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

import com.sun.management.ThreadMXBean
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.render.primitives.TerminalPlatformEmojiPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.*
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the text rendering pipeline, ensuring that fast-path ASCII runs,
 * complex-shaped text, and wide-cell grapheme clusters are painted accurately
 * according to the terminal's rigid column grid.
 */
class TerminalTextPainterTest {
    @ParameterizedTest
    @CsvSource("ABC, אבג", "אבג, ABC", "אבA, Aאב")
    fun freshSourceDoesNotReuseOldBidiClassification(
        previousText: String,
        replacementText: String,
    ) {
        val reused = fixture()
        val fresh = fixture()
        val previous = renderCache(TestRenderFrame.text(previousText))
        val replacement = renderCache(TestRenderFrame.text(replacementText))
        assertEquals(previous.columns, replacement.columns)
        assertEquals(previous.rows, replacement.rows)
        assertEquals(previous.frameGeneration, replacement.frameGeneration)
        assertEquals(previous.structureGeneration, replacement.structureGeneration)
        assertContentEquals(previous.lineIds, replacement.lineIds)
        assertContentEquals(previous.lineGenerations, replacement.lineGenerations)
        try {
            reused.paintRow(previous)
            reused.g.composite = AlphaComposite.Clear
            reused.g.fillRect(0, 0, reused.image.width, reused.image.height)
            reused.g.composite = AlphaComposite.SrcOver

            reused.paintRow(replacement)
            fresh.paintRow(replacement)

            assertContentEquals(
                fresh.image.getRGB(0, 0, fresh.image.width, fresh.image.height, null, 0, fresh.image.width),
                reused.image.getRGB(0, 0, reused.image.width, reused.image.height, null, 0, reused.image.width),
                "Replacement source $replacementText retained bidi state from $previousText",
            )
        } finally {
            reused.g.dispose()
            fresh.g.dispose()
        }
    }

    @Test
    fun `rtl punctuation segment uses the row direction instead of inferring ltr`() {
        val actual = fixture()
        val expected = fixture()
        try {
            actual.paintRow(renderCache(TestRenderFrame.text("\u05D0?!")))
            expected.paintRow(renderCache(TestRenderFrame.text("!?")))
            for (y in 0 until actual.metrics.cellHeight) {
                for (x in 0 until actual.metrics.cellWidth * 2) {
                    assertEquals(expected.image.getRGB(x, y), actual.image.getRGB(x, y), "Punctuation order at ($x,$y)")
                }
            }
        } finally {
            actual.g.dispose()
            expected.g.dispose()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["AAA", "ééé", "\u0915\u0915\u0915", "\u05D0\u05D0\u05D0"])
    fun `hover underline stays within its span without an activation color override`(text: String) {
        val fixture = fixture()
        val cache = renderCache(TestRenderFrame.text(text))
        cache.hyperlinkIds.fill(7)
        try {
            fixture.paintRow(
                cache,
                hoveredHyperlinkId = 7,
                hoveredHyperlinkStartColumn = 1,
                hoveredHyperlinkEndColumn = 2,
                hoveredHyperlinkEndRow = 0,
            )

            val secondUnderlineY = fixture.metrics.underlineY + 1
            for (column in 0..2) {
                val x = column * fixture.metrics.cellWidth
                assertEquals(TEST_RED, fixture.image.getRGB(x, fixture.metrics.underlineY))
                assertEquals(if (column == 1) TEST_RED else 0, fixture.image.getRGB(x, secondUnderlineY))
            }
        } finally {
            fixture.g.dispose()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["AAA", "\u0915\u0915\u0915", "\u05D0\u05D0\u05D0"])
    fun `reusing a painter across rows and hover phases matches a fresh painter`(text: String) {
        val reused = fixture()
        val cache =
            renderCache(
                TestRenderFrame(
                    Array(2) {
                        Array(3) { column ->
                            TestCell(
                                codeWord = text[column].code,
                                flags = TerminalRenderCellFlags.CODEPOINT,
                                attr = TerminalRenderAttrs.pack(blink = column == 1),
                            )
                        }
                    },
                ),
            )
        val discoveredHyperlinks = IntArray(cache.flags.size) { 7 }
        try {
            for (phase in 0..3) {
                for (row in 0..1) {
                    val fresh = fixture()
                    try {
                        reused.g.composite = AlphaComposite.Clear
                        reused.g.fillRect(0, 0, reused.image.width, reused.image.height)
                        reused.g.composite = AlphaComposite.SrcOver
                        for (target in listOf(reused, fresh)) {
                            target.paintRow(
                                cache,
                                row = row,
                                textBlinkVisible = phase % 2 == 0,
                                hyperlinkIds = if (phase < 3) discoveredHyperlinks else cache.hyperlinkIds,
                                hoveredHyperlinkId = if (phase < 2) 7 else 0,
                                hoveredHyperlinkStartRow = 0,
                                hoveredHyperlinkStartColumn = 1,
                                hoveredHyperlinkEndRow = 1,
                                hoveredHyperlinkEndColumn = 2,
                                hyperlinkActivationHover = phase == 0,
                            )
                        }
                        for (y in 0 until reused.image.height) {
                            for (x in 0 until reused.image.width) {
                                assertEquals(
                                    fresh.image.getRGB(x, y),
                                    reused.image.getRGB(x, y),
                                    "Stale row style at phase=$phase row=$row ($x,$y)",
                                )
                            }
                        }
                    } finally {
                        fresh.g.dispose()
                    }
                }
            }
        } finally {
            reused.g.dispose()
        }
    }

    @Nested
    inner class ConcealedTextRendering {
        @ParameterizedTest
        @ValueSource(strings = ["AAA", "ééé", "\u2588\u2588\u2588", "\u0915\u0915\u0915", "\u05D0\u05D0\u05D0"])
        fun `conceal splits visible hyperlink runs without painting glyphs or decorations`(text: String) {
            for (activationHover in listOf(false, true)) {
                val fixture = fixture()
                val visible = TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE)
                val hidden = TerminalRenderAttrs.pack(invisible = true, underlineStyle = TerminalRenderUnderline.SINGLE)
                val cache = renderCache(TestRenderFrame.text(text, attrs = longArrayOf(visible, hidden, visible)))
                cache.hyperlinkIds.fill(1)
                cache.extraAttrWords.fill(underlineColor(TEST_GREEN))

                try {
                    fixture.paintRow(cache, hoveredHyperlinkId = 1, hyperlinkActivationHover = activationHover)

                    assertTrue(fixture.image.containsPaintedPixelInRange(0, fixture.metrics.cellWidth, 0, fixture.metrics.cellHeight))
                    assertTrue(
                        !fixture.image.containsPaintedPixelInRange(
                            fixture.metrics.cellWidth,
                            fixture.metrics.cellWidth * 2,
                            0,
                            fixture.metrics.cellHeight,
                        ),
                        "Concealed $text painted foreground with activationHover=$activationHover",
                    )
                    assertTrue(
                        fixture.image.containsPaintedPixelInRange(
                            fixture.metrics.cellWidth * 2,
                            fixture.metrics.cellWidth * 3,
                            0,
                            fixture.metrics.cellHeight,
                        ),
                        "Visible text after conceal must still paint",
                    )
                } finally {
                    fixture.g.dispose()
                }
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `concealed native emoji stays hidden in row and cursor foreground`(cluster: Boolean) {
            for (cursorForeground in listOf(false, true)) {
                var rasterizations = 0
                val rasterizer =
                    object : TerminalPlatformEmojiRasterizer {
                        override val available = true

                        override fun rasterize(
                            text: String,
                            pixelSize: Int,
                        ): BufferedImage {
                            rasterizations++
                            return BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB).apply {
                                for (y in 0 until height) for (x in 0 until width) setRGB(x, y, TEST_RED)
                            }
                        }
                    }
                val fixture = fixture(platformEmojiPainter = TerminalPlatformEmojiPainter(rasterizer))
                val cache =
                    renderCache(
                        TestRenderFrame(
                            arrayOf(
                                arrayOf(
                                    TestCell(
                                        codeWord = ASTRAL_SMILE_CODE_POINT,
                                        flags =
                                            (if (cluster) TerminalRenderCellFlags.CLUSTER else TerminalRenderCellFlags.CODEPOINT) or
                                                TerminalRenderCellFlags.WIDE_LEADING,
                                        attr = TerminalRenderAttrs.pack(invisible = true),
                                        cluster = if (cluster) "\uD83D\uDE42\uFE0F" else null,
                                    ),
                                    TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                                ),
                            ),
                        ),
                    )
                try {
                    for (concealed in listOf(true, false)) {
                        cache.attrWords[0] = TerminalRenderAttrs.pack(invisible = concealed)
                        if (cursorForeground) {
                            fixture.painter.paintCellForeground(
                                fixture.g,
                                cache,
                                fixture.metrics,
                                column = 0,
                                row = 0,
                                columnSpan = 2,
                                foreground = TEST_GREEN,
                                fontRenderContext = fixture.g.fontRenderContext,
                            )
                        } else {
                            fixture.paintRow(cache)
                        }
                        assertEquals(
                            !concealed,
                            fixture.image.containsPaintedPixelInRange(0, fixture.metrics.cellWidth * 2, 0, fixture.metrics.cellHeight),
                            "Native emoji visibility must respect conceal, including cursor foreground",
                        )
                        assertEquals(if (concealed) 0 else 1, rasterizations)
                    }
                } finally {
                    fixture.g.dispose()
                }
            }
        }
    }

    @Nested
    inner class AsciiTextRendering {
        @Test
        fun `paints contiguous ascii run in consecutive terminal cells`() {
            // Arrange
            val fixture = fixture()
            val cache = renderCache(TestRenderFrame.text("ii"))

            // Act
            fixture.paintRow(cache)

            // Assert
            assertTrue(
                fixture.image.containsColorInRange(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellWidth * 2),
                "The second glyph 'i' was not painted in the second terminal column",
            )
        }

        /**
         * "Mismatch path" refers to the fallback triggered when Java2D's natural
         * font advances do not align perfectly with the terminal's rigid cell width
         * (often caused by fractional metrics or kerning).
         * The painter must abandon `drawChars` and use positioned `GlyphVectors`.
         */
        @Test
        fun `ascii mismatch path maintains absolute origin for positioned glyphs`() {
            // Arrange
            val settings = createMismatchSettings()
            val (image, metrics, painter) = createMismatchFixture(settings)
            val g = image.createGraphics()
            val cache = renderCache(TestRenderFrame.text("ii"))

            // Act
            painter.paintRow(g, cache, settings.palette, metrics, row = 0, fontRenderContext = g.fontRenderContext)
            g.dispose()

            // Assert
            assertTrue(image.containsColorInRange(TEST_RED, 0, metrics.cellWidth), "First positioned glyph missing")
            assertTrue(image.containsColorInRange(TEST_RED, metrics.cellWidth, metrics.cellWidth * 2), "Second positioned glyph missing")
        }

        @Test
        fun `ascii mismatch path leaves graphics transform completely unchanged`() {
            // Arrange
            val settings = createMismatchSettings()
            val (image, metrics, painter) = createMismatchFixture(settings)
            val g = image.createGraphics()

            // Introduce a deliberate transform to verify the painter cleans up after itself
            val initialTransform = AffineTransform.getTranslateInstance(3.0, 5.0)
            g.transform = initialTransform
            val cache = renderCache(TestRenderFrame.text("ii"))

            // Act
            painter.paintRow(g, cache, settings.palette, metrics, row = 0, fontRenderContext = g.fontRenderContext)

            // Assert
            assertEquals(initialTransform, g.transform, "Painter modified the AffineTransform without restoring it")
            g.dispose()
        }

        @Test
        fun `ascii mismatch path does not dynamically rescale prefixes when runs grow`() {
            // Arrange
            val settings = createMismatchSettings()

            // Act
            val shortImage = paintSerifAscii(settings, "Wi")
            val longImage = paintSerifAscii(settings, "Wii")
            val metrics = testMetrics(shortImage, settings)

            // Assert
            // The pixels rendered for 'W' in the first cell must remain identical
            // regardless of the string length that follows it.
            assertEquals(
                shortImage.countColorInRange(TEST_RED, 0, metrics.cellWidth, 0, metrics.cellHeight),
                longImage.countColorInRange(TEST_RED, 0, metrics.cellWidth, 0, metrics.cellHeight),
                "Prefix glyph was warped/rescaled due to sub-pixel layout changes in a longer string",
            )
        }

        @Test
        fun `ascii mismatch path correctly spaces out compact kerning runs`() {
            // Arrange
            val settings = createMismatchSettings()

            // Act
            val singleCell = paintSerifAscii(settings, "i")
            val twoCells = paintSerifAscii(settings, "ii")
            val metrics = testMetrics(singleCell, settings)

            // Assert
            // 'i' is a narrow character. If the pipeline falls back to default Java2D string drawing
            // instead of our strict grid glyph-vector positioning, the two 'i's will squish together.
            assertEquals(
                singleCell.countColorInRange(TEST_RED, 0, metrics.cellWidth, 0, metrics.cellHeight),
                twoCells.countColorInRange(TEST_RED, 0, metrics.cellWidth, 0, metrics.cellHeight),
                "The first 'i' bled into the second cell, or the run was not spaced to the terminal grid",
            )
        }

        @Test
        fun `ascii runs are split correctly when text decorations change`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE)

            // Frame with same text and foreground, but different underline colors
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "AB",
                        attrs =
                            longArrayOf(
                                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                            ),
                        extraAttrs =
                            longArrayOf(
                                TerminalRenderExtraAttrs.pack(
                                    underlineColorKind = TerminalRenderColorKind.RGB,
                                    underlineColorValue = 0x00FF00, // Green underline
                                ),
                                TerminalRenderExtraAttrs.pack(
                                    underlineColorKind = TerminalRenderColorKind.RGB,
                                    underlineColorValue = 0x0000FF, // Blue underline
                                ),
                            ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(TEST_GREEN, fixture.image.getRGB(1, fixture.metrics.underlineY), "First cell underline should be green")
            assertEquals(
                TEST_BLUE,
                fixture.image.getRGB(fixture.metrics.cellWidth + 1, fixture.metrics.underlineY),
                "Second cell underline should be blue",
            )
        }

        @Test
        fun `blink visible phase paints blinking ascii text`() {
            val fixture = fixture()
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "A",
                        attrs = longArrayOf(TerminalRenderAttrs.pack(blink = true)),
                    ),
                )

            fixture.paintRow(cache, textBlinkVisible = true)

            assertTrue(
                fixture.image.containsColor(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Blinking ASCII text was not painted during the visible phase",
            )
        }

        @Test
        fun `blink hidden phase suppresses blinking ascii text and decorations`() {
            val fixture = fixture(foreground = TEST_WHITE)
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "A",
                        attrs = longArrayOf(TerminalRenderAttrs.pack(blink = true, underlineStyle = TerminalRenderUnderline.SINGLE)),
                    ),
                )

            fixture.paintRow(cache, textBlinkVisible = false)

            assertTrue(
                !fixture.image.containsColor(TEST_WHITE, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Blinking ASCII text or its underline painted during the hidden phase",
            )
        }

        @Test
        fun `blink hidden phase does not hide neighboring non-blinking ascii text`() {
            val fixture = fixture()
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "AB",
                        attrs =
                            longArrayOf(
                                TerminalRenderAttrs.pack(blink = true),
                                TerminalRenderAttrs.DEFAULT,
                            ),
                    ),
                )

            fixture.paintRow(cache, textBlinkVisible = false)

            assertTrue(
                !fixture.image.containsColorInRange(TEST_RED, 0, fixture.metrics.cellWidth),
                "Blinking first cell painted during the hidden phase",
            )
            assertTrue(
                fixture.image.containsColorInRange(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellWidth * 2),
                "Non-blinking neighboring cell was hidden with the blinking run",
            )
        }
    }

    @Nested
    inner class HyperlinkRendering {
        @Test
        fun `hyperlinks get solid underline by default`() {
            val fixture = fixture()
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = ' '.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    hyperlinkId = 7,
                                ),
                            ),
                        ),
                    ),
                )

            fixture.paintRow(cache)

            assertEquals(TEST_RED, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY))
        }

        @Test
        fun `hovered hyperlink gets solid underline`() {
            val fixture = fixture()
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = ' '.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    hyperlinkId = 7,
                                ),
                            ),
                        ),
                    ),
                )

            fixture.paintRow(cache, hoveredHyperlinkId = 7)

            assertEquals(TEST_RED, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY))
        }

        @Test
        fun `ctrl hovered hyperlink uses activation blue without bleeding into another link`() {
            val activationBlue = 0xFF4DA3FF.toInt()
            val fixture = fixture()
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = ' '.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    hyperlinkId = 7,
                                ),
                                TestCell(
                                    codeWord = ' '.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    hyperlinkId = 8,
                                ),
                            ),
                        ),
                    ),
                )

            fixture.paintRow(
                cache = cache,
                hoveredHyperlinkId = 7,
                hyperlinkActivationHover = true,
                hyperlinkActivationForeground = activationBlue,
            )

            assertEquals(activationBlue, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(fixture.metrics.cellWidth, fixture.metrics.underlineY))
        }

        @Test
        fun `ctrl hovered hyperlink span does not bleed into another same-id span`() {
            val activationBlue = 0xFF4DA3FF.toInt()
            val fixture = fixture()
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = ' '.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    hyperlinkId = 7,
                                ),
                                TestCell(),
                                TestCell(
                                    codeWord = ' '.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    hyperlinkId = 7,
                                ),
                            ),
                        ),
                    ),
                )

            fixture.paintRow(
                cache = cache,
                hoveredHyperlinkId = 7,
                hoveredHyperlinkStartRow = 0,
                hoveredHyperlinkStartColumn = 0,
                hoveredHyperlinkEndRow = 0,
                hoveredHyperlinkEndColumn = 1,
                hyperlinkActivationHover = true,
                hyperlinkActivationForeground = activationBlue,
            )

            assertEquals(activationBlue, fixture.image.getRGB(0, fixture.metrics.underlineY))
            assertEquals(TEST_RED, fixture.image.getRGB(fixture.metrics.cellWidth * 2, fixture.metrics.underlineY))
        }

        @Test
        fun `ctrl hovered hyperlink span paints across soft-wrapped rows`() {
            val activationBlue = 0xFF4DA3FF.toInt()
            val fixture = fixture()
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(),
                                TestCell(
                                    codeWord = ' '.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    hyperlinkId = 7,
                                ),
                            ),
                            arrayOf(
                                TestCell(
                                    codeWord = ' '.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    hyperlinkId = 7,
                                ),
                                TestCell(),
                            ),
                        ),
                    ),
                )

            fixture.paintRow(
                cache = cache,
                row = 0,
                hoveredHyperlinkId = 7,
                hoveredHyperlinkStartRow = 0,
                hoveredHyperlinkStartColumn = 1,
                hoveredHyperlinkEndRow = 1,
                hoveredHyperlinkEndColumn = 1,
                hyperlinkActivationHover = true,
                hyperlinkActivationForeground = activationBlue,
            )
            fixture.paintRow(
                cache = cache,
                row = 1,
                hoveredHyperlinkId = 7,
                hoveredHyperlinkStartRow = 0,
                hoveredHyperlinkStartColumn = 1,
                hoveredHyperlinkEndRow = 1,
                hoveredHyperlinkEndColumn = 1,
                hyperlinkActivationHover = true,
                hyperlinkActivationForeground = activationBlue,
            )

            assertEquals(activationBlue, fixture.image.getRGB(fixture.metrics.cellWidth, fixture.metrics.underlineY))
            assertEquals(
                activationBlue,
                fixture.image.getRGB(0, fixture.metrics.cellHeight + fixture.metrics.underlineY),
            )
        }
    }

    @Nested
    inner class ComplexTextRendering {
        @Test
        fun `warmed styled shaping adds no allocations beyond equivalent Java2D drawing`() {
            val bean = ManagementFactory.getThreadMXBean()
            assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
            val allocationBean = bean as ThreadMXBean
            assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled)
            val text = "\u05D0\u05D1\u05D2".repeat(8)
            val colors = arrayOf(Color(TEST_RED, true), Color(TEST_WHITE, true))
            val fixture = fixture(width = 500)
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text,
                        attrs =
                            LongArray(text.length) { column ->
                                TerminalRenderAttrs.pack(
                                    foregroundKind = TerminalRenderColorKind.RGB,
                                    foregroundValue = colors[column % colors.size].rgb and 0x00FF_FFFF,
                                )
                            },
                    ),
                )
            try {
                fixture.paintRow(cache)
                fixture.g.setClip(0, 0, fixture.image.width, fixture.image.height)
                val context = fixture.g.fontRenderContext
                val vector = fixture.settings.font.layoutGlyphVector(context, text.toCharArray(), 0, text.length, Font.LAYOUT_RIGHT_TO_LEFT)
                for (glyph in 0..vector.numGlyphs) {
                    val position = vector.getGlyphPosition(glyph)
                    position.setLocation(glyph * fixture.metrics.cellWidth.toDouble(), position.y)
                    vector.setGlyphPosition(glyph, position)
                }
                repeat(5) {
                    paintStyledRows(fixture, cache, context, 2_000)
                    drawStyledGlyphVector(fixture, vector, colors, text.length, 2_000)
                }

                val threadId = Thread.currentThread().threadId()
                var rendererBytes = Long.MAX_VALUE
                var drawingBytes = Long.MAX_VALUE
                repeat(5) {
                    val beforeRenderer = allocationBean.getThreadAllocatedBytes(threadId)
                    paintStyledRows(fixture, cache, context, 1_000)
                    rendererBytes = minOf(rendererBytes, allocationBean.getThreadAllocatedBytes(threadId) - beforeRenderer)
                    val beforeDrawing = allocationBean.getThreadAllocatedBytes(threadId)
                    drawStyledGlyphVector(fixture, vector, colors, text.length, 1_000)
                    drawingBytes = minOf(drawingBytes, allocationBean.getThreadAllocatedBytes(threadId) - beforeDrawing)
                }
                assertEquals(drawingBytes, rendererBytes, "Warmed row shaping must not allocate lookup or ownership storage")
            } finally {
                fixture.g.dispose()
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["uniform", "foreground", "underline", "conceal"])
        fun `Arabic run retains contextual forms across cell presentation changes`(presentation: String) {
            val settings = defaultTestSettings().copy(font = Font(Font.SERIF, Font.PLAIN, 18))
            val actual = fixture(settings = settings)
            val expected = fixture(settings = settings)
            val colors = intArrayOf(TEST_RED, TEST_GREEN, TEST_BLUE)
            val attrs =
                LongArray(3) { column ->
                    when (presentation) {
                        "foreground" ->
                            TerminalRenderAttrs.pack(
                                foregroundKind = TerminalRenderColorKind.RGB,
                                foregroundValue = colors[column] and 0x00FF_FFFF,
                            )
                        "underline" -> TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE)
                        "conceal" -> TerminalRenderAttrs.pack(invisible = column == 1)
                        else -> TerminalRenderAttrs.DEFAULT
                    }
                }
            val extraAttrs =
                LongArray(3) { column ->
                    if (presentation == "underline") underlineColor(colors[column]) else TerminalRenderExtraAttrs.DEFAULT
                }
            try {
                actual.paintRow(renderCache(TestRenderFrame.text("\u0628\u0628\u0628", attrs = attrs, extraAttrs = extraAttrs)))
                expected.paintRow(renderCache(TestRenderFrame.text("\uFE91\uFE92\uFE90", attrs = attrs, extraAttrs = extraAttrs)))

                assertContentEquals(
                    expected.image.getRGB(0, 0, expected.image.width, expected.image.height, null, 0, expected.image.width),
                    actual.image.getRGB(0, 0, actual.image.width, actual.image.height, null, 0, actual.image.width),
                    "The Arabic run must retain its initial, medial, and final Beh forms with $presentation presentation",
                )
                if (presentation == "conceal") {
                    assertTrue(
                        !actual.image.containsPaintedPixelInRange(
                            actual.metrics.cellWidth,
                            actual.metrics.cellWidth * 2,
                            0,
                            actual.metrics.cellHeight,
                        ),
                        "The hidden neighbor must contribute shaping context without painting foreground",
                    )
                }
            } finally {
                actual.g.dispose()
                expected.g.dispose()
            }
        }

        @Test
        fun `Arabic joiner cluster retains text shaping without native emoji dispatch`() {
            var rasterizations = 0
            val rasterizer =
                object : TerminalPlatformEmojiRasterizer {
                    override val available = true

                    override fun rasterize(
                        text: String,
                        pixelSize: Int,
                    ): BufferedImage {
                        rasterizations++
                        return BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB)
                    }
                }
            val settings = defaultTestSettings().copy(font = Font(Font.SERIF, Font.PLAIN, 18))
            val actual = fixture(settings = settings, platformEmojiPainter = TerminalPlatformEmojiPainter(rasterizer))
            val expected = fixture(settings = settings)
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(codeWord = 0x0628, flags = TerminalRenderCellFlags.CODEPOINT),
                                TestCell(flags = TerminalRenderCellFlags.CLUSTER, cluster = "\u0628\u200D"),
                                TestCell(codeWord = 0x0628, flags = TerminalRenderCellFlags.CODEPOINT),
                            ),
                        ),
                    ),
                )
            try {
                actual.paintRow(cache)
                expected.paintRow(renderCache(TestRenderFrame.text("\uFE91\uFE92\uFE90")))
                assertEquals(0, rasterizations, "A joiner in Arabic text must not request native emoji rasterization")
                val contextualPixels =
                    expected.image.getRGB(0, 0, expected.image.width, expected.image.height, null, 0, expected.image.width)
                assertContentEquals(
                    contextualPixels,
                    actual.image.getRGB(0, 0, actual.image.width, actual.image.height, null, 0, actual.image.width),
                    "The joiner cluster must preserve contextual forms in its neighboring cells",
                )

                actual.painter.paintCellForeground(
                    actual.g,
                    cache,
                    actual.metrics,
                    column = 1,
                    visualColumn = 1,
                    row = 0,
                    foreground = TEST_WHITE,
                    fontRenderContext = actual.g.fontRenderContext,
                )
                assertEquals(0, rasterizations, "Cursor repaint must retain text dispatch for the joiner cluster")
                assertContentEquals(
                    contextualPixels,
                    actual.image.getRGB(0, 0, actual.image.width, actual.image.height, null, 0, actual.image.width),
                    "Cursor repaint must preserve the same contextual joiner cluster",
                )
            } finally {
                actual.g.dispose()
                expected.g.dispose()
            }
        }

        @Test
        fun `same style Hebrew glyphs occupy their individual terminal cells`() {
            val fixture = fixture(settings = defaultTestSettings().copy(font = Font(Font.SERIF, Font.PLAIN, 18)))
            val cache = renderCache(TestRenderFrame.text("\u05D0\u05D1\u05D2"))
            try {
                fixture.paintRow(cache)

                for (visualColumn in 0 until cache.columns) {
                    assertTrue(
                        fixture.image.containsPaintedPixelInRange(
                            visualColumn * fixture.metrics.cellWidth,
                            (visualColumn + 1) * fixture.metrics.cellWidth,
                            0,
                            fixture.metrics.cellHeight,
                        ),
                        "The glyph assigned to visual column $visualColumn must occupy that cell",
                    )
                }
            } finally {
                fixture.g.dispose()
            }
        }

        @Test
        fun `long shaped rows retain their suffix and cursor glyphs across bounded spans`() {
            val columns = 2051
            val settings = defaultTestSettings()
            val fixture = fixture(settings = settings, width = columns * settings.font.size * 2)
            val cache = renderCache(TestRenderFrame.text("\u05D0".repeat(columns)))
            try {
                fixture.paintRow(cache)

                for (visualColumn in 0 until columns) {
                    assertTrue(
                        fixture.image.containsPaintedPixelInRange(
                            visualColumn * fixture.metrics.cellWidth,
                            (visualColumn + 1) * fixture.metrics.cellWidth,
                            0,
                            fixture.metrics.cellHeight,
                        ),
                        "Long rows must retain the glyph assigned to visual column $visualColumn",
                    )
                }
                for (column in intArrayOf(2047, 2048, columns - 1)) {
                    val visualColumn = columns - column - 1
                    val x = visualColumn * fixture.metrics.cellWidth
                    val before =
                        fixture.image.getRGB(
                            x,
                            0,
                            fixture.metrics.cellWidth,
                            fixture.metrics.cellHeight,
                            null,
                            0,
                            fixture.metrics.cellWidth,
                        )
                    fixture.painter.paintCellForeground(
                        fixture.g,
                        cache,
                        fixture.metrics,
                        column = column,
                        visualColumn = visualColumn,
                        row = 0,
                        foreground = TEST_WHITE,
                        fontRenderContext = fixture.g.fontRenderContext,
                    )
                    assertContentEquals(
                        before,
                        fixture.image.getRGB(
                            x,
                            0,
                            fixture.metrics.cellWidth,
                            fixture.metrics.cellHeight,
                            null,
                            0,
                            fixture.metrics.cellWidth,
                        ),
                        "Cursor at logical column $column must retain the same bounded shaping context as row painting",
                    )
                }
            } finally {
                fixture.g.dispose()
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `bidi rows retain native emoji dispatch and visual cell placement`(cluster: Boolean) {
            val rasterizedTexts = mutableListOf<String>()
            val rasterizer =
                object : TerminalPlatformEmojiRasterizer {
                    override val available = true

                    override fun rasterize(
                        text: String,
                        pixelSize: Int,
                    ): BufferedImage {
                        rasterizedTexts += text
                        return BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB).apply {
                            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, TEST_GREEN)
                        }
                    }
                }
            val emoji = if (cluster) "\u2764\uFE0F" else String(Character.toChars(ASTRAL_SMILE_CODE_POINT))
            val fixture = fixture(platformEmojiPainter = TerminalPlatformEmojiPainter(rasterizer))
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(codeWord = 0x05D0, flags = TerminalRenderCellFlags.CODEPOINT),
                                TestCell(
                                    codeWord = if (cluster) 0 else ASTRAL_SMILE_CODE_POINT,
                                    flags =
                                        (if (cluster) TerminalRenderCellFlags.CLUSTER else TerminalRenderCellFlags.CODEPOINT) or
                                            TerminalRenderCellFlags.WIDE_LEADING,
                                    cluster = if (cluster) emoji else null,
                                ),
                                TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                            ),
                        ),
                    ),
                )
            try {
                fixture.paintRow(cache)

                assertEquals(listOf(emoji), rasterizedTexts, "Adding RTL text must retain native emoji rendering")
                for (visualColumn in 0..1) {
                    assertTrue(
                        fixture.image.containsColorInRange(
                            TEST_GREEN,
                            visualColumn * fixture.metrics.cellWidth,
                            (visualColumn + 1) * fixture.metrics.cellWidth,
                        ),
                        "The wide emoji must occupy visual column $visualColumn",
                    )
                }
                assertTrue(
                    !fixture.image.containsColorInRange(TEST_GREEN, fixture.metrics.cellWidth * 2, fixture.image.width),
                    "The emoji must not paint into the Hebrew cell",
                )
            } finally {
                fixture.g.dispose()
            }
        }

        @Test
        fun `bidi rows retain full block primitive coverage at its visual cell`() {
            val fixture = fixture(foreground = TEST_WHITE)
            val cache = renderCache(TestRenderFrame.text("\u05D0\u2588"))
            try {
                fixture.paintRow(cache)

                for (y in 0 until fixture.metrics.cellHeight) {
                    for (x in 0 until fixture.metrics.cellWidth) {
                        assertEquals(TEST_WHITE, fixture.image.getRGB(x, y), "Full block must fill its visual cell at ($x,$y)")
                    }
                }
            } finally {
                fixture.g.dispose()
            }
        }

        @Test
        fun `geometric square meter glyphs are painted as contiguous terminal primitives`() {
            val fixture = fixture()
            val cache = renderCache(TestRenderFrame.text("\u25A0\u25A0"))
            val centerY = fixture.metrics.cellHeight / 2

            fixture.paintRow(cache)

            assertEquals(TEST_RED, fixture.image.getRGB(fixture.metrics.cellWidth - 1, centerY))
            assertEquals(TEST_RED, fixture.image.getRGB(fixture.metrics.cellWidth, centerY))
        }

        @Test
        fun `paints non-ascii unicode code point using layout cache`() {
            // Arrange
            val fixture = fixture()
            val cache = renderCache(TestRenderFrame.text("\u03A9")) // Greek Omega

            // Act
            fixture.paintRow(cache)

            // Assert
            assertTrue(
                fixture.image.containsColor(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Non-ascii codepoint failed to render",
            )
        }

        @Test
        fun `blink hidden phase suppresses blinking complex text`() {
            val fixture = fixture()
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "\u03A9",
                        attrs = longArrayOf(TerminalRenderAttrs.pack(blink = true)),
                    ),
                )

            fixture.paintRow(cache, textBlinkVisible = false)

            assertTrue(
                !fixture.image.containsColor(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Blinking complex text painted during the hidden phase",
            )
        }

        @Test
        fun `paints astral unicode scalar as one terminal cell`() {
            // Arrange
            val fixture = fixture(width = 80)
            val text = String(Character.toChars(ASTRAL_SMILE_CODE_POINT))
            val cache = renderCache(TestRenderFrame.text(text))

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(1, cache.columns)
            assertEquals(ASTRAL_SMILE_CODE_POINT, cache.codeWords[0])
            val isLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
            if (!isLinux) {
                assertTrue(
                    fixture.image.containsPaintedPixelInRange(0, fixture.metrics.cellWidth, 0, fixture.metrics.cellHeight),
                    "Astral-plane scalar failed to render",
                )
            }
        }

        @Test
        fun `paints grapheme cluster sourced from cluster sink`() {
            // Arrange
            val fixture = fixture()
            // Thai cluster (base consonant + combining mark)
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    flags = TerminalRenderCellFlags.CLUSTER,
                                    attr = TerminalRenderAttrs.DEFAULT,
                                    cluster = "\u0E01\u0E34",
                                ),
                            ),
                        ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            assertTrue(
                fixture.image.containsColor(TEST_RED, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Grapheme cluster failed to render",
            )
        }

        @Test
        fun `paints astral grapheme cluster sourced from primitive cluster data`() {
            // Arrange
            val fixture = fixture(width = 80)
            val cluster = String(Character.toChars(ASTRAL_SMILE_CODE_POINT)) + "\uFE0F"
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    flags = TerminalRenderCellFlags.CLUSTER,
                                    attr = TerminalRenderAttrs.DEFAULT,
                                    cluster = cluster,
                                ),
                            ),
                        ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(cluster, cache.clusterText(row = 0, column = 0))
            val isLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
            if (!isLinux) {
                assertTrue(
                    fixture.image.containsPaintedPixelInRange(0, fixture.metrics.cellWidth, 0, fixture.metrics.cellHeight),
                    "Astral grapheme cluster failed to render",
                )
            }
        }

        @Test
        fun `wide complex cell layout and decorations span two columns`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE, width = 80)
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = 0x4E2D, // CJK 'Middle' character
                                    flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                                    attr = TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                                ),
                                TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                            ),
                        ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            // Ensure the underline (or glyph itself) painted successfully into the trailing column's X bounds
            assertTrue(
                fixture.image.containsColorInRange(TEST_WHITE, fixture.metrics.cellWidth, fixture.metrics.cellWidth * 2),
                "Wide cell did not paint into its trailing column",
            )
        }

        @Test
        fun `single-width complex glyph is clipped to its core cell span`() {
            // Arrange
            val fixture = fixture(width = 40)
            val narrowMetrics =
                SwingMetrics(
                    cellWidth = 4,
                    cellHeight = fixture.metrics.cellHeight,
                    baseline = fixture.metrics.baseline,
                    underlineY = fixture.metrics.underlineY,
                    strikethroughY = fixture.metrics.strikethroughY,
                    overlineY = fixture.metrics.overlineY,
                    cursorStrokeWidth = fixture.metrics.cursorStrokeWidth,
                )
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = 0x221E,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = TerminalRenderAttrs.DEFAULT,
                                ),
                                TestCell(),
                            ),
                        ),
                    ),
                )

            // Act
            fixture.painter.paintRow(
                fixture.g,
                cache,
                fixture.settings.palette,
                narrowMetrics,
                row = 0,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertTrue(
                !fixture.image.containsPaintedPixelInRange(narrowMetrics.cellWidth, narrowMetrics.cellWidth * 2),
                "Complex glyph escaped the single-cell span owned by core",
            )
        }

        @Test
        fun `symbol heavy narrow row does not paint beyond its core columns`() {
            // Arrange
            val text = "∀∂∈ℝ∧∪≡∞ ↑↗↨↻⇣ ┐┼╔╘░►☺♀ ﬁ�⑀₂ἠḂӥẄɐː⍎אԱა"
            val cellWidth = 4
            val fixture = fixture(width = text.codePointCount(0, text.length) * cellWidth + cellWidth)
            val narrowMetrics =
                SwingMetrics(
                    cellWidth = cellWidth,
                    cellHeight = fixture.metrics.cellHeight,
                    baseline = fixture.metrics.baseline,
                    underlineY = fixture.metrics.underlineY,
                    strikethroughY = fixture.metrics.strikethroughY,
                    overlineY = fixture.metrics.overlineY,
                    cursorStrokeWidth = fixture.metrics.cursorStrokeWidth,
                )
            val cache = renderCache(TestRenderFrame.text(text))

            // Act
            fixture.painter.paintRow(
                fixture.g,
                cache,
                fixture.settings.palette,
                narrowMetrics,
                row = 0,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            val textEnd = text.codePointCount(0, text.length) * cellWidth
            assertTrue(
                !fixture.image.containsPaintedPixelInRange(textEnd, textEnd + cellWidth),
                "Narrow symbol row painted past the core-owned terminal columns",
            )
        }

        @Test
        fun `fitted complex glyph path restores graphics transform`() {
            // Arrange
            val fixture = fixture(width = 40)
            val initialTransform = AffineTransform.getTranslateInstance(3.0, 5.0)
            fixture.g.transform = initialTransform
            val narrowMetrics =
                SwingMetrics(
                    cellWidth = 4,
                    cellHeight = fixture.metrics.cellHeight,
                    baseline = fixture.metrics.baseline,
                    underlineY = fixture.metrics.underlineY,
                    strikethroughY = fixture.metrics.strikethroughY,
                    overlineY = fixture.metrics.overlineY,
                    cursorStrokeWidth = fixture.metrics.cursorStrokeWidth,
                )
            val cache = renderCache(TestRenderFrame.text("\u221E"))

            // Act
            fixture.painter.paintRow(
                fixture.g,
                cache,
                fixture.settings.palette,
                narrowMetrics,
                row = 0,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertEquals(initialTransform, fixture.g.transform, "Complex glyph fitting leaked a Graphics2D transform")
        }

        @Test
        fun `complex Indic script run shapes as one clipped span and restores graphics state`() {
            // Arrange
            val text = "\u0928\u092E\u0938\u094D\u0924\u0947 \u0926\u0941\u0928\u093F\u092F\u093E"
            val fixture = fixture(width = text.codePointCount(0, text.length) * 16 + 32)
            val initialTransform = AffineTransform.getTranslateInstance(2.0, 3.0)
            fixture.g.transform = initialTransform
            val cache = renderCache(TestRenderFrame.text(text))

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(initialTransform, fixture.g.transform, "Complex-script run shaping leaked a Graphics2D transform")
            val textEnd = cache.columns * fixture.metrics.cellWidth
            assertTrue(
                fixture.image.containsPaintedPixelInRange(0, textEnd, 0, fixture.metrics.cellHeight),
                "Complex-script run did not paint any visible glyph pixels",
            )
            assertTrue(
                !fixture.image.containsPaintedPixelInRange(
                    textEnd + initialTransform.translateX.toInt(),
                    fixture.image.width,
                    0,
                    fixture.metrics.cellHeight,
                ),
                "Complex-script run painted beyond the core-owned terminal columns",
            )
        }

        @Test
        fun `strong rtl row paints in visual order without changing logical cache order`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE, width = 120)
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "\u05D0\u05D1\u05D2",
                        attrs =
                            longArrayOf(
                                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                                TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                            ),
                        extraAttrs =
                            longArrayOf(
                                underlineColor(TEST_RED),
                                underlineColor(TEST_GREEN),
                                underlineColor(TEST_BLUE),
                            ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(0x05D0, cache.codeWords[0], "Core/cache storage must remain logical")
            assertEquals(TEST_BLUE, fixture.image.getRGB(1, fixture.metrics.underlineY), "Last RTL cell should paint first visually")
            assertEquals(
                TEST_GREEN,
                fixture.image.getRGB(fixture.metrics.cellWidth + 1, fixture.metrics.underlineY),
                "Middle RTL cell should remain in the middle visually",
            )
            assertEquals(
                TEST_RED,
                fixture.image.getRGB(fixture.metrics.cellWidth * 2 + 1, fixture.metrics.underlineY),
                "First RTL cell should paint last visually",
            )
        }

        @Test
        fun `mixed ltr and rtl row keeps ltr prefix and reverses rtl run visually`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE, width = 160)
            val underlineAttr = TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE)
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "AB \u05D0\u05D1\u05D2",
                        attrs = LongArray(6) { underlineAttr },
                        extraAttrs =
                            longArrayOf(
                                underlineColor(TEST_RED),
                                underlineColor(TEST_GREEN),
                                underlineColor(TEST_WHITE),
                                underlineColor(TEST_RED),
                                underlineColor(TEST_GREEN),
                                underlineColor(TEST_BLUE),
                            ),
                    ),
                )

            // Act
            fixture.paintRow(cache)

            // Assert
            assertEquals(TEST_RED, fixture.image.getRGB(1, fixture.metrics.underlineY), "LTR prefix first cell moved")
            assertEquals(
                TEST_GREEN,
                fixture.image.getRGB(fixture.metrics.cellWidth + 1, fixture.metrics.underlineY),
                "LTR prefix second cell moved",
            )
            assertEquals(
                TEST_BLUE,
                fixture.image.getRGB(fixture.metrics.cellWidth * 3 + 1, fixture.metrics.underlineY),
                "RTL run did not reverse after the LTR prefix",
            )
            assertEquals(
                TEST_RED,
                fixture.image.getRGB(fixture.metrics.cellWidth * 5 + 1, fixture.metrics.underlineY),
                "RTL run first logical cell should paint at the visual run end",
            )
        }

        @Test
        fun `scrolling does not bleed cached bidi layout across rows with same generation`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE, width = 120)
            val underlineAttr = TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE)

            // Frame 1: Row 0 has RTL string "\u05D0\u05D1\u05D2" (Red, Green, Blue underlines). Row 1 has LTR string "abc". Both have line generation 1.
            val frame1 =
                object : TestRenderFrame(
                    cells =
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = 0x05D0,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_RED),
                                ),
                                TestCell(
                                    codeWord = 0x05D1,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_GREEN),
                                ),
                                TestCell(
                                    codeWord = 0x05D2,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_BLUE),
                                ),
                            ),
                            arrayOf(
                                TestCell(
                                    codeWord = 'a'.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_RED),
                                ),
                                TestCell(
                                    codeWord = 'b'.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_GREEN),
                                ),
                                TestCell(
                                    codeWord = 'c'.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_BLUE),
                                ),
                            ),
                        ),
                ) {
                    override fun lineId(row: Int): Long = if (row == 0) 10L else 20L

                    override fun lineGeneration(row: Int): Long = 1L
                }

            // Update cache from Frame 1
            val cache = TerminalRenderCache(columns = 3, rows = 2)
            cache.updateFrom(frame1)

            // Paint Row 0 (RTL). This caches bidi status for Row 0 (generation 1, lineId 10L).
            fixture.paintRow(cache, row = 0)

            // Now, simulate scrolling so that the LTR line "abc" is at Row 0.
            // Row 0 of Frame 2 will return lineId = 20L (different) and generation = 1L (same).
            val frame2 =
                object : TestRenderFrame(
                    cells =
                        arrayOf(
                            arrayOf(
                                TestCell(
                                    codeWord = 'a'.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_RED),
                                ),
                                TestCell(
                                    codeWord = 'b'.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_GREEN),
                                ),
                                TestCell(
                                    codeWord = 'c'.code,
                                    flags = TerminalRenderCellFlags.CODEPOINT,
                                    attr = underlineAttr,
                                    extraAttr = underlineColor(TEST_BLUE),
                                ),
                            ),
                            arrayOf(
                                TestCell(),
                                TestCell(),
                                TestCell(),
                            ),
                        ),
                ) {
                    override fun lineId(row: Int): Long = if (row == 0) 20L else 30L

                    override fun lineGeneration(row: Int): Long = 1L
                }

            // Update cache from Frame 2
            cache.updateFrom(frame2)

            // Clear image so we don't read old pixels
            val g2 = fixture.image.createGraphics()
            g2.composite = AlphaComposite.Clear
            g2.fillRect(0, 0, fixture.image.width, fixture.image.height)
            g2.dispose()

            // Paint Row 0 of new cache (now LTR "abc").
            fixture.paintRow(cache, row = 0)

            // Assert that "abc" was painted in LTR visual order (Red, Green, Blue underlines).
            // If the bug was present, it would be visually reversed to "cba" (Blue, Green, Red).
            assertEquals(
                TEST_RED,
                fixture.image.getRGB(1, fixture.metrics.underlineY),
                "First LTR cell underline should be red (visual order LTR)",
            )
            assertEquals(
                TEST_GREEN,
                fixture.image.getRGB(fixture.metrics.cellWidth + 1, fixture.metrics.underlineY),
                "Middle LTR cell underline should be green",
            )
            assertEquals(
                TEST_BLUE,
                fixture.image.getRGB(fixture.metrics.cellWidth * 2 + 1, fixture.metrics.underlineY),
                "Last LTR cell underline should be blue",
            )
        }
    }

    @Nested
    inner class CursorForegroundRendering {
        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `block cursor preserves contextual Arabic glyphs and only recolors its cell`(cluster: Boolean) {
            val cache =
                renderCache(
                    TestRenderFrame(
                        arrayOf(
                            Array(3) {
                                TestCell(
                                    codeWord = 0x0628,
                                    flags = if (cluster) TerminalRenderCellFlags.CLUSTER else TerminalRenderCellFlags.CODEPOINT,
                                    cluster = if (cluster) "\u0628\u064E" else null,
                                )
                            },
                        ),
                    ),
                )
            for (column in 0 until cache.columns) {
                val fixture = fixture(foreground = TEST_WHITE)
                val visualColumn = cache.columns - column - 1
                try {
                    fixture.paintRow(cache)
                    val before = fixture.image.getRGB(0, 0, fixture.image.width, fixture.image.height, null, 0, fixture.image.width)
                    fixture.painter.paintCellForeground(
                        fixture.g,
                        cache,
                        fixture.metrics,
                        column = column,
                        visualColumn = visualColumn,
                        row = 0,
                        foreground = TEST_WHITE,
                        fontRenderContext = fixture.g.fontRenderContext,
                    )

                    assertContentEquals(
                        before,
                        fixture.image.getRGB(0, 0, fixture.image.width, fixture.image.height, null, 0, fixture.image.width),
                        "Repainting logical column $column in the existing color must preserve contextual shaping",
                    )

                    fixture.painter.paintCellForeground(
                        fixture.g,
                        cache,
                        fixture.metrics,
                        column = column,
                        visualColumn = visualColumn,
                        row = 0,
                        foreground = TEST_GREEN,
                        fontRenderContext = fixture.g.fontRenderContext,
                    )
                    val cellStart = visualColumn * fixture.metrics.cellWidth
                    val cellEnd = cellStart + fixture.metrics.cellWidth
                    assertTrue(fixture.image.containsColorInRange(TEST_GREEN, cellStart, cellEnd))
                    for (y in 0 until fixture.image.height) {
                        for (x in 0 until fixture.image.width) {
                            val previousColor = before[y * fixture.image.width + x]
                            val expectedColor =
                                if (x in cellStart until cellEnd && previousColor == TEST_WHITE) TEST_GREEN else previousColor
                            assertEquals(
                                expectedColor,
                                fixture.image.getRGB(x, y),
                                "Cursor at logical column $column changed the positioned glyph mask at ($x,$y)",
                            )
                        }
                    }
                } finally {
                    fixture.g.dispose()
                }
            }
        }

        @Test
        fun `paints ascii cell inverted with supplied cursor foreground`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE) // Default text is white
            val cache = renderCache(TestRenderFrame.text("A"))

            // Act
            fixture.painter.paintCellForeground(
                fixture.g,
                cache,
                fixture.metrics,
                column = 0,
                row = 0,
                foreground = TEST_GREEN, // Override to Green for the cursor block
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertTrue(
                fixture.image.containsColor(TEST_GREEN, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Cursor foreground did not override cell text color",
            )
        }

        @Test
        fun `empty cell does not accidentally paint foreground debris`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE)
            val cache = renderCache(TestRenderFrame(arrayOf(arrayOf(TestCell())))) // Empty cell

            // Act
            fixture.painter.paintCellForeground(
                fixture.g,
                cache,
                fixture.metrics,
                column = 0,
                row = 0,
                foreground = TEST_GREEN,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertTrue(
                !fixture.image.containsColor(TEST_GREEN, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Empty cell painted ghost text when targeted by block cursor",
            )
        }

        @Test
        fun `paintCellForeground restores rectangular clip bounds`() {
            // Arrange
            val fixture = fixture(foreground = TEST_WHITE)
            val cache = renderCache(TestRenderFrame.text("A"))
            val originalClip = Rectangle(3, 4, fixture.metrics.cellWidth, fixture.metrics.cellHeight)
            fixture.g.setClip(originalClip.x, originalClip.y, originalClip.width, originalClip.height)

            // Act
            fixture.painter.paintCellForeground(
                fixture.g,
                cache,
                fixture.metrics,
                column = 0,
                row = 0,
                foreground = TEST_GREEN,
                fontRenderContext = fixture.g.fontRenderContext,
            )

            // Assert
            assertEquals(originalClip, fixture.g.getClipBounds(Rectangle()))
        }

        @Test
        fun `paintCellForeground respects blinking text hidden phase`() {
            val fixture = fixture(foreground = TEST_WHITE)
            val cache =
                renderCache(
                    TestRenderFrame.text(
                        text = "A",
                        attrs = longArrayOf(TerminalRenderAttrs.pack(blink = true)),
                    ),
                )

            fixture.painter.paintCellForeground(
                fixture.g,
                cache,
                fixture.metrics,
                column = 0,
                row = 0,
                foreground = TEST_GREEN,
                fontRenderContext = fixture.g.fontRenderContext,
                textBlinkVisible = false,
            )

            assertTrue(
                !fixture.image.containsColor(TEST_GREEN, fixture.metrics.cellWidth, fixture.metrics.cellHeight),
                "Block cursor foreground resurrected hidden blinking text",
            )
        }
    }

    // --- Testing Utilities & Helpers ---

    private fun paintStyledRows(
        fixture: Fixture,
        cache: TerminalRenderCache,
        context: FontRenderContext,
        count: Int,
    ) {
        repeat(count) {
            fixture.painter.paintRow(fixture.g, cache, fixture.settings.palette, fixture.metrics, 0, context)
        }
    }

    private fun drawStyledGlyphVector(
        fixture: Fixture,
        vector: GlyphVector,
        colors: Array<Color>,
        columns: Int,
        count: Int,
    ) {
        repeat(count) {
            for (column in 0 until columns) {
                val oldClip = fixture.g.clip
                try {
                    fixture.g.clipRect(column * fixture.metrics.cellWidth, 0, fixture.metrics.cellWidth, fixture.metrics.cellHeight)
                    fixture.g.color = colors[(columns - column - 1) % colors.size]
                    fixture.g.drawGlyphVector(vector, 0f, fixture.metrics.baseline.toFloat())
                } finally {
                    fixture.g.clip = oldClip
                }
            }
        }
    }

    private data class Fixture(
        val image: BufferedImage,
        val g: Graphics2D,
        val settings: SwingSettings,
        val metrics: SwingMetrics,
        val painter: TerminalTextPainter,
    ) {
        fun paintRow(
            cache: TerminalRenderCache,
            row: Int = 0,
            textBlinkVisible: Boolean = true,
            hyperlinkIds: IntArray = cache.hyperlinkIds,
            hoveredHyperlinkId: Int = 0,
            hoveredHyperlinkStartRow: Int = 0,
            hoveredHyperlinkStartColumn: Int = 0,
            hoveredHyperlinkEndRow: Int = Int.MAX_VALUE,
            hoveredHyperlinkEndColumn: Int = Int.MAX_VALUE,
            hyperlinkActivationHover: Boolean = false,
            hyperlinkActivationForeground: Int = 0xFF4DA3FF.toInt(),
        ) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
            painter.paintRow(
                g = g,
                cache = cache,
                palette = settings.palette,
                metrics = metrics,
                row = row,
                fontRenderContext = g.fontRenderContext,
                textBlinkVisible = textBlinkVisible,
                hyperlinkIds = hyperlinkIds,
                hoveredHyperlinkId = hoveredHyperlinkId,
                hoveredHyperlinkStartRow = hoveredHyperlinkStartRow,
                hoveredHyperlinkStartColumn = hoveredHyperlinkStartColumn,
                hoveredHyperlinkEndRow = hoveredHyperlinkEndRow,
                hoveredHyperlinkEndColumn = hoveredHyperlinkEndColumn,
                hyperlinkActivationHover = hyperlinkActivationHover,
                hyperlinkActivationForeground = hyperlinkActivationForeground,
            )
        }
    }

    private fun fixture(
        foreground: Int = TEST_RED,
        background: Int = TEST_BLACK,
        width: Int = 80,
        platformEmojiPainter: TerminalPlatformEmojiPainter = TerminalPlatformEmojiPainter(),
        settings: SwingSettings = defaultTestSettings(foreground = foreground, background = background),
    ): Fixture {
        val image = BufferedImage(width, 40, BufferedImage.TYPE_INT_ARGB)
        val colorCache = AwtColorCache()
        val painter = TerminalTextPainter(colorCache, TerminalDecorationPainter(colorCache), platformEmojiPainter)
        painter.updateSettings(settings)
        return Fixture(
            image = image,
            g = image.createGraphics(),
            settings = settings,
            metrics = testMetrics(image, settings),
            painter = painter,
        )
    }

    private fun createMismatchSettings(): SwingSettings =
        SwingSettings(
            font = Font(Font.SERIF, Font.PLAIN, 18),
            palette = defaultTestSettings(foreground = TEST_RED, background = TEST_BLACK).palette,
            textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
            fractionalMetrics = RenderingHints.VALUE_FRACTIONALMETRICS_ON,
            padding = SwingPadding(0, 0, 0, 0),
        )

    private fun createMismatchFixture(settings: SwingSettings): Triple<BufferedImage, SwingMetrics, TerminalTextPainter> {
        val image = BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val fontMetrics = g.getFontMetrics(settings.font)
        val metrics =
            SwingMetrics(
                cellWidth = maxOf(1, fontMetrics.charWidth('W')),
                cellHeight = fontMetrics.height,
                baseline = fontMetrics.ascent,
                underlineY = minOf(fontMetrics.height - 1, fontMetrics.ascent + 1),
                strikethroughY = maxOf(0, fontMetrics.ascent - fontMetrics.ascent / 3),
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val painter = TerminalTextPainter(AwtColorCache(), TerminalDecorationPainter(AwtColorCache()))
        painter.updateSettings(settings)

        // Push graphics hints to ensure GlyphVector mismatch path triggers
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)

        g.dispose()
        return Triple(image, metrics, painter)
    }

    private fun paintSerifAscii(
        settings: SwingSettings,
        text: String,
    ): BufferedImage {
        val image = BufferedImage(140, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val colorCache = AwtColorCache()
        val painter = TerminalTextPainter(colorCache, TerminalDecorationPainter(colorCache))

        painter.updateSettings(settings)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)

        painter.paintRow(
            g,
            renderCache(TestRenderFrame.text(text)),
            settings.palette,
            testMetrics(image, settings),
            row = 0,
            fontRenderContext = g.fontRenderContext,
        )
        g.dispose()
        return image
    }

    private companion object {
        private const val ASTRAL_SMILE_CODE_POINT = 0x1F642

        private fun underlineColor(rgb: Int): Long =
            TerminalRenderExtraAttrs.pack(
                underlineColorKind = TerminalRenderColorKind.RGB,
                underlineColorValue = rgb and 0x00FF_FFFF,
            )
    }
}

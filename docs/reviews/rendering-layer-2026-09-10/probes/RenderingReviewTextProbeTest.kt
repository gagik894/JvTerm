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
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.render.primitives.TerminalPlatformEmojiPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.RenderingHints
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Temporary deterministic review probes; not a production fix. */
class RenderingReviewTextProbeTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun bidiRowRetainsNativeEmojiPainting(cluster: Boolean) {
        var rasterizations = 0
        val native = TerminalPlatformEmojiPainter(object : TerminalPlatformEmojiRasterizer {
            override val available = true
            override fun rasterize(text: String, pixelSize: Int): BufferedImage {
                rasterizations++
                return BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB).also {
                    it.setRGB(0, 0, TEST_RED)
                }
            }
        })
        val settings = defaultTestSettings()
        val colors = AwtColorCache()
        val painter = TerminalTextPainter(colors, TerminalDecorationPainter(colors), native)
        painter.updateSettings(settings)
        val image = BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val cells = arrayOf(
            TestCell(codeWord = 0x05D0, flags = TerminalRenderCellFlags.CODEPOINT),
            TestCell(
                codeWord = if (cluster) 0 else 0x1F600,
                flags = (if (cluster) TerminalRenderCellFlags.CLUSTER else TerminalRenderCellFlags.CODEPOINT) or
                    TerminalRenderCellFlags.WIDE_LEADING,
                cluster = if (cluster) "\u2764\uFE0F" else null,
            ),
            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
        )
        try {
            painter.paintRow(g, renderCache(TestRenderFrame(arrayOf(cells))), settings.palette,
                testMetrics(image, settings), 0, g.fontRenderContext)
            assertEquals(1, rasterizations, "Adding Hebrew text must not disable native emoji rendering")
        } finally {
            g.dispose()
        }
    }

    @Test
    fun bidiRowRetainsFullCellBlockPrimitive() {
        val settings = defaultTestSettings()
        val colors = AwtColorCache()
        val painter = TerminalTextPainter(colors, TerminalDecorationPainter(colors))
        painter.updateSettings(settings)
        val image = BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics = testMetrics(image, settings)
        try {
            painter.paintRow(g, renderCache(TestRenderFrame.text("\u05D0\u2588")), settings.palette,
                metrics, 0, g.fontRenderContext)
            assertEquals(TEST_WHITE, image.getRGB(0, 0), "Full block must fill its cell including its top-left corner")
        } finally {
            g.dispose()
        }
    }

    @Test
    fun repeatedUnsupportedNativeEmojiUsesCachedFailure() {
        var rasterizations = 0
        val painter = TerminalPlatformEmojiPainter(object : TerminalPlatformEmojiRasterizer {
            override val available = true
            override fun rasterize(text: String, pixelSize: Int): BufferedImage? {
                rasterizations++
                return null
            }
        })
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics = SwingMetrics(10, 20, 14, 15, 9, 0, 1)
        try {
            repeat(3) {
                assertFalse(painter.paintCodePoint(g, 0x1F600, 0, 0, 2, metrics))
            }
            assertEquals(1, rasterizations, "Native rasterizer failure should be bounded per text and size")
        } finally {
            g.dispose()
        }
    }

    @Test
    fun blockCursorRetainsArabicContextualGlyph() {
        val settings = defaultTestSettings()
        val colors = AwtColorCache()
        val painter = TerminalTextPainter(colors, TerminalDecorationPainter(colors))
        painter.updateSettings(settings)
        val image = BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics = testMetrics(image, settings)
        val cache = renderCache(TestRenderFrame.text("\u0628\u0628\u0628"))
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            painter.paintRow(g, cache, settings.palette, metrics, 0, g.fontRenderContext)
            val before = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            painter.paintCellForeground(g, cache, metrics, column = 1, row = 0,
                foreground = TEST_WHITE, fontRenderContext = g.fontRenderContext)
            assertContentEquals(before, image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
                "Repainting the cursor foreground in the existing color must retain the contextual Arabic glyph")
        } finally {
            g.dispose()
        }
    }

    @Test
    fun shapedHebrewGlyphsRetainTheirCellAdvances() {
        val settings = defaultTestSettings().copy(font = Font(Font.SERIF, Font.PLAIN, 18))
        val colors = AwtColorCache()
        val painter = TerminalTextPainter(colors, TerminalDecorationPainter(colors))
        painter.updateSettings(settings)
        val image = BufferedImage(90, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics = testMetrics(image, settings)
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            painter.paintRow(g, renderCache(TestRenderFrame.text("\u05D0\u05D1\u05D2")), settings.palette,
                metrics, 0, g.fontRenderContext)
            assertTrue(image.containsPaintedPixelInRange(metrics.cellWidth * 2, metrics.cellWidth * 3),
                "The glyph mapped to visual column 2 must paint inside that cell")
        } finally {
            g.dispose()
        }
    }

    @Test
    fun warmedNativeEmojiLookupDoesNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled)
        val glyph = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val painter = TerminalPlatformEmojiPainter(object : TerminalPlatformEmojiRasterizer {
            override val available = true
            override fun rasterize(text: String, pixelSize: Int): BufferedImage = glyph
        })
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val metrics = SwingMetrics(10, 20, 14, 15, 9, 0, 1)
        val threadId = Thread.currentThread().threadId()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            repeat(5) {
                paintNativeRepeatedly(painter, g, metrics, 10_000)
                drawNativeImageRepeatedly(g, glyph, 10_000)
            }
            var minimumPainter = Long.MAX_VALUE
            var minimumDirect = Long.MAX_VALUE
            repeat(5) {
                val beforePainter = allocationBean.getThreadAllocatedBytes(threadId)
                paintNativeRepeatedly(painter, g, metrics, 10_000)
                minimumPainter = minOf(minimumPainter, allocationBean.getThreadAllocatedBytes(threadId) - beforePainter)
                val beforeDirect = allocationBean.getThreadAllocatedBytes(threadId)
                drawNativeImageRepeatedly(g, glyph, 10_000)
                minimumDirect = minOf(minimumDirect, allocationBean.getThreadAllocatedBytes(threadId) - beforeDirect)
            }
            println("Review cached native emoji bytes per glyph: painter=${minimumPainter / 10_000}, direct=${minimumDirect / 10_000}")
            assertEquals(minimumDirect, minimumPainter, "Warm emoji lookup must not allocate above equivalent image drawing")
        } finally {
            g.dispose()
        }
    }

    private fun paintNativeRepeatedly(painter: TerminalPlatformEmojiPainter, g: Graphics2D, metrics: SwingMetrics, count: Int) {
        repeat(count) { painter.paintCodePoint(g, 0x1F600, 0, 0, 1, metrics) }
    }

    private fun drawNativeImageRepeatedly(g: Graphics2D, glyph: BufferedImage, count: Int) {
        repeat(count) {
            val oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(glyph, 0, 5, 10, 10, null)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation)
        }
    }
}

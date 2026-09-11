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
package io.github.ketraterm.ui.swing.render.primitives

import com.sun.management.ThreadMXBean
import io.github.ketraterm.ui.swing.render.TEST_RED
import io.github.ketraterm.ui.swing.render.containsColor
import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import kotlin.test.*

class TerminalPlatformEmojiPainterTest {
    @ParameterizedTest
    @ValueSource(ints = [0x41, 0xE9, 0x5D0, 0x6F22, 0x2764, 0x1D11E])
    fun `ordinary code point does not initialize native emoji rasterizer`(codePoint: Int) {
        var initializations = 0
        val painter =
            TerminalPlatformEmojiPainter {
                initializations++
                FakeEmojiRasterizer()
            }
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            repeat(2) {
                assertFalse(painter.paintCodePoint(g, codePoint, 0, 0, 1, METRICS))
            }
            assertEquals(0, initializations)
        } finally {
            g.dispose()
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "e\u0301", "\u05D0\u05D1", "\u6F22", "\u2764\uFE0E", "\uD83D\uDE00\uFE0E",
            "\u0628\u200D", "\u0915\u094D\u200D", "\u0628\uFE0F", "\u0915\uFE0F",
            "\u200D", "\uFE0F", "A\u200D", "A\uFE0F", "A\u20E3", "#\uFE0E\u20E3",
        ],
    )
    fun `ordinary or text presentation cluster does not initialize native emoji rasterizer`(text: String) {
        var initializations = 0
        val painter =
            TerminalPlatformEmojiPainter {
                initializations++
                FakeEmojiRasterizer()
            }
        // Emoji outside the requested slice must not affect classification.
        val codepoints = intArrayOf(0x1F600) + text.codePoints().toArray() + intArrayOf(0x1F600)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            repeat(2) {
                assertFalse(painter.paintCluster(g, codepoints, 1, codepoints.size - 2, 0, 0, 1, METRICS))
            }
            assertEquals(0, initializations)
        } finally {
            g.dispose()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `emoji initializes rasterizer once across code point and cluster paths`(available: Boolean) {
        var initializations = 0
        val rasterizer = FakeEmojiRasterizer(available)
        val painter =
            TerminalPlatformEmojiPainter {
                initializations++
                rasterizer
            }
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val codepoints = intArrayOf(0x2764, 0xFE0F)
        try {
            assertEquals(0, initializations)
            repeat(2) {
                assertEquals(available, painter.paintCodePoint(g, 0x1F600, 0, 0, 1, METRICS))
                assertEquals(available, painter.paintCluster(g, codepoints, 0, codepoints.size, 0, 0, 1, METRICS))
            }
            assertEquals(1, initializations)
            assertEquals(if (available) listOf("\uD83D\uDE00", "\u2764\uFE0F") else emptyList(), rasterizer.texts)
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `default emoji code point is rasterized through platform hook`() {
        val rasterizer = FakeEmojiRasterizer()
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            assertTrue(painter.paintCodePoint(g, 0x1F600, 0, 0, 1, METRICS))
        } finally {
            g.dispose()
        }

        assertEquals(listOf("\uD83D\uDE00"), rasterizer.texts)
        assertTrue(image.containsColor(TEST_RED))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\u2764\uFE0F", "\u00A9\uFE0F", "\u00AE\uFE0F", "1\uFE0F\u20E3", "#\u20E3",
            "\uD83D\uDC69\u200D\uD83D\uDCBB", "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08",
        ],
    )
    fun `emoji sequences retain native dispatch and classification stays lazy`(text: String) {
        var initializations = 0
        val rasterizer = FakeEmojiRasterizer()
        val painter =
            TerminalPlatformEmojiPainter {
                initializations++
                rasterizer
            }
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val codepoints = text.codePoints().toArray()
        try {
            assertTrue(painter.usesEmojiPresentation(codepoints, 0, codepoints.size))
            assertEquals(0, initializations)
            assertTrue(painter.paintCluster(g, codepoints, 0, codepoints.size, 0, 0, 1, METRICS))
            assertEquals(1, initializations)
        } finally {
            g.dispose()
        }

        assertEquals(listOf(text), rasterizer.texts)
        assertTrue(image.containsColor(TEST_RED))
    }

    @Test
    fun `text presentation cluster stays on Java2D fallback path`() {
        val rasterizer = FakeEmojiRasterizer()
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val codepoints = intArrayOf(0x2764, 0xFE0E)
        try {
            assertFalse(painter.paintCluster(g, codepoints, 0, codepoints.size, 0, 0, 1, METRICS))
        } finally {
            g.dispose()
        }

        assertTrue(rasterizer.texts.isEmpty())
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `warmed emoji paints allocate no storage for images or negative results`(
        cluster: Boolean,
        supported: Boolean,
    ) {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled)
        val rasterizedImage = if (supported) BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB) else null
        val rasterizer = CountingEmojiRasterizer(rasterizedImage)
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val codepoints = intArrayOf(0x41, 0x1F469, 0x200D, 0x1F4BB, 0x42)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            fun paintBatch(): Int {
                var painted = 0
                repeat(10_000) {
                    val native =
                        if (cluster) {
                            painter.paintCluster(g, codepoints, 1, 3, 0, 0, 1, METRICS)
                        } else {
                            painter.paintCodePoint(g, 0x1F600, 0, 0, 1, METRICS)
                        }
                    if (native) painted++
                }
                return painted
            }

            repeat(5) { paintBatch() }
            val threadId = Thread.currentThread().threadId()
            var minimum = Long.MAX_VALUE
            var painted = 0
            repeat(5) {
                val before = allocationBean.getThreadAllocatedBytes(threadId)
                painted += paintBatch()
                minimum = minOf(minimum, allocationBean.getThreadAllocatedBytes(threadId) - before)
            }
            assertEquals(if (supported) 50_000 else 0, painted)
            assertEquals(1, rasterizer.calls, "A retained miss must not retry native rasterization")
            assertEquals(0L, minimum, "Warmed emoji painting must not allocate lookup keys or text")
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `unsupported scalar and cluster results are retained`() {
        val rasterizer = FakeEmojiRasterizer(supported = false)
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val codepoints = intArrayOf(0x2764, 0xFE0F)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            repeat(3) {
                assertFalse(painter.paintCodePoint(g, 0x1F600, 0, 0, 1, METRICS))
                assertFalse(painter.paintCluster(g, codepoints, 0, codepoints.size, 0, 0, 1, METRICS))
            }
            assertEquals(listOf("\uD83D\uDE00", "\u2764\uFE0F"), rasterizer.texts)
        } finally {
            g.dispose()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `scalar and single code point cluster share text and pixel size identity`(supported: Boolean) {
        val rasterizer = FakeEmojiRasterizer(supported = supported)
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val codepoints = intArrayOf(0x41, 0x1F600, 0x42)
        val narrowerMetrics = METRICS.copy(cellWidth = 5)
        val image = BufferedImage(60, 60, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            assertEquals(supported, painter.paintCodePoint(g, 0x1F600, 0, 0, 1, METRICS))
            assertEquals(supported, painter.paintCluster(g, codepoints, 1, 1, 1, 1, 1, METRICS))
            assertEquals(supported, painter.paintCodePoint(g, 0x1F600, 2, 2, 2, narrowerMetrics))
            assertEquals(listOf(10), rasterizer.pixelSizes, "Position and cell span do not change an equal raster size")

            assertEquals(supported, painter.paintCluster(g, codepoints, 1, 1, 0, 0, 2, METRICS))
            assertEquals(supported, painter.paintCodePoint(g, 0x1F600, 1, 1, 2, METRICS))
            assertEquals(listOf(10, 20), rasterizer.pixelSizes, "A different raster size requires its own result")
            assertEquals(listOf("\uD83D\uDE00", "\uD83D\uDE00"), rasterizer.texts)
        } finally {
            g.dispose()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `cluster lookup compares only its slice and owns retained text`(supported: Boolean) {
        val rasterizer = FakeEmojiRasterizer(supported = supported)
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val buffer = intArrayOf(0x41, 0x2764, 0xFE0F, 0x42)
        val equalSlice = intArrayOf(0x2764, 0xFE0F)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            assertEquals(supported, painter.paintCluster(g, buffer, 1, 2, 0, 0, 1, METRICS))
            assertEquals(supported, painter.paintCluster(g, equalSlice, 0, 2, 0, 0, 1, METRICS))
            buffer[0] = 0x1F600
            buffer[3] = 0xFE0E
            assertEquals(supported, painter.paintCluster(g, buffer, 1, 2, 0, 0, 1, METRICS))
            assertEquals(1, rasterizer.texts.size)

            buffer[1] = 0x263A
            assertEquals(supported, painter.paintCluster(g, buffer, 1, 2, 0, 0, 1, METRICS))
            assertEquals(supported, painter.paintCluster(g, equalSlice, 0, 2, 0, 0, 1, METRICS))
            assertEquals(listOf("\u2764\uFE0F", "\u263A\uFE0F"), rasterizer.texts)
        } finally {
            g.dispose()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `scalar and cluster entries share bounded least recently used retention`(supported: Boolean) {
        val rasterizer = FakeEmojiRasterizer(supported = supported)
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val codepoints = intArrayOf(0, 0xFE0F)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            fun paint(index: Int): Boolean {
                val codePoint = 0x1F000 + index
                return if (index % 2 == 0) {
                    painter.paintCodePoint(g, codePoint, 0, 0, 1, METRICS)
                } else {
                    codepoints[0] = codePoint
                    painter.paintCluster(g, codepoints, 0, 2, 0, 0, 1, METRICS)
                }
            }

            repeat(1024) { assertEquals(supported, paint(it)) }
            assertEquals(1024, rasterizer.texts.size)
            assertEquals(supported, paint(0))
            assertEquals(1024, rasterizer.texts.size)

            assertEquals(supported, paint(1024))
            assertEquals(supported, paint(0))
            assertEquals(1025, rasterizer.texts.size, "Reading an entry must protect it from eviction")
            assertEquals(supported, paint(1))
            assertEquals(1026, rasterizer.texts.size, "The oldest unread entry must be evicted at capacity")
        } finally {
            g.dispose()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `hash colliding cluster content keeps images and unsupported results distinct`(firstSupported: Boolean) {
        val first = intArrayOf(0x1F600, 0x1F610)
        val second = intArrayOf(0x1F601, 0x1F5F1)
        assertEquals(first.contentHashCode(), second.contentHashCode())
        val firstText = String(first, 0, first.size)
        val secondText = String(second, 0, second.size)
        val supportedText = if (firstSupported) firstText else secondText
        val calls = mutableListOf<String>()
        val rasterizedImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val rasterizer =
            object : TerminalPlatformEmojiRasterizer {
                override val available: Boolean = true

                override fun rasterize(
                    text: String,
                    pixelSize: Int,
                ): BufferedImage? {
                    calls += text
                    return if (text == supportedText) rasterizedImage else null
                }
            }
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            repeat(3) {
                assertEquals(firstSupported, painter.paintCluster(g, first, 0, 2, 0, 0, 1, METRICS))
                assertEquals(!firstSupported, painter.paintCluster(g, second, 0, 2, 0, 0, 1, METRICS))
            }
            assertEquals(listOf(firstText, secondText), calls)
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `rasterizer exception propagates and does not retain an unsupported result`() {
        val failure = IllegalStateException("Rasterization failed")
        val calls = mutableListOf<String>()
        val rasterizedImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val rasterizer =
            object : TerminalPlatformEmojiRasterizer {
                override val available: Boolean = true

                override fun rasterize(
                    text: String,
                    pixelSize: Int,
                ): BufferedImage {
                    calls += text
                    if (calls.size == 1) throw failure
                    return rasterizedImage
                }
            }
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val codepoints = intArrayOf(0x41, 0x2764, 0xFE0F, 0x42)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    painter.paintCluster(g, codepoints, 1, 2, 0, 0, 1, METRICS)
                },
            )
            repeat(2) {
                assertTrue(painter.paintCluster(g, codepoints, 1, 2, 0, 0, 1, METRICS))
            }
            assertEquals(listOf("\u2764\uFE0F", "\u2764\uFE0F"), calls)
        } finally {
            g.dispose()
        }
    }

    private class FakeEmojiRasterizer(
        override val available: Boolean = true,
        private val supported: Boolean = true,
    ) : TerminalPlatformEmojiRasterizer {
        val texts = mutableListOf<String>()
        val pixelSizes = mutableListOf<Int>()

        override fun rasterize(
            text: String,
            pixelSize: Int,
        ): BufferedImage? {
            texts += text
            pixelSizes += pixelSize
            if (!supported) return null
            return BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB).also { image ->
                image.setRGB(pixelSize / 2, pixelSize / 2, TEST_RED)
            }
        }
    }

    private class CountingEmojiRasterizer(
        private val image: BufferedImage?,
    ) : TerminalPlatformEmojiRasterizer {
        override val available: Boolean = true
        var calls: Int = 0
            private set

        override fun rasterize(
            text: String,
            pixelSize: Int,
        ): BufferedImage? {
            calls++
            return image
        }
    }

    private companion object {
        private val METRICS =
            SwingMetrics(
                cellWidth = 10,
                cellHeight = 20,
                baseline = 14,
                underlineY = 15,
                strikethroughY = 9,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
    }
}

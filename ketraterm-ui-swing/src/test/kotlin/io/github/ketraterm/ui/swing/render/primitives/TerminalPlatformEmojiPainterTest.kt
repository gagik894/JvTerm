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

import io.github.ketraterm.ui.swing.render.TEST_RED
import io.github.ketraterm.ui.swing.render.containsColor
import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    @ValueSource(strings = ["", "e\u0301", "\u05D0\u05D1", "\u6F22", "\u2764\uFE0E", "\uD83D\uDE00\uFE0E"])
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

    @Test
    fun `variation selector 16 cluster is rasterized through platform hook`() {
        val rasterizer = FakeEmojiRasterizer()
        val painter = TerminalPlatformEmojiPainter(rasterizer)
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val codepoints = intArrayOf(0x2764, 0xFE0F)
        try {
            assertTrue(painter.paintCluster(g, codepoints, 0, codepoints.size, 0, 0, 1, METRICS))
        } finally {
            g.dispose()
        }

        assertEquals(listOf("\u2764\uFE0F"), rasterizer.texts)
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

    private class FakeEmojiRasterizer(
        override val available: Boolean = true,
    ) : TerminalPlatformEmojiRasterizer {
        val texts = mutableListOf<String>()

        override fun rasterize(
            text: String,
            pixelSize: Int,
        ): BufferedImage {
            texts += text
            return BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB).also { image ->
                image.setRGB(pixelSize / 2, pixelSize / 2, TEST_RED)
            }
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

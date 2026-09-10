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

import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.min

/**
 * Paints emoji through a native platform text stack when available.
 * Classifies text before accessing the lazy rasterizer so ordinary text cannot trigger native font loading.
 */
internal class TerminalPlatformEmojiPainter(
    rasterizerFactory: () -> TerminalPlatformEmojiRasterizer = { TerminalPlatformEmojiRasterizer.create() },
) {
    constructor(rasterizer: TerminalPlatformEmojiRasterizer) : this({ rasterizer })

    private val rasterizer: TerminalPlatformEmojiRasterizer by lazy(LazyThreadSafetyMode.PUBLICATION) {
        rasterizerFactory()
    }

    private val cache =
        object : LinkedHashMap<EmojiImageKey, BufferedImage>(CACHE_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EmojiImageKey, BufferedImage>?): Boolean = size > CACHE_CAPACITY
        }

    fun paintCodePoint(
        g: Graphics2D,
        codePoint: Int,
        column: Int,
        row: Int,
        columnSpan: Int,
        metrics: SwingMetrics,
    ): Boolean {
        if (!isDefaultEmojiPresentationCodePoint(codePoint) || !rasterizer.available) return false
        val text = String(Character.toChars(codePoint))
        return paintText(g, text, column, row, columnSpan, metrics)
    }

    fun paintCluster(
        g: Graphics2D,
        codepoints: IntArray,
        offset: Int,
        length: Int,
        column: Int,
        row: Int,
        columnSpan: Int,
        metrics: SwingMetrics,
    ): Boolean {
        if (!containsEmojiPresentation(codepoints, offset, length) || !rasterizer.available) return false
        val text = String(codepoints, offset, length)
        return paintText(g, text, column, row, columnSpan, metrics)
    }

    private fun paintText(
        g: Graphics2D,
        text: String,
        column: Int,
        row: Int,
        columnSpan: Int,
        metrics: SwingMetrics,
    ): Boolean {
        val pixelSize = maxOf(1, min(metrics.cellWidth * columnSpan, metrics.cellHeight))
        val key = EmojiImageKey(text, pixelSize)
        val image =
            synchronized(cache) {
                cache[key] ?: rasterizer.rasterize(text, pixelSize)?.also { cache[key] = it }
            } ?: return false

        val cellX = column * metrics.cellWidth
        val cellY = row * metrics.cellHeight
        val cellWidth = metrics.cellWidth * columnSpan
        val x = cellX + (cellWidth - pixelSize) / 2
        val y = cellY + (metrics.cellHeight - pixelSize) / 2
        val oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, x, y, pixelSize, pixelSize, null)
        } finally {
            if (oldInterpolation != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation)
            }
        }
        return true
    }

    private fun containsEmojiPresentation(
        codepoints: IntArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (containsCodePoint(codepoints, offset, length, VARIATION_SELECTOR_15)) return false
        if (containsCodePoint(codepoints, offset, length, VARIATION_SELECTOR_16) ||
            containsCodePoint(codepoints, offset, length, ZERO_WIDTH_JOINER)
        ) {
            return true
        }

        var index = 0
        while (index < length) {
            val codePoint = codepoints[offset + index]
            if (isDefaultEmojiPresentationCodePoint(codePoint)) {
                return true
            }
            index++
        }
        return false
    }

    private fun containsCodePoint(
        codepoints: IntArray,
        offset: Int,
        length: Int,
        needle: Int,
    ): Boolean {
        var index = 0
        while (index < length) {
            if (codepoints[offset + index] == needle) return true
            index++
        }
        return false
    }

    private fun isDefaultEmojiPresentationCodePoint(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x231A..0x231B ||
            codePoint in 0x23E9..0x23EC ||
            codePoint == 0x23F0 ||
            codePoint == 0x23F3 ||
            codePoint in 0x25FD..0x25FE ||
            codePoint in 0x2614..0x2615 ||
            codePoint in 0x2648..0x2653 ||
            codePoint == 0x267F ||
            codePoint == 0x2693 ||
            codePoint == 0x26A1 ||
            codePoint in 0x26AA..0x26AB ||
            codePoint in 0x26BD..0x26BE ||
            codePoint in 0x26C4..0x26C5 ||
            codePoint == 0x26CE ||
            codePoint == 0x26D4 ||
            codePoint == 0x26EA ||
            codePoint in 0x26F2..0x26F3 ||
            codePoint == 0x26F5 ||
            codePoint == 0x26FA ||
            codePoint == 0x26FD ||
            codePoint == 0x2705 ||
            codePoint in 0x270A..0x270B ||
            codePoint == 0x2728 ||
            codePoint == 0x274C ||
            codePoint == 0x274E ||
            codePoint in 0x2753..0x2755 ||
            codePoint == 0x2757 ||
            codePoint in 0x2795..0x2797 ||
            codePoint == 0x27B0 ||
            codePoint == 0x27BF ||
            codePoint in 0x2B1B..0x2B1C ||
            codePoint == 0x2B50 ||
            codePoint == 0x2B55

    private data class EmojiImageKey(
        val text: String,
        val pixelSize: Int,
    )

    private companion object {
        private const val CACHE_CAPACITY = 1024
        private const val LOAD_FACTOR = 0.75f
        private const val VARIATION_SELECTOR_15 = 0xFE0E
        private const val VARIATION_SELECTOR_16 = 0xFE0F
        private const val ZERO_WIDTH_JOINER = 0x200D
    }
}

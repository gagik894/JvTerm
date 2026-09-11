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

import io.github.ketraterm.ui.swing.render.TerminalEmojiPresentation
import io.github.ketraterm.ui.swing.render.cache.TerminalEmojiImageCache
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

    private val cache by lazy(LazyThreadSafetyMode.PUBLICATION) {
        TerminalEmojiImageCache(rasterizerFactory())
    }

    fun paintCodePoint(
        g: Graphics2D,
        codePoint: Int,
        column: Int,
        row: Int,
        columnSpan: Int,
        metrics: SwingMetrics,
    ): Boolean {
        if (!TerminalEmojiPresentation.usesEmojiPresentation(codePoint)) return false
        val pixelSize = pixelSize(metrics, columnSpan)
        val image = cache.codePointImage(codePoint, pixelSize) ?: return false
        return paintImage(g, image, pixelSize, column, row, columnSpan, metrics)
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
        if (!TerminalEmojiPresentation.usesEmojiPresentation(codepoints, offset, length)) return false
        val pixelSize = pixelSize(metrics, columnSpan)
        val image = cache.clusterImage(codepoints, offset, length, pixelSize) ?: return false
        return paintImage(g, image, pixelSize, column, row, columnSpan, metrics)
    }

    private fun pixelSize(
        metrics: SwingMetrics,
        columnSpan: Int,
    ): Int = maxOf(1, min(metrics.cellWidth * columnSpan, metrics.cellHeight))

    private fun paintImage(
        g: Graphics2D,
        image: BufferedImage,
        pixelSize: Int,
        column: Int,
        row: Int,
        columnSpan: Int,
        metrics: SwingMetrics,
    ): Boolean {
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
}

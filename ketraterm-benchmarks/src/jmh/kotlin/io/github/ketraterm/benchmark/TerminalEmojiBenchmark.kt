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
package io.github.ketraterm.benchmark

import io.github.ketraterm.ui.swing.render.cache.TerminalEmojiImageCache
import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import io.github.ketraterm.ui.swing.render.primitives.TerminalPlatformEmojiPainter
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.openjdk.jmh.annotations.*
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit

/**
 * Retained scalar/cluster image and negative-result lookup, plus native-image paint dispatch.
 * Native rasterization is completed during setup with a fixed image; these workloads do not
 * measure OS font loading or rasterization. Graphics and images belong to one JMH worker.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalEmojiBenchmark {
    @Param("false", "true")
    @JvmField
    var cluster: Boolean = false

    @Param("false", "true")
    @JvmField
    var supported: Boolean = false

    private lateinit var cache: TerminalEmojiImageCache
    private lateinit var painter: TerminalPlatformEmojiPainter
    private lateinit var graphics: Graphics2D
    private var rasterizedImage: BufferedImage? = null
    private val codepoints = intArrayOf(0x41, 0x1F469, 0x200D, 0x1F4BB, 0x42)
    private val metrics = SwingMetrics(10, 20, 14, 15, 9, 0, 1)

    @Setup(Level.Trial)
    open fun setup() {
        rasterizedImage = if (supported) BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB) else null
        val rasterizer =
            object : TerminalPlatformEmojiRasterizer {
                override val available = true

                override fun rasterize(
                    text: String,
                    pixelSize: Int,
                ): BufferedImage? = rasterizedImage
            }
        cache = TerminalEmojiImageCache(rasterizer)
        painter = TerminalPlatformEmojiPainter(rasterizer)
        graphics = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB).createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        check(cachedImage() === rasterizedImage)
        check(paintEmoji() == supported)
    }

    @TearDown(Level.Trial)
    open fun dispose() {
        graphics.dispose()
    }

    @Benchmark
    open fun cachedImage(): BufferedImage? = if (cluster) cache.clusterImage(codepoints, 1, 3, 10) else cache.codePointImage(0x1F600, 10)

    /** Includes presentation classification and lazy-cache access; negative hits return before Java2D fallback. */
    @Benchmark
    open fun paintEmoji(): Boolean =
        if (cluster) {
            painter.paintCluster(graphics, codepoints, 1, 3, 0, 0, 1, metrics)
        } else {
            painter.paintCodePoint(graphics, 0x1F600, 0, 0, 1, metrics)
        }

    /** Same retained image, bounds and interpolation policy; unsupported cases return without drawing. */
    @Benchmark
    open fun directImage(): Boolean {
        val image = rasterizedImage ?: return false
        val previousInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(image, 0, 5, 10, 10, null)
        } finally {
            if (previousInterpolation != null) graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previousInterpolation)
        }
        return true
    }
}

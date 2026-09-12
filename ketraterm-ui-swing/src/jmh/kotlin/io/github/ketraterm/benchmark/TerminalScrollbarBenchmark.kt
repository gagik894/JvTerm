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

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.viewport.TerminalScrollbarOverlay
import kotlinx.collections.immutable.persistentListOf
import org.openjdk.jmh.annotations.*
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit

/** Compares a worker-confined scrollbar overlay with the same Java2D thumb and graphics-state restoration. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalScrollbarBenchmark {
    private lateinit var overlay: TerminalScrollbarOverlay
    private lateinit var settings: SwingSettings
    private lateinit var image: BufferedImage
    private lateinit var graphics: Graphics2D
    private val palette = TerminalColorPalette(defaultForeground = Color.RED.rgb)
    private val normalColor = Color(255, 0, 0, 96)
    private val hoverColor = Color(255, 0, 0, 160)
    private var hovered = false

    @Setup
    open fun setup() {
        overlay = TerminalScrollbarOverlay()
        settings = SwingSettings(padding = SwingPadding(0, 4, 8, 10), fallbackFonts = persistentListOf(), useSystemFallbackFonts = false)
        image = BufferedImage(110, 108, BufferedImage.TYPE_INT_ARGB)
        graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        paintOverlay()
    }

    @Benchmark
    open fun paintOverlay(): Int {
        hovered = !hovered
        overlay.handleMoved(if (hovered) 105 else 0, 50, settings, TerminalRenderBufferKind.PRIMARY, 110, 108)
        overlay.paint(graphics, settings, TerminalRenderBufferKind.PRIMARY, palette, 110, 108, 10, 80.0, 200, 100)
        return image.getRGB(105, 50)
    }

    @Benchmark
    open fun paintDirectThumb(): Int {
        hovered = !hovered
        val previousColor = graphics.color
        val previousPaint = graphics.paint
        val previousAntialiasing = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = if (hovered) hoverColor else normalColor
            graphics.fillRoundRect(102, 40, 6, 33, 6, 6)
        } finally {
            graphics.color = previousColor
            graphics.paint = previousPaint
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialiasing)
        }
        return image.getRGB(105, 50)
    }

    @TearDown
    open fun tearDown() {
        graphics.dispose()
    }
}

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

import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderColorKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.cache.*
import io.github.ketraterm.ui.swing.render.painter.TerminalDecorationPainter
import io.github.ketraterm.ui.swing.render.painter.TerminalTextPainter
import io.github.ketraterm.ui.swing.render.painter.TerminalTextRunStyle
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import kotlinx.collections.immutable.toImmutableList
import org.openjdk.jmh.annotations.*
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit

/**
 * Warm text lookup, run scanning and Java2D submission workloads. Each worker owns
 * its images, graphics and painters; no Swing component is accessed off the EDT.
 * Direct drawing controls expose platform Java2D costs separately from renderer work.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalTextRenderingBenchmark {
    @Param("false", "true")
    @JvmField
    var antialiased: Boolean = false

    private lateinit var settings: SwingSettings
    private lateinit var metrics: SwingMetrics
    private lateinit var context: FontRenderContext
    private lateinit var asciiGraphics: Graphics2D
    private lateinit var styledGraphics: Graphics2D
    private lateinit var batchGraphics: Graphics2D
    private lateinit var asciiPainter: TerminalTextPainter
    private lateinit var styledPainter: TerminalTextPainter
    private lateinit var asciiCache: TerminalRenderCache
    private lateinit var styledCache: TerminalRenderCache
    private lateinit var styleCache: TerminalRenderCache
    private val runStyle = TerminalTextRunStyle()
    private var hoverActive = false
    private val asciiChars = "AAAAAA".toCharArray()
    private lateinit var asciiVector: GlyphVector
    private var asciiUsesDrawChars = false
    private val styledText = "אבג".repeat(8)
    private val colors = arrayOf(Color.RED, Color.WHITE)
    private lateinit var styledVector: GlyphVector
    private val shapingCache = TerminalShapedGlyphVectorCache()
    private val contextualChars = "بَبب".toCharArray()
    private val contextualOwners = intArrayOf(0, 0, 1, 2)
    private lateinit var shapingFonts: FontCache
    private lateinit var clippedRun: TerminalShapedGlyphVectorCache.Run
    private lateinit var directBatch: GlyphVector

    @Setup(Level.Trial)
    open fun setup() {
        settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                useSystemFallbackFonts = false,
                textAntialiasing = if (antialiased) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
            )
        asciiGraphics = BufferedImage(500, 40, BufferedImage.TYPE_INT_ARGB).createGraphics()
        styledGraphics = BufferedImage(500, 40, BufferedImage.TYPE_INT_ARGB).createGraphics()
        batchGraphics = BufferedImage(135 * CELL_WIDTH, 40, BufferedImage.TYPE_INT_ARGB).createGraphics()
        for (graphics in arrayOf(asciiGraphics, styledGraphics, batchGraphics)) {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
            graphics.font = settings.font
            graphics.color = Color.WHITE
        }
        metrics = SwingMetrics.from(asciiGraphics.getFontMetrics(settings.font))
        context = asciiGraphics.fontRenderContext
        asciiCache = TerminalRenderCache(asciiChars.size, 1).apply { accept(TerminalRenderBenchmarkFrame(listOf(String(asciiChars)))) }
        val attrs =
            LongArray(styledText.length) { column ->
                TerminalRenderAttrs.pack(
                    foregroundKind = TerminalRenderColorKind.RGB,
                    foregroundValue = colors[column % colors.size].rgb and 0x00FF_FFFF,
                )
            }
        styledCache =
            TerminalRenderCache(styledText.length, 1).apply { accept(TerminalRenderBenchmarkFrame(listOf(styledText), attributes = attrs)) }
        styleCache =
            TerminalRenderCache(80, 1).apply {
                accept(TerminalRenderBenchmarkFrame(listOf("A".repeat(80))))
                hyperlinkIds.fill(7)
            }
        val asciiColors = AwtColorCache()
        asciiPainter = TerminalTextPainter(asciiColors, TerminalDecorationPainter(asciiColors)).apply { updateSettings(settings) }
        val styledColors = AwtColorCache()
        styledPainter = TerminalTextPainter(styledColors, TerminalDecorationPainter(styledColors)).apply { updateSettings(settings) }
        asciiGraphics.clipRect(0, 0, asciiChars.size * metrics.cellWidth, metrics.cellHeight)
        styledGraphics.setClip(0, 0, 500, 40)
        paintAscii()
        paintStyledHebrew()
        val font = asciiPainter.font(Font.PLAIN)
        asciiUsesDrawChars = TerminalAsciiDrawCharsCache().canDrawChars(font, Font.PLAIN, metrics.cellWidth, context)
        asciiVector =
            TerminalAsciiGlyphVectorCache().glyphVector(
                asciiChars,
                0,
                asciiChars.size,
                font,
                Font.PLAIN,
                metrics.cellWidth,
                context,
            )
        styledVector = settings.font.layoutGlyphVector(context, styledText.toCharArray(), 0, styledText.length, Font.LAYOUT_RIGHT_TO_LEFT)
        for (glyph in 0..styledVector.numGlyphs) {
            val position = styledVector.getGlyphPosition(glyph)
            position.setLocation(glyph * metrics.cellWidth.toDouble(), position.y)
            styledVector.setGlyphPosition(glyph, position)
        }
        shapingFonts = FontCache().apply { update(Font(Font.SERIF, Font.PLAIN, 18), emptyList(), useSystemFallbackFonts = false) }
        contextualShapingCacheHit()

        val fontRecorder = BatchRecordingFont()
        val batchFonts = FontCache().apply { update(fontRecorder, emptyList(), useSystemFallbackFonts = false) }
        val text = "אבג".repeat(45)
        clippedRun =
            TerminalShapedGlyphVectorCache().run(
                text.toCharArray(),
                text.length,
                IntArray(text.length) { it },
                text.length,
                Font.PLAIN,
                CELL_WIDTH,
                batchFonts,
                context,
                rtl = true,
            )
        check(clippedRun.glyphVector.font === fontRecorder)
        // Record the actual retained batch so the control submits identical glyphs,
        // positions and context even when the JDK copies vectors for its native pipeline.
        directBatch =
            fontRecorder.batches.single {
                val bounds = it.visualBounds
                bounds.maxX > CLIP_START && bounds.minX < CLIP_END
            }
        check(directBatch.numGlyphs < clippedRun.glyphVector.numGlyphs)
        batchGraphics.clipRect(CLIP_START, 0, CLIP_END - CLIP_START, 40)
    }

    @TearDown(Level.Trial)
    open fun dispose() {
        asciiGraphics.dispose()
        styledGraphics.dispose()
        batchGraphics.dispose()
    }

    @Benchmark
    open fun paintAscii() {
        asciiGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        asciiGraphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
        asciiPainter.paintRow(asciiGraphics, asciiCache, settings.palette, metrics, 0, asciiGraphics.fontRenderContext)
    }

    @Benchmark
    open fun directAscii() {
        if (asciiUsesDrawChars) {
            asciiGraphics.drawChars(asciiChars, 0, asciiChars.size, 0, metrics.baseline)
        } else {
            asciiGraphics.drawGlyphVector(asciiVector, 0f, metrics.baseline.toFloat())
        }
    }

    @Benchmark
    open fun paintStyledHebrew() {
        styledPainter.paintRow(styledGraphics, styledCache, settings.palette, metrics, 0, context)
    }

    @Benchmark
    open fun directStyledHebrew() {
        for (column in styledText.indices) {
            val oldClip = styledGraphics.clip
            try {
                styledGraphics.clipRect(column * metrics.cellWidth, 0, metrics.cellWidth, metrics.cellHeight)
                styledGraphics.color = colors[(styledText.length - column - 1) % colors.size]
                styledGraphics.drawGlyphVector(styledVector, 0f, metrics.baseline.toFloat())
            } finally {
                styledGraphics.clip = oldClip
            }
        }
    }

    @Benchmark
    open fun contextualShapingCacheHit(): Any =
        shapingCache.run(
            contextualChars,
            contextualChars.size,
            contextualOwners,
            3,
            Font.PLAIN,
            CELL_WIDTH,
            shapingFonts,
            context,
            rtl = true,
        )

    @Benchmark
    open fun drawClippedShapedBatch() {
        clippedRun.draw(batchGraphics, 0f, 26f, CLIP_START.toFloat(), CLIP_END.toFloat())
    }

    @Benchmark
    open fun directShapedBatch() {
        batchGraphics.drawGlyphVector(directBatch, 0f, 26f)
    }

    @Benchmark
    open fun scanHyperlinkStyles(): Int {
        hoverActive = !hoverActive
        runStyle.configureRow(
            row = 0,
            textBlinkVisible = hoverActive,
            hyperlinkIds = styleCache.hyperlinkIds,
            hoveredHyperlinkId = 7,
            hoveredHyperlinkStartRow = 0,
            hoveredHyperlinkStartColumn = 20,
            hoveredHyperlinkEndRow = 0,
            hoveredHyperlinkEndColumn = 60,
            hyperlinkActivationHover = hoverActive,
            hyperlinkActivationForeground = 0xFF4DA3FF.toInt(),
        )
        runStyle.begin(styleCache, styleCache.palette, 0, 0)
        var matches = 0
        var column = 1
        while (column < styleCache.columns) {
            if (runStyle.matches(styleCache, styleCache.palette, 0, column)) {
                matches++
            } else {
                runStyle.begin(styleCache, styleCache.palette, 0, column)
            }
            column++
        }
        return matches
    }

    private class BatchRecordingFont : Font(SERIF, PLAIN, 18) {
        val batches = mutableListOf<GlyphVector>()

        override fun createGlyphVector(
            context: FontRenderContext,
            glyphCodes: IntArray,
        ): GlyphVector = super.createGlyphVector(context, glyphCodes).also { batches.add(it) }
    }

    private companion object {
        const val CELL_WIDTH = 24
        const val CLIP_START = 31 * CELL_WIDTH
        const val CLIP_END = 32 * CELL_WIDTH
    }
}

/** Unchanged font settings, retained unsupported-glyph lookup and primary/alternate chrome reads. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalFontConfigurationBenchmark {
    @Param("0", "1", "3")
    @JvmField
    var fallbackCount: Int = 0

    private lateinit var settings: SwingSettings
    private val fonts = FontCache()

    @Setup(Level.Trial)
    open fun setup() {
        val font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        settings =
            SwingSettings(font = font, fallbackFonts = List(fallbackCount) { font }.toImmutableList(), useSystemFallbackFonts = false)
        fonts.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts)
        fonts.fontForCodePoint(0x10FFFF, Font.PLAIN)
    }

    @Benchmark
    open fun unchangedFontAndChrome(): Int {
        var checksum = if (fonts.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts)) -1 else 0
        checksum += fonts.fontForCodePoint(0x10FFFF, Font.PLAIN).style
        checksum += SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY)
        checksum += SwingTerminalChrome.verticalInset(settings, TerminalRenderBufferKind.PRIMARY)
        checksum += SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE)
        checksum += SwingTerminalChrome.verticalInset(settings, TerminalRenderBufferKind.ALTERNATE)
        return checksum
    }
}

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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandRecord
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

class GridPainterTest {
    @Test
    fun `ascii runs paint contiguous measured cells`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = RED,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(TextFrame(text = "ii", cursorVisible = false, palette = settings.palette))

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertTrue(
            image.containsColorInRange(RED, xStart = metrics.cellWidth, xEnd = metrics.cellWidth * 2),
            "second glyph was not painted in the second terminal cell",
        )
    }

    @Test
    fun `ascii run fallback preserves grid cells when measured width differs`() {
        val image = BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.SERIF, Font.PLAIN, 18),
                palette =
                    TerminalColorPalette(
                        defaultForeground = RED,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                fractionalMetrics = RenderingHints.VALUE_FRACTIONALMETRICS_ON,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
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
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(TextFrame(text = "ii", cursorVisible = false, palette = settings.palette))

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertTrue(
            image.containsColorInRange(RED, xStart = 0, xEnd = metrics.cellWidth),
            "first glyph was not painted in the first terminal cell",
        )
        assertTrue(
            image.containsColorInRange(RED, xStart = metrics.cellWidth, xEnd = metrics.cellWidth * 2),
            "fallback glyph vector did not paint the second glyph in the second terminal cell",
        )
    }

    @Test
    fun `block cursor redraws covered glyph with cursor foreground`() {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                        cursorForeground = RED,
                        cursorBackground = BLUE,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 1, rows = 1)
        cache.updateFrom(TextFrame(text = "A", cursorVisible = true, palette = settings.palette))

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertEquals(BLUE, image.getRGB(1, 1))
        assertTrue(
            image.containsColor(RED, metrics.cellWidth, metrics.cellHeight),
            "cursor foreground glyph was not painted over the block cursor",
        )
    }

    @Test
    fun `complex code point paints with foreground color`() {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = RED,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 1, rows = 1)
        cache.updateFrom(TextFrame(text = "\u03A9", cursorVisible = false, palette = settings.palette))

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertTrue(
            image.containsColor(RED, metrics.cellWidth, metrics.cellHeight),
            "complex code point was not painted with the foreground color",
        )
    }

    @Test
    fun `block cursor redraws covered complex code point with cursor foreground`() {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                        cursorForeground = GREEN,
                        cursorBackground = BLUE,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 1, rows = 1)
        cache.updateFrom(TextFrame(text = "\u03A9", cursorVisible = true, palette = settings.palette))

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertEquals(BLUE, image.getRGB(1, 1))
        assertTrue(
            image.containsColor(GREEN, metrics.cellWidth, metrics.cellHeight),
            "complex code point was not redrawn over the block cursor",
        )
    }

    @Test
    fun `decorations use extra attributes for underline color and overline`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(
            TextFrame(
                text = "AB",
                cursorVisible = false,
                attrs =
                    longArrayOf(
                        TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                        TerminalRenderAttrs.DEFAULT,
                    ),
                extraAttrs =
                    longArrayOf(
                        TerminalRenderExtraAttrs.pack(
                            underlineColorKind = TerminalRenderColorKind.RGB,
                            underlineColorValue = 0x00FF00,
                        ),
                        TerminalRenderExtraAttrs.pack(overline = true),
                    ),
                palette = settings.palette,
            ),
        )

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertEquals(GREEN, image.getRGB(1, metrics.underlineY))
        assertEquals(WHITE, image.getRGB(metrics.cellWidth + 1, metrics.overlineY))
    }

    @Test
    fun `extra decoration attributes split ascii runs`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(
            TextFrame(
                text = "AB",
                cursorVisible = false,
                attrs =
                    longArrayOf(
                        TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                        TerminalRenderAttrs.pack(underlineStyle = TerminalRenderUnderline.SINGLE),
                    ),
                extraAttrs =
                    longArrayOf(
                        TerminalRenderExtraAttrs.pack(
                            underlineColorKind = TerminalRenderColorKind.RGB,
                            underlineColorValue = 0x00FF00,
                        ),
                        TerminalRenderExtraAttrs.pack(
                            underlineColorKind = TerminalRenderColorKind.RGB,
                            underlineColorValue = 0x0000FF,
                        ),
                    ),
                palette = settings.palette,
            ),
        )

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
        )
        g.dispose()

        assertEquals(GREEN, image.getRGB(1, metrics.underlineY))
        assertEquals(BLUE, image.getRGB(metrics.cellWidth + 1, metrics.underlineY))
    }

    @Test
    fun `selection background uses configurable Swing color`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                selectionBackground = RED,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(TextFrame(text = "AB", cursorVisible = false, palette = settings.palette))

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            selection = CellSelection(0, 0, 1, 0),
        )
        g.dispose()

        assertEquals(RED, image.getRGB(1, 1))
        assertEquals(BLACK, image.getRGB(metrics.cellWidth + 1, 1))
    }

    @Test
    fun `selection adjusts low contrast background to be visible`() {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = BLACK,
                        defaultBackground = WHITE,
                    ),
                selectionBackground = 0x66FFFFFF.toInt(),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 2, rows = 1)
        cache.updateFrom(TextFrame(text = "AB", cursorVisible = false, palette = settings.palette))

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            selection = CellSelection(0, 0, 1, 0),
        )
        g.dispose()

        val rgb = image.getRGB(1, 1)
        assertNotEquals(WHITE, rgb)
    }

    @Test
    fun `shell integration prompt marker paints compact gutter node on prompt row`() {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                shellIntegrationPromptDotColor = GREEN,
                shellIntegrationPromptDotDiameter = 6,
                shellIntegrationDecorationGutterWidth = 8,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 8, 0, 0),
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lines = arrayOf("abc", "def", "ghi"), palette = settings.palette))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordPromptStart(2)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)
        val geometry =
            visualGeometry(
                metrics = metrics,
                rows = cache.rows,
                viewportPixelHeight = image.height,
            )

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            shellIntegrationDecorations = decorations,
            visualGeometry = geometry,
        )
        g.dispose()

        val nodeCenterY = metrics.cellHeight + metrics.cellHeight / 2
        assertEquals(GREEN, image.getRGB(12, metrics.cellHeight / 2))
        assertEquals(GREEN, image.getRGB(12, nodeCenterY))
        assertEquals(BLACK, image.getRGB(0, nodeCenterY))
        assertEquals(BLACK, image.getRGB(image.width - 1, nodeCenterY))
    }

    @Test
    fun `shell integration prompt dot does not offset row painting`() {
        val image = BufferedImage(120, 100, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                selectionBackground = BLUE,
                shellIntegrationPromptDotColor = GREEN,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 0, 0),
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lines = arrayOf("abc", "def", "ghi"), palette = settings.palette))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordPromptStart(2)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)
        val geometry =
            visualGeometry(
                metrics = metrics,
                rows = cache.rows,
                viewportPixelHeight = image.height,
            )

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            selection = CellSelection(0, 2, 3, 2),
            shellIntegrationDecorations = decorations,
            visualGeometry = geometry,
        )
        g.dispose()

        val normalRowTwoTop = metrics.cellHeight * 2
        assertEquals(BLUE, image.getRGB(settings.shellIntegrationDecorationGutterWidth + 1, normalRowTwoTop))
    }

    @Test
    fun `hovered shell integration prompt marker paints a larger button halo`() {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette = TerminalColorPalette(defaultForeground = WHITE, defaultBackground = BLACK),
                shellIntegrationPromptDotColor = GREEN,
                shellIntegrationPromptDotDiameter = 6,
                shellIntegrationDecorationGutterWidth = 12,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 12, 0, 0),
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 3, rows = 1)
        cache.updateFrom(TextRowsFrame(lines = arrayOf("   "), palette = settings.palette))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            shellIntegrationDecorations = decorations,
            hoveredPromptMarkerRow = 0,
        )
        g.dispose()

        val centerY = metrics.cellHeight / 2
        assertEquals(GREEN, image.getRGB(18, centerY))
        assertTrue(image.getRGB(13, centerY) != BLACK)
        var y = 0
        while (y < metrics.cellHeight) {
            assertEquals(BLACK, image.getRGB(settings.padding.left + settings.shellIntegrationDecorationGutterWidth, y))
            y++
        }
    }

    @Test
    fun `fractional scroll keeps prompt dot attached to its prompt row`() {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                shellIntegrationPromptDotColor = GREEN,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 8, 0, 0),
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lines = arrayOf("old", "cmd", "out"), palette = settings.palette))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordPromptStart(2)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)
        val geometry =
            visualGeometry(
                metrics = metrics,
                rows = cache.rows,
                viewportPixelHeight = image.height,
                contentOriginY = -metrics.cellHeight.toDouble(),
            )

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            shellIntegrationDecorations = decorations,
            visualGeometry = geometry,
        )
        g.dispose()

        val nodeCenterY = metrics.cellHeight / 2
        assertNotEquals(BLACK, image.getRGB(12, nodeCenterY))
        assertEquals(BLACK, image.getRGB(image.width - 1, nodeCenterY))
    }

    @Test
    fun `fractional scroll clips translated overscan before bottom padding`() {
        val image = BufferedImage(120, 48, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 0, 8, 0),
                shellIntegrationDecorationGutterWidth = 0,
            )
        val metrics =
            SwingMetrics(
                cellWidth = 10,
                cellHeight = 20,
                baseline = 15,
                underlineY = 16,
                strikethroughY = 10,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(
            TextRowsFrame(
                lines = arrayOf("   ", "   ", "   "),
                palette = settings.palette,
                rowAttrs =
                    longArrayOf(
                        TerminalRenderAttrs.DEFAULT,
                        TerminalRenderAttrs.DEFAULT,
                        TerminalRenderAttrs.pack(
                            backgroundKind = TerminalRenderColorKind.RGB,
                            backgroundValue = 0x00_FF_00,
                        ),
                    ),
            ),
        )
        val geometry =
            visualGeometry(
                metrics = metrics,
                rows = cache.rows,
                viewportPixelHeight = image.height - settings.padding.bottom,
                contentOriginY = -10.0,
            )

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            visualGeometry = geometry,
        )
        g.dispose()

        val bottomPaddingStart = image.height - settings.padding.bottom
        assertEquals(GREEN, image.getRGB(1, bottomPaddingStart - 1))
        assertEquals(BLACK, image.getRGB(1, bottomPaddingStart))
        assertEquals(BLACK, image.getRGB(1, image.height - 1))
    }

    @Test
    fun `shell integration failed command paints red prompt dot and original output rail`() {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                shellIntegrationFailedPromptDotColor = RED,
                shellIntegrationFailedCommandRailColor = RED,
                shellIntegrationDecorationGutterWidth = 8,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 8, 0, 0),
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lines = arrayOf("abc", "def", "ghi"), palette = settings.palette))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordCommandStart(2, includeLine = true)
        state.recordCommandFinished(3, exitCode = 2)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            shellIntegrationDecorations = decorations,
        )
        g.dispose()

        assertEquals(RED, image.getRGB(12, metrics.cellHeight / 2))
        assertTrue(image.getRGB(12, metrics.cellHeight) != RED)
        assertEquals(RED, image.getRGB(12, metrics.cellHeight + 1))
        assertEquals(RED, image.getRGB(12, metrics.cellHeight * 2))
        assertEquals(RED, image.getRGB(12, metrics.cellHeight * 3 - 2))
        assertTrue(image.getRGB(12, metrics.cellHeight * 3 - 1) != RED)
        assertEquals(BLACK, image.getRGB(10, metrics.cellHeight + 1))
        assertEquals(BLACK, image.getRGB(14, metrics.cellHeight + 1))
    }

    @Test
    fun `shell integration successful command paints neutral prompt dot without output decoration`() {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette = TerminalColorPalette(defaultForeground = WHITE, defaultBackground = BLACK),
                shellIntegrationPromptDotColor = GREEN,
                shellIntegrationDecorationGutterWidth = 8,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 8, 0, 0),
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lines = arrayOf("cmd", "out", "done"), palette = settings.palette))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordCommandStart(2, includeLine = true)
        state.recordCommandFinished(3, exitCode = 0)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            shellIntegrationDecorations = decorations,
        )
        g.dispose()

        assertEquals(GREEN, image.getRGB(12, metrics.cellHeight / 2))
        assertEquals(BLACK, image.getRGB(12, metrics.cellHeight + 1))
    }

    @Test
    fun `shell integration prompt dots honor visibility setting`() {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                palette =
                    TerminalColorPalette(
                        defaultForeground = WHITE,
                        defaultBackground = BLACK,
                    ),
                shellIntegrationPromptDotsVisible = false,
                shellIntegrationFailedCommandRailsVisible = false,
                shellIntegrationPromptDotColor = GREEN,
                shellIntegrationFailedPromptDotColor = RED,
                shellIntegrationDecorationGutterWidth = 8,
                textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                padding = SwingPadding(0, 8, 0, 0),
            )
        val metrics = SwingMetrics.from(g.getFontMetrics(settings.font))
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lines = arrayOf("abc", "def", "ghi"), palette = settings.palette))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(2)
        state.recordCommandStart(1, includeLine = true)
        state.recordCommandFinished(3, exitCode = 1)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)

        GridPainter().paint(
            g = g,
            cache = cache,
            settings = settings,
            metrics = metrics,
            width = image.width,
            height = image.height,
            cursorBlinkVisible = true,
            shellIntegrationDecorations = decorations,
        )
        g.dispose()

        assertEquals(BLACK, image.getRGB(4, metrics.cellHeight))
        assertEquals(BLACK, image.getRGB(4, metrics.cellHeight + 1))
    }

    @Test
    fun `shell integration viewport snapshot does not clear session state while scrolled back`() {
        val settings =
            SwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 14),
                padding = SwingPadding(0, 8, 0, 0),
            )
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(
            TextRowsFrame(
                lines = arrayOf("abc", "def", "ghi"),
                palette = settings.palette,
                historySize = 100,
                scrollbackOffset = 20,
                lineIds = longArrayOf(80, 81, 82),
            ),
        )
        val state = TerminalShellIntegrationState()
        state.observeLiveBottomRow(102)
        state.recordPromptStart(80)
        state.recordPromptStart(81)
        val decorations = TerminalShellIntegrationViewportDecorations()

        decorations.updateFrom(state, cache)

        assertTrue(state.hasPromptStartAtLine(80))
        assertTrue(state.hasPromptStartAtLine(81))
        assertTrue(decorations.hasPromptStartAt(1))
        assertTrue(decorations.hasPromptStartAt(0))
    }

    @Test
    fun `shell integration viewport snapshot reports visible marker changes without render cache mutation`() {
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lines = arrayOf("abc", "def", "ghi"), palette = TerminalColorPalette()))
        val state = TerminalShellIntegrationState()
        val decorations = TerminalShellIntegrationViewportDecorations()

        assertTrue(decorations.updateFrom(state, cache))
        assertFalse(decorations.updateFrom(state, cache))

        state.recordPromptStart(1)
        state.recordPromptStart(2)

        assertTrue(decorations.updateFrom(state, cache))
        assertTrue(decorations.hasPromptStartAt(1))
        assertFalse(decorations.updateFrom(state, cache))
    }

    @Test
    fun `shell integration viewport snapshot suppresses guides in alternate screen`() {
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordCommandStart(2, includeLine = true)
        state.recordCommandFinished(3, exitCode = 1)
        val decorations = TerminalShellIntegrationViewportDecorations()
        val primaryCache = TerminalRenderCache(columns = 3, rows = 3)
        primaryCache.updateFrom(
            TextRowsFrame(lines = arrayOf("prompt", "out", "more"), palette = TerminalColorPalette()),
        )
        val alternateCache = TerminalRenderCache(columns = 3, rows = 3)
        alternateCache.updateFrom(
            TextRowsFrame(
                lines = arrayOf("app", "app", "app"),
                palette = TerminalColorPalette(),
                activeBuffer = TerminalRenderBufferKind.ALTERNATE,
            ),
        )

        decorations.updateFrom(state, primaryCache)
        assertTrue(decorations.hasPromptStartAt(0))
        assertTrue(decorations.hasFailedCommandRailAt(1))

        assertTrue(decorations.updateFrom(state, alternateCache))
        assertFalse(decorations.hasPromptStartAt(0))
        assertFalse(decorations.hasFailedCommandRailAt(1))
    }

    @Test
    fun `shell integration viewport snapshot exposes command boundaries and lifecycle`() {
        val cache = TerminalRenderCache(columns = 3, rows = 4)
        cache.updateFrom(
            TextRowsFrame(
                lines = arrayOf("cmd", "aaa", "bbb", "ccc"),
                palette = TerminalColorPalette(),
                lineIds = longArrayOf(10, 11, 11, 12),
            ),
        )
        val state = TerminalShellIntegrationState()
        state.recordCommandStart(10, includeLine = false)
        state.recordCommandFinished(12, exitCode = 1)
        val decorations = TerminalShellIntegrationViewportDecorations()

        decorations.updateFrom(state, cache)

        val commandRecordId = decorations.commandRecordIdAt(0)
        assertTrue(decorations.hasCommandStartAt(0))
        assertFalse(decorations.hasFailedCommandRailAt(0))
        assertTrue(decorations.hasFailedCommandRailAt(1))
        assertTrue(decorations.hasFailedCommandRailAt(2))
        assertTrue(decorations.hasFailedCommandRailAt(3))
        assertTrue(decorations.hasCommandEndAt(3))
        assertTrue(commandRecordId != TerminalShellIntegrationCommandRecord.NONE)
        assertEquals(commandRecordId, decorations.commandRecordIdAt(1))
        assertEquals(commandRecordId, decorations.commandRecordIdAt(3))
        assertEquals(TerminalShellIntegrationCommandLifecycle.FAILED, decorations.commandLifecycleAt(3))
    }

    private fun BufferedImage.containsColorInRange(
        argb: Int,
        xStart: Int,
        xEnd: Int,
    ): Boolean {
        var y = 0
        while (y < height) {
            var x = xStart
            while (x < xEnd) {
                if (getRGB(x, y) == argb) return true
                x++
            }
            y++
        }
        return false
    }

    private fun BufferedImage.containsColor(
        argb: Int,
        width: Int,
        height: Int,
    ): Boolean {
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (getRGB(x, y) == argb) return true
                x++
            }
            y++
        }
        return false
    }

    private fun visualGeometry(
        metrics: SwingMetrics,
        rows: Int,
        viewportPixelHeight: Int,
        contentOriginY: Double = 0.0,
    ): TerminalVisualViewportGeometry =
        TerminalVisualViewportGeometry().also {
            it.updateLayout(
                metrics = metrics,
                rows = rows,
                viewportPixelHeight = viewportPixelHeight,
            )
            it.updateContentOrigin(contentOriginY)
        }

    private class TextFrame(
        private val text: String,
        cursorVisible: Boolean,
        private val attrs: LongArray = LongArray(text.length) { TerminalRenderAttrs.DEFAULT },
        private val extraAttrs: LongArray = LongArray(text.length) { TerminalRenderExtraAttrs.DEFAULT },
        override val palette: TerminalColorPalette = TerminalColorPalette(),
    ) : TerminalRenderFrameReader,
        TerminalRenderFrame {
        override val columns: Int = text.length
        override val rows: Int = 1
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = cursorVisible,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(this)
        }

        override fun lineGeneration(row: Int): Long = 1

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = text[column].code
                attrWords[attrOffset + column] = attrs[column]
                flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                extraAttrWords?.set(extraAttrOffset + column, extraAttrs[column])
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private class TextRowsFrame(
        private val lines: Array<String>,
        override val palette: TerminalColorPalette,
        override val historySize: Int = 0,
        override val scrollbackOffset: Int = 0,
        override val discardedCount: Long = 0L,
        private val lineIds: LongArray = LongArray(lines.size) { row -> row + 1L },
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
        private val rowAttrs: LongArray = LongArray(lines.size) { TerminalRenderAttrs.DEFAULT },
    ) : TerminalRenderFrameReader,
        TerminalRenderFrame {
        override val columns: Int = lines.maxOf { it.length }
        override val rows: Int = lines.size
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(this)
        }

        override fun lineGeneration(row: Int): Long = 1

        override fun lineId(row: Int): Long = lineIds[row]

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            val line = lines[row]
            var column = 0
            while (column < columns) {
                val offset = codeOffset + column
                if (column < line.length) {
                    codeWords[offset] = line[column].code
                    flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                } else {
                    codeWords[offset] = 0
                    flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                }
                attrWords[attrOffset + column] = rowAttrs[row]
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val RED = 0xFFFF0000.toInt()
        private const val GREEN = 0xFF00FF00.toInt()
        private const val BLUE = 0xFF0000FF.toInt()
    }
}

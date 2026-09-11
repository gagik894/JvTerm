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
package io.github.ketraterm.ui.swing.settings

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.render.cache.FontCache
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Canvas
import java.awt.Font
import java.awt.RenderingHints
import javax.swing.SwingUtilities
import kotlin.test.*

class SwingSettingsTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun unchangedFontSettingsRetainUnsupportedGlyphResolution(fallbackCount: Int) {
        val font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val settings =
            SwingSettings(
                font = font,
                fallbackFonts = List(fallbackCount) { font }.toImmutableList(),
                useSystemFallbackFonts = false,
            )
        val cache = FontCache()
        assertTrue(cache.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts))
        val missingGlyphFont = cache.fontForCodePoint(0x10FFFF, Font.PLAIN)

        assertFalse(cache.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts))
        assertSame(missingGlyphFont, cache.fontForCodePoint(0x10FFFF, Font.PLAIN))
        assertEquals(26, SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(4, SwingTerminalChrome.verticalInset(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(4, SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(2, SwingTerminalChrome.verticalInset(settings, TerminalRenderBufferKind.ALTERNATE))
    }

    @Test
    fun settingsFontSnapshotIsIndependentOfHostList() {
        val font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val fonts = mutableListOf(font)
        val settings = SwingSettings(fallbackFonts = fonts.toImmutableList())
        val hash = settings.hashCode()
        fonts.clear()

        assertEquals(listOf(font), settings.fallbackFonts)
        assertEquals(settings, settings.copy())
        assertEquals(hash, settings.copy().hashCode())
    }

    @Test
    fun settingsCopySharesImmutableValues() {
        val settings =
            SwingSettings(
                fallbackFonts = persistentListOf(Font(Font.MONOSPACED, Font.PLAIN, 14)),
                padding = SwingPadding(1, 2, 3, 4),
                alternateScreenPadding = SwingPadding(5, 6, 7, 8),
            )
        val copy = settings.copy(columns = 123)
        assertSame(settings.padding, copy.padding)
        assertSame(settings.alternateScreenPadding, copy.alternateScreenPadding)
        assertSame(settings.fallbackFonts, copy.fallbackFonts)
        assertEquals(80, settings.columns)
        assertEquals(123, copy.columns)
    }

    @Test
    fun replacingPaddingAndFontsLeavesOriginalSettingsUnchanged() {
        val originalFont = Font(Font.MONOSPACED, Font.PLAIN, 14)
        val replacementFont = Font(Font.DIALOG, Font.BOLD, 18)
        val settings = SwingSettings(fallbackFonts = persistentListOf(originalFont))
        val fonts = mutableListOf(replacementFont)
        val copy =
            settings.copy(
                fallbackFonts = fonts.toImmutableList(),
                padding = settings.padding.copy(left = 12),
                alternateScreenPadding = settings.alternateScreenPadding.copy(bottom = 9),
            )
        fonts.clear()

        assertEquals(listOf(originalFont), settings.fallbackFonts)
        assertEquals(listOf(replacementFont), copy.fallbackFonts)
        assertEquals(SwingPadding(0, 4, 4, 6), settings.padding)
        assertEquals(SwingPadding(0, 12, 4, 6), copy.padding)
        assertEquals(SwingPadding(0, 2, 2, 2), settings.alternateScreenPadding)
        assertEquals(SwingPadding(0, 2, 9, 2), copy.alternateScreenPadding)
        assertEquals(settings.font, copy.font)
        assertTrue(settings != copy)
    }

    @Test
    fun settingsRejectInvalidGridSizes() {
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(columns = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(rows = 0)
        }
    }

    @Test
    fun settingsAllowZeroCursorBlinkToDisableBlinking() {
        val settings = SwingSettings(cursorBlinkMillis = 0)

        assertEquals(0, settings.cursorBlinkMillis)
    }

    @Test
    fun metricsArePositiveForMonospacedFont() {
        val component = Canvas()
        val fontMetrics = component.getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val metrics = SwingMetrics.from(fontMetrics)

        assertTrue(metrics.cellWidth > 0)
        assertTrue(metrics.cellHeight > 0)
        assertTrue(metrics.baseline in 0..metrics.cellHeight)
        assertTrue(metrics.cursorStrokeWidth > 0)
    }

    @Test
    fun metricsScaleCellHeightAndBaselineWithLineHeightMultiplier() {
        val component = Canvas()
        val fontMetrics = component.getFontMetrics(Font(Font.MONOSPACED, Font.PLAIN, 14))
        val unscaled = SwingMetrics.from(fontMetrics, 1.0f)
        val scaled = SwingMetrics.from(fontMetrics, 1.5f)

        val expectedHeight = (fontMetrics.height * 1.5f).toInt()
        assertEquals(expectedHeight, scaled.cellHeight)

        val expectedBaselineShift = (scaled.cellHeight - unscaled.cellHeight) / 2
        assertEquals(unscaled.baseline + expectedBaselineShift, scaled.baseline)
        assertEquals(unscaled.cellWidth, scaled.cellWidth)

        // Verify that a very small line height (0.5f) does not crash and baseline is coerced
        val tinyMetrics = SwingMetrics.from(fontMetrics, 0.5f)
        assertTrue(tinyMetrics.cellHeight > 0)
        assertTrue(tinyMetrics.baseline in 0..tinyMetrics.cellHeight)
    }

    @Test
    fun metricsDoNotCrashForVariousFontsSizesAndLineHeights() {
        val component = Canvas()
        val families = arrayOf(Font.MONOSPACED, Font.SERIF, Font.SANS_SERIF, "Cascadia Mono", "Courier New", "Arial")
        val sizes = intArrayOf(8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 48, 72)
        val lineHeights = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f)

        for (family in families) {
            for (size in sizes) {
                val font = Font(family, Font.PLAIN, size)
                val fontMetrics = component.getFontMetrics(font)
                for (lh in lineHeights) {
                    try {
                        val metrics = SwingMetrics.from(fontMetrics, lh)
                        assertTrue(metrics.cellWidth > 0, "Width <= 0 for $family $size lh=$lh")
                        assertTrue(metrics.cellHeight > 0, "Height <= 0 for $family $size lh=$lh")
                        assertTrue(metrics.baseline in 0..metrics.cellHeight, "Baseline $metrics out of bounds for $family $size lh=$lh")
                    } catch (e: Exception) {
                        kotlin.test.fail("Failed for $family $size lh=$lh: ${e.message}")
                    }
                }
            }
        }
    }

    @Test
    fun printMetricsForDebugging() {
        val component = Canvas()
        for (family in listOf("Cascadia Mono", Font.MONOSPACED)) {
            val font = Font(family, Font.PLAIN, 16)
            val fm = component.getFontMetrics(font)
            println("=== FONT: $family 16 ===")
            println("  height=${fm.height} ascent=${fm.ascent} descent=${fm.descent} leading=${fm.leading}")
            for (lh in listOf(0.5f, 0.6f, 1.0f)) {
                val m = SwingMetrics.from(fm, lh)
                println(
                    "  lh=$lh -> cellHeight=${m.cellHeight} baseline=${m.baseline} underlineY=${m.underlineY} strikethroughY=${m.strikethroughY}",
                )
            }
        }
    }

    @Test
    fun settingsDefaultToHighQualityGridSafeTextHints() {
        val settings = SwingSettings()

        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, settings.textAntialiasing)
        assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF, settings.fractionalMetrics)
        assertTrue(settings.fallbackFonts.isNotEmpty())
        assertEquals(true, settings.useSystemFallbackFonts)
        assertEquals(false, settings.treatAmbiguousAsWide)
        assertEquals(0xFF4DA3FF.toInt(), settings.hyperlinkActivationForeground)
        assertEquals(true, settings.visualBellEnabled)
        assertEquals(0x664DA3FF, settings.visualBellColor)
        assertEquals(240, settings.visualBellDurationMillis)
        assertEquals(18, settings.visualBellEdgeThicknessPixels)
        assertEquals(true, settings.shellIntegrationPromptDotsVisible)
        assertEquals(0x8CFFFFFF.toInt(), settings.shellIntegrationPromptDotColor)
        assertEquals(0xFFE74856.toInt(), settings.shellIntegrationFailedPromptDotColor)
        assertEquals(8, settings.shellIntegrationPromptDotDiameter)
        assertEquals(16, settings.shellIntegrationDecorationGutterWidth)
        assertEquals(true, settings.shellIntegrationFailedCommandRailsVisible)
        assertEquals(0xFFE74856.toInt(), settings.shellIntegrationFailedCommandRailColor)
        assertEquals(3, settings.shellIntegrationFailedCommandRailWidth)
        assertEquals(SwingPadding(0, 4, 4, 6), settings.padding)
        assertEquals(SwingPadding(0, 2, 2, 2), settings.alternateScreenPadding)
        assertEquals(0, settings.padding.top)
        assertEquals(4, settings.padding.left)
        assertEquals(4, settings.padding.bottom)
    }

    @Test
    fun settingsRejectInvalidShellIntegrationDecorationDimensions() {
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(visualBellDurationMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(visualBellEdgeThicknessPixels = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(shellIntegrationPromptDotDiameter = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(shellIntegrationDecorationGutterWidth = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(shellIntegrationFailedCommandRailWidth = 0)
        }
    }

    @Test
    fun settingsAcceptCustomPadding() {
        val settings =
            SwingSettings(
                padding = SwingPadding(4, 8, 4, 8),
                alternateScreenPadding = SwingPadding(1, 2, 3, 4),
            )
        assertEquals(SwingPadding(4, 8, 4, 8), settings.padding)
        assertEquals(SwingPadding(1, 2, 3, 4), settings.alternateScreenPadding)
    }

    @Test
    fun alternateScreenChromeUsesExplicitAlternatePadding() {
        val settings = SwingSettings()

        assertEquals(20, SwingTerminalChrome.left(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(6, SwingTerminalChrome.right(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(2, SwingTerminalChrome.left(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(2, SwingTerminalChrome.right(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(26, SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(4, SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(2, SwingTerminalChrome.verticalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(16, SwingTerminalChrome.promptDecorationGutterWidth(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(0, SwingTerminalChrome.promptDecorationGutterWidth(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(true, settings.shellSuggestionsEnabled)
    }

    @Test
    fun alternateScreenChromeDoesNotInheritPrimaryScrollbarGutter() {
        val settings =
            SwingSettings(
                padding = SwingPadding(0, 40, 8, 14),
                alternateScreenPadding = SwingPadding(0, 3, 4, 5),
            )

        assertEquals(56, SwingTerminalChrome.left(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(14, SwingTerminalChrome.right(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(3, SwingTerminalChrome.left(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(5, SwingTerminalChrome.right(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(70, SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY))
        assertEquals(8, SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.ALTERNATE))
        assertEquals(4, SwingTerminalChrome.verticalInset(settings, TerminalRenderBufferKind.ALTERNATE))
    }

    @Test
    fun settingsRejectNegativePaddingEdges() {
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(padding = SwingPadding(0, -1, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            SwingSettings(alternateScreenPadding = SwingPadding(0, 0, 0, -1))
        }
    }

    @Test
    fun resolveFontFamilyReturnsInstalledFontWithSystemCasing() {
        val monospaced = "monospaced"
        val resolved = SwingSettings.resolveFontFamily(monospaced)
        assertTrue(resolved.equals("Monospaced", ignoreCase = true))
    }

    @Test
    fun resolveFontFamilyFallsBackToPreferredMonospaceForMissingFont() {
        val missingFont = "NonExistentFontFamilyName12345"
        val resolved = SwingSettings.resolveFontFamily(missingFont)
        val preferred = listOf("Cascadia Mono", "Cascadia Code", "Consolas", Font.MONOSPACED)
        assertTrue(
            resolved in preferred ||
                java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .availableFontFamilyNames
                    .contains(resolved),
            "Resolved font '$resolved' must be either one of the preferred defaults or an installed system font",
        )
    }

    @Test
    fun getMonospaceFontFamiliesReturnsOnlyInstalledMonospaceFonts() {
        val monospaceFamilies = SwingSettings.getMonospaceFontFamilies()
        assertTrue(monospaceFamilies.isNotEmpty(), "Monospace font families list must not be empty")

        val installedSet =
            java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toSet()

        for (family in monospaceFamilies) {
            assertTrue(
                installedSet.contains(family),
                "Resolved monospace font family '$family' must be installed on the system",
            )
        }
    }

    @Test
    fun fallbackPolicyPrefersInstalledColorEmojiBeforeSymbolFonts() {
        val families =
            SwingSettings.fallbackFontFamiliesForInstalledFonts(
                arrayOf(
                    "Segoe UI Symbol",
                    "Dialog",
                    "Segoe UI Emoji",
                    "SansSerif",
                ),
            )

        assertTrue(families.indexOf("Segoe UI Emoji") < families.indexOf(Font.DIALOG))
        assertTrue(families.indexOf("Segoe UI Emoji") < families.indexOf("Segoe UI Symbol"))
    }

    @Test
    fun fallbackPolicyIncludesInstalledIndicKhmerAndSinhalaFonts() {
        val families =
            SwingSettings.fallbackFontFamiliesForInstalledFonts(
                arrayOf(
                    "Nirmala UI",
                    "Noto Sans Devanagari",
                    "Noto Sans Bengali",
                    "Noto Sans Tamil",
                    "Noto Sans Khmer",
                    "Noto Sans Sinhala",
                    "Khmer UI",
                    "Iskoola Pota",
                ),
            )

        assertTrue("Nirmala UI" in families)
        assertTrue("Noto Sans Devanagari" in families)
        assertTrue("Noto Sans Bengali" in families)
        assertTrue("Noto Sans Tamil" in families)
        assertTrue("Noto Sans Khmer" in families)
        assertTrue("Noto Sans Sinhala" in families)
        assertTrue("Khmer UI" in families)
        assertTrue("Iskoola Pota" in families)
    }

    @Test
    fun settingsDefaultPaletteOwnsSwingThemeColors() {
        val palette = SwingSettings.defaultPalette()

        assertEquals(0xFFF2F2F2.toInt(), palette.defaultForeground)
        assertEquals(0xFF0C0C0C.toInt(), palette.defaultBackground)
        assertEquals(0xFF0C0C0C.toInt(), palette.indexedColor(0))
        assertEquals(0xFFC50F1F.toInt(), palette.indexedColor(1))
    }

    @Test
    fun terminalThemeIdsRoundTripAndAcceptLegacyNames() {
        for (theme in TerminalTheme.entries) {
            assertEquals(theme, TerminalTheme.fromId(theme.id))
            assertEquals(theme, TerminalTheme.fromId(theme.name))
            assertEquals(theme, TerminalTheme.fromId(theme.name.lowercase()))
        }
        assertEquals("one-dark", TerminalTheme.ONE_DARK.id)
        assertEquals("tokyo-night", TerminalTheme.TOKYO_NIGHT.id)
        assertEquals(null, TerminalTheme.fromId("intellij"))
        assertEquals(null, TerminalTheme.fromId("unknown"))
    }

    @Test
    fun terminalThemesRemainPaletteFactoriesForHosts() {
        val palette = TerminalTheme.ONE_DARK.createPalette()

        assertEquals(0xFFABB2BF.toInt(), palette.defaultForeground)
        assertEquals(0xFF1E2127.toInt(), palette.defaultBackground)
    }

    @Test
    fun componentReportsVisibleGridFromFrozenMetrics() {
        val component =
            SwingTerminal(settingsProvider = {
                SwingSettings(columns = 10, rows = 4)
            })
        val preferred = component.preferredSize
        var visibleColumns = 0
        var visibleRows = 0

        SwingUtilities.invokeAndWait {
            component.size = preferred
            val visible = component.visibleGridSize()
            visibleColumns = visible.width
            visibleRows = visible.height
        }

        assertEquals(10, visibleColumns)
        assertEquals(4, visibleRows)
    }

    @Test
    fun preferredGridSizeIncludesPrimaryChrome() {
        val component =
            SwingTerminal(settingsProvider = {
                SwingSettings(columns = 10, rows = 4, padding = SwingPadding(3, 5, 7, 11))
            })
        val cellWidth = (component.preferredGridSize(2, 1).width - component.preferredGridSize(1, 1).width)
        val cellHeight = (component.preferredGridSize(1, 2).height - component.preferredGridSize(1, 1).height)

        assertEquals(10 * cellWidth + 5 + 16 + 11, component.preferredGridSize(10, 4).width)
        assertEquals(4 * cellHeight + 3 + 7, component.preferredGridSize(10, 4).height)
    }
}

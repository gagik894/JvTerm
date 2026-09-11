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
package io.github.ketraterm.ui.swing.render.cache

import io.github.ketraterm.ui.swing.api.TerminalFontResolver
import io.github.ketraterm.ui.swing.render.font.TerminalSystemFontFamilies
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Font

class FontCacheTest {
    @ParameterizedTest
    @ValueSource(strings = ["\u0628\u0628\u200D\u0628", "\u0628\uFE0F", "A\u200D", "\u2764", "\u2764\uFE0E", "\uD83D\uDE00\uFE0E"])
    fun `text presentation keeps primary font when emoji fallback advertises native substitutes`(text: String) {
        val primary = NativeCoverageFont("Text")
        val cache = FontCache()
        cache.update(primary, listOf(NativeCoverageFont("Test Emoji")), useSystemFallbackFonts = false)

        Assertions.assertSame(primary, cache.fontForText(text, Font.PLAIN))
        if (text.codePointCount(0, text.length) == 1) {
            Assertions.assertSame(primary, cache.fontForCodePoint(text.codePointAt(0), Font.PLAIN))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\u0628\u0628\u200D\u0628", "\u0628\uFE0F", "\u2764\uFE0E"])
    fun `text presentation does not request an emoji priority override from host resolver`(text: String) {
        val primary = NativeCoverageFont("Text")
        var resolutions = 0
        val resolver =
            object : TerminalFontResolver {
                override fun resolveFallbackFont(
                    codePoint: Int,
                    style: Int,
                    size2D: Float,
                ): Font = error("Unexpected scalar lookup")

                override fun resolveFallbackFont(
                    text: String,
                    style: Int,
                    size2D: Float,
                ): Font {
                    resolutions++
                    return NativeCoverageFont("Host Emoji", style, size2D)
                }
            }
        val cache = FontCache(fontResolver = resolver)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        Assertions.assertSame(primary, cache.fontForText(text, Font.PLAIN))
        Assertions.assertEquals(0, resolutions)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\uD83D\uDE00", "\u2764\uFE0F", "\uD83D\uDC69\u200D\uD83D\uDCBB", "1\uFE0F\u20E3"])
    fun `emoji presentation still prefers the configured emoji font`(text: String) {
        val cache = FontCache()
        cache.update(NativeCoverageFont("Text"), listOf(NativeCoverageFont("Test Emoji")), useSystemFallbackFonts = false)

        Assertions.assertEquals("Test Emoji", cache.fontForText(text, Font.PLAIN).family)
        if (text.codePointCount(0, text.length) == 1) {
            Assertions.assertEquals("Test Emoji", cache.fontForCodePoint(text.codePointAt(0), Font.PLAIN).family)
        }
    }

    /** Models font coverage that includes native substitution, as with CoreText on macOS. */
    private class NativeCoverageFont(
        private val reportedFamily: String,
        style: Int = PLAIN,
        size: Float = 18f,
    ) : Font(DIALOG, style, size.toInt()) {
        override fun getFamily(): String = reportedFamily

        override fun canDisplay(codePoint: Int): Boolean = true

        override fun canDisplayUpTo(text: String): Int = -1

        override fun deriveFont(
            style: Int,
            size: Float,
        ): Font = NativeCoverageFont(reportedFamily, style, size)
    }

    @Test
    fun replacingCallerFallbackDoesNotChangeConfiguredFontsBeforeUpdate() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val fallback = TerminalCacheTestFonts.fallback(14f)
        val text = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT
        val fallbacks = mutableListOf(fallback)
        val cache = FontCache()
        cache.update(primary, fallbacks, useSystemFallbackFonts = false)

        fallbacks[0] = primary
        Assertions.assertEquals(fallback.family, cache.fontForText(text, Font.BOLD).family)
        Assertions.assertTrue(cache.update(primary, fallbacks, useSystemFallbackFonts = false))
        Assertions.assertEquals(primary.family, cache.fontForText(text, Font.BOLD).family)
    }

    @Test
    fun mutableFallbackInputCannotCrashLookup() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val fallbacks = mutableListOf<Font>()
        val cache = FontCache()
        cache.update(primary, fallbacks, useSystemFallbackFonts = false)

        fallbacks.add(TerminalCacheTestFonts.fallback(14f))

        Assertions.assertSame(primary, cache.fontForCodePoint(0x10FFFF, Font.PLAIN))
        Assertions.assertSame(primary, cache.fontForText(String(Character.toChars(0x10FFFF)), Font.PLAIN))
    }

    @Test
    fun callerMutationTakesEffectOnlyOnExplicitUpdate() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val fallback = TerminalCacheTestFonts.fallback(14f)
        val text = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT
        val codePoint = text.codePointAt(0)
        val fallbacks = mutableListOf(fallback)
        val cache = FontCache()
        cache.update(primary, fallbacks, useSystemFallbackFonts = false)
        val generation = cache.generation

        fallbacks.clear()
        Assertions.assertEquals(fallback.family, cache.fontForCodePoint(codePoint, Font.PLAIN).family)
        Assertions.assertEquals(fallback.family, cache.fontForText(text, Font.PLAIN).family)
        Assertions.assertEquals(generation, cache.generation)

        Assertions.assertTrue(cache.update(primary, fallbacks, useSystemFallbackFonts = false))
        Assertions.assertEquals(generation + 1, cache.generation)
        Assertions.assertSame(primary, cache.fontForCodePoint(codePoint, Font.PLAIN))
        Assertions.assertSame(primary, cache.fontForText(text, Font.PLAIN))

        fallbacks.add(fallback)
        Assertions.assertTrue(cache.update(primary, fallbacks, useSystemFallbackFonts = false))
        Assertions.assertEquals(generation + 2, cache.generation)
        Assertions.assertEquals(fallback.family, cache.fontForText(text, Font.PLAIN).family)
        val resolved = cache.fontForCodePoint(codePoint, Font.PLAIN)
        Assertions.assertFalse(cache.update(primary, fallbacks.toList(), useSystemFallbackFonts = false))
        Assertions.assertSame(resolved, cache.fontForCodePoint(codePoint, Font.PLAIN))
        Assertions.assertEquals(generation + 2, cache.generation)
    }

    @Test
    fun `font returns cached primary style variant`() {
        val base = TerminalCacheTestFonts.primary(14f)
        val cache = FontCache()

        cache.update(base, emptyList(), useSystemFallbackFonts = false)

        Assertions.assertSame(base, cache.font(Font.PLAIN))
        Assertions.assertSame(cache.font(Font.BOLD), cache.font(Font.BOLD))
    }

    @Test
    fun `update reports whether font settings changed`() {
        val base = TerminalCacheTestFonts.primary(14f)
        val fallback = TerminalCacheTestFonts.fallback(14f)
        val cache = FontCache()

        Assertions.assertTrue(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        Assertions.assertFalse(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        Assertions.assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = false))
        Assertions.assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = true))
    }

    @Test
    fun `generation changes only when font settings change`() {
        val base = TerminalCacheTestFonts.primary(14f)
        val fallback = TerminalCacheTestFonts.fallback(14f)
        val cache = FontCache()

        val initialGeneration = cache.generation
        Assertions.assertTrue(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        val firstGeneration = cache.generation
        Assertions.assertFalse(cache.update(base, emptyList(), useSystemFallbackFonts = false))
        Assertions.assertEquals(firstGeneration, cache.generation)
        Assertions.assertTrue(cache.update(base, listOf(fallback), useSystemFallbackFonts = false))

        Assertions.assertEquals(initialGeneration + 1, firstGeneration)
        Assertions.assertEquals(firstGeneration + 1, cache.generation)
    }

    @Test
    fun `fontForText uses configured fallback when primary cannot display text`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val fallback = TerminalCacheTestFonts.fallback(11f)
        val fallbackOnlyText = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT

        Assertions.assertTrue(primary.canDisplayUpTo(fallbackOnlyText) >= 0)
        Assertions.assertTrue(fallback.canDisplayUpTo(fallbackOnlyText) < 0)

        val cache = FontCache()
        cache.update(primary, listOf(fallback), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(fallbackOnlyText, Font.BOLD)

        Assertions.assertEquals(Font.BOLD, resolved.style)
        Assertions.assertEquals(primary.size2D, resolved.size2D)
        Assertions.assertEquals(fallback.family, resolved.family)
    }

    @Test
    fun `fontForText caches fallback fonts per style`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val fallback = TerminalCacheTestFonts.fallback(11f)
        val fallbackOnlyText = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT

        Assertions.assertTrue(primary.canDisplayUpTo(fallbackOnlyText) >= 0)
        Assertions.assertTrue(fallback.canDisplayUpTo(fallbackOnlyText) < 0)

        val cache = FontCache()
        cache.update(primary, listOf(fallback), useSystemFallbackFonts = false)

        val plain = cache.fontForText(fallbackOnlyText, Font.PLAIN)
        val bold = cache.fontForText(fallbackOnlyText, Font.BOLD)

        Assertions.assertEquals(Font.PLAIN, plain.style)
        Assertions.assertEquals(Font.BOLD, bold.style)
        Assertions.assertEquals(fallback.family, plain.family)
        Assertions.assertEquals(fallback.family, bold.family)
    }

    @Test
    fun `fontForText caches missing glyph resolution to primary font`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val missing = String(Character.toChars(TerminalCacheTestFonts.MISSING_CODE_POINT))

        Assertions.assertTrue(primary.canDisplayUpTo(missing) >= 0)

        val cache = FontCache()
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(missing, Font.PLAIN)

        Assertions.assertSame(primary, resolved)
        Assertions.assertSame(primary, cache.resolvedTextFontCache(Font.PLAIN)[missing])
    }

    @Test
    fun `fontForText caches missing glyph resolution per style`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val missing = String(Character.toChars(TerminalCacheTestFonts.MISSING_CODE_POINT))

        Assertions.assertTrue(primary.canDisplayUpTo(missing) >= 0)

        val cache = FontCache()
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(missing, Font.BOLD)

        Assertions.assertEquals(Font.BOLD, resolved.style)
        Assertions.assertSame(resolved, cache.resolvedTextFontCache(Font.BOLD)[missing])
        Assertions.assertNull(cache.resolvedTextFontCache(Font.PLAIN)[missing])
    }

    @Test
    fun `fontForText evicts old cluster fallback entries`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val cache = FontCache(textFallbackCapacityPerStyle = 2)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        cache.fontForText(String(Character.toChars(0x10FFFF)), Font.PLAIN)
        cache.fontForText(String(Character.toChars(0x10FFFE)), Font.PLAIN)
        cache.fontForText(String(Character.toChars(0x10FFFD)), Font.PLAIN)

        Assertions.assertEquals(2, cache.resolvedTextFontCache(Font.PLAIN).size)
    }

    @Test
    fun `fontForCodePoint uses primitive bounded cache`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        val codePoint = TerminalCacheTestFonts.MISSING_CODE_POINT

        Assertions.assertFalse(primary.canDisplay(codePoint))

        val cache = FontCache(codePointFallbackCapacityPerStyle = 2)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForCodePoint(codePoint, Font.BOLD)

        Assertions.assertEquals(Font.BOLD, resolved.style)
        Assertions.assertEquals(1, cache.resolvedCodePointFontCacheSize(Font.BOLD))
        Assertions.assertTrue(cache.resolvedTextFontCache(Font.BOLD).isEmpty())
    }

    @Test
    fun `fontForCodePoint evicts old primitive fallback entries`() {
        val primary = TerminalCacheTestFonts.primary(14f)
        Assertions.assertFalse(primary.canDisplay(0x10FFFF))
        Assertions.assertFalse(primary.canDisplay(0x10FFFE))
        Assertions.assertFalse(primary.canDisplay(0x10FFFD))

        val cache = FontCache(codePointFallbackCapacityPerStyle = 2)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        cache.fontForCodePoint(0x10FFFF, Font.PLAIN)
        cache.fontForCodePoint(0x10FFFE, Font.PLAIN)
        cache.fontForCodePoint(0x10FFFD, Font.PLAIN)

        Assertions.assertEquals(2, cache.resolvedCodePointFontCacheSize(Font.PLAIN))
    }

    @Test
    fun `system fallback uses lazy family fonts after configured fallbacks miss`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val fallbackFamily = TerminalCacheTestFonts.registerFallbackFamily()
        val systemFamilies = RecordingSystemFontFamilies(listOf(fallbackFamily))
        val fallbackOnlyText = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT

        Assertions.assertTrue(primary.canDisplayUpTo(fallbackOnlyText) >= 0)

        val cache = FontCache(systemFontFamilies = systemFamilies)
        cache.update(primary, emptyList(), useSystemFallbackFonts = true)
        Assertions.assertEquals(0, systemFamilies.calls)

        val resolved = cache.fontForText(fallbackOnlyText, Font.PLAIN)

        Assertions.assertEquals(fallbackFamily, resolved.family)
        Assertions.assertEquals(1, systemFamilies.calls)
    }

    @Suppress("UNCHECKED_CAST")
    private fun FontCache.resolvedTextFontCache(style: Int): Map<String, Font> {
        val field = FontCache::class.java.getDeclaredField("resolvedTextFonts")
        field.isAccessible = true
        val caches = field.get(this) as Array<Map<String, Font>>
        return caches[style and (Font.BOLD or Font.ITALIC)]
    }

    @Suppress("UNCHECKED_CAST")
    private fun FontCache.resolvedCodePointFontCacheSize(style: Int): Int {
        val field = FontCache::class.java.getDeclaredField("resolvedCodePointFonts")
        field.isAccessible = true
        val caches = field.get(this) as Array<Any>
        val sizeField = caches[style and (Font.BOLD or Font.ITALIC)].javaClass.getDeclaredField("size")
        sizeField.isAccessible = true
        return sizeField.getInt(caches[style and (Font.BOLD or Font.ITALIC)])
    }

    private class RecordingSystemFontFamilies(
        private val families: List<String>,
    ) : TerminalSystemFontFamilies {
        var calls = 0
            private set

        override fun familiesOrStartLoading(): List<String> {
            calls++
            return families
        }
    }

    @Test
    fun `custom fontResolver resolves and caches code point fallbacks`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val expectedFallback = Font(Font.SANS_SERIF, Font.PLAIN, 17)
        val fallbackChar = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT.codePointAt(0)
        val resolver =
            object : TerminalFontResolver {
                override fun resolveFallbackFont(
                    codePoint: Int,
                    style: Int,
                    size2D: Float,
                ): Font? {
                    if (codePoint == fallbackChar) return expectedFallback
                    return null
                }

                override fun resolveFallbackFont(
                    text: String,
                    style: Int,
                    size2D: Float,
                ): Font? = null
            }

        val cache = FontCache(fontResolver = resolver)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForCodePoint(fallbackChar, Font.PLAIN)
        Assertions.assertEquals(Font.SANS_SERIF, resolved.family)
        Assertions.assertEquals(17f, resolved.size2D)
    }

    @Test
    fun `custom fontResolver resolves and caches text fallbacks`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val expectedFallback = Font(Font.SERIF, Font.PLAIN, 17)
        val fallbackText = TerminalCacheTestFonts.FALLBACK_ONLY_TEXT
        val resolver =
            object : TerminalFontResolver {
                override fun resolveFallbackFont(
                    codePoint: Int,
                    style: Int,
                    size2D: Float,
                ): Font? = null

                override fun resolveFallbackFont(
                    text: String,
                    style: Int,
                    size2D: Float,
                ): Font? {
                    if (text == fallbackText) return expectedFallback
                    return null
                }
            }

        val cache = FontCache(fontResolver = resolver)
        cache.update(primary, emptyList(), useSystemFallbackFonts = false)

        val resolved = cache.fontForText(fallbackText, Font.PLAIN)
        Assertions.assertEquals(Font.SERIF, resolved.family)
        Assertions.assertEquals(17f, resolved.size2D)
    }

    @Test
    fun `custom fontResolver returns null falls back to normal chain`() {
        val primary = TerminalCacheTestFonts.primary(17f)
        val fallbackFamily = TerminalCacheTestFonts.registerFallbackFamily()
        val resolver =
            object : TerminalFontResolver {
                override fun resolveFallbackFont(
                    codePoint: Int,
                    style: Int,
                    size2D: Float,
                ): Font? = null

                override fun resolveFallbackFont(
                    text: String,
                    style: Int,
                    size2D: Float,
                ): Font? = null
            }
        val fallback = Font(fallbackFamily, Font.PLAIN, 17)
        val cache = FontCache(fontResolver = resolver)
        cache.update(primary, listOf(fallback), useSystemFallbackFonts = false)

        val resolved = cache.fontForCodePoint(TerminalCacheTestFonts.FALLBACK_ONLY_TEXT.codePointAt(0), Font.PLAIN)
        Assertions.assertEquals(fallbackFamily, resolved.family)
    }
}

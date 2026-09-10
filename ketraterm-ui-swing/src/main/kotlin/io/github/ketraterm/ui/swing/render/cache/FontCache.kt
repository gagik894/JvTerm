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
import io.github.ketraterm.ui.swing.render.font.TerminalSystemFallbackFonts
import io.github.ketraterm.ui.swing.render.font.TerminalSystemFontFamilies
import java.awt.Font
import java.util.*

/**
 * Caches terminal font style variants and resolved fallbacks for one settings snapshot.
 *
 * Single code points use a primitive-keyed bounded cache to prevent hostile streams
 * of unique Unicode cells from retaining unbounded strings in memory.
 *
 * **Thread Safety:** Not thread-safe. This cache must only be accessed
 * from the Swing Event Dispatch Thread (EDT).
 */
internal class FontCache(
    codePointFallbackCapacityPerStyle: Int = DEFAULT_CODE_POINT_FALLBACK_CAPACITY_PER_STYLE,
    textFallbackCapacityPerStyle: Int = DEFAULT_TEXT_FALLBACK_CAPACITY_PER_STYLE,
    private val systemFontFamilies: TerminalSystemFontFamilies = TerminalSystemFallbackFonts,
    private val fontResolver: TerminalFontResolver? = null,
) {
    init {
        require(codePointFallbackCapacityPerStyle > 0) {
            "codePointFallbackCapacityPerStyle must be > 0, was $codePointFallbackCapacityPerStyle"
        }
        require(textFallbackCapacityPerStyle > 0) {
            "textFallbackCapacityPerStyle must be > 0, was $textFallbackCapacityPerStyle"
        }
    }

    private var baseFont: Font? = null
    private var fallbackBaseFonts: List<Font> = emptyList()
    private var systemFallbackFamilies: List<String> = emptyList()
    private var useSystemFallbackFonts: Boolean = false
    private val styleFonts = arrayOfNulls<Font>(STYLE_COUNT)
    private var fallbackStyleFonts: Array<Array<Font?>> = emptyArray()
    private val systemFontCache = SystemFontLru(DEFAULT_SYSTEM_FONT_CACHE_CAPACITY)
    private val resolvedCodePointFonts =
        Array(STYLE_COUNT) {
            IntFontLru(codePointFallbackCapacityPerStyle)
        }
    private val resolvedTextFonts =
        Array(STYLE_COUNT) {
            StringFontLru(textFallbackCapacityPerStyle)
        }
    private var fontGeneration: Int = 0

    /**
     * Increments whenever font resolution can produce different fonts.
     */
    val generation: Int
        get() = fontGeneration

    /**
     * Rebuilds the primary and fallback font pipelines for a new settings snapshot.
     *
     * This method evaluates whether the core typography configuration has changed.
     * If so, it discards all cached style variants, reallocates the fallback
     * arrays, and invalidates all dynamically resolved glyphs. Fallback inputs
     * are snapshotted only on a configuration change; caller mutations take
     * effect only through a subsequent update.
     *
     * @param font The primary base font for the terminal grid.
     * @param fallbackFonts A prioritized list of fallback fonts for missing glyphs.
     * @param useSystemFallbackFonts Whether to query the host OS for additional fonts.
     * @return `true` if the configuration changed and caches were invalidated;
     * `false` if the provided configuration perfectly matches the current state.
     */
    fun update(
        font: Font,
        fallbackFonts: List<Font>,
        useSystemFallbackFonts: Boolean,
    ): Boolean {
        if (
            font == baseFont &&
            matchesFallbackFonts(fallbackFonts) &&
            useSystemFallbackFonts == this.useSystemFallbackFonts
        ) {
            return false
        }

        baseFont = font
        fallbackBaseFonts = fallbackFonts.toList()
        this.useSystemFallbackFonts = useSystemFallbackFonts

        styleFonts.fill(null)
        styleFonts[font.style and STYLE_MASK] = font
        fallbackStyleFonts = Array(fallbackBaseFonts.size) { arrayOfNulls(STYLE_COUNT) }

        systemFallbackFamilies = emptyList()
        systemFontCache.clear()

        invalidateResolvedCaches()
        return true
    }

    private fun matchesFallbackFonts(fonts: List<Font>): Boolean {
        if (fonts.size != fallbackBaseFonts.size) return false
        // List equality can allocate iterators; configuration is checked on every paint.
        for (index in fonts.indices) {
            if (fonts[index] != fallbackBaseFonts[index]) return false
        }
        return true
    }

    /**
     * Returns a cached font variant for [style].
     *
     * @param style AWT style bit mask.
     * @return cached style font.
     */
    fun font(style: Int): Font {
        val normalizedStyle = style and STYLE_MASK
        val cached = styleFonts[normalizedStyle]
        if (cached != null) return cached

        val font =
            requireNotNull(baseFont) {
                "FontCache.update must be called before font"
            }.deriveFont(normalizedStyle)
        styleFonts[normalizedStyle] = font
        return font
    }

    /**
     * Returns the first cached style font that can display [codePoint].
     *
     * Single code points use a primitive-keyed bounded cache so hostile streams
     * of unique Unicode cells cannot retain unbounded strings.
     */
    fun fontForCodePoint(
        codePoint: Int,
        style: Int,
    ): Font {
        val normalizedStyle = style and STYLE_MASK
        val styleResolvedFonts = resolvedCodePointFonts[normalizedStyle]
        val cached = styleResolvedFonts[codePoint]
        if (cached != null) return cached

        val primary = font(normalizedStyle)
        val isEmoji = isEmojiPresentationCodePoint(codePoint)

        if (isEmoji && fontResolver != null) {
            val resolved = fontResolver.resolveFallbackFont(codePoint, normalizedStyle, primary.size2D)
            if (resolved != null) {
                val derived = resolved.deriveFont(Font.PLAIN, primary.size2D)
                styleResolvedFonts.put(codePoint, derived)
                return derived
            }
        }

        if (isEmoji) {
            val emojiFont = emojiFontForCodePoint(codePoint, normalizedStyle)
            if (emojiFont != null) {
                styleResolvedFonts.put(codePoint, emojiFont)
                return emojiFont
            }
        }

        if (primary.canDisplay(codePoint)) return primary

        if (fontResolver != null) {
            val resolved = fontResolver.resolveFallbackFont(codePoint, normalizedStyle, primary.size2D)
            if (resolved != null) {
                val effectiveStyle = if (isEmojiFontFamily(resolved.family)) Font.PLAIN else normalizedStyle
                val derived = resolved.deriveFont(effectiveStyle, primary.size2D)
                styleResolvedFonts.put(codePoint, derived)
                return derived
            }
        }

        var index = 0
        while (index < fallbackBaseFonts.size) {
            val fallback = fallbackFont(index, normalizedStyle)
            if (fallback.canDisplay(codePoint)) {
                styleResolvedFonts.put(codePoint, fallback)
                return fallback
            }
            index++
        }

        if (useSystemFallbackFonts) {
            refreshSystemFallbackFonts()
            index = 0
            while (index < systemFallbackFamilies.size) {
                val fallback = systemFallbackFont(index, normalizedStyle)
                if (fallback.canDisplay(codePoint)) {
                    styleResolvedFonts.put(codePoint, fallback)
                    return fallback
                }
                index++
            }
        }

        styleResolvedFonts.put(codePoint, primary)
        return primary
    }

    /**
     * Returns the first cached style font that can display all UTF-16 units in
     * [text], falling back to [font] when no configured fallback covers it.
     *
     * Grapheme-cluster lookups are bounded per style. The render cache already
     * owns cluster strings for visible cells; this renderer cache must not keep
     * every historical cluster alive for a months-long terminal session.
     */
    fun fontForText(
        text: String,
        style: Int,
    ): Font {
        val normalizedStyle = style and STYLE_MASK
        val styleResolvedTextFonts = resolvedTextFonts[normalizedStyle]
        val cached = styleResolvedTextFonts[text]
        if (cached != null) return cached

        val primary = font(normalizedStyle)
        val isEmoji = containsEmojiPresentation(text)

        if (isEmoji && fontResolver != null) {
            val resolved = fontResolver.resolveFallbackFont(text, normalizedStyle, primary.size2D)
            if (resolved != null) {
                val derived = resolved.deriveFont(Font.PLAIN, primary.size2D)
                styleResolvedTextFonts[text] = derived
                return derived
            }
        }

        if (isEmoji) {
            val emojiFont = emojiFontForText(text, normalizedStyle)
            if (emojiFont != null) {
                styleResolvedTextFonts[text] = emojiFont
                return emojiFont
            }
        }

        if (primary.canDisplayUpTo(text) < 0) return primary

        if (fontResolver != null) {
            val resolved = fontResolver.resolveFallbackFont(text, normalizedStyle, primary.size2D)
            if (resolved != null) {
                val effectiveStyle = if (isEmojiFontFamily(resolved.family)) Font.PLAIN else normalizedStyle
                val derived = resolved.deriveFont(effectiveStyle, primary.size2D)
                styleResolvedTextFonts[text] = derived
                return derived
            }
        }

        var index = 0
        while (index < fallbackBaseFonts.size) {
            val fallback = fallbackFont(index, normalizedStyle)
            if (fallback.canDisplayUpTo(text) < 0) {
                styleResolvedTextFonts[text] = fallback
                return fallback
            }
            index++
        }

        if (useSystemFallbackFonts) {
            refreshSystemFallbackFonts()
            index = 0
            while (index < systemFallbackFamilies.size) {
                val fallback = systemFallbackFont(index, normalizedStyle)
                if (fallback.canDisplayUpTo(text) < 0) {
                    styleResolvedTextFonts[text] = fallback
                    return fallback
                }
                index++
            }
        }

        styleResolvedTextFonts[text] = primary
        return primary
    }

    private fun emojiFontForCodePoint(
        codePoint: Int,
        style: Int,
    ): Font? {
        var index = 0
        while (index < fallbackBaseFonts.size) {
            val fallback = fallbackFont(index, style)
            if (isEmojiFontFamily(fallback.family) && fallback.canDisplay(codePoint)) {
                return fallback
            }
            index++
        }

        if (useSystemFallbackFonts) {
            refreshSystemFallbackFonts()
            index = 0
            while (index < systemFallbackFamilies.size) {
                if (isEmojiFontFamily(systemFallbackFamilies[index])) {
                    val fallback = systemFallbackFont(index, style)
                    if (fallback.canDisplay(codePoint)) return fallback
                }
                index++
            }
        }
        return null
    }

    private fun emojiFontForText(
        text: String,
        style: Int,
    ): Font? {
        var index = 0
        while (index < fallbackBaseFonts.size) {
            val fallback = fallbackFont(index, style)
            if (isEmojiFontFamily(fallback.family) && fallback.canDisplayUpTo(text) < 0) {
                return fallback
            }
            index++
        }

        if (useSystemFallbackFonts) {
            refreshSystemFallbackFonts()
            index = 0
            while (index < systemFallbackFamilies.size) {
                if (isEmojiFontFamily(systemFallbackFamilies[index])) {
                    val fallback = systemFallbackFont(index, style)
                    if (fallback.canDisplayUpTo(text) < 0) return fallback
                }
                index++
            }
        }
        return null
    }

    /**
     * Refreshes asynchronously loaded system fallback fonts.
     *
     * This method polls the background font-loading thread. If new system fonts
     * have finished loading since the last check, it integrates them into the
     * fallback pipeline and aggressively invalidates all dynamically resolved
     * text caches to force a re-layout on the next frame.
     *
     * @return `true` if the system font list changed and caches were invalidated;
     * `false` if system fonts are disabled, still loading, or unchanged.
     */
    fun refreshSystemFallbackFonts(): Boolean {
        if (!useSystemFallbackFonts) return false

        val loadedFamilies = systemFontFamilies.familiesOrStartLoading()
        if (loadedFamilies == systemFallbackFamilies) return false

        systemFallbackFamilies = loadedFamilies
        systemFontCache.clear()
        invalidateResolvedCaches()
        return true
    }

    /**
     * Clears all dynamically resolved glyphs and increments the cache generation.
     * * This must be called exclusively when the base configuration or system
     * fallbacks change. Incrementing the generation counter signals dependent
     * layout caches (e.g., `TerminalComplexTextLayoutCache`) that they must
     * also flush their state to prevent rendering stale font metrics.
     */
    private fun invalidateResolvedCaches() {
        for (cache in resolvedCodePointFonts) {
            cache.clear()
        }
        for (cache in resolvedTextFonts) {
            cache.clear()
        }
        fontGeneration++
    }

    private fun fallbackFont(
        index: Int,
        style: Int,
    ): Font {
        val normalizedStyle = style and STYLE_MASK
        val cached = fallbackStyleFonts[index][normalizedStyle]
        if (cached != null) return cached

        val base =
            requireNotNull(baseFont) {
                "FontCache.update must be called before fallbackFont"
            }
        val fallback = fallbackBaseFonts[index]
        val effectiveStyle = if (isEmojiFontFamily(fallback.family)) Font.PLAIN else normalizedStyle
        val derived = fallback.deriveFont(effectiveStyle, base.size2D)
        fallbackStyleFonts[index][normalizedStyle] = derived
        return derived
    }

    private fun systemFallbackFont(
        index: Int,
        style: Int,
    ): Font {
        val normalizedStyle = style and STYLE_MASK
        val family = systemFallbackFamilies[index]
        val key = "$family:$normalizedStyle"
        val cached = systemFontCache[key]
        if (cached != null) return cached

        val base =
            requireNotNull(baseFont) {
                "FontCache.update must be called before systemFallbackFont"
            }
        val effectiveStyle = if (isEmojiFontFamily(family)) Font.PLAIN else normalizedStyle
        val fallback =
            Font(family, effectiveStyle, base.size)
                .deriveFont(base.size2D)
        systemFontCache[key] = fallback
        return fallback
    }

    private class StringFontLru(
        private val capacity: Int,
    ) : LinkedHashMap<String, Font>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Font>?): Boolean = size > capacity
    }

    private class SystemFontLru(
        private val capacity: Int,
    ) : LinkedHashMap<String, Font>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Font>?): Boolean = size > capacity
    }

    @Suppress("DuplicatedCode")
    private class IntFontLru(
        capacity: Int,
    ) {
        private val entryKeys = IntArray(capacity)
        private val entryFonts = arrayOfNulls<Font>(capacity)
        private val previous = IntArray(capacity) { EMPTY }
        private val next = IntArray(capacity) { EMPTY }
        private val hashKeys = IntArray(hashCapacity(capacity))
        private val hashEntries = IntArray(hashKeys.size) { EMPTY }
        private val hashMask = hashKeys.size - 1
        private var size = 0
        private var head = EMPTY
        private var tail = EMPTY

        operator fun get(key: Int): Font? {
            val entry = findEntry(key)
            if (entry == EMPTY) return null

            moveToHead(entry)
            return entryFonts[entry]
        }

        fun put(
            key: Int,
            font: Font,
        ) {
            val existing = findEntry(key)
            if (existing != EMPTY) {
                entryFonts[existing] = font
                moveToHead(existing)
                return
            }

            val entry =
                if (size < entryKeys.size) {
                    size++
                } else {
                    val evicted = tail
                    removeHashEntry(entryKeys[evicted], evicted)
                    unlink(evicted)
                    evicted
                }

            entryKeys[entry] = key
            entryFonts[entry] = font
            linkHead(entry)
            insertHashEntry(key, entry)
        }

        fun clear() {
            Arrays.fill(entryFonts, null)
            Arrays.fill(previous, EMPTY)
            Arrays.fill(next, EMPTY)
            Arrays.fill(hashEntries, EMPTY)
            size = 0
            head = EMPTY
            tail = EMPTY
        }

        private fun findEntry(key: Int): Int {
            var slot = hashSlot(key)
            while (true) {
                val entry = hashEntries[slot]
                if (entry == EMPTY) return EMPTY
                if (hashKeys[slot] == key) return entry
                slot = (slot + 1) and hashMask
            }
        }

        private fun insertHashEntry(
            key: Int,
            entry: Int,
        ) {
            var slot = hashSlot(key)
            while (hashEntries[slot] != EMPTY) {
                slot = (slot + 1) and hashMask
            }
            hashKeys[slot] = key
            hashEntries[slot] = entry
        }

        private fun removeHashEntry(
            key: Int,
            entry: Int,
        ) {
            var slot = hashSlot(key)
            while (true) {
                if (hashEntries[slot] == entry && hashKeys[slot] == key) {
                    hashEntries[slot] = EMPTY
                    reinsertHashCluster((slot + 1) and hashMask)
                    return
                }
                slot = (slot + 1) and hashMask
            }
        }

        private fun reinsertHashCluster(startSlot: Int) {
            var slot = startSlot
            while (hashEntries[slot] != EMPTY) {
                val key = hashKeys[slot]
                val entry = hashEntries[slot]
                hashEntries[slot] = EMPTY
                insertHashEntry(key, entry)
                slot = (slot + 1) and hashMask
            }
        }

        private fun moveToHead(entry: Int) {
            if (entry == head) return
            unlink(entry)
            linkHead(entry)
        }

        private fun unlink(entry: Int) {
            val previousEntry = previous[entry]
            val nextEntry = next[entry]
            if (previousEntry != EMPTY) {
                next[previousEntry] = nextEntry
            } else {
                head = nextEntry
            }
            if (nextEntry != EMPTY) {
                previous[nextEntry] = previousEntry
            } else {
                tail = previousEntry
            }
            previous[entry] = EMPTY
            next[entry] = EMPTY
        }

        private fun linkHead(entry: Int) {
            previous[entry] = EMPTY
            next[entry] = head
            if (head != EMPTY) {
                previous[head] = entry
            } else {
                tail = entry
            }
            head = entry
        }

        private fun hashSlot(key: Int): Int {
            var hash = key
            hash = hash xor (hash ushr 16)
            hash *= -2048144789
            hash = hash xor (hash ushr 13)
            return hash and hashMask
        }
    }

    private companion object {
        private const val STYLE_COUNT = 4
        private const val STYLE_MASK = Font.BOLD or Font.ITALIC
        private const val DEFAULT_CODE_POINT_FALLBACK_CAPACITY_PER_STYLE = 4096
        private const val DEFAULT_TEXT_FALLBACK_CAPACITY_PER_STYLE = 1024
        private const val DEFAULT_SYSTEM_FONT_CACHE_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val EMPTY = -1

        private fun containsEmojiPresentation(text: String): Boolean {
            var charIndex = 0
            while (charIndex < text.length) {
                val codePoint = text.codePointAt(charIndex)
                if (codePoint == VARIATION_SELECTOR_16 ||
                    codePoint == ZERO_WIDTH_JOINER ||
                    isEmojiPresentationCodePoint(codePoint)
                ) {
                    return true
                }
                charIndex += Character.charCount(codePoint)
            }
            return false
        }

        private fun isEmojiPresentationCodePoint(codePoint: Int): Boolean =
            codePoint in 0x1F000..0x1FAFF ||
                codePoint in 0x2600..0x27BF ||
                codePoint in 0x2B00..0x2BFF

        private fun isEmojiFontFamily(family: String): Boolean {
            val normalized = family.lowercase(Locale.ROOT)
            return "emoji" in normalized ||
                "twemoji" in normalized ||
                "joypixels" in normalized
        }

        private fun hashCapacity(entryCapacity: Int): Int {
            var capacity = 1
            while (capacity < entryCapacity * 2) {
                capacity = capacity shl 1
            }
            return capacity
        }

        private const val VARIATION_SELECTOR_16 = 0xFE0F
        private const val ZERO_WIDTH_JOINER = 0x200D
    }
}

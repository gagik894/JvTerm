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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Font
import java.awt.font.FontRenderContext

class TerminalComplexTextLayoutCacheTest {
    @Test
    fun `constructor rejects non-positive capacities`() {
        assertThrows<IllegalArgumentException> {
            TerminalComplexTextLayoutCache(codePointCapacity = 0)
        }
        assertThrows<IllegalArgumentException> {
            TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 0)
        }
    }

    @Test
    fun `code point layouts are reused without rebuilding layout objects`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 4)
        val frc = FontRenderContext(null, false, false)

        val first = layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)
        val second = layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)

        assertSame(first, second)
    }

    @Test
    fun `code point layouts are split by style`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 4)
        val frc = FontRenderContext(null, false, false)

        val plain = layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)
        val bold = layoutCache.codePointLayout(0x03A9, Font.BOLD, frc, fontCache)
        val italic = layoutCache.codePointLayout(0x03A9, Font.ITALIC, frc, fontCache)

        assertNotSame(plain, bold)
        assertNotSame(plain, italic)
        assertSame(bold, layoutCache.codePointLayout(0x03A9, Font.BOLD, frc, fontCache))
    }

    @Test
    fun `code point layouts support maximum Unicode scalar`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 4)
        val frc = FontRenderContext(null, false, false)

        val layout = layoutCache.codePointLayout(0x10FFFF, Font.PLAIN, frc, fontCache)

        assertEquals(2, layout.characterCount)
        assertSame(layout, layoutCache.codePointLayout(0x10FFFF, Font.PLAIN, frc, fontCache))
    }

    @Test
    fun `invalid code point layouts normalize to replacement glyph`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 4)
        val frc = FontRenderContext(null, false, false)

        val invalid = layoutCache.codePointLayout(0x11_0000, Font.PLAIN, frc, fontCache)
        val replacement = layoutCache.codePointLayout(0xFFFD, Font.PLAIN, frc, fontCache)

        assertSame(replacement, invalid)
    }

    @Test
    fun `code point layouts are bounded by lru capacity`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 2)
        val frc = FontRenderContext(null, false, false)

        val omega = layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)
        val cjk = layoutCache.codePointLayout(0x4E2D, Font.PLAIN, frc, fontCache)
        layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)
        layoutCache.codePointLayout(0x3042, Font.PLAIN, frc, fontCache)

        assertSame(omega, layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache))
        assertNotSame(cjk, layoutCache.codePointLayout(0x4E2D, Font.PLAIN, frc, fontCache))
    }

    @Test
    fun `code point access refreshes lru order`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 2)
        val frc = FontRenderContext(null, false, false)

        val omega = layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)
        val cjk = layoutCache.codePointLayout(0x4E2D, Font.PLAIN, frc, fontCache)
        assertSame(omega, layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache))
        layoutCache.codePointLayout(0x3042, Font.PLAIN, frc, fontCache)

        assertSame(omega, layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache))
        assertNotSame(cjk, layoutCache.codePointLayout(0x4E2D, Font.PLAIN, frc, fontCache))
    }

    @Test
    fun `code point cache bulk eviction creates reusable free slots`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 20)
        val frc = FontRenderContext(null, false, false)

        repeat(21) { index ->
            layoutCache.codePointLayout(0x0400 + index, Font.PLAIN, frc, fontCache)
        }

        assertEquals(19, layoutCache.cachedCodePointLayoutCount())
    }

    @Test
    fun `code point cache keeps collision chain reachable after bulk eviction`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 20)
        val frc = FontRenderContext(null, false, false)
        val codePoints = collidingCodePoints(21)
        layoutCache.codePointLayout(codePoints[0], Font.PLAIN, frc, fontCache)
        layoutCache.codePointLayout(codePoints[1], Font.PLAIN, frc, fontCache)
        val retainedLayout = layoutCache.codePointLayout(codePoints[2], Font.PLAIN, frc, fontCache)
        repeat(17) { index ->
            layoutCache.codePointLayout(codePoints[index + 3], Font.PLAIN, frc, fontCache)
        }
        val newestLayout = layoutCache.codePointLayout(codePoints[20], Font.PLAIN, frc, fontCache)

        assertSame(newestLayout, layoutCache.codePointLayout(codePoints[20], Font.PLAIN, frc, fontCache))
        assertSame(retainedLayout, layoutCache.codePointLayout(codePoints[2], Font.PLAIN, frc, fontCache))
    }

    @Test
    fun `code point cache fails fast if LRU entry is missing from hash map during eviction`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 1)
        val frc = FontRenderContext(null, false, false)
        layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)
        layoutCache.clearCodePointHashEntriesForTest()

        assertThrows<IllegalStateException> {
            layoutCache.codePointLayout(0x4E2D, Font.PLAIN, frc, fontCache)
        }
    }

    @Test
    fun `clear invalidates code point layouts`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 4)
        val frc = FontRenderContext(null, false, false)

        val first = layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)
        layoutCache.clear()
        val second = layoutCache.codePointLayout(0x03A9, Font.PLAIN, frc, fontCache)

        assertNotSame(first, second)
    }

    @Test
    fun `cluster layouts are reused and split by style`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 4)
        val frc = FontRenderContext(null, false, false)
        val cluster = "\u0E01\u0E34"

        val plain = layoutCache.clusterLayout(cluster, Font.PLAIN, frc, fontCache)
        val plainAgain = layoutCache.clusterLayout(cluster, Font.PLAIN, frc, fontCache)
        val bold = layoutCache.clusterLayout(cluster, Font.BOLD, frc, fontCache)

        assertSame(plain, plainAgain)
        assertNotSame(plain, bold)
    }

    @Test
    fun `cluster layouts support astral Unicode scalars`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 4)
        val frc = FontRenderContext(null, false, false)
        val cluster = intArrayOf(0x1F642, 0xFE0F)

        val layout = layoutCache.clusterLayout(cluster, 0, cluster.size, Font.PLAIN, frc, fontCache)

        assertEquals(3, layout.characterCount)
        assertSame(layout, layoutCache.clusterLayout(cluster, 0, cluster.size, Font.PLAIN, frc, fontCache))
    }

    @Test
    fun `cluster layouts replace malformed code points before shaping`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 4)
        val frc = FontRenderContext(null, false, false)
        val malformed = intArrayOf(0x41, 0xD800, 0x11_0000)
        val sanitized = intArrayOf(0x41, 0xFFFD, 0xFFFD)

        val malformedLayout = layoutCache.clusterLayout(malformed, 0, malformed.size, Font.PLAIN, frc, fontCache)
        val sanitizedLayout = layoutCache.clusterLayout(sanitized, 0, sanitized.size, Font.PLAIN, frc, fontCache)

        assertSame(sanitizedLayout, malformedLayout)
    }

    @Test
    fun `cluster layouts are bounded by lru capacity per style`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 2)
        val frc = FontRenderContext(null, false, false)

        val first = layoutCache.clusterLayout("\u0E01\u0E34", Font.PLAIN, frc, fontCache)
        val second = layoutCache.clusterLayout("\u0E02\u0E34", Font.PLAIN, frc, fontCache)
        layoutCache.clusterLayout("\u0E01\u0E34", Font.PLAIN, frc, fontCache)
        layoutCache.clusterLayout("\u0E03\u0E34", Font.PLAIN, frc, fontCache)

        assertSame(first, layoutCache.clusterLayout("\u0E01\u0E34", Font.PLAIN, frc, fontCache))
        assertNotSame(second, layoutCache.clusterLayout("\u0E02\u0E34", Font.PLAIN, frc, fontCache))
    }

    @Test
    fun `cluster capacity is independent for each style`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 1)
        val frc = FontRenderContext(null, false, false)
        val cluster = "\u0E01\u0E34"

        val plain = layoutCache.clusterLayout(cluster, Font.PLAIN, frc, fontCache)
        val bold = layoutCache.clusterLayout(cluster, Font.BOLD, frc, fontCache)
        layoutCache.clusterLayout("\u0E02\u0E34", Font.PLAIN, frc, fontCache)

        assertNotSame(plain, layoutCache.clusterLayout(cluster, Font.PLAIN, frc, fontCache))
        assertSame(bold, layoutCache.clusterLayout(cluster, Font.BOLD, frc, fontCache))
    }

    @Test
    fun `cluster cache bulk eviction creates reusable free slots`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 20)
        val frc = FontRenderContext(null, false, false)

        repeat(21) { index ->
            val cluster = intArrayOf(0x1000 + index, 0x0301)
            layoutCache.clusterLayout(cluster, 0, cluster.size, Font.PLAIN, frc, fontCache)
        }

        assertEquals(19, layoutCache.cachedClusterLayoutCount())
    }

    @Test
    fun `cluster cache keeps collision chain reachable after bulk eviction`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 20)
        val frc = FontRenderContext(null, false, false)
        val clusters = collidingClusters(21)
        layoutCache.clusterLayout(clusters[0], 0, clusters[0].size, Font.PLAIN, frc, fontCache)
        layoutCache.clusterLayout(clusters[1], 0, clusters[1].size, Font.PLAIN, frc, fontCache)
        val retainedLayout = layoutCache.clusterLayout(clusters[2], 0, clusters[2].size, Font.PLAIN, frc, fontCache)
        repeat(17) { index ->
            val cluster = clusters[index + 3]
            layoutCache.clusterLayout(cluster, 0, cluster.size, Font.PLAIN, frc, fontCache)
        }
        val newest = clusters[20]
        val newestLayout = layoutCache.clusterLayout(newest, 0, newest.size, Font.PLAIN, frc, fontCache)

        assertSame(newestLayout, layoutCache.clusterLayout(newest, 0, newest.size, Font.PLAIN, frc, fontCache))
        assertSame(
            retainedLayout,
            layoutCache.clusterLayout(clusters[2], 0, clusters[2].size, Font.PLAIN, frc, fontCache),
        )
    }

    @Test
    fun `cluster cache fails fast if LRU entry is missing from hash map during eviction`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 1)
        val frc = FontRenderContext(null, false, false)
        layoutCache.clusterLayout("\u0E01\u0E34", Font.PLAIN, frc, fontCache)
        layoutCache.clearClusterHashEntriesForTest()

        assertThrows<IllegalStateException> {
            layoutCache.clusterLayout("\u0E02\u0E34", Font.PLAIN, frc, fontCache)
        }
    }

    @Test
    fun `clear invalidates cluster layouts`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 4)
        val frc = FontRenderContext(null, false, false)
        val cluster = "\u0E01\u0E34"

        val first = layoutCache.clusterLayout(cluster, Font.PLAIN, frc, fontCache)
        layoutCache.clear()
        val second = layoutCache.clusterLayout(cluster, Font.PLAIN, frc, fontCache)

        assertNotSame(first, second)
    }

    @Test
    fun `layouts are invalidated when font render context changes`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(codePointCapacity = 4)
        val firstFrc = FontRenderContext(null, false, false)
        val secondFrc = FontRenderContext(null, true, false)

        val first = layoutCache.codePointLayout(0x03A9, Font.PLAIN, firstFrc, fontCache)
        val second = layoutCache.codePointLayout(0x03A9, Font.PLAIN, secondFrc, fontCache)

        assertNotSame(first, second)
    }

    @Test
    fun `clusterLayout shapes bounded prefix for sequences exceeding structural length limits`() {
        // Arrange
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 4)
        val frc = FontRenderContext(null, false, false)
        val longInput = "A".repeat(TerminalComplexTextLayoutCache.MAX_CLUSTER_LENGTH + 1)

        // Act
        val layout = layoutCache.clusterLayout(longInput, Font.PLAIN, frc, fontCache)
        val replacementLayout = layoutCache.clusterLayout("\uFFFD", Font.PLAIN, frc, fontCache)

        // Assert
        assertEquals(TerminalComplexTextLayoutCache.MAX_CLUSTER_LENGTH, layout.characterCount)
        assertNotSame(
            replacementLayout,
            layout,
            "Long clusters must preserve a visible bounded prefix instead of collapsing to U+FFFD",
        )
    }

    @Test
    fun `clusterLayout reuses identical bounded prefixes for long inputs`() {
        // Arrange
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 4)
        val frc = FontRenderContext(null, false, false)
        val attackSequenceAlpha = "X".repeat(40)
        val attackSequenceBeta = "X".repeat(100)

        // Act
        val firstLayout = layoutCache.clusterLayout(attackSequenceAlpha, Font.PLAIN, frc, fontCache)
        val secondLayout = layoutCache.clusterLayout(attackSequenceBeta, Font.PLAIN, frc, fontCache)

        // Assert
        assertSame(
            firstLayout,
            secondLayout,
            "Long inputs with the same bounded prefix should reuse one shaped layout",
        )
    }

    @Test
    fun `clusterLayout does not collapse distinct long visible prefixes`() {
        val fontCache = fontCache()
        val layoutCache = TerminalComplexTextLayoutCache(clusterCapacityPerStyle = 4)
        val frc = FontRenderContext(null, false, false)

        val firstLayout = layoutCache.clusterLayout("X".repeat(40), Font.PLAIN, frc, fontCache)
        val secondLayout = layoutCache.clusterLayout("Y".repeat(40), Font.PLAIN, frc, fontCache)

        assertNotSame(firstLayout, secondLayout)
    }

    private fun fontCache(): FontCache {
        val cache = FontCache()
        cache.update(Font(Font.MONOSPACED, Font.PLAIN, 14), emptyList(), useSystemFallbackFonts = false)
        return cache
    }

    private fun TerminalComplexTextLayoutCache.cachedCodePointLayoutCount(): Int {
        val cache = declaredField("codePointLayouts").get(this)
        return cache.countCachedLayouts()
    }

    private fun TerminalComplexTextLayoutCache.cachedClusterLayoutCount(): Int {
        val caches = declaredField("clusterLayouts").get(this) as Array<*>
        return caches[Font.PLAIN]!!.countCachedLayouts()
    }

    private fun TerminalComplexTextLayoutCache.clearCodePointHashEntriesForTest() {
        val cache = declaredField("codePointLayouts").get(this)
        cache.clearHashEntriesForTest()
    }

    private fun TerminalComplexTextLayoutCache.clearClusterHashEntriesForTest() {
        val caches = declaredField("clusterLayouts").get(this) as Array<*>
        caches[Font.PLAIN]!!.clearHashEntriesForTest()
    }

    private fun Any.countCachedLayouts(): Int {
        val layouts = declaredField("entryLayouts").get(this) as Array<*>
        return layouts.count { it != null }
    }

    private fun Any.clearHashEntriesForTest() {
        val hashEntries = declaredField("hashEntries").get(this) as IntArray
        hashEntries.fill(EMPTY)
    }

    private fun collidingCodePoints(count: Int): IntArray {
        val result = IntArray(count)
        var found = 0
        var codePoint = 0x20
        while (found < count) {
            if (codePointHashSlot(codePoint) == 0) {
                result[found++] = codePoint
            }
            codePoint++
        }
        return result
    }

    private fun collidingClusters(count: Int): Array<IntArray> {
        val result = arrayOfNulls<IntArray>(count)
        var found = 0
        var codePoint = 0x20
        while (found < count) {
            val cluster = intArrayOf(codePoint, 0x0301)
            if (clusterHashSlot(cluster) == 0) {
                result[found++] = cluster
            }
            codePoint++
        }
        @Suppress("UNCHECKED_CAST")
        return result as Array<IntArray>
    }

    private fun codePointHashSlot(codePoint: Int): Int {
        var hash = codePoint.toLong()
        hash = hash xor (hash ushr 33)
        hash *= -49064778989728563L
        hash = hash xor (hash ushr 33)
        return hash.toInt() and TEST_HASH_MASK
    }

    private fun clusterHashSlot(cluster: IntArray): Int {
        var hash = cluster.size
        for (codePoint in cluster) {
            hash = 31 * hash + codePoint
        }
        var mixed = hash
        mixed = mixed xor (mixed ushr 16)
        mixed *= -2048144789
        mixed = mixed xor (mixed ushr 13)
        return mixed and TEST_HASH_MASK
    }

    private fun Any.declaredField(name: String) = javaClass.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        private const val TEST_HASH_MASK = 63
        private const val EMPTY = -1
    }
}

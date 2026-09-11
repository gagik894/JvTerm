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

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.util.*

/**
 * Bounded renderer-local cache for shaped complex text layouts.
 *
 * Single code points and grapheme clusters use primitive-key LRUs so repeated
 * Unicode cells do not allocate lookup keys or transient strings on cache hits.
 * Contextual script runs use [TerminalShapedGlyphVectorCache], which retains
 * their glyph-to-cell positioning as well as their shaping context.
 *
 * **Thread Safety:** Not thread-safe. This cache must only be accessed
 * from the Swing Event Dispatch Thread (EDT).
*/
internal class TerminalComplexTextLayoutCache(
    codePointCapacity: Int = DEFAULT_CODE_POINT_CAPACITY,
    clusterCapacityPerStyle: Int = DEFAULT_CLUSTER_CAPACITY_PER_STYLE,
) {
    init {
        require(codePointCapacity > 0) {
            "codePointCapacity must be > 0, was $codePointCapacity"
        }
        require(clusterCapacityPerStyle > 0) {
            "clusterCapacityPerStyle must be > 0, was $clusterCapacityPerStyle"
        }
    }

    private val codePointLayouts = LongTextLayoutLru(codePointCapacity)
    private val clusterLayouts =
        Array(STYLE_COUNT) {
            ClusterTextLayoutLru(clusterCapacityPerStyle)
        }
    private var stringClusterScratch = IntArray(MAX_CLUSTER_LENGTH)
    private var sanitizedClusterScratch = IntArray(MAX_CLUSTER_LENGTH)
    private var fontRenderContext: FontRenderContext? = null
    private var fontGeneration: Int = -1

    /**
     * Clears cached layouts.
     */
    fun clear() {
        fontRenderContext = null
        codePointLayouts.clear()
        for (cache in clusterLayouts) {
            cache.clear()
        }
    }

    /**
     * Resolves a shaped text layout for a single Unicode code point using an allocation-free primitive key.
     *
     * Avoids heap allocation by converting the 32-bit integer code point and its corresponding
     * style mask into a single packed 64-bit primitive key. This layout bypasses standard string
     * instantiation during cache lookup passes.
     *
     * @param codePoint The 32-bit Unicode scalar value to render.
     * @param style The packed integer bitmask specifying the target font style.
     * @param fontRenderContext The active Java2D graphics context for metrics tracking.
     * @param fontCache The stateful font resolver for fallback execution.
     * @return An immutable, single-character [TextLayout].
     */
    fun codePointLayout(
        codePoint: Int,
        style: Int,
        fontRenderContext: FontRenderContext,
        fontCache: FontCache,
    ): TextLayout {
        fontCache.refreshSystemFallbackFonts()
        prepare(fontRenderContext, fontCache.generation)

        val safeCodePoint = unicodeScalarOrReplacement(codePoint)
        val normalizedStyle = style and STYLE_MASK
        val key = codePointKey(safeCodePoint, normalizedStyle)
        val cached = codePointLayouts[key]
        if (cached != null) return cached

        // Convert the code point directly to a transient char array on cache misses to minimize
        // object lifecycle footprints prior to layout shaping.
        val text = String(Character.toChars(safeCodePoint))
        val layout = TextLayout(text, fontCache.fontForCodePoint(safeCodePoint, normalizedStyle), fontRenderContext)
        codePointLayouts.put(key, layout)
        return layout
    }

    /**
     * Resolves a shaped text layout for a multi-code-unit grapheme cluster.
     *
     * Shaping is capped to [MAX_CLUSTER_LENGTH] code points so adversarial cluster
     * sequences cannot force unbounded OpenType work. Callers that pass longer
     * clusters should render the remaining code points through the single-code-point
     * path to preserve data visibility.
     *
     * @param style The packed integer bitmask specifying the target font style (Bold/Italic variants).
     * @param fontRenderContext The active Java2D graphics context specifying scaling and anti-aliasing configurations.
     * @param fontCache The stateful primary and fallback font resolver for typography resolution.
     * @return An immutable, shaped [TextLayout]; callers fit it to the owning cell span.
     */
    fun clusterLayout(
        codepoints: IntArray,
        offset: Int,
        length: Int,
        style: Int,
        fontRenderContext: FontRenderContext,
        fontCache: FontCache,
    ): TextLayout {
        require(length >= 0) { "length must be >= 0, was $length" }
        require(offset >= 0 && codepoints.size - offset >= length) {
            "cluster source has insufficient capacity: size=${codepoints.size}, offset=$offset, length=$length"
        }
        if (length == 0) {
            return codePointLayout(REPLACEMENT_CODE_POINT, style, fontRenderContext, fontCache)
        }
        if (length == 1) {
            return codePointLayout(codepoints[offset], style, fontRenderContext, fontCache)
        }
        val shapedLength = minOf(length, MAX_CLUSTER_LENGTH)

        fontCache.refreshSystemFallbackFonts()
        prepare(fontRenderContext, fontCache.generation)

        val normalizedStyle = style and STYLE_MASK
        val styleLayouts = clusterLayouts[normalizedStyle]
        val safeCodepoints =
            if (hasOnlyUnicodeScalars(codepoints, offset, shapedLength)) {
                codepoints
            } else {
                sanitizeCluster(codepoints, offset, shapedLength)
            }
        val safeOffset = if (safeCodepoints === codepoints) offset else 0

        val hash = styleLayouts.contentHash(safeCodepoints, safeOffset, shapedLength)
        val cached = styleLayouts.get(safeCodepoints, safeOffset, shapedLength, hash)
        if (cached != null) return cached

        // TextLayout and Font.canDisplayUpTo require text objects. Construct
        // them only on cache misses; repeated paint passes compare primitive
        // codepoint slices directly.
        val text = String(safeCodepoints, safeOffset, shapedLength)
        val layout = TextLayout(text, fontCache.fontForText(text, normalizedStyle), fontRenderContext)
        styleLayouts.put(safeCodepoints, safeOffset, shapedLength, hash, layout)
        return layout
    }

    /**
     * Resolves a shaped layout from a string source.
     *
     * This overload exists for tests and compatibility helpers. The renderer's
     * hot path should pass primitive render-cache slices to the main overload.
     */
    fun clusterLayout(
        text: String,
        style: Int,
        fontRenderContext: FontRenderContext,
        fontCache: FontCache,
    ): TextLayout {
        val length = copyStringCodepoints(text)
        return clusterLayout(stringClusterScratch, 0, length, style, fontRenderContext, fontCache)
    }

    private fun prepare(
        nextFontRenderContext: FontRenderContext,
        nextFontGeneration: Int,
    ) {
        if (nextFontRenderContext == fontRenderContext && nextFontGeneration == fontGeneration) return

        fontRenderContext = nextFontRenderContext
        fontGeneration = nextFontGeneration
        codePointLayouts.clear()
        for (cache in clusterLayouts) {
            cache.clear()
        }
    }

    private fun copyStringCodepoints(text: String): Int {
        var required = 0
        var charIndex = 0
        while (charIndex < text.length && required <= MAX_CLUSTER_LENGTH) {
            val codePoint = Character.codePointAt(text, charIndex)
            if (required == stringClusterScratch.size) {
                stringClusterScratch = stringClusterScratch.copyOf(stringClusterScratch.size * 2)
            }
            stringClusterScratch[required] = codePoint
            required++
            charIndex += Character.charCount(codePoint)
        }
        return required
    }

    private fun hasOnlyUnicodeScalars(
        codepoints: IntArray,
        offset: Int,
        length: Int,
    ): Boolean {
        var index = 0
        while (index < length) {
            if (!isUnicodeScalar(codepoints[offset + index])) return false
            index++
        }
        return true
    }

    private fun sanitizeCluster(
        codepoints: IntArray,
        offset: Int,
        length: Int,
    ): IntArray {
        if (sanitizedClusterScratch.size < length) {
            sanitizedClusterScratch = IntArray(length)
        }
        var index = 0
        while (index < length) {
            sanitizedClusterScratch[index] = unicodeScalarOrReplacement(codepoints[offset + index])
            index++
        }
        return sanitizedClusterScratch
    }

    /**
     * Primitive-key LRU; [ClusterTextLayoutLru] instead compares primitive code-point slices.
     * These specialized lookup paths avoid boxing keys or constructing lookup objects.
     *
     * LRU links and free-slot bookkeeping do not inherently require duplication. Share
     * that bookkeeping only when it reduces complexity, preserves eviction and collision
     * handling, and before/after measurements confirm no regression in warmed allocation
     * or throughput. Primitive key storage alone does not justify duplicating all behavior.
     */
    @Suppress("DuplicatedCode")
    private class LongTextLayoutLru(
        capacity: Int,
    ) {
        private val entryKeys = LongArray(capacity)
        private val entryLayouts = arrayOfNulls<TextLayout>(capacity)
        private val previous = IntArray(capacity) { EMPTY }
        private val next = IntArray(capacity) { EMPTY }
        private val freeEntries = IntArray(capacity)
        private val hashKeys = LongArray(hashCapacity(capacity))
        private val hashEntries = IntArray(hashKeys.size) { EMPTY }
        private val hashMask = hashKeys.size - 1
        private val evictionBatchSize = maxOf(1, capacity / EVICTION_BATCH_DIVISOR)
        private var size = 0
        private var freeSize = 0
        private var head = EMPTY
        private var tail = EMPTY

        operator fun get(key: Long): TextLayout? {
            var slot = hashSlot(key)
            while (true) {
                val entry = hashEntries[slot]
                if (entry == EMPTY) return null
                if (hashKeys[slot] == key) {
                    moveToHead(entry)
                    return entryLayouts[entry]
                }
                slot = (slot + 1) and hashMask
            }
        }

        fun put(
            key: Long,
            layout: TextLayout,
        ) {
            val entry = allocateEntry()

            entryKeys[entry] = key
            entryLayouts[entry] = layout
            linkHead(entry)
            insertHashEntry(key, entry)
        }

        fun clear() {
            Arrays.fill(entryLayouts, null)
            Arrays.fill(previous, EMPTY)
            Arrays.fill(next, EMPTY)
            Arrays.fill(hashEntries, EMPTY)
            size = 0
            freeSize = 0
            head = EMPTY
            tail = EMPTY
        }

        private fun allocateEntry(): Int {
            if (freeSize > 0) return freeEntries[--freeSize]
            if (size < entryKeys.size) return size++

            evictOldestBatch()
            return freeEntries[--freeSize]
        }

        private fun evictOldestBatch() {
            var remaining = evictionBatchSize
            while (remaining > 0 && tail != EMPTY) {
                val evicted = tail
                removeHashEntry(entryKeys[evicted], evicted)
                unlink(evicted)
                entryLayouts[evicted] = null
                freeEntries[freeSize++] = evicted
                remaining--
            }
        }

        private fun insertHashEntry(
            key: Long,
            entry: Int,
        ) {
            insertHashEntryAtSlot(key, entry, hashSlot(key))
        }

        private fun insertHashEntryAtSlot(
            key: Long,
            entry: Int,
            slotHint: Int,
        ) {
            var slot = slotHint
            while (hashEntries[slot] != EMPTY) {
                slot = (slot + 1) and hashMask
            }
            hashKeys[slot] = key
            hashEntries[slot] = entry
        }

        private fun removeHashEntry(
            key: Long,
            entry: Int,
        ) {
            var slot = hashSlot(key)
            var probes = 0
            while (probes < hashEntries.size) {
                val candidate = hashEntries[slot]
                if (candidate == entry && hashKeys[slot] == key) {
                    hashEntries[slot] = EMPTY
                    reinsertHashCluster((slot + 1) and hashMask)
                    return
                }
                if (candidate == EMPTY) {
                    throw IllegalStateException("Text layout cache corruption: key $key in LRU but missing from hash map")
                }
                slot = (slot + 1) and hashMask
                probes++
            }
            throw IllegalStateException("Text layout cache corruption: hash map probe overflow for key $key")
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

        private fun hashSlot(key: Long): Int {
            var hash = key
            hash = hash xor (hash ushr 33)
            hash *= -49064778989728563L
            hash = hash xor (hash ushr 33)
            return hash.toInt() and hashMask
        }
    }

    @Suppress("DuplicatedCode")
    private class ClusterTextLayoutLru(
        capacity: Int,
    ) {
        private val entryCodepoints = arrayOfNulls<IntArray>(capacity)
        private val entryLengths = IntArray(capacity)
        private val entryHashes = IntArray(capacity)
        private val entryLayouts = arrayOfNulls<TextLayout>(capacity)
        private val previous = IntArray(capacity) { EMPTY }
        private val next = IntArray(capacity) { EMPTY }
        private val freeEntries = IntArray(capacity)
        private val hashKeys = IntArray(hashCapacity(capacity))
        private val hashEntries = IntArray(hashKeys.size) { EMPTY }
        private val hashMask = hashKeys.size - 1
        private val evictionBatchSize = maxOf(1, capacity / EVICTION_BATCH_DIVISOR)
        private var size = 0
        private var freeSize = 0
        private var head = EMPTY
        private var tail = EMPTY

        fun contentHash(
            codepoints: IntArray,
            offset: Int,
            length: Int,
        ): Int {
            var result = length
            var index = 0
            while (index < length) {
                result = 31 * result + codepoints[offset + index]
                index++
            }
            return result
        }

        fun get(
            codepoints: IntArray,
            offset: Int,
            length: Int,
            hash: Int,
        ): TextLayout? {
            var slot = hashSlot(hash)
            while (true) {
                val entry = hashEntries[slot]
                if (entry == EMPTY) return null
                if (hashKeys[slot] == hash && equalsEntry(entry, codepoints, offset, length)) {
                    moveToHead(entry)
                    return entryLayouts[entry]
                }
                slot = (slot + 1) and hashMask
            }
        }

        fun put(
            codepoints: IntArray,
            offset: Int,
            length: Int,
            hash: Int,
            layout: TextLayout,
        ) {
            val entry = allocateEntry()

            val stored = IntArray(length)
            System.arraycopy(codepoints, offset, stored, 0, length)
            entryCodepoints[entry] = stored
            entryLengths[entry] = length
            entryHashes[entry] = hash
            entryLayouts[entry] = layout
            linkHead(entry)
            insertHashEntry(hash, entry)
        }

        fun clear() {
            Arrays.fill(entryCodepoints, null)
            Arrays.fill(entryLayouts, null)
            Arrays.fill(previous, EMPTY)
            Arrays.fill(next, EMPTY)
            Arrays.fill(hashEntries, EMPTY)
            size = 0
            freeSize = 0
            head = EMPTY
            tail = EMPTY
        }

        private fun allocateEntry(): Int {
            if (freeSize > 0) return freeEntries[--freeSize]
            if (size < entryCodepoints.size) return size++

            evictOldestBatch()
            return freeEntries[--freeSize]
        }

        private fun evictOldestBatch() {
            var remaining = evictionBatchSize
            while (remaining > 0 && tail != EMPTY) {
                val evicted = tail
                removeHashEntry(entryHashes[evicted], evicted)
                unlink(evicted)
                entryCodepoints[evicted] = null
                entryLengths[evicted] = 0
                entryHashes[evicted] = 0
                entryLayouts[evicted] = null
                freeEntries[freeSize++] = evicted
                remaining--
            }
        }

        private fun equalsEntry(
            entry: Int,
            codepoints: IntArray,
            offset: Int,
            length: Int,
        ): Boolean {
            if (entryLengths[entry] != length) return false
            val stored = entryCodepoints[entry] ?: return false
            var index = 0
            while (index < length) {
                if (stored[index] != codepoints[offset + index]) return false
                index++
            }
            return true
        }

        private fun insertHashEntry(
            hash: Int,
            entry: Int,
        ) {
            insertHashEntryAtSlot(hash, entry, hashSlot(hash))
        }

        private fun insertHashEntryAtSlot(
            hash: Int,
            entry: Int,
            slotHint: Int,
        ) {
            var slot = slotHint
            while (hashEntries[slot] != EMPTY) {
                slot = (slot + 1) and hashMask
            }
            hashKeys[slot] = hash
            hashEntries[slot] = entry
        }

        private fun removeHashEntry(
            hash: Int,
            entry: Int,
        ) {
            var slot = hashSlot(hash)
            var probes = 0
            while (probes < hashEntries.size) {
                val candidate = hashEntries[slot]
                if (candidate == entry && hashKeys[slot] == hash) {
                    hashEntries[slot] = EMPTY
                    reinsertHashCluster((slot + 1) and hashMask)
                    return
                }
                if (candidate == EMPTY) {
                    throw IllegalStateException(
                        "Text layout cluster cache corruption: hash $hash in LRU but missing from hash map",
                    )
                }
                slot = (slot + 1) and hashMask
                probes++
            }
            throw IllegalStateException("Text layout cluster cache corruption: hash map probe overflow for hash $hash")
        }

        private fun reinsertHashCluster(startSlot: Int) {
            var slot = startSlot
            while (hashEntries[slot] != EMPTY) {
                val hash = hashKeys[slot]
                val entry = hashEntries[slot]
                hashEntries[slot] = EMPTY
                insertHashEntry(hash, entry)
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

        private fun hashSlot(hash: Int): Int {
            var mixed = hash
            mixed = mixed xor (mixed ushr 16)
            mixed *= -2048144789
            mixed = mixed xor (mixed ushr 13)
            return mixed and hashMask
        }
    }

    companion object {
        private const val DEFAULT_CODE_POINT_CAPACITY = 4096
        private const val DEFAULT_CLUSTER_CAPACITY_PER_STYLE = 1024
        private const val STYLE_COUNT = 4
        private const val STYLE_MASK = Font.BOLD or Font.ITALIC
        private const val EMPTY = -1
        private const val EVICTION_BATCH_DIVISOR = 10

        /**
         * Maximum code points shaped as one cluster before the renderer falls
         * back to drawing the remainder as individual code points.
         */
        internal const val MAX_CLUSTER_LENGTH = 32

        private const val REPLACEMENT_CODE_POINT = 0xFFFD

        @JvmStatic
        private fun codePointKey(
            codePoint: Int,
            style: Int,
        ): Long = (style.toLong() shl 32) or (codePoint.toLong() and 0xFFFF_FFFFL)

        @JvmStatic
        private fun unicodeScalarOrReplacement(codePoint: Int): Int = if (isUnicodeScalar(codePoint)) codePoint else REPLACEMENT_CODE_POINT

        @JvmStatic
        private fun isUnicodeScalar(codePoint: Int): Boolean = codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF

        @JvmStatic
        private fun hashCapacity(entryCapacity: Int): Int {
            var capacity = 1
            while (capacity < entryCapacity * 2) {
                capacity = capacity shl 1
            }
            return capacity
        }
    }
}

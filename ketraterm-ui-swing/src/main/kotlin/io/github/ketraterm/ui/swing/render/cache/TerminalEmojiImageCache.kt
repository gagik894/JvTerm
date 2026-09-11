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

import io.github.ketraterm.ui.swing.render.platform.TerminalPlatformEmojiRasterizer
import java.awt.image.BufferedImage

/**
 * Bounded image and unsupported-result cache for one fixed native rasterizer.
 *
 * Identity is code point content and raster pixel size. Scalar lookups compare
 * primitive fields; cluster lookups borrow a slice only for the duration of the
 * lookup. Stored keys own their content. Text and retained entries are allocated
 * only on misses, including negative results, which share the same LRU budget.
 * Lookup mutation and native rasterization are serialized by the cache monitor.
 */
internal class TerminalEmojiImageCache(
    private val rasterizer: TerminalPlatformEmojiRasterizer,
) {
    private val lookupKey = Key()
    private val entries =
        object : LinkedHashMap<Key, RasterizedImage>(CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, RasterizedImage>): Boolean = size > CAPACITY
        }

    fun codePointImage(
        codePoint: Int,
        pixelSize: Int,
    ): BufferedImage? =
        synchronized(entries) {
            if (!rasterizer.available) return@synchronized null
            lookupKey.setCodePoint(codePoint, pixelSize)
            resolveImage()
        }

    fun clusterImage(
        codepoints: IntArray,
        offset: Int,
        length: Int,
        pixelSize: Int,
    ): BufferedImage? =
        synchronized(entries) {
            if (!rasterizer.available) return@synchronized null
            try {
                lookupKey.setCluster(codepoints, offset, length, pixelSize)
                resolveImage()
            } finally {
                lookupKey.releaseSlice()
            }
        }

    /** Called with [entries] locked; a non-null entry can contain an unsupported null image. */
    private fun resolveImage(): BufferedImage? {
        entries[lookupKey]?.let { return it.image }
        val key = lookupKey.snapshot()
        val image = rasterizer.rasterize(key.text(), key.pixelSize)
        entries[key] = RasterizedImage(image)
        return image
    }

    private class RasterizedImage(
        val image: BufferedImage?,
    )

    /** Only [lookupKey] is mutated; map keys are owned snapshots. */
    private class Key {
        var pixelSize: Int = 0
            private set
        private var codePoint: Int = 0
        private var codepoints: IntArray = EMPTY_CODEPOINTS
        private var offset: Int = 0
        private var length: Int = 0
        private var hash: Int = 0

        fun setCodePoint(
            codePoint: Int,
            pixelSize: Int,
        ) {
            this.codePoint = codePoint
            this.pixelSize = pixelSize
            codepoints = EMPTY_CODEPOINTS
            offset = 0
            length = 1
            hash = 31 * (31 + codePoint) + pixelSize
        }

        fun setCluster(
            codepoints: IntArray,
            offset: Int,
            length: Int,
            pixelSize: Int,
        ) {
            require(length > 0 && offset >= 0 && codepoints.size - offset >= length) { "Invalid emoji code point slice" }
            if (length == 1) {
                setCodePoint(codepoints[offset], pixelSize)
                return
            }
            this.codepoints = codepoints
            this.offset = offset
            this.length = length
            this.pixelSize = pixelSize
            codePoint = 0
            var contentHash = 1
            var index = 0
            while (index < length) {
                contentHash = 31 * contentHash + codepoints[offset + index]
                index++
            }
            hash = 31 * contentHash + pixelSize
        }

        fun snapshot(): Key {
            val stored = Key()
            stored.codePoint = codePoint
            stored.pixelSize = pixelSize
            stored.length = length
            stored.hash = hash
            if (length > 1) stored.codepoints = codepoints.copyOfRange(offset, offset + length)
            return stored
        }

        fun releaseSlice() {
            codepoints = EMPTY_CODEPOINTS
        }

        fun text(): String = if (length == 1) String(Character.toChars(codePoint)) else String(codepoints, offset, length)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is Key || pixelSize != other.pixelSize || length != other.length) return false
            if (length == 1) return codePoint == other.codePoint
            var index = 0
            while (index < length) {
                if (codepoints[offset + index] != other.codepoints[other.offset + index]) return false
                index++
            }
            return true
        }
    }

    private companion object {
        private const val CAPACITY = 1024
        private val EMPTY_CODEPOINTS = IntArray(0)
    }
}

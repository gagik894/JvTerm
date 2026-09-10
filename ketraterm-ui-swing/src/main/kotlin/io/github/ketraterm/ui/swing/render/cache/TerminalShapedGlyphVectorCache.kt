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
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import kotlin.math.abs

/**
 * Bounded cache of contextually shaped glyph vectors positioned in terminal cells.
 *
 * Java2D chooses the glyphs for an entire directional run. Positioning then moves
 * each grapheme's glyphs together, preserving mark offsets and ligatures spanning
 * multiple cells. Cached vectors are read-only to callers and belong to the EDT.
 */
internal class TerminalShapedGlyphVectorCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val lookupKey = Key()
    private val glyphPosition = Point2D.Float()
    private val layouts =
        object : LinkedHashMap<Key, Run>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Run>): Boolean = size > capacity
        }
    private var fontCache: FontCache? = null
    private var fontGeneration = -1
    private var fontRenderContext: FontRenderContext? = null

    fun clear() {
        layouts.clear()
        fontCache = null
        fontGeneration = -1
        fontRenderContext = null
    }

    /**
     * Shapes [length] UTF-16 units and returns positions relative to the visual
     * start of this run. [charColumns] maps every UTF-16 unit to its logical
     * terminal-cell owner; all units of one grapheme share an owner. Owners must
     * start at zero and increase in logical order. [columns] includes wide tails.
     *
     * Input storage is copied only on a cache miss. Callers must split oversized
     * runs at cell boundaries before exceeding [MAX_RUN_LENGTH].
     */
    fun run(
        chars: CharArray,
        length: Int,
        charColumns: IntArray,
        columns: Int,
        style: Int,
        cellWidth: Int,
        fontCache: FontCache,
        fontRenderContext: FontRenderContext,
        rtl: Boolean,
    ): Run {
        require(length in 1..MAX_RUN_LENGTH && length <= chars.size && length <= charColumns.size)
        require(columns in 1..MAX_RUN_LENGTH * 2 && cellWidth > 0)
        fontCache.refreshSystemFallbackFonts()
        prepare(fontCache, fontRenderContext)
        lookupKey.update(chars, length, charColumns, columns, style and STYLE_MASK, cellWidth, rtl)
        layouts[lookupKey]?.let { return it }

        val key = lookupKey.snapshot()
        checkCellOwners(key)
        val font = fontCache.fontForText(String(key.chars), key.style)
        val direction = if (rtl) Font.LAYOUT_RIGHT_TO_LEFT else Font.LAYOUT_LEFT_TO_RIGHT
        val vector = font.layoutGlyphVector(fontRenderContext, key.chars, 0, length, direction)
        positionClusters(vector, key)
        val run = Run(vector, key.columns * key.cellWidth.toFloat())
        layouts[key] = run
        return run
    }

    private fun prepare(
        fonts: FontCache,
        context: FontRenderContext,
    ) {
        if (fonts === fontCache && fonts.generation == fontGeneration && context == fontRenderContext) return
        layouts.clear()
        fontCache = fonts
        fontGeneration = fonts.generation
        fontRenderContext = context
    }

    private fun checkCellOwners(key: Key) {
        require(key.charColumns[0] == 0) { "The first character must own column zero" }
        var previous = 0
        var index = 0
        while (index < key.length) {
            val owner = key.charColumns[index]
            require(owner in previous until key.columns) { "Character owners must increase within the run" }
            previous = owner
            index++
        }
    }

    private fun positionClusters(
        vector: GlyphVector,
        key: Key,
    ) {
        val glyphCount = vector.numGlyphs
        val positions = vector.getGlyphPositions(0, glyphCount + 1, null)
        val owners = IntArray(glyphCount)
        val origins = FloatArray(key.columns) { Float.NaN }
        val advanceEnds = FloatArray(key.columns) { Float.NEGATIVE_INFINITY }
        val firstPositions = FloatArray(key.columns) { Float.POSITIVE_INFINITY }
        var glyph = 0
        while (glyph < glyphCount) {
            val owner = key.charColumns[vector.getGlyphCharIndex(glyph)]
            owners[glyph] = owner
            val x = positions[glyph * 2]
            val advance = vector.getGlyphMetrics(glyph).advanceX
            firstPositions[owner] = minOf(firstPositions[owner], x)
            if (advance > 0f) {
                origins[owner] = if (origins[owner].isNaN()) x else minOf(origins[owner], x)
                advanceEnds[owner] = maxOf(advanceEnds[owner], x + advance)
            }
            glyph++
        }

        // A ligature reports its first logical character. Owners with no glyph
        // belong to the preceding represented owner, including its wide cells.
        val ends = IntArray(key.columns)
        var next = key.columns
        var owner = key.columns - 1
        while (owner >= 0) {
            if (firstPositions[owner] != Float.POSITIVE_INFINITY) {
                ends[owner] = next
                next = owner
                if (origins[owner].isNaN()) origins[owner] = firstPositions[owner]
            }
            owner--
        }

        val scales = FloatArray(key.columns) { 1f }
        val transforms = arrayOfNulls<AffineTransform>(key.columns)
        owner = 0
        while (owner < key.columns) {
            val end = ends[owner]
            if (end > 0) {
                val available = (end - owner) * key.cellWidth.toFloat()
                val advance = advanceEnds[owner] - origins[owner]
                if (advance > available) {
                    scales[owner] = available / advance
                    transforms[owner] = AffineTransform.getScaleInstance(scales[owner].toDouble(), 1.0)
                }
            }
            owner++
        }

        glyph = 0
        while (glyph < glyphCount) {
            owner = owners[glyph]
            val visualColumn = if (key.rtl) key.columns - ends[owner] else owner
            glyphPosition.x = visualColumn * key.cellWidth + (positions[glyph * 2] - origins[owner]) * scales[owner]
            glyphPosition.y = positions[glyph * 2 + 1]
            vector.setGlyphPosition(glyph, glyphPosition)
            transforms[owner]?.let { vector.setGlyphTransform(glyph, it) }
            glyph++
        }
        glyphPosition.x = key.columns * key.cellWidth.toFloat()
        glyphPosition.y = positions[glyphCount * 2 + 1]
        vector.setGlyphPosition(glyphCount, glyphPosition)
    }

    /**
     * Read-only positioned text shared by ordinary painting and cursor repaint.
     * Large runs retain glyph batches so a short color or cursor span does not
     * resubmit every glyph in the contextual run. Batches keep the already shaped
     * glyphs, including their marks, ligatures, fallback fonts and transforms.
     */
    class Run internal constructor(
        val glyphVector: GlyphVector,
        private val width: Float,
    ) {
        private val batches: Array<GlyphVector>
        private val minX: FloatArray
        private val maxX: FloatArray
        private val prefixMaxX: FloatArray
        private val suffixMinX: FloatArray

        init {
            val glyphCount = glyphVector.numGlyphs
            val batchCount = if (glyphCount > GLYPHS_PER_BATCH) (glyphCount + GLYPHS_PER_BATCH - 1) / GLYPHS_PER_BATCH else 0
            minX = FloatArray(batchCount)
            maxX = FloatArray(batchCount)
            prefixMaxX = FloatArray(batchCount)
            suffixMinX = FloatArray(batchCount)
            val context = glyphVector.fontRenderContext
            val transform = context.transform
            val determinant = abs(transform.determinant)
            // Glyph outlines need a device-pixel margin for raster rounding at
            // fractional origins. Convert that margin back to baseline units.
            val pixelMargin =
                if (determinant > 0.0) ((abs(transform.scaleY) + abs(transform.shearX)) / determinant).toFloat() else 0f
            val position = Point2D.Float()
            batches =
                Array(batchCount) { batchIndex ->
                    val start = batchIndex * GLYPHS_PER_BATCH
                    val count = minOf(GLYPHS_PER_BATCH, glyphCount - start)
                    val codes = glyphVector.getGlyphCodes(start, count, null)
                    val batch = glyphVector.font.createGlyphVector(context, codes)
                    var glyph = 0
                    while (glyph <= count) {
                        val original = glyphVector.getGlyphPosition(start + glyph)
                        position.x = original.x.toFloat()
                        position.y = original.y.toFloat()
                        batch.setGlyphPosition(glyph, position)
                        if (glyph < count) batch.setGlyphTransform(glyph, glyphVector.getGlyphTransform(start + glyph))
                        glyph++
                    }
                    val bounds = batch.visualBounds
                    minX[batchIndex] = bounds.minX.toFloat() - pixelMargin
                    maxX[batchIndex] = bounds.maxX.toFloat() + pixelMargin
                    batch
                }
            var runningMax = Float.NEGATIVE_INFINITY
            var index = 0
            while (index < batchCount) {
                runningMax = maxOf(runningMax, maxX[index])
                prefixMaxX[index++] = runningMax
            }
            var runningMin = Float.POSITIVE_INFINITY
            index = batchCount - 1
            while (index >= 0) {
                runningMin = minOf(runningMin, minX[index])
                suffixMinX[index--] = runningMin
            }
        }

        /**
         * Draws the glyphs intersecting the caller's existing clip. [clipStartX]
         * and [clipEndX] are relative to this run's visual origin [x]. A complete
         * span submits the original vector once; short spans only submit nearby
         * batches. No glyph positions or temporary drawing objects are created.
         */
        fun draw(
            g: Graphics2D,
            x: Float,
            y: Float,
            clipStartX: Float,
            clipEndX: Float,
        ) {
            if (clipEndX <= clipStartX) return
            if (batches.isEmpty() || clipStartX <= 0f && clipEndX >= width) {
                g.drawGlyphVector(glyphVector, x, y)
                return
            }
            var start = 0
            var end = batches.size
            while (start < end) {
                val middle = (start + end) ushr 1
                if (prefixMaxX[middle] <= clipStartX) start = middle + 1 else end = middle
            }
            var batch = start
            end = batches.size
            while (start < end) {
                val middle = (start + end) ushr 1
                if (suffixMinX[middle] < clipEndX) start = middle + 1 else end = middle
            }
            while (batch < end) {
                if (maxX[batch] > clipStartX && minX[batch] < clipEndX) g.drawGlyphVector(batches[batch], x, y)
                batch++
            }
        }

        private companion object {
            const val GLYPHS_PER_BATCH = 64
        }
    }

    /** Only the reusable lookup instance is mutated; stored keys own snapshots. */
    private class Key {
        var chars = CharArray(0)
            private set
        var charColumns = IntArray(0)
            private set
        var length = 0
            private set
        var columns = 0
            private set
        var style = 0
            private set
        var cellWidth = 0
            private set
        var rtl = false
            private set
        private var hash = 0

        fun update(
            chars: CharArray,
            length: Int,
            charColumns: IntArray,
            columns: Int,
            style: Int,
            cellWidth: Int,
            rtl: Boolean,
        ) {
            this.chars = chars
            this.length = length
            this.charColumns = charColumns
            this.columns = columns
            this.style = style
            this.cellWidth = cellWidth
            this.rtl = rtl
            var result = 31 * (31 * (31 * columns + style) + cellWidth) + if (rtl) 1 else 0
            var index = 0
            while (index < length) {
                result = 31 * result + chars[index].code
                result = 31 * result + charColumns[index]
                index++
            }
            hash = result
        }

        fun snapshot(): Key =
            Key().apply {
                update(
                    this@Key.chars.copyOf(this@Key.length),
                    this@Key.length,
                    this@Key.charColumns.copyOf(this@Key.length),
                    this@Key.columns,
                    this@Key.style,
                    this@Key.cellWidth,
                    this@Key.rtl,
                )
            }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key ||
                hash != other.hash ||
                length != other.length ||
                columns != other.columns ||
                style != other.style ||
                cellWidth != other.cellWidth ||
                rtl != other.rtl
            ) {
                return false
            }
            var index = 0
            while (index < length) {
                if (chars[index] != other.chars[index] || charColumns[index] != other.charColumns[index]) return false
                index++
            }
            return true
        }
    }

    companion object {
        /** Maximum UTF-16 units in one cached shaping run. */
        const val MAX_RUN_LENGTH = 4096
        private const val DEFAULT_CAPACITY = 2048
        private const val STYLE_MASK = Font.BOLD or Font.ITALIC
    }
}

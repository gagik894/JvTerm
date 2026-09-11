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

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import java.util.*

class TerminalShapedGlyphVectorCacheTest {
    private val font = Font(Font.SERIF, Font.PLAIN, 18)
    private val fonts = FontCache().apply { update(font, emptyList(), useSystemFallbackFonts = false) }
    private val context = FontRenderContext(null, false, false)

    @Test
    fun `same style Hebrew glyphs retain their individual visual cells`() {
        val cache = TerminalShapedGlyphVectorCache()
        val vector = cache.shape("אבג", intArrayOf(0, 1, 2), columns = 3, rtl = true)

        assertEquals(3, vector.numGlyphs)
        for (glyph in 0 until vector.numGlyphs) {
            val visualColumn = 2 - vector.getGlyphCharIndex(glyph)
            assertEquals(visualColumn * CELL_WIDTH.toDouble(), vector.getGlyphPosition(glyph).x, 0.0)
        }
        assertEquals(3 * CELL_WIDTH.toDouble(), vector.getGlyphPosition(vector.numGlyphs).x, 0.0)
    }

    @Test
    fun `Arabic glyph selection retains neighboring shaping context`() {
        val text = "ببب"
        val natural = naturalVector(text, rtl = true)
        val positioned = TerminalShapedGlyphVectorCache().shape(text, intArrayOf(0, 1, 2), columns = 3, rtl = true)

        assertArrayEquals(natural.getGlyphCodes(0, natural.numGlyphs, null), positioned.getGlyphCodes(0, positioned.numGlyphs, null))
        assertArrayEquals(
            natural.getGlyphCharIndices(0, natural.numGlyphs, null),
            positioned.getGlyphCharIndices(0, positioned.numGlyphs, null),
        )
    }

    @Test
    fun `RTL shaping mirrors paired brackets while retaining their cell ownership`() {
        val cache = TerminalShapedGlyphVectorCache()
        val brackets = "()[]"
        for (index in brackets.indices) {
            val source = brackets[index].toString()
            val mirrored = brackets[index xor 1].toString()
            val vector = cache.shape(source, intArrayOf(0), columns = 1, rtl = true)
            val expected = naturalVector(mirrored, rtl = false)

            assertArrayEquals(expected.getGlyphCodes(0, expected.numGlyphs, null), vector.getGlyphCodes(0, vector.numGlyphs, null))
            assertEquals(0, vector.getGlyphCharIndex(0))
            assertEquals(0.0, vector.getGlyphPosition(0).x, 0.0)
        }
    }

    @Test
    fun `combining mark offsets remain relative to their contextual base glyph`() {
        for (text in listOf("بَبب", "בַבב")) {
            val natural = naturalVector(text, rtl = true)
            val positioned = TerminalShapedGlyphVectorCache().shape(text, intArrayOf(0, 0, 1, 2), columns = 3, rtl = true)
            val base = glyphForCharacter(natural, 0)
            val mark = glyphForCharacter(natural, 1)
            val naturalBase = natural.getGlyphPosition(base)
            val naturalMark = natural.getGlyphPosition(mark)
            val positionedBase = positioned.getGlyphPosition(base)
            val positionedMark = positioned.getGlyphPosition(mark)

            assertEquals(naturalMark.x - naturalBase.x, positionedMark.x - positionedBase.x, 0.00001)
            assertEquals(naturalMark.y - naturalBase.y, positionedMark.y - positionedBase.y, 0.00001)
            assertEquals(2 * CELL_WIDTH.toDouble(), positionedBase.x, 0.0)
        }
    }

    @Test
    fun `lam alef ligatures occupy the union of their logical cells`() {
        val natural = naturalVector("لالا", rtl = true)
        assumeTrue(natural.numGlyphs == 2, "The platform font must shape lam-alef ligatures")
        val positioned = TerminalShapedGlyphVectorCache().shape("لالا", intArrayOf(0, 1, 2, 3), columns = 4, rtl = true)

        assertEquals(0.0, positioned.getGlyphPosition(0).x, 0.0)
        assertEquals(2 * CELL_WIDTH.toDouble(), positioned.getGlyphPosition(1).x, 0.0)
        assertArrayEquals(natural.getGlyphCodes(0, 2, null), positioned.getGlyphCodes(0, 2, null))
    }

    @Test
    fun `wide owners retain both visual cells and surrogate units share one owner`() {
        val text = "\uD83D\uDE00אב"
        val positioned = TerminalShapedGlyphVectorCache().shape(text, intArrayOf(0, 0, 2, 3), columns = 4, rtl = true)
        val alef = glyphForCharacter(positioned, 2)
        val bet = glyphForCharacter(positioned, 3)

        assertEquals(CELL_WIDTH.toDouble(), positioned.getGlyphPosition(alef).x, 0.0)
        assertEquals(0.0, positioned.getGlyphPosition(bet).x, 0.0)
        assertEquals(4 * CELL_WIDTH.toDouble(), positioned.getGlyphPosition(positioned.numGlyphs).x, 0.0)
    }

    @Test
    fun `oversized grapheme is compressed without moving the next cell`() {
        val vector = TerminalShapedGlyphVectorCache().shape("WWא", intArrayOf(0, 0, 1), columns = 2, cellWidth = 4)
        val secondCell = glyphForCharacter(vector, 2)
        assertEquals(4.0, vector.getGlyphPosition(secondCell).x, 0.0)
        assertTrue(vector.getGlyphTransform(0).scaleX < 1.0)
        assertEquals(vector.getGlyphTransform(0), vector.getGlyphTransform(1))
    }

    @Test
    fun `cache identity includes ownership span style direction and cell width`() {
        val cache = TerminalShapedGlyphVectorCache()
        val owners = intArrayOf(0, 1)
        val base = cache.shape("אב", owners, columns = 2)

        assertSame(base, cache.shape("אב", owners, columns = 2))
        assertNotSame(base, cache.shape("אב", intArrayOf(0, 0), columns = 2))
        assertNotSame(base, cache.shape("אב", owners, columns = 3))
        assertNotSame(base, cache.shape("אב", owners, columns = 2, style = Font.BOLD))
        assertNotSame(base, cache.shape("אב", owners, columns = 2, rtl = true))
        assertNotSame(base, cache.shape("אב", owners, columns = 2, cellWidth = CELL_WIDTH + 1))
    }

    @Test
    fun `stored text and ownership do not retain mutable caller storage`() {
        val cache = TerminalShapedGlyphVectorCache()
        val chars = "אב".toCharArray()
        val owners = intArrayOf(0, 1)
        val first = cache.run(chars, 2, owners, 2, Font.PLAIN, CELL_WIDTH, fonts, context, rtl = true)
        chars[0] = 'ג'
        owners[1] = 0
        assertNotSame(first, cache.run(chars, 2, owners, 2, Font.PLAIN, CELL_WIDTH, fonts, context, rtl = true))
        chars[0] = 'א'
        owners[1] = 1
        assertSame(first, cache.run(chars, 2, owners, 2, Font.PLAIN, CELL_WIDTH, fonts, context, rtl = true))
    }

    @Test
    fun `font generation identity and render context invalidate shaped vectors`() {
        val cache = TerminalShapedGlyphVectorCache()
        val owners = intArrayOf(0, 1)
        val first = cache.shape("אב", owners, columns = 2)
        fonts.update(font.deriveFont(19f), emptyList(), useSystemFallbackFonts = false)
        val afterUpdate = cache.shape("אב", owners, columns = 2)
        assertNotSame(first, afterUpdate)

        val replacement =
            FontCache().apply {
                update(font, emptyList(), useSystemFallbackFonts = false)
                update(font.deriveFont(19f), emptyList(), useSystemFallbackFonts = false)
            }
        assertEquals(fonts.generation, replacement.generation)
        val replaced = cache.shape("אב", owners, columns = 2, fontCache = replacement)
        assertNotSame(afterUpdate, replaced)
        val scaled = FontRenderContext(AffineTransform.getScaleInstance(1.5, 1.5), false, false)
        assertNotSame(replaced, cache.shape("אב", owners, columns = 2, fontCache = replacement, renderContext = scaled))
    }

    @Test
    fun `least recently used runs are evicted and clear discards all runs`() {
        val cache = TerminalShapedGlyphVectorCache(capacity = 2)
        val owners = intArrayOf(0, 1)
        val first = cache.shape("אב", owners, columns = 2)
        val second = cache.shape("אג", owners, columns = 2)
        assertSame(first, cache.shape("אב", owners, columns = 2))
        cache.shape("אד", owners, columns = 2)
        assertNotSame(second, cache.shape("אג", owners, columns = 2))
        val beforeClear = cache.shape("אב", owners, columns = 2)
        cache.clear()
        assertNotSame(beforeClear, cache.shape("אב", owners, columns = 2))
    }

    @Test
    fun `runs beyond cluster cap retain their full text while oversized shaping input is rejected`() {
        val cache = TerminalShapedGlyphVectorCache()
        val length = TerminalComplexTextLayoutCache.MAX_CLUSTER_LENGTH + 8
        val text = "א".repeat(length)
        val vector = cache.shape(text, IntArray(length) { it }, columns = length)
        assertEquals(length, vector.numGlyphs)

        val oversized = TerminalShapedGlyphVectorCache.MAX_RUN_LENGTH + 1
        assertThrows<IllegalArgumentException> {
            cache.shape("א".repeat(oversized), IntArray(oversized) { it }, columns = oversized)
        }
    }

    @Test
    fun `invalid cache capacity and cell ownership are rejected`() {
        assertThrows<IllegalArgumentException> { TerminalShapedGlyphVectorCache(capacity = 0) }
        val cache = TerminalShapedGlyphVectorCache()
        assertThrows<IllegalArgumentException> { cache.shape("אב", intArrayOf(1, 2), columns = 3) }
        assertThrows<IllegalArgumentException> { cache.shape("אבג", intArrayOf(0, 2, 1), columns = 3) }
        assertThrows<IllegalArgumentException> { cache.shape("אב", intArrayOf(0, 2), columns = 2) }
    }

    @Test
    fun `warmed contextual shaping cache hits allocate no storage`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        allocationBean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val cache = TerminalShapedGlyphVectorCache()
        val chars = "بَبب".toCharArray()
        val owners = intArrayOf(0, 0, 1, 2)
        repeat(20_000) { cache.run(chars, chars.size, owners, 3, Font.PLAIN, CELL_WIDTH, fonts, context, rtl = true) }

        val before = allocationBean.getThreadAllocatedBytes(thread)
        repeat(10_000) { cache.run(chars, chars.size, owners, 3, Font.PLAIN, CELL_WIDTH, fonts, context, rtl = true) }
        val allocated = allocationBean.getThreadAllocatedBytes(thread) - before
        assertEquals(0L, allocated)
    }

    @Test
    fun `clipped glyph batches preserve full vector pixels across marks ligatures and batch boundaries`() {
        val marked = "بَبب".repeat(40) + "ب"
        val markedOwners = IntArray(marked.length) { index -> index / 4 * 3 + maxOf(0, index % 4 - 1) }
        val cases =
            listOf(
                Triple("W".repeat(129), IntArray(129) { it }, false),
                Triple("אבג".repeat(45), IntArray(135) { it }, true),
                Triple(marked, markedOwners, true),
                Triple("لا".repeat(70), IntArray(140) { it }, true),
            )
        for ((text, owners, rtl) in cases) {
            for (cellWidth in intArrayOf(4, CELL_WIDTH)) {
                for (scale in doubleArrayOf(1.0, 1.25)) {
                    val columns = owners.last() + 1
                    val width = columns * cellWidth
                    val frc = FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, false)
                    val run =
                        TerminalShapedGlyphVectorCache().run(
                            text.toCharArray(),
                            text.length,
                            owners,
                            columns,
                            Font.PLAIN,
                            cellWidth,
                            fonts,
                            frc,
                            rtl,
                        )
                    val clips = arrayOf(0 to width, 0 to cellWidth, 62 * cellWidth to 65 * cellWidth, width - cellWidth to width)
                    for ((start, end) in clips) {
                        val expected = renderRun(run, width, start, end, scale, batched = false)
                        val actual = renderRun(run, width, start, end, scale, batched = true)
                        assertEquals(
                            -1,
                            Arrays.mismatch(expected, actual),
                            "rtl=$rtl, cellWidth=$cellWidth, scale=$scale, clip=$start..$end",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `warmed single batch selection allocates no more than one direct glyph submission`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean as ThreadMXBean
        allocationBean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val text = "אבג".repeat(45)
        for (antialiased in booleanArrayOf(false, true)) {
            val recordingFont = BatchRecordingFont()
            val recordingFonts = FontCache().apply { update(recordingFont, emptyList(), useSystemFallbackFonts = false) }
            val run =
                TerminalShapedGlyphVectorCache().run(
                    text.toCharArray(),
                    text.length,
                    IntArray(text.length) { it },
                    text.length,
                    Font.PLAIN,
                    CELL_WIDTH,
                    recordingFonts,
                    FontRenderContext(null, antialiased, false),
                    rtl = true,
                )
            assertSame(recordingFont, run.glyphVector.font)
            // This clip is well inside one retained batch. Use that exact vector
            // so its glyph count, flags and context match the direct control.
            // macOS can promote AA_OFF to AA_ON and copy the submitted vector;
            // using the full run as the control would measure a larger JDK copy.
            val start = 31 * CELL_WIDTH
            val end = 32 * CELL_WIDTH
            val batch =
                recordingFont.batches.single {
                    val bounds = it.visualBounds
                    bounds.maxX > start && bounds.minX < end
                }
            assertTrue(batch.numGlyphs < run.glyphVector.numGlyphs)

            val image = BufferedImage(text.length * CELL_WIDTH, 40, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.color = Color.WHITE
                g.clipRect(start, 0, end - start, image.height)
                repeat(10_000) {
                    run.draw(g, 0f, 26f, start.toFloat(), end.toFloat())
                    g.drawGlyphVector(batch, 0f, 26f)
                }
                val batchBefore = allocationBean.getThreadAllocatedBytes(thread)
                repeat(2_000) { run.draw(g, 0f, 26f, start.toFloat(), end.toFloat()) }
                val batchAllocated = allocationBean.getThreadAllocatedBytes(thread) - batchBefore
                val directBefore = allocationBean.getThreadAllocatedBytes(thread)
                repeat(2_000) { g.drawGlyphVector(batch, 0f, 26f) }
                val directAllocated = allocationBean.getThreadAllocatedBytes(thread) - directBefore
                assertEquals(directAllocated, batchAllocated, "antialiased=$antialiased")
            } finally {
                g.dispose()
            }
        }
    }

    /** Records batches during cache construction, before positions and transforms are assigned. */
    private class BatchRecordingFont : Font(SERIF, PLAIN, 18) {
        val batches = mutableListOf<GlyphVector>()

        override fun createGlyphVector(
            context: FontRenderContext,
            glyphCodes: IntArray,
        ): GlyphVector = super.createGlyphVector(context, glyphCodes).also { batches.add(it) }
    }

    private fun renderRun(
        run: TerminalShapedGlyphVectorCache.Run,
        width: Int,
        start: Int,
        end: Int,
        scale: Double,
        batched: Boolean,
    ): IntArray {
        val image = BufferedImage(((width + 2) * scale).toInt(), (40 * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.scale(scale, scale)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            g.color = Color.WHITE
            g.clip(Rectangle2D.Float(start + 0.375f, 0f, (end - start).toFloat(), 40f))
            if (batched) run.draw(g, 0.375f, 26f, start.toFloat(), end.toFloat()) else g.drawGlyphVector(run.glyphVector, 0.375f, 26f)
        } finally {
            g.dispose()
        }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun TerminalShapedGlyphVectorCache.shape(
        text: String,
        owners: IntArray,
        columns: Int,
        style: Int = Font.PLAIN,
        cellWidth: Int = CELL_WIDTH,
        rtl: Boolean = false,
        fontCache: FontCache = fonts,
        renderContext: FontRenderContext = context,
    ): GlyphVector = run(text.toCharArray(), text.length, owners, columns, style, cellWidth, fontCache, renderContext, rtl).glyphVector

    private fun naturalVector(
        text: String,
        rtl: Boolean,
    ): GlyphVector = font.layoutGlyphVector(context, text.toCharArray(), 0, text.length, if (rtl) Font.LAYOUT_RIGHT_TO_LEFT else 0)

    private fun glyphForCharacter(
        vector: GlyphVector,
        charIndex: Int,
    ): Int = (0 until vector.numGlyphs).first { vector.getGlyphCharIndex(it) == charIndex }

    private companion object {
        const val CELL_WIDTH = 24
    }
}

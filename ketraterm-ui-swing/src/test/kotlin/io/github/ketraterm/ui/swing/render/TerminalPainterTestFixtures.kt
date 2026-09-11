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
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

internal const val TEST_BLACK: Int = 0xFF000000.toInt()
internal const val TEST_WHITE: Int = 0xFFFFFFFF.toInt()
internal const val TEST_RED: Int = 0xFFFF0000.toInt()
internal const val TEST_GREEN: Int = 0xFF00FF00.toInt()
internal const val TEST_BLUE: Int = 0xFF0000FF.toInt()

internal fun defaultTestSettings(
    foreground: Int = TEST_WHITE,
    background: Int = TEST_BLACK,
): SwingSettings =
    SwingSettings(
        font = Font(Font.MONOSPACED, Font.PLAIN, 14),
        palette =
            TerminalColorPalette(
                defaultForeground = foreground,
                defaultBackground = background,
                cursorForeground = TEST_RED,
                cursorBackground = TEST_BLUE,
            ),
        textAntialiasing = RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
        padding = SwingPadding(0, 0, 0, 0),
    )

internal fun testMetrics(
    image: BufferedImage,
    settings: SwingSettings,
): SwingMetrics {
    val g = image.createGraphics()
    try {
        return SwingMetrics.from(g.getFontMetrics(settings.font))
    } finally {
        g.dispose()
    }
}

internal fun renderCache(frame: TestRenderFrame): TerminalRenderCache {
    val cache = TerminalRenderCache(columns = frame.columns, rows = frame.rows)
    cache.updateFrom(frame)
    return cache
}

internal fun BufferedImage.containsColor(
    argb: Int,
    width: Int = this.width,
    height: Int = this.height,
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

internal fun BufferedImage.containsColorInRange(
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

internal fun BufferedImage.containsPaintedPixelInRange(
    xStart: Int,
    xEnd: Int,
    yStart: Int = 0,
    yEnd: Int = height,
): Boolean {
    var y = yStart
    while (y < yEnd) {
        var x = xStart
        while (x < xEnd) {
            if ((getRGB(x, y) ushr 24) != 0) return true
            x++
        }
        y++
    }
    return false
}

internal fun BufferedImage.countColorInRange(
    argb: Int,
    xStart: Int,
    xEnd: Int,
    yStart: Int,
    yEnd: Int,
): Int {
    var count = 0
    var y = yStart
    while (y < yEnd) {
        var x = xStart
        while (x < xEnd) {
            if (getRGB(x, y) == argb) count++
            x++
        }
        y++
    }
    return count
}

internal data class TestCell(
    val codeWord: Int = 0,
    val flags: Int = TerminalRenderCellFlags.EMPTY,
    val attr: Long = TerminalRenderAttrs.DEFAULT,
    val extraAttr: Long = TerminalRenderExtraAttrs.DEFAULT,
    val hyperlinkId: Int = 0,
    val cluster: String? = null,
)

internal open class TestRenderFrame(
    private val cells: Array<Array<TestCell>>,
    private val cursorValue: TerminalRenderCursor =
        TerminalRenderCursor(
            column = 0,
            row = 0,
            visible = false,
            blinking = false,
            shape = TerminalRenderCursorShape.BLOCK,
            generation = 1,
        ),
) : TerminalRenderFrameReader,
    TerminalRenderFrame {
    override val columns: Int = cells.firstOrNull()?.size ?: 0
    override val rows: Int = cells.size
    override val frameGeneration: Long = 1
    override val structureGeneration: Long = 1
    override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
    override val cursor: TerminalRenderCursor = cursorValue

    init {
        require(rows > 0) { "rows must be > 0" }
        require(columns > 0) { "columns must be > 0" }
        for (row in cells) {
            require(row.size == columns) { "all rows must have the same column count" }
        }
    }

    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
        consumer.accept(this)
    }

    override fun lineGeneration(row: Int): Long = 1

    override fun lineId(row: Int): Long = row + 1L

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
            val cell = cells[row][column]
            codeWords[codeOffset + column] = cell.codeWord
            attrWords[attrOffset + column] = cell.attr
            flags[flagOffset + column] = cell.flags
            extraAttrWords?.set(extraAttrOffset + column, cell.extraAttr)
            hyperlinkIds?.set(hyperlinkOffset + column, cell.hyperlinkId)
            if (cell.cluster != null) {
                clusterSink?.onCluster(column, cell.cluster)
                val codepoints = clusterCodepoints(cell.cluster)
                clusterDataSink?.onCluster(column, codepoints, 0, codepoints.size)
            }
            column++
        }
    }

    private fun clusterCodepoints(text: String): IntArray {
        val codepoints = IntArray(text.codePointCount(0, text.length))
        var codepointIndex = 0
        var charIndex = 0
        while (charIndex < text.length) {
            val codepoint = Character.codePointAt(text, charIndex)
            codepoints[codepointIndex++] = codepoint
            charIndex += Character.charCount(codepoint)
        }
        return codepoints
    }

    companion object {
        fun text(
            text: String,
            cursorVisible: Boolean = false,
            attrs: LongArray = LongArray(text.codePointCount(0, text.length)) { TerminalRenderAttrs.DEFAULT },
            extraAttrs: LongArray = LongArray(text.codePointCount(0, text.length)) { TerminalRenderExtraAttrs.DEFAULT },
        ): TestRenderFrame {
            val codePointCount = text.codePointCount(0, text.length)
            require(attrs.size == codePointCount) {
                "attrs size must match text code point count: attrs=${attrs.size}, codePoints=$codePointCount"
            }
            require(extraAttrs.size == codePointCount) {
                "extraAttrs size must match text code point count: extraAttrs=${extraAttrs.size}, codePoints=$codePointCount"
            }

            var charIndex = 0
            val row =
                Array(codePointCount) { column ->
                    val codePoint = text.codePointAt(charIndex)
                    charIndex += Character.charCount(codePoint)
                    TestCell(
                        codeWord = codePoint,
                        flags = TerminalRenderCellFlags.CODEPOINT,
                        attr = attrs[column],
                        extraAttr = extraAttrs[column],
                    )
                }
            return TestRenderFrame(
                cells = arrayOf(row),
                cursorValue =
                    TerminalRenderCursor(
                        column = 0,
                        row = 0,
                        visible = cursorVisible,
                        blinking = false,
                        shape = TerminalRenderCursorShape.BLOCK,
                        generation = 1,
                    ),
            )
        }
    }
}

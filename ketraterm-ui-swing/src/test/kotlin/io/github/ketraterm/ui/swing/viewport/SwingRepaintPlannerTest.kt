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
package io.github.ketraterm.ui.swing.viewport

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.render.TerminalShellIntegrationViewportDecorations
import io.github.ketraterm.ui.swing.render.TerminalVisualViewportGeometry
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

class SwingRepaintPlannerTest {
    @Test
    fun `rtl cursor movement repaints old and new visual cells`() {
        val frame = MutableFrame(columns = 3, rows = 1)
        frame.setRow(0, "\u05D0\u05D1\u05D2")
        frame.cursor = cursor(column = 0, row = 0, generation = 1)
        val cache = TerminalRenderCache(3, 1)
        cache.updateFrom(frame.reader)
        val geometry = TerminalVisualViewportGeometry()
        geometry.updateLayout(METRICS, 1, HEIGHT)
        val planner = SwingRepaintPlanner()
        planner.requestFrameRepaint(cache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink, visualGeometry = geometry)

        frame.cursor = cursor(column = 2, row = 0, generation = 2)
        frame.frameGeneration++
        cache.updateFrom(frame.reader)
        val sink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(cache, METRICS, WIDTH, HEIGHT, PADDING, sink, visualGeometry = geometry)

        assertEquals(
            listOf(Region(2 * CELL_WIDTH, 0, CELL_WIDTH, CELL_HEIGHT), Region(0, 0, CELL_WIDTH, CELL_HEIGHT)),
            sink.regions,
        )
    }

    @Test
    fun `changed rows repaint only changed row runs`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        val planner = SwingRepaintPlanner()

        cache.updateFrom(frame.reader)
        planner.requestFrameRepaint(cache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink)

        frame.setRow(2, "WXYZ")
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(listOf(Region(0, 2 * CELL_HEIGHT, WIDTH, CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `contiguous changed rows are coalesced into one repaint region`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        val planner = SwingRepaintPlanner()

        cache.updateFrom(frame.reader)
        planner.requestFrameRepaint(cache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink)

        frame.setRow(1, "BBBB")
        frame.setRow(2, "CCCC")
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(listOf(Region(0, CELL_HEIGHT, WIDTH, 2 * CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `row repaint compares against last EDT snapshot when a cache buffer skipped a frame`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val paintedCache = TerminalRenderCache(columns = 4, rows = 4)
        val skippedCache = TerminalRenderCache(columns = 4, rows = 4)
        val planner = SwingRepaintPlanner()

        paintedCache.updateFrom(frame.reader)
        planner.requestFrameRepaint(paintedCache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink)

        frame.setRow(1, "BBBB")
        skippedCache.updateFrom(frame.reader)
        frame.setRow(0, "ZZZZ")
        skippedCache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(
            cache = skippedCache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(listOf(Region(0, 0, WIDTH, 2 * CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `cursor-only update repaints old and new cursor cells`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        val planner = SwingRepaintPlanner()

        frame.cursor = cursor(column = 1, row = 0, generation = 1)
        cache.updateFrom(frame.reader)
        planner.requestFrameRepaint(cache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink)

        frame.cursor = cursor(column = 3, row = 2, generation = 2)
        frame.frameGeneration++
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(
            listOf(
                Region(CELL_WIDTH, 0, CELL_WIDTH, CELL_HEIGHT),
                Region(3 * CELL_WIDTH, 2 * CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT),
            ),
            repaintSink.regions,
        )
    }

    @Test
    fun `cursor repaint compares against last EDT snapshot when a cache buffer skipped a frame`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val paintedCache = TerminalRenderCache(columns = 4, rows = 4)
        val skippedCache = TerminalRenderCache(columns = 4, rows = 4)
        val planner = SwingRepaintPlanner()

        frame.cursor = cursor(column = 0, row = 0, generation = 1)
        paintedCache.updateFrom(frame.reader)
        planner.requestFrameRepaint(paintedCache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink)

        frame.cursor = cursor(column = 3, row = 2, generation = 2)
        frame.frameGeneration++
        skippedCache.updateFrom(frame.reader)
        frame.frameGeneration++
        skippedCache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(
            cache = skippedCache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(
            listOf(
                Region(0, 0, CELL_WIDTH, CELL_HEIGHT),
                Region(3 * CELL_WIDTH, 2 * CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT),
            ),
            repaintSink.regions,
        )
    }

    @Test
    fun `cursor blink repaints only the cursor cell`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        frame.cursor = cursor(column = 2, row = 1, blinking = true, generation = 1)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink()
        SwingRepaintPlanner().requestCursorBlinkRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(listOf(Region(2 * CELL_WIDTH, CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `cursor blink over wide leader repaints both cells`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        frame.cursor = cursor(column = 1, row = 1, blinking = true, generation = 1)
        frame.setWideCell(row = 1, column = 1)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink()
        SwingRepaintPlanner().requestCursorBlinkRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(listOf(Region(CELL_WIDTH, CELL_HEIGHT, 2 * CELL_WIDTH, CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `cursor blink over wide trailing spacer repaints owner pair`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        frame.cursor = cursor(column = 2, row = 1, blinking = true, generation = 1)
        frame.setWideCell(row = 1, column = 1)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink()
        SwingRepaintPlanner().requestCursorBlinkRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(listOf(Region(CELL_WIDTH, CELL_HEIGHT, 2 * CELL_WIDTH, CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `fractional content offset shifts changed row repaint bounds`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        val planner = SwingRepaintPlanner()

        cache.updateFrom(frame.reader)
        planner.requestFrameRepaint(cache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink)

        frame.setRow(1, "BBBB")
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
            visualGeometry = visualGeometry(cache.rows, contentOriginY = -12.0),
        )

        assertEquals(listOf(Region(0, 4, WIDTH, CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `fractional content offset clips changed row repaint bounds at component top`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        val planner = SwingRepaintPlanner()

        cache.updateFrom(frame.reader)
        planner.requestFrameRepaint(cache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink)

        frame.setRow(0, "BBBB")
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
            visualGeometry = visualGeometry(cache.rows, contentOriginY = -12.0),
        )

        assertEquals(listOf(Region(0, 0, WIDTH, 4)), repaintSink.regions)
    }

    @Test
    fun `cursor blink repaint uses fractional content offset`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        frame.cursor = cursor(column = 2, row = 1, blinking = true, generation = 1)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink()
        SwingRepaintPlanner().requestCursorBlinkRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
            visualGeometry = visualGeometry(cache.rows, contentOriginY = -12.0),
        )

        assertEquals(listOf(Region(2 * CELL_WIDTH, 4, CELL_WIDTH, CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `cursor blink repaint remains fixed pitch with command gutter guides`() {
        val frame = MutableFrame(columns = 4, rows = 4, lineIds = longArrayOf(1, 2, 3, 4))
        frame.cursor = cursor(column = 2, row = 1, blinking = true, generation = 1)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        cache.updateFrom(frame.reader)
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordPromptStart(2)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)
        val geometry = visualGeometry(cache.rows)

        val repaintSink = RecordingRepaintSink()
        SwingRepaintPlanner().requestCursorBlinkRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
            visualGeometry = geometry,
        )

        assertEquals(
            listOf(Region(2 * CELL_WIDTH, CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT)),
            repaintSink.regions,
        )
    }

    @Test
    fun `blinking text repaint coalesces visible blinking row runs`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        frame.setBlink(row = 1, column = 0, blink = true)
        frame.setBlink(row = 2, column = 3, blink = true)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink()
        SwingRepaintPlanner().requestBlinkingTextRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(listOf(Region(0, CELL_HEIGHT, WIDTH, 2 * CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `blinking text repaint ignores non-blinking frames`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        SwingRepaintPlanner().requestBlinkingTextRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(emptyList(), repaintSink.regions)
    }

    @Test
    fun `blinking text repaint uses fractional content offset`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        frame.setBlink(row = 1, column = 0, blink = true)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        cache.updateFrom(frame.reader)

        val repaintSink = RecordingRepaintSink()
        SwingRepaintPlanner().requestBlinkingTextRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
            visualGeometry = visualGeometry(cache.rows, contentOriginY = -12.0),
        )

        assertEquals(listOf(Region(0, 4, WIDTH, CELL_HEIGHT)), repaintSink.regions)
    }

    @Test
    fun `forced frame repaint snapshots unchanged cache for metadata only decorations`() {
        val frame = MutableFrame(columns = 4, rows = 4)
        val cache = TerminalRenderCache(columns = 4, rows = 4)
        val planner = SwingRepaintPlanner()
        var fullRepaints = 0

        cache.updateFrom(frame.reader)
        planner.requestFrameRepaint(cache, METRICS, WIDTH, HEIGHT, PADDING, NoOpRepaintSink)

        planner.requestFrameRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink =
                object : TerminalRepaintSink {
                    override fun requestFullRepaint() {
                        fullRepaints++
                    }

                    override fun requestRegionRepaint(
                        x: Int,
                        y: Int,
                        width: Int,
                        height: Int,
                    ) {
                        error("metadata-only decoration repaint must not request a partial row repaint")
                    }
                },
            forceFullRepaint = true,
        )

        val repaintSink = RecordingRepaintSink(failOnFullRepaint = true)
        planner.requestFrameRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink = repaintSink,
        )

        assertEquals(1, fullRepaints)
        assertEquals(emptyList(), repaintSink.regions)
    }

    @Test
    fun `resized render cache requests full repaint`() {
        val cache = TerminalRenderCache(columns = 2, rows = 2)
        val planner = SwingRepaintPlanner()
        val frame = MutableFrame(columns = 3, rows = 3)
        var fullRepaints = 0

        cache.updateFrom(frame.reader)

        planner.requestFrameRepaint(
            cache = cache,
            metrics = METRICS,
            componentWidth = WIDTH,
            componentHeight = HEIGHT,
            padding = PADDING,
            repaintSink =
                object : TerminalRepaintSink {
                    override fun requestFullRepaint() {
                        fullRepaints++
                    }

                    override fun requestRegionRepaint(
                        x: Int,
                        y: Int,
                        width: Int,
                        height: Int,
                    ) {
                        error("resize must not request partial repaint")
                    }
                },
        )

        assertEquals(1, fullRepaints)
    }

    private fun visualGeometry(
        rows: Int,
        contentOriginY: Double = 0.0,
    ): TerminalVisualViewportGeometry =
        TerminalVisualViewportGeometry().also {
            it.updateLayout(
                metrics = METRICS,
                rows = rows,
                viewportPixelHeight = HEIGHT,
            )
            it.updateContentOrigin(contentOriginY)
        }

    private data class Region(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private class RecordingRepaintSink(
        private val failOnFullRepaint: Boolean = false,
    ) : TerminalRepaintSink {
        val regions = mutableListOf<Region>()

        override fun requestFullRepaint() {
            if (failOnFullRepaint) {
                error("update must not request full repaint")
            }
        }

        override fun requestRegionRepaint(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            regions.add(Region(x, y, width, height))
        }
    }

    private object NoOpRepaintSink : TerminalRepaintSink {
        override fun requestFullRepaint() = Unit

        override fun requestRegionRepaint(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) = Unit
    }

    private class MutableFrame(
        override val columns: Int,
        override val rows: Int,
        private val lineIds: LongArray = LongArray(rows) { row -> row + 1L },
    ) : TerminalRenderFrame {
        private val textRows =
            Array(rows) { row ->
                CharArray(columns) { column -> ('a'.code + row * columns + column).toChar() }
            }
        private val lineGenerations = LongArray(rows) { 1L }
        private val cellFlags = Array(rows) { IntArray(columns) { TerminalRenderCellFlags.CODEPOINT } }
        private val cellAttrs = Array(rows) { LongArray(columns) }

        override var frameGeneration: Long = 1L
        override var structureGeneration: Long = 1L
        override var activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override var cursor: TerminalRenderCursor = cursor(column = 0, row = 0, generation = 1)

        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(this@MutableFrame)
                }
            }

        fun setRow(
            row: Int,
            text: String,
        ) {
            require(text.length == columns)
            var column = 0
            while (column < columns) {
                textRows[row][column] = text[column]
                column++
            }
            lineGenerations[row]++
            frameGeneration++
        }

        fun setWideCell(
            row: Int,
            column: Int,
        ) {
            require(column in 0 until columns - 1)
            cellFlags[row][column] = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING
            cellFlags[row][column + 1] = TerminalRenderCellFlags.WIDE_TRAILING
            lineGenerations[row]++
            frameGeneration++
        }

        fun setBlink(
            row: Int,
            column: Int,
            blink: Boolean,
        ) {
            textRows[row][column] = 'B'
            lineGenerations[row]++
            frameGeneration++
            cellAttrs[row][column] = TerminalRenderAttrs.pack(blink = blink)
        }

        override fun lineGeneration(row: Int): Long = lineGenerations[row]

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
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = textRows[row][column].code
                attrWords[attrOffset + column] = cellAttrs[row][column]
                flags[flagOffset + column] = cellFlags[row][column]
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private companion object {
        private val PADDING = java.awt.Insets(0, 0, 0, 0)
        private const val CELL_WIDTH = 8
        private const val CELL_HEIGHT = 16
        private const val WIDTH = 120
        private const val HEIGHT = 80
        private val METRICS =
            SwingMetrics(
                cellWidth = CELL_WIDTH,
                cellHeight = CELL_HEIGHT,
                baseline = 12,
                underlineY = 13,
                strikethroughY = 8,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )

        private fun cursor(
            column: Int,
            row: Int,
            blinking: Boolean = false,
            generation: Long,
        ): TerminalRenderCursor =
            TerminalRenderCursor(
                column = column,
                row = row,
                visible = true,
                blinking = blinking,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = generation,
            )
    }
}

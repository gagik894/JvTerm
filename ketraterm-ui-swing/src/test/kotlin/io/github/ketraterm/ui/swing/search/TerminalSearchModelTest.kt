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
package io.github.ketraterm.ui.swing.search

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalSearchModelTest {
    @Test
    fun `viewport projection includes all boundary matches and clips a wrapped active result`() {
        val cache = renderCache(WrappedTextFrame(Array(1000) { "aa" }, wrapped = BooleanArray(1000) { it < 999 }))
        val highlights = TerminalSearchModel().search(cache, "aaa", ignoreCase = false)
        val viewportCache = renderCache(WrappedTextFrame(arrayOf("aa", "aa"), historySize = 998, scrollbackOffset = 499))
        val viewport = TerminalSearchViewportHighlights()
        // Result 332 starts on row 498 and continues into the first viewport row (499).
        highlights.activate(332)

        highlights.buildViewportHighlights(viewportCache, viewport)

        assertEquals(3, viewport.segmentCount)
        assertEquals(2, viewport.segmentCountForRow(0))
        assertEquals(1, viewport.segmentCountForRow(1))
        assertEquals(0, viewport.startColumn(0))
        assertEquals(1, viewport.endColumn(0))
        assertTrue(viewport.isActive(0))
    }

    @Test
    fun `literal search returns all result occurrences`() {
        val cache = renderCache(WrappedTextFrame(arrayOf("foo bar foo")))
        val highlights = TerminalSearchModel().search(cache, "foo", ignoreCase = true)

        assertEquals(2, highlights.resultCount)
        assertEquals(0, highlights.activeResultIndex)
    }

    @Test
    fun `search joins soft wrapped rows`() {
        val cache =
            renderCache(
                WrappedTextFrame(
                    textRows = arrayOf("hello", "world"),
                    wrapped = booleanArrayOf(true, false),
                ),
            )
        val highlights = TerminalSearchModel().search(cache, "lowo", ignoreCase = true)
        val viewport = TerminalSearchViewportHighlights()

        highlights.buildViewportHighlights(cache, viewport)

        assertEquals(1, highlights.resultCount)
        assertEquals(1, viewport.segmentCountForRow(0))
        assertEquals(1, viewport.segmentCountForRow(1))
    }

    @Test
    fun `case sensitive search honors toggle policy`() {
        val cache = renderCache(WrappedTextFrame(arrayOf("Build build")))
        val model = TerminalSearchModel()

        assertEquals(2, model.search(cache, "build", ignoreCase = true).resultCount)
        assertEquals(1, model.search(cache, "build", ignoreCase = false).resultCount)
    }

    @Test
    fun `search reanchors absolute rows after repeated history eviction`() {
        val model = TerminalSearchModel()
        val beforeEviction =
            renderCache(
                WrappedTextFrame(
                    textRows = arrayOf("needle"),
                    historySize = 5,
                    scrollbackOffset = 2,
                    discardedCount = 0,
                ),
            )
        val afterEviction =
            renderCache(
                WrappedTextFrame(
                    textRows = arrayOf("needle"),
                    historySize = 5,
                    scrollbackOffset = 2,
                    discardedCount = 3,
                ),
            )

        assertEquals(3L, model.search(beforeEviction, "needle", ignoreCase = true).activeStartAbsoluteRow())
        assertEquals(6L, model.search(afterEviction, "needle", ignoreCase = true).activeStartAbsoluteRow())
    }

    private fun renderCache(frame: TerminalRenderFrame): TerminalRenderCache {
        val cache = TerminalRenderCache(frame.columns, frame.rows)
        cache.updateFrom(
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(frame)
                }
            },
        )
        return cache
    }

    private class WrappedTextFrame(
        private val textRows: Array<String>,
        private val wrapped: BooleanArray = BooleanArray(textRows.size),
        override val historySize: Int = 0,
        override val scrollbackOffset: Int = 0,
        override val discardedCount: Long = 0L,
    ) : TerminalRenderFrame {
        override val columns: Int = textRows.maxOf { it.length }
        override val rows: Int = textRows.size
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

        override fun lineWrapped(row: Int): Boolean = wrapped[row]

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
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                if (column < textRows[row].length) {
                    codeWords[codeOffset + column] = textRows[row][column].code
                    flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                } else {
                    codeWords[codeOffset + column] = 0
                    flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                }
                column++
            }
        }
    }
}

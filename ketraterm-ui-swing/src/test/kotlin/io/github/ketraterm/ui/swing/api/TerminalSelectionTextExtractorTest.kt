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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.ui.swing.render.TestCell
import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.render.renderCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TerminalSelectionTextExtractorTest {
    private val extractor = TerminalSelectionTextExtractor()

    @Test
    fun `selected text trims trailing row blanks but preserves interior blanks`() {
        val cache = renderCache(TestRenderFrame.text("ab  cd  "))
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 8, caretRow = 0)

        assertEquals("ab  cd", extractor.selectedText(cache, selection))
    }

    @Test
    fun `selected text ignores empty cells after row content`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'a'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(codeWord = 'b'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(),
                            TestCell(),
                            TestCell(),
                        ),
                    ),
                ),
            )
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 5, caretRow = 0)

        assertEquals("ab", extractor.selectedText(cache, selection))
    }

    @Test
    fun `selected text returns empty when only empty cells are selected`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'a'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(),
                            TestCell(),
                            TestCell(),
                        ),
                    ),
                ),
            )
        val selection = CellSelection(anchorColumn = 1, anchorRow = 0, caretColumn = 4, caretRow = 0)

        assertEquals("", extractor.selectedText(cache, selection))
    }

    @Test
    fun `selected text preserves empty positioning cells before later content`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'a'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(),
                            TestCell(),
                            TestCell(codeWord = 'b'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                        ),
                    ),
                ),
            )
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 4, caretRow = 0)

        assertEquals("a  b", extractor.selectedText(cache, selection))
    }

    @Test
    fun `selected text joins soft wrapped rows for linear copy`() {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("ab"), textCells("cd"))) {
                override fun lineWrapped(row: Int): Boolean = row == 0
            }
        val cache = renderCache(frame)
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 2, caretRow = 1)

        assertEquals("abcd", extractor.selectedText(cache, selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `selected text preserves row breaks for block copy across soft wraps`() {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("ab"), textCells("cd"))) {
                override fun lineWrapped(row: Int): Boolean = row == 0
            }
        val cache = renderCache(frame)
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 2, caretRow = 1, isBlock = true)

        assertEquals("ab\ncd", extractor.selectedText(cache, selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `block copy projects the same visual interval separately on each row`() {
        val cache = renderCache(TestRenderFrame(arrayOf(textCells("ABC"), textCells("אבג"))))
        val selection = CellSelection(0, 0, 1, 1, isBlock = true)

        assertEquals("A\nג", extractor.selectedText(cache, selection))
    }

    @Test
    fun `block copy preserves logical text order within an rtl row`() {
        val cache = renderCache(TestRenderFrame.text("אבג"))

        assertEquals("אבג", extractor.selectedText(cache, CellSelection(0, 0, 3, 0, isBlock = true)))
        assertEquals("אב", extractor.selectedText(cache, CellSelection(3, 0, 1, 0, isBlock = true)))
    }

    @Test
    fun `mixed bidi block copy selects disjoint logical spans without filling their gap`() {
        // Logical "AB אבג" displays as "AB גבא". Visual columns 1..4 select B, space and ג.
        val cache = renderCache(TestRenderFrame.text("AB אבג"))
        val selection = CellSelection(1, 0, 4, 0, isBlock = true)

        assertEquals("B ג", extractor.selectedText(cache, selection))
        assertEquals("B א", extractor.selectedText(cache, selection.copy(isBlock = false)))
    }

    @ParameterizedTest
    @CsvSource("1, false", "2, false", "1, true", "2, true")
    fun `block copy includes a whole wide cell when either visual half is selected`(
        selectedColumn: Int,
        clustered: Boolean,
    ) {
        val expected = if (clustered) "🙂\uFE0F" else "字"
        val cell =
            if (clustered) {
                TestCell(flags = TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING, cluster = expected)
            } else {
                TestCell(codeWord = '字'.code, flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING)
            }
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'א'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            cell,
                            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                            TestCell(codeWord = 'ב'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                        ),
                    ),
                ),
            )

        assertEquals(expected, extractor.selectedText(cache, CellSelection(selectedColumn, 0, selectedColumn + 1, 0, isBlock = true)))
    }

    @Test
    fun `block copy updates its row mapping when the cache is replaced`() {
        val selection = CellSelection(0, 0, 1, 0, isBlock = true)

        assertEquals("A", extractor.selectedText(renderCache(TestRenderFrame.text("ABC")), selection))
        assertEquals("ג", extractor.selectedText(renderCache(TestRenderFrame.text("אבג")), selection))
    }

    @Test
    fun `selected text preserves leading and repeated empty hard rows`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        Array(2) { TestCell() },
                        Array(2) { TestCell() },
                        textCells("ab"),
                    ),
                ),
            )
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 2, caretRow = 2)

        assertEquals("\n\nab", extractor.selectedText(cache, selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `word selection groups letters digits and underscore`() {
        val cache = renderCache(TestRenderFrame.text("foo_bar-99"))
        val selection = extractor.wordSelectionAt(cache, row = 0, column = 2)

        assertEquals(CellSelection(0, 0, 7, 0), selection)
    }

    @Test
    fun `path selection groups entire path when containing indicators`() {
        val cache = renderCache(TestRenderFrame.text("/usr/local/bin/git"))
        val selection = extractor.wordSelectionAt(cache, row = 0, column = 6) // clicks on "l" in "local"

        assertEquals(CellSelection(0, 0, 18, 0), selection)
    }

    @Test
    fun `url selection groups entire url when containing indicators`() {
        val cache = renderCache(TestRenderFrame.text("https://google.com/search?q=test"))
        val selection = extractor.wordSelectionAt(cache, row = 0, column = 10) // clicks on "o" in "google"

        assertEquals(CellSelection(0, 0, 32, 0), selection)
    }

    private fun textCells(text: String): Array<TestCell> =
        Array(text.length) { index ->
            TestCell(codeWord = text[index].code, flags = TerminalRenderCellFlags.CODEPOINT)
        }
}

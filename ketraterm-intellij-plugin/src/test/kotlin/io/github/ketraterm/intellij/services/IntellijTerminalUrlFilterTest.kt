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
package io.github.ketraterm.intellij.ui

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.UrlFilter
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ketraterm.ui.swing.api.SwingHyperlinkDetectionContext

/**
 * Verifies the baseline IntelliJ URL filter contract used by terminal
 * hyperlink discovery.
 */
class IntellijTerminalUrlFilterTest : BasePlatformTestCase() {
    fun testDetectorPreservesViewportContextForConsoleFilters() {
        assertEquals(SwingHyperlinkDetectionContext.VIEWPORT, IntellijTerminalHyperlinkDetector(project).context)
    }

    fun testUrlFilterReturnsAbsoluteResultItemOffsets() {
        val text = "https://example.com"
        val lineStartOffset = 100

        val result = UrlFilter(project).applyFilter(text, lineStartOffset + text.length)

        assertNotNull(result)
        val item = result!!.resultItems.single()
        assertEquals(lineStartOffset, item.highlightStartOffset)
        assertEquals(lineStartOffset + text.length, item.highlightEndOffset)
        assertNotNull(item.hyperlinkInfo)
        assertEquals(Filter.NextAction.EXIT, result.nextAction)
    }

    fun testUrlValidationExcludesProgressOutsideToken() {
        val text = "Downloading https://example.com/archive.zip 10%\n"

        val range = validationRange(text)

        assertEquals(" https://example.com/archive.zip ", range.substring(text))
        assertEquals(range.substring(text), range.substring(text.replace("10%", "20%")))
    }

    fun testUrlValidationIncludesTrailingPunctuationAndDelimiter() {
        val text = "https://example.com. 10%\n"

        assertEquals("https://example.com. ", validationRange(text).substring(text))
    }

    fun testUrlValidationIncludesEarlierTextInSameToken() {
        val text = "tag/https://example.com 10%\n"

        assertEquals("tag/https://example.com ", validationRange(text).substring(text))
    }

    fun testUrlValidationIncludesLineEndSoAppendingChangesDependency() {
        val text = "https://example.com\n"

        assertEquals(text, validationRange(text).substring(text))
    }

    fun testUrlValidationIncludesTabBoundaries() {
        val text = "download\thttps://example.com\t10%\n"

        assertEquals("\thttps://example.com\t", validationRange(text).substring(text))
    }

    fun testFileUrlValidationKeepsUnicodeWhitespaceInsideToken() {
        val text = "file:/tmp/report\u2003part.txt 10%\n"

        assertEquals("file:/tmp/report\u2003part.txt ", validationRange(text).substring(text))
    }

    fun testUrlValidationKeepsClosingPunctuationInsideToken() {
        val text = "(https://example.com) 10%\n"

        assertEquals("(https://example.com) ", validationRange(text).substring(text))
    }

    fun testEachUrlValidatesItsOwnToken() {
        val text = "https://first.example 10% https://second.example 20%\n"
        val items = requireNotNull(UrlFilter(project).applyFilter(text, text.length)).resultItems

        assertEquals(2, items.size)
        assertEquals(
            "https://first.example ",
            urlValidationRange(text, items[0].highlightStartOffset, items[0].highlightEndOffset).substring(text),
        )
        assertEquals(
            " https://second.example ",
            urlValidationRange(text, items[1].highlightStartOffset, items[1].highlightEndOffset).substring(text),
        )
    }

    private fun validationRange(text: String): TextRange {
        val item = requireNotNull(UrlFilter(project).applyFilter(text, text.length)).resultItems.single()
        return urlValidationRange(text, item.highlightStartOffset, item.highlightEndOffset)
    }
}

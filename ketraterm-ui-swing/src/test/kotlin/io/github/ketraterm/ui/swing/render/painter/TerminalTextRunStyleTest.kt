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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.render.renderCache
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTextRunStyleTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `hover interval splits runs at its inclusive start and exclusive end`(activationHover: Boolean) {
        val cache = renderCache(TestRenderFrame.text("A".repeat(80)))
        cache.hyperlinkIds.fill(7)
        val style = TerminalTextRunStyle()
        val activationForeground = 0xFF4DA3FF.toInt()
        style.configureRow(
            row = 0,
            textBlinkVisible = true,
            hyperlinkIds = cache.hyperlinkIds,
            hoveredHyperlinkId = 7,
            hoveredHyperlinkStartRow = 0,
            hoveredHyperlinkStartColumn = 20,
            hoveredHyperlinkEndRow = 0,
            hoveredHyperlinkEndColumn = 60,
            hyperlinkActivationHover = activationHover,
            hyperlinkActivationForeground = activationForeground,
        )
        style.begin(cache, cache.palette, 0, 0)
        val starts = mutableListOf(0)
        for (column in 1 until cache.columns) {
            if (!style.matches(cache, cache.palette, 0, column)) {
                starts.add(column)
                style.begin(cache, cache.palette, 0, column)
            }
        }
        assertEquals(listOf(0, 20, 60), starts)

        style.begin(cache, cache.palette, 0, 20)
        assertTrue(style.hovered)
        assertEquals(if (activationHover) activationForeground else cache.palette.defaultForeground, style.foreground)
        assertFalse(style.matches(cache, cache.palette, 0, 60))
        assertTrue(style.hovered, "Comparing another cell must not replace the retained run style")
        style.begin(cache, cache.palette, 0, 60)
        assertFalse(style.hovered)
        assertEquals(cache.palette.defaultForeground, style.foreground)
    }
}

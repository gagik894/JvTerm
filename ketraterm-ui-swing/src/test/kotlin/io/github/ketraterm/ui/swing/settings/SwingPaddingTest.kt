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
package io.github.ketraterm.ui.swing.settings

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwingPaddingTest {
    @ParameterizedTest
    @CsvSource("-1, 0, 0, 0", "0, -1, 0, 0", "0, 0, -1, 0", "0, 0, 0, -1")
    fun negativeEdgesAreRejected(
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    ) {
        assertFailsWith<IllegalArgumentException> {
            SwingPadding(top, left, bottom, right)
        }
        val padding = SwingPadding(1, 2, 3, 4)
        assertFailsWith<IllegalArgumentException> {
            padding.copy(top = top, left = left, bottom = bottom, right = right)
        }
        assertEquals(SwingPadding(1, 2, 3, 4), padding)
    }

    @Test
    fun zeroPaddingAndIndependentEdgeCopiesAreValid() {
        val empty = SwingPadding()
        val padding = empty.copy(left = 12)
        assertEquals(SwingPadding(0, 0, 0, 0), empty)
        assertEquals(SwingPadding(0, 12, 0, 0), padding)
    }
}

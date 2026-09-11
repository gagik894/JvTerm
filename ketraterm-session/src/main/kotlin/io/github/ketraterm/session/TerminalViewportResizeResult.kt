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
package io.github.ketraterm.session

/**
 * Viewport anchor and history baseline captured atomically after terminal resize.
 *
 * Reflow can replace the history storage and its discarded-row counter. Apply
 * all three values together before interpreting later history changes as output.
 *
 * @property scrollbackOffset whole-row distance from the live viewport that
 * preserves the pre-resize logical content where it survives; zero follows live output.
 * @property historySize retained history rows after resize.
 * @property discardedCount discarded-row counter belonging to the resized history.
 */
data class TerminalViewportResizeResult(
    val scrollbackOffset: Int,
    val historySize: Int,
    val discardedCount: Long,
) {
    init {
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        require(scrollbackOffset in 0..historySize) {
            "scrollbackOffset must be in 0..$historySize, was $scrollbackOffset"
        }
        require(discardedCount >= 0L) { "discardedCount must be >= 0, was $discardedCount" }
    }
}

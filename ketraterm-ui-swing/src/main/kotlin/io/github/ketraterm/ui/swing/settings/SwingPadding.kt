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

/**
 * Immutable, non-negative visual spacing around the terminal grid, in pixels.
 *
 * The same value can be shared by settings snapshots and read during painting.
 * Changing an edge with [copy] creates a new value and validates all edges.
 *
 * @property top spacing above the grid.
 * @property left spacing before the grid.
 * @property bottom spacing below the grid.
 * @property right spacing after the grid.
 */
data class SwingPadding(
    val top: Int = 0,
    val left: Int = 0,
    val bottom: Int = 0,
    val right: Int = 0,
) {
    init {
        require(top >= 0 && left >= 0 && bottom >= 0 && right >= 0) {
            "padding must be non-negative, was $this"
        }
    }
}

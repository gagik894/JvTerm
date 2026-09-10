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
package io.github.ketraterm.intellij.services

/** Exact shared visit budget for bounded IDE snapshot collection. */
internal class BoundedVisitBudget(
    private val limit: Int,
    private val cancellationCheckpoint: () -> Unit,
) {
    private var visited = 0
    private var stopped = false

    init {
        require(limit > 0) { "limit must be > 0, was $limit" }
    }

    /** Visits values without requesting an element after the shared limit is exhausted. */
    fun <T> visit(
        values: Iterable<T>,
        action: (T) -> Boolean,
    ) {
        if (stopped) return
        val iterator = values.iterator()
        while (visited < limit) {
            cancellationCheckpoint()
            if (!iterator.hasNext()) return
            val value = iterator.next()
            visited++
            if (!action(value)) {
                stopped = true
                return
            }
        }
    }
}

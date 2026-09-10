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
package io.github.ketraterm.app.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame

class FailureSafeCleanupTest {
    @Test
    fun `cleanup preserves the first failure while every action is attempted`() {
        val firstFailure = IllegalStateException("first")
        val laterFailure = IllegalArgumentException("later")
        val calls = ArrayList<String>()
        var failure: Throwable? = null

        failure =
            captureCleanupFailure(failure) {
                calls += "first"
                throw firstFailure
            }
        failure =
            captureCleanupFailure(failure) {
                calls += "second"
                throw laterFailure
            }
        failure = captureCleanupFailure(failure) { calls += "third" }

        assertContentEquals(listOf("first", "second", "third"), calls)
        assertSame(firstFailure, failure)
        assertContentEquals(arrayOf(laterFailure), firstFailure.suppressed)
    }
}

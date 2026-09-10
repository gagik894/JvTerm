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
package io.github.ketraterm.completion.matching

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompletionMatcherTest {
    @Test
    fun `empty query matches all targets with empty ranges`() {
        val result = CompletionMatcher.match("git-status", "")
        assertNotNull(result)
        assertEquals(0, result!!.score)
        assertTrue(result.matchedRanges.isEmpty())
    }

    @Test
    fun `exact prefix matches and produces single range`() {
        val result = CompletionMatcher.match("git-status", "git")
        assertNotNull(result)
        assertTrue(result!!.score > 400)
        assertRanges(result, 0, 3)
    }

    @Test
    fun `exact match gets highest score bonus`() {
        val exact = CompletionMatcher.match("status", "status")
        val prefix = CompletionMatcher.match("status-all", "status")
        assertNotNull(exact)
        assertNotNull(prefix)
        assertTrue(exact!!.score > prefix!!.score)
    }

    @Test
    fun `case insensitive prefix matches`() {
        val result = CompletionMatcher.match("buildRelease", "build")
        assertNotNull(result)
        assertRanges(result!!, 0, 5)
    }

    @Test
    fun `camel hump matches word boundary capitals`() {
        val result = CompletionMatcher.match("buildRelease", "bRel")
        assertNotNull(result)
        assertRanges(result!!, 0, 1, 5, 8)
    }

    @Test
    fun `camel hump matches multiple uppercase words`() {
        val result = CompletionMatcher.match("getProjectRootDirectory", "gPRD")
        assertNotNull(result)
        assertRanges(result!!, 0, 1, 3, 4, 10, 11, 14, 15)
    }

    @Test
    fun `kebab case acronym matches word boundaries`() {
        val result = CompletionMatcher.match("docker-compose", "dc")
        assertNotNull(result)
        assertRanges(result!!, 0, 1, 7, 8)
    }

    @Test
    fun `kebab case prefix with separator matches`() {
        val result = CompletionMatcher.match("docker-compose", "d-c")
        assertNotNull(result)
        assertRanges(result!!, 0, 1, 6, 8)
    }

    @Test
    fun `snake case matches word boundaries`() {
        val result = CompletionMatcher.match("git_status_helper", "gsh")
        assertNotNull(result)
        assertRanges(result!!, 0, 1, 4, 5, 11, 12)
    }

    @Test
    fun `direct prefix source score outranks shorter boundary match`() {
        val prefix = CompletionMatcher.match("start-an-extremely-long-operation", "st")!!
        val boundary = CompletionMatcher.match("saveTask", "st")!!

        assertTrue(prefix.sourceScore(300, "st", 1) > boundary.sourceScore(300, "st", 0))
    }

    @Test
    fun `source score preserves direct prefix scoring`() {
        val match = CompletionMatcher.match("status", "st")!!

        assertEquals(300 + 40 - ("status".length - "st".length) - 3, match.sourceScore(300, "st", 3))
        assertEquals(300 - 3, CompletionMatcher.match("status", "")!!.sourceScore(300, "", 3))
    }

    @Test
    fun `non matching pattern returns null`() {
        assertNull(CompletionMatcher.match("git-status", "xyz"))
    }

    @Test
    fun `longer query than target returns null`() {
        assertNull(CompletionMatcher.match("git", "git-status-longer"))
    }

    private fun assertRanges(
        result: CompletionMatchResult,
        vararg expectedOffsets: Int,
    ) {
        assertArrayEquals(expectedOffsets, result.matchedRanges.copyPackedOffsets())
    }
}

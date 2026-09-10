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
package io.github.ketraterm.completion.model

import io.github.ketraterm.completion.internal.terminalCompletionRankingIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TerminalCompletionLearningModelsTest {
    @Test
    fun `ranking stats reject invalid digests and negative values`() {
        assertFailsWith<IllegalArgumentException> {
            stats(identityDigest = "plaintext-command")
        }
        assertFailsWith<IllegalArgumentException> {
            stats(useCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            stats(useCount = 1, successCount = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionRankingStats(
                identityDigest = terminalCompletionRankingIdentity("git status"),
                failureCount = -1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            stats(lastUsedEpochMillis = -1)
        }
    }

    @Test
    fun `replay requires plaintext to match its opaque identity`() {
        val command = "git status"
        val identity = terminalCompletionRankingIdentity(command)

        assertFailsWith<IllegalArgumentException> {
            TerminalCommandReplay(identity, "git log")
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCommandReplay(identity, "git status\ngit log")
        }
    }

    @Test
    fun `replay rejects controls and structural bounds while allowing internal tabs`() {
        for (
        command in
        listOf(
            "git\u0000status",
            "git\u007fstatus",
            "git\u0085status",
            "git\uD800status",
            "git\uDC00status",
            "x".repeat(4_097),
            "界".repeat(2_731),
        )
        ) {
            assertFailsWith<IllegalArgumentException> {
                TerminalCommandReplay(terminalCompletionRankingIdentity(command), command)
            }
        }

        val tabbed = "printf\tvalue"
        assertEquals(tabbed, TerminalCommandReplay(terminalCompletionRankingIdentity(tabbed), tabbed).commandLine)
        val utf16Boundary = "x".repeat(4_096)
        assertEquals(utf16Boundary, TerminalCommandReplay(terminalCompletionRankingIdentity(utf16Boundary), utf16Boundary).commandLine)
        val utf8Boundary = "界".repeat(2_730)
        assertEquals(utf8Boundary, TerminalCommandReplay(terminalCompletionRankingIdentity(utf8Boundary), utf8Boundary).commandLine)
    }

    @Test
    fun `identity preserves case and exact trailing whitespace`() {
        assertNotEquals(
            terminalCompletionRankingIdentity("cat Foo"),
            terminalCompletionRankingIdentity("cat Foo  "),
        )
        assertNotEquals(
            terminalCompletionRankingIdentity("cat Foo"),
            terminalCompletionRankingIdentity("cat foo"),
        )
    }

    @Test
    fun `snapshot defensively copies both immutable lanes`() {
        val identity = terminalCompletionRankingIdentity("git status")
        val mutableStats = arrayListOf(stats(identityDigest = identity))
        val mutableReplay = arrayListOf(TerminalCommandReplay(identity, "git status"))
        val snapshot = TerminalCompletionLearningSnapshot(mutableStats, mutableReplay)

        mutableStats += stats(identityDigest = terminalCompletionRankingIdentity("git log"))
        mutableReplay += TerminalCommandReplay(terminalCompletionRankingIdentity("git log"), "git log")

        assertEquals(listOf(identity), snapshot.rankingStats.map { it.identityDigest })
        assertEquals(listOf("git status"), snapshot.replayCommands.map { it.commandLine })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.rankingStats as MutableList<TerminalCompletionRankingStats>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.replayCommands as MutableList<TerminalCommandReplay>).clear()
        }
    }

    private fun stats(
        identityDigest: String = terminalCompletionRankingIdentity("git status"),
        useCount: Int = 0,
        successCount: Int = 0,
        lastUsedEpochMillis: Long = 0,
    ): TerminalCompletionRankingStats =
        TerminalCompletionRankingStats(
            identityDigest = identityDigest,
            useCount = useCount,
            successCount = successCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}

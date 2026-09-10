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
package io.github.ketraterm.completion.ranking

import kotlin.test.Test
import kotlin.test.assertEquals

class LearnedEvidenceScoringTest {
    @Test
    fun `saturated positive evidence remains bounded`() {
        val counts =
            LearnedEvidenceCounts(
                useCount = Long.MAX_VALUE,
                acceptedCount = Long.MAX_VALUE,
            )

        assertEquals(350, LearnedEvidenceScoring.exact(counts, contextBoost = 0, nowEpochMillis = 0))
    }

    @Test
    fun `saturated dismissal evidence remains bounded`() {
        val counts =
            LearnedEvidenceCounts(
                dismissedCount = Long.MAX_VALUE,
            )

        assertEquals(-200, LearnedEvidenceScoring.exact(counts, contextBoost = 0, nowEpochMillis = 0))
    }

    @Test
    fun `recent dismissal without positive evidence remains negative`() {
        val now = 2_000_000_000_000L
        val counts = LearnedEvidenceCounts(dismissedCount = 1, lastUsedEpochMillis = now)

        assertEquals(-10, LearnedEvidenceScoring.exact(counts, contextBoost = 50, nowEpochMillis = now))
    }

    @Test
    fun `recency uses elapsed age and safely handles future timestamps`() {
        val now = 2_000_000_000_000L

        assertEquals(60, LearnedEvidenceScoring.recencyBoost(now, now - 60_000L))
        assertEquals(40, LearnedEvidenceScoring.recencyBoost(now, now - 2L * 60L * 60L * 1_000L))
        assertEquals(20, LearnedEvidenceScoring.recencyBoost(now, now - 2L * 24L * 60L * 60L * 1_000L))
        assertEquals(10, LearnedEvidenceScoring.recencyBoost(now, now - 8L * 24L * 60L * 60L * 1_000L))
        assertEquals(0, LearnedEvidenceScoring.recencyBoost(now, now - 31L * 24L * 60L * 60L * 1_000L))
        assertEquals(60, LearnedEvidenceScoring.recencyBoost(now, now + 1_000L))
        assertEquals(0, LearnedEvidenceScoring.recencyBoost(now, 0L))
    }
}

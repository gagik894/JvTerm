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
package io.github.ketraterm.completion.testing

import io.github.ketraterm.completion.internal.terminalCompletionRankingIdentity
import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats

internal data class TestCommandLearning(
    val commandLine: String,
    val profileId: String? = null,
    val workingDirectoryUri: String? = null,
    val useCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val acceptedCount: Int = 0,
    val dismissedCount: Int = 0,
    val lastUsedEpochMillis: Long = 0L,
    val replay: Boolean = true,
)

internal fun commandLearning(
    commandLine: String,
    profileId: String? = null,
    workingDirectoryUri: String? = null,
    useCount: Int = 0,
    successCount: Int = 0,
    failureCount: Int = 0,
    acceptedCount: Int = 0,
    dismissedCount: Int = 0,
    lastUsedEpochMillis: Long = 0L,
    replay: Boolean = true,
): TestCommandLearning =
    TestCommandLearning(
        commandLine,
        profileId,
        workingDirectoryUri,
        useCount,
        successCount,
        failureCount,
        acceptedCount,
        dismissedCount,
        lastUsedEpochMillis,
        replay,
    )

internal fun learningSnapshot(vararg rows: TestCommandLearning): TerminalCompletionLearningSnapshot = learningSnapshot(rows.asList())

internal fun learningSnapshot(rows: List<TestCommandLearning>): TerminalCompletionLearningSnapshot {
    val rankingStats = ArrayList<TerminalCompletionRankingStats>(rows.size)
    val replayCommands = ArrayList<TerminalCommandReplay>(rows.size)
    for (row in rows) {
        val identity = terminalCompletionRankingIdentity(row.commandLine)
        rankingStats +=
            TerminalCompletionRankingStats(
                identityDigest = identity,
                profileId = row.profileId,
                workingDirectoryUri = row.workingDirectoryUri,
                useCount = maxOf(row.useCount, row.successCount),
                successCount = row.successCount,
                failureCount = row.failureCount,
                acceptedCount = row.acceptedCount,
                dismissedCount = row.dismissedCount,
                lastUsedEpochMillis = row.lastUsedEpochMillis,
            )
        if (row.replay && row.successCount > 0) {
            replayCommands +=
                TerminalCommandReplay(
                    identityDigest = identity,
                    commandLine = row.commandLine,
                    profileId = row.profileId,
                    workingDirectoryUri = row.workingDirectoryUri,
                )
        }
    }
    return TerminalCompletionLearningSnapshot(rankingStats, replayCommands)
}

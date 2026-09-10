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

/**
 * Aggregated ranking evidence for one opaque exact-command identity.
 *
 * The identity is a stable, one-way digest produced by the completion module.
 * It does not retain directly decodable command text, although common commands
 * remain guessable by hashing candidate strings. The digest preserves the
 * exact case-sensitive command, including trailing whitespace.
 *
 * @property identityDigest constant-length URL-safe digest of the exact command.
 * @property profileId optional host profile id associated with the evidence.
 * @property workingDirectoryUri optional working-directory URI associated with the evidence.
 * @property useCount number of observed command executions.
 * @property successCount number of successful executions.
 * @property failureCount number of unsuccessful executions. This is observational and does not penalize ranking.
 * @property acceptedCount number of accepted suggestions producing this outcome.
 * @property dismissedCount number of dismissed suggestions producing this outcome.
 * @property lastUsedEpochMillis newest execution or feedback timestamp represented by this row.
 * @throws IllegalArgumentException if the digest shape is invalid, a counter is
 * negative, successful executions exceed total executions, or [lastUsedEpochMillis] is negative.
 */
data class TerminalCompletionRankingStats
    @JvmOverloads
    constructor(
        val identityDigest: String,
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val useCount: Int = 0,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val acceptedCount: Int = 0,
        val dismissedCount: Int = 0,
        val lastUsedEpochMillis: Long = 0L,
    ) {
        init {
            requireValidTerminalCompletionRankingDigest(identityDigest)
            require(useCount >= 0) { "useCount must be >= 0, was $useCount" }
            require(successCount >= 0) { "successCount must be >= 0, was $successCount" }
            require(successCount <= useCount) { "successCount must be <= useCount, was $successCount > $useCount" }
            require(failureCount >= 0) { "failureCount must be >= 0, was $failureCount" }
            require(acceptedCount >= 0) { "acceptedCount must be >= 0, was $acceptedCount" }
            require(dismissedCount >= 0) { "dismissedCount must be >= 0, was $dismissedCount" }
            require(lastUsedEpochMillis >= 0L) { "lastUsedEpochMillis must be >= 0, was $lastUsedEpochMillis" }
        }
    }

internal fun requireValidTerminalCompletionRankingDigest(identityDigest: String) {
    require(identityDigest.length == IDENTITY_DIGEST_LENGTH && identityDigest.all(::isDigestCharacter)) {
        "identityDigest must be a $IDENTITY_DIGEST_LENGTH-character URL-safe SHA-256 digest"
    }
}

private const val IDENTITY_DIGEST_LENGTH = 43

private fun isDigestCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9' ||
        character == '-' ||
        character == '_'

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

import io.github.ketraterm.completion.internal.isStructurallyValidTerminalCompletionReplay
import io.github.ketraterm.completion.internal.terminalCompletionRankingIdentity

/**
 * Positive, policy-approved plaintext command retained solely for replay suggestions.
 *
 * Counters belong to the opaque [TerminalCompletionRankingStats] row identified
 * by the same digest and context. Keeping this projection counter-free prevents
 * replay eligibility from becoming a second mutable learning family.
 *
 * @property identityDigest opaque identity of the matching ranking row.
 * @property commandLine exact case- and whitespace-preserved command text shown to the user.
 * @property profileId optional host profile id associated with the command.
 * @property workingDirectoryUri optional working-directory URI associated with the command.
 * @throws IllegalArgumentException if the digest is invalid, [commandLine] is
 * blank, multiline, malformed UTF-16, oversized, or contains a disallowed ISO control.
 */
data class TerminalCommandReplay(
    val identityDigest: String,
    val commandLine: String,
    val profileId: String? = null,
    val workingDirectoryUri: String? = null,
) {
    init {
        requireValidTerminalCompletionRankingDigest(identityDigest)
        require(isStructurallyValidTerminalCompletionReplay(commandLine)) {
            "commandLine must be well-formed, bounded, single-line text without ISO controls other than tab"
        }
        require(identityDigest == terminalCompletionRankingIdentity(commandLine)) {
            "identityDigest must match the exact commandLine"
        }
    }
}

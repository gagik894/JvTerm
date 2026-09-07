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

import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.host.TerminalBoundedDirectoryScanner
import io.github.ketraterm.completion.host.TerminalDirectoryScanner
import io.github.ketraterm.ui.swing.host.SwingCompletionContext

/**
 * Host context used to create one testable IntelliJ completion provider.
 *
 * @property profileId non-blank stable terminal profile identifier.
 * @property workingDirectoryUriProvider thread-safe supplier sampled once when
 * constructing immutable completion request metadata.
 * @property shellCapabilities shell syntax and quoting capabilities.
 * @property additionalSources project-aware completion sources for this session.
 * @property directoryScanner suspending bounded directory scanner.
 * @throws IllegalArgumentException if [profileId] is blank.
 */
internal data class IntellijCompletionContext(
    val profileId: String,
    val workingDirectoryUriProvider: () -> String?,
    val shellCapabilities: TerminalShellCapabilities,
    val additionalSources: List<TerminalCompletionSourceEntry> = emptyList(),
    val directoryScanner: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(),
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
    }

    fun swingContext(): SwingCompletionContext =
        SwingCompletionContext(
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUriProvider(),
            shellCapabilities = shellCapabilities,
        )
}

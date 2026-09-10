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
package io.github.ketraterm.completion.host

import io.github.ketraterm.completion.api.TerminalFileEntry
import java.nio.file.Path

/** Suspending bounded direct-directory scan contract used by completion providers. */
fun interface TerminalDirectoryScanner {
    /**
     * Scans direct children beginning with [entryNamePrefix].
     *
     * Implementations own any dispatcher switch required by their backing API.
     *
     * @param directory normalized absolute local directory.
     * @param entryNamePrefix case-insensitive child-name prefix.
     * @return bounded deterministically ordered entries, or an empty list when
     * [directory] is absent or not a directory.
     */
    suspend fun scan(
        directory: Path,
        entryNamePrefix: String,
    ): List<TerminalFileEntry>
}

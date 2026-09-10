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

import io.github.ketraterm.completion.api.TerminalDirectoryListingRequest
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Resolves lexical completion paths without filesystem access or authority loss.
 *
 * @param homeDirectory explicit local home used for tilde expansion.
 * @param windows whether Windows drive and UNC syntax is accepted.
 */
class TerminalCompletionPathResolver
    @JvmOverloads
    constructor(
        private val homeDirectory: Path? = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
        private val windows: Boolean = FileSystems.getDefault().separator == "\\",
    ) {
        /**
         * Resolves [request] into a normalized absolute local directory.
         *
         * @param request lexical directory request produced by the completion engine.
         * @return local path, or `null` for malformed, remote, or unsupported input.
         */
        fun resolve(request: TerminalDirectoryListingRequest): Path? {
            val workingDirectory = TerminalLocalFileUriResolver.resolve(request.workingDirectoryUri) ?: return null
            val prefix = request.directoryPrefix
            return try {
                val resolved =
                    when {
                        prefix == "~/" -> homeDirectory
                        prefix.startsWith("~/") -> homeDirectory?.resolve(prefix.substring(2))
                        prefix.hasWindowsDriveRoot() -> if (windows) Path.of(prefix.replace('/', '\\')) else null
                        prefix.startsWith("//") -> if (windows) Path.of(prefix.replace('/', '\\')) else null
                        prefix.startsWith('/') -> Path.of(prefix)
                        prefix.isEmpty() -> workingDirectory
                        else -> workingDirectory.resolve(prefix)
                    } ?: return null
                resolved.toAbsolutePath().normalize()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun String.hasWindowsDriveRoot(): Boolean = length >= 3 && this[0].isLetter() && this[1] == ':' && this[2] == '/'
    }

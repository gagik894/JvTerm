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

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Path
import java.nio.file.Paths

/** Safe resolver for authoritative local `file` working-directory URIs. */
object TerminalLocalFileUriResolver {
    /**
     * Resolves [value] without reinterpreting remote file authorities locally.
     *
     * Empty authorities and `localhost` are accepted. Other schemes,
     * authorities, and malformed values return `null`.
     *
     * @param value candidate working-directory URI.
     * @return normalized absolute local path, or `null` when unsupported.
     */
    @JvmStatic
    fun resolve(value: String?): Path? {
        if (value == null) return null
        return try {
            val uri = URI(value)
            if (!uri.scheme.equals("file", ignoreCase = true)) return null
            val authority = uri.authority
            if (!authority.isNullOrEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
            val localUri = URI("file", null, uri.path ?: return null, null)
            Paths.get(localUri).toAbsolutePath().normalize()
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: FileSystemNotFoundException) {
            null
        }
    }
}

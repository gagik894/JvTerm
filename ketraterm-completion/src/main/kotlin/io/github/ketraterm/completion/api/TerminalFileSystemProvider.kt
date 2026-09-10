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
package io.github.ketraterm.completion.api

/**
 * Single file system entry description returned by [TerminalFileSystemProvider].
 *
 * @property name base name of the file or directory (e.g. "src" or "README.md").
 * @property isDirectory `true` if the entry represents a directory.
 * @throws IllegalArgumentException if [name] is empty, `.` or `..`.
 */
data class TerminalFileEntry(
    val name: String,
    val isDirectory: Boolean,
) {
    init {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(name != "." && name != "..") { "name must not be dot or dot-dot self-references" }
    }
}

/**
 * Host-resolved directory-listing request used by path completion.
 *
 * The completion engine deliberately keeps [directoryPrefix] lexical. The
 * host owns interpreting drive roots, UNC paths, home-directory prefixes, and
 * the authority of [workingDirectoryUri] for its actual file-system context.
 * Implementations resolve and load the requested directory suspendingly.
 *
 * @property workingDirectoryUri absolute host-reported working-directory URI.
 * @property directoryPrefix typed path portion ending at the final separator;
 * forward slashes are used as a transport-neutral separator. An empty value
 * addresses the working directory and `~/` addresses the host user's home.
 * @property entryNamePrefix typed base-name prefix after the final separator.
 * @throws IllegalArgumentException if the URI is blank, the directory prefix is
 * not empty and separator-terminated, or the entry prefix contains a separator.
 */
data class TerminalDirectoryListingRequest(
    val workingDirectoryUri: String,
    val directoryPrefix: String,
    val entryNamePrefix: String,
) {
    init {
        require(workingDirectoryUri.isNotBlank()) { "workingDirectoryUri must not be blank" }
        require(directoryPrefix.isEmpty() || directoryPrefix.endsWith('/')) {
            "directoryPrefix must be empty or end with '/', was $directoryPrefix"
        }
        require(!entryNamePrefix.contains('/')) { "entryNamePrefix must not contain '/'" }
        require(!entryNamePrefix.contains('\\')) { "entryNamePrefix must not contain '\\'" }
    }
}

/**
 * Host-implemented file system provider for sandbox-aware path completion.
 *
 * Since the completions module stays pure and I/O-free, hosts must implement
 * this interface to resolve folder paths safely. In particular, the host must
 * reject working-directory authorities it cannot prove are local rather than
 * silently converting remote OSC 7 locations into local paths.
 */
fun interface TerminalFileSystemProvider {
    /**
     * Returns matching children for [request].
     *
     * Implementations cooperate with cancellation and switch dispatchers around
     * blocking host APIs when required.
     *
     * @param request lexical path request and host working-directory context.
     * @return bounded matching children, or an empty list when the request is
     * unsupported or resolves to an absent/non-directory path. Operational
     * filesystem failures must propagate to the completion engine's source
     * failure boundary.
     */
    suspend fun listDirectory(request: TerminalDirectoryListingRequest): List<TerminalFileEntry>
}

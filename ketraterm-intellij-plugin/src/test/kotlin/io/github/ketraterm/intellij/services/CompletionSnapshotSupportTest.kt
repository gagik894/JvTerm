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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/** Pure tests for lexical IntelliJ completion-path support. */
class CompletionSnapshotSupportTest {
    @Test
    fun `relative completion paths use shell separators and preserve parent navigation`() {
        val base = Path.of("project", "module").toAbsolutePath()

        assertEquals("../shared/File.kt", toRelativeCompletionPath(base, base.resolve("../shared/File.kt")))
        assertEquals("", toRelativeCompletionPath(base, base))
    }
}

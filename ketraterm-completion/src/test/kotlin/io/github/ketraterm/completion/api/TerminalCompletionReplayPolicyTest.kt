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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalCompletionReplayPolicyTest {
    @Test
    fun `allows ordinary command text`() {
        assertTrue(TerminalCompletionReplayPolicy.allowsPlaintext("git status"))
    }

    @Test
    fun `rejects blank multiline and leading-whitespace command text`() {
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("   "))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("git status\ngit log"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("git status\rgit log"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext(" git status"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("\tgit status"))
    }

    @Test
    fun `rejects controls malformed text and structural bounds`() {
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("git\u0000status"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("git\u007fstatus"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("git\u0085status"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("git\uD800status"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("git\uDC00status"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("x".repeat(4_097)))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("界".repeat(2_731)))
        assertTrue(TerminalCompletionReplayPolicy.allowsPlaintext("printf\tvalue"))
        assertTrue(TerminalCompletionReplayPolicy.allowsPlaintext("x".repeat(4_096)))
        assertTrue(TerminalCompletionReplayPolicy.allowsPlaintext("界".repeat(2_730)))
    }

    @Test
    fun `rejects credential vocabulary case-insensitively`() {
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("docker login --password hunter2"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("export SECRET_TOKEN=123"))
    }

    @Test
    fun `rejects curl user credentials in spaced attached and quoted forms`() {
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("curl -u alice:s3cr3t https://example.test"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("curl -ualice:s3cr3t https://example.test"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("curl '--user=alice:s3cr3t' https://example.test"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("C:\\tools\\curl.exe --proxy-user alice:s3cr3t example.test"))
        assertFalse(
            TerminalCompletionReplayPolicy.allowsPlaintext(
                "C:\\Windows\\System32\\curl.exe -u alice:s3cr3t https://example.test",
            ),
        )
    }

    @Test
    fun `rejects mysql password short options in spaced and attached forms`() {
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("mysql -p hunter2"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("mysql -phunter2"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("mariadb-dump '-p' hunter2 database"))
    }

    @Test
    fun `rejects docker login credential options`() {
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("docker login -u alice -p hunter2"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("docker.exe login -ualice -phunter2"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("docker login '--username=alice' registry.test"))
        assertTrue(TerminalCompletionReplayPolicy.allowsPlaintext("docker login registry.test"))
    }

    @Test
    fun `rejects redis and sshpass credential commands`() {
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("redis-cli -a hunter2"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("redis-cli -ahunter2 ping"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("redis-cli '--pass=hunter2' ping"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("sshpass -p hunter2 ssh host"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("C:\\tools\\sshpass.exe -phunter2 ssh host"))
        assertTrue(TerminalCompletionReplayPolicy.allowsPlaintext("redis-cli ping"))
    }

    @Test
    fun `rejects URI password user info`() {
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("curl https://alice:s3cr3t@example.test/path"))
        assertFalse(TerminalCompletionReplayPolicy.allowsPlaintext("open 'ssh://alice:s3cr3t@example.test/repo'"))
        assertTrue(TerminalCompletionReplayPolicy.allowsPlaintext("open https://example.test/path"))
    }
}

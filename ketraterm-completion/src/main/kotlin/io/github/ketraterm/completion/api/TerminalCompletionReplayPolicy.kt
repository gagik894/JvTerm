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

import io.github.ketraterm.completion.internal.isStructurallyValidTerminalCompletionReplay
import java.util.*

/**
 * Best-effort conservative filter for retaining plaintext replay suggestions.
 *
 * Ranking does not depend on this decision: every valid event may update opaque
 * ranking evidence, while only commands approved here may enter either the
 * in-memory replay index or a persistence file. Approval is not proof that a
 * command contains no credential or other sensitive value.
 */
object TerminalCompletionReplayPolicy {
    /**
     * Returns whether [command] may be retained as plaintext replay data.
     *
     * The policy first enforces bounded, control-free replay structure. Its
     * best-effort credential classifier then honors leading-whitespace privacy,
     * rejects common credential vocabulary and option forms, and rejects URI
     * user-info containing a password. It cannot recognize every secret.
     *
     * @param command full command line captured by an authoritative host integration.
     * @return `true` only when the command is eligible for plaintext replay.
     */
    fun allowsPlaintext(command: String): Boolean {
        if (!isStructurallyValidTerminalCompletionReplay(command)) return false
        if (command.startsWith(' ') || command.startsWith('\t')) return false
        if (SENSITIVE_KEYWORDS.any { command.contains(it, ignoreCase = true) }) return false

        val words = command.lexicalWords()
        return !words.hasCredentialPattern() && words.none { it.containsPasswordUserInfo() }
    }

    private fun List<String>.hasCredentialPattern(): Boolean {
        var hasCurlFamily = false
        var hasMysqlFamily = false
        var hasDocker = false
        var hasRedisCli = false
        var hasSshpass = false
        for (word in this) {
            when (word.executableName()) {
                in CURL_EXECUTABLES -> hasCurlFamily = true
                in MYSQL_EXECUTABLES -> hasMysqlFamily = true
                "docker" -> hasDocker = true
                "redis-cli" -> hasRedisCli = true
                "sshpass" -> hasSshpass = true
            }
        }
        if (hasSshpass) return true
        val hasDockerLogin = hasDocker && any { it.lowercase(Locale.ROOT) == "login" }
        if (!hasCurlFamily && !hasMysqlFamily && !hasDockerLogin && !hasRedisCli) return false

        for (word in this) {
            val option = word.lowercase(Locale.ROOT)
            if (hasCurlFamily && option.isCurlCredentialOption()) return true
            if (hasMysqlFamily && option.isMysqlCredentialOption()) return true
            if (hasDockerLogin && option.isDockerLoginCredentialOption()) return true
            if (hasRedisCli && option.isRedisCredentialOption()) return true
        }
        return false
    }

    private fun String.isCurlCredentialOption(): Boolean =
        this == "-u" ||
            (startsWith("-u") && !startsWith("--")) ||
            this == "--user" ||
            startsWith("--user=") ||
            this == "--proxy-user" ||
            startsWith("--proxy-user=")

    private fun String.isMysqlCredentialOption(): Boolean =
        this == "-p" ||
            (startsWith("-p") && !startsWith("--"))

    private fun String.isDockerLoginCredentialOption(): Boolean =
        this == "-u" ||
            (startsWith("-u") && !startsWith("--")) ||
            this == "--username" ||
            startsWith("--username=") ||
            this == "-p" ||
            (startsWith("-p") && !startsWith("--")) ||
            this == "--password" ||
            startsWith("--password=")

    private fun String.isRedisCredentialOption(): Boolean =
        this == "-a" ||
            (startsWith("-a") && !startsWith("--")) ||
            this == "--pass" ||
            startsWith("--pass=")

    private fun String.executableName(): String =
        trim('"', '\'')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase(Locale.ROOT)
            .removeSuffix(".exe")

    private fun String.containsPasswordUserInfo(): Boolean {
        val schemeEnd = indexOf("://")
        if (schemeEnd <= 0) return false
        val authorityStart = schemeEnd + URI_SCHEME_SEPARATOR_LENGTH
        var authorityEnd = length
        for (index in authorityStart until length) {
            if (this[index] == '/' || this[index] == '?' || this[index] == '#') {
                authorityEnd = index
                break
            }
        }
        val at = lastIndexOf('@', authorityEnd - 1)
        return at >= authorityStart && indexOf(':', authorityStart) in authorityStart until at
    }

    private fun String.lexicalWords(): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var quote = '\u0000'
        var index = 0
        while (index < length) {
            val character = this[index]
            when {
                quote == '\u0000' && (character == '\'' || character == '"') -> quote = character
                quote == character -> quote = '\u0000'
                quote == '\u0000' && character.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        words += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(character)
            }
            index++
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    private const val URI_SCHEME_SEPARATOR_LENGTH = 3
    private val CURL_EXECUTABLES = setOf("curl")
    private val MYSQL_EXECUTABLES = setOf("mysql", "mysqladmin", "mysqldump", "mariadb", "mariadb-admin", "mariadb-dump")
    private val SENSITIVE_KEYWORDS =
        listOf(
            "password",
            "passwd",
            "secret",
            "token",
            "apikey",
            "api_key",
            "private_key",
            "access_key",
            "secret_key",
            "bearer",
            "authorization",
            "credential",
            "credentials",
            "passphrase",
            "passcode",
            "jwt",
            "key=",
            "_key",
            "key_",
            "-key",
            "--key",
            "key ",
            "auth ",
            "auth=",
        )
}

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
package io.github.ketraterm.completion.persistence

import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.*

/** Strict internal line codec for split completion learning. */
internal object CompletionLearningSnapshotCodec {
    fun currentFileName(): String = FILE_NAME

    fun encode(snapshot: TerminalCompletionLearningSnapshot): List<String> =
        buildList(1 + snapshot.rankingStats.size + snapshot.replayCommands.size) {
            add(HEADER)
            for (record in snapshot.rankingStats) add(encodeRankingRow(record))
            for (record in snapshot.replayCommands) add(encodeReplayRow(record))
        }

    fun decode(lines: List<String>): TerminalCompletionLearningSnapshot? {
        if (lines.firstOrNull() != HEADER) return null

        val rankingStats = ArrayList<TerminalCompletionRankingStats>()
        val replayCommands = ArrayList<TerminalCommandReplay>()
        var replayRowsStarted = false
        var index = 1
        while (index < lines.size) {
            val fields = lines[index].split('\t')
            when (fields.firstOrNull()) {
                ROW_RANKING -> {
                    if (replayRowsStarted) return null
                    rankingStats += decodeRankingRow(fields) ?: return null
                }
                ROW_REPLAY -> {
                    replayRowsStarted = true
                    replayCommands += decodeReplayRow(fields) ?: return null
                }
                else -> return null
            }
            index++
        }
        return TerminalCompletionLearningSnapshot(rankingStats, replayCommands)
    }

    fun encodeRankingRow(record: TerminalCompletionRankingStats): String =
        listOf(
            ROW_RANKING,
            record.identityDigest,
            encodeText(record.profileId.orEmpty()),
            encodeText(record.workingDirectoryUri.orEmpty()),
            record.useCount.toString(),
            record.successCount.toString(),
            record.failureCount.toString(),
            record.acceptedCount.toString(),
            record.dismissedCount.toString(),
            record.lastUsedEpochMillis.toString(),
        ).joinToString("\t")

    fun encodeReplayRow(record: TerminalCommandReplay): String =
        listOf(
            ROW_REPLAY,
            record.identityDigest,
            encodeText(record.commandLine),
            encodeText(record.profileId.orEmpty()),
            encodeText(record.workingDirectoryUri.orEmpty()),
        ).joinToString("\t")

    private fun decodeRankingRow(fields: List<String>): TerminalCompletionRankingStats? {
        if (fields.size != RANKING_FIELD_COUNT) return null
        return try {
            TerminalCompletionRankingStats(
                identityDigest = fields[1],
                profileId = decodeText(fields[2]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[3]).takeIf(String::isNotEmpty),
                useCount = fields[4].toInt(),
                successCount = fields[5].toInt(),
                failureCount = fields[6].toInt(),
                acceptedCount = fields[7].toInt(),
                dismissedCount = fields[8].toInt(),
                lastUsedEpochMillis = fields[9].toLong(),
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun decodeReplayRow(fields: List<String>): TerminalCommandReplay? {
        if (fields.size != REPLAY_FIELD_COUNT) return null
        return try {
            TerminalCommandReplay(
                identityDigest = fields[1],
                commandLine = decodeText(fields[2]),
                profileId = decodeText(fields[3]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[4]).takeIf(String::isNotEmpty),
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun encodeText(value: String): String {
        val utf8 =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        val bytes = ByteArray(utf8.remaining())
        utf8.get(bytes)
        return encoder.encodeToString(bytes)
    }

    private fun decodeText(value: String): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decoder.decode(value)))
            .toString()

    private const val FORMAT_VERSION = 3
    private const val FILE_NAME = "command-completion-learning-v$FORMAT_VERSION.tsv"
    private const val HEADER = "KetraTerm_COMMAND_COMPLETION_LEARNING\t$FORMAT_VERSION"
    private const val RANKING_FIELD_COUNT = 10
    private const val REPLAY_FIELD_COUNT = 5
    private const val ROW_RANKING = "R"
    private const val ROW_REPLAY = "H"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
}

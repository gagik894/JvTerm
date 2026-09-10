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

import io.github.ketraterm.completion.api.TerminalCompletionReplayPolicy
import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionRankingStats
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Result of reading a completion-learning file without conflating absence, rejection, and I/O failure. */
internal sealed interface CompletionLearningFileLoadOutcome {
    /** No file exists at the configured path. */
    data object Missing : CompletionLearningFileLoadOutcome

    /** The file was valid and decoded to [snapshot]. */
    data class Loaded(
        val snapshot: TerminalCompletionLearningSnapshot,
    ) : CompletionLearningFileLoadOutcome

    /** The file exists but has an unsupported format or exceeds a hard input bound. */
    data object Rejected : CompletionLearningFileLoadOutcome

    /** The file could not be inspected or read. */
    data class Failed(
        val cause: Exception,
    ) : CompletionLearningFileLoadOutcome
}

/** Fixed-path snapshot operations used by the persistence coordinator. */
internal interface CompletionLearningSnapshotFileStore {
    /** Reads and validates one bounded snapshot. */
    fun loadSnapshot(): CompletionLearningFileLoadOutcome

    /** Atomically replaces the file with one bounded, sanitized [snapshot]. */
    fun persist(snapshot: TerminalCompletionLearningSnapshot)
}

/** Bounded local-file implementation used by the persistence coordinator. */
internal class CompletionLearningFileStore(
    private val path: Path,
    private val openInput: (Path) -> InputStream = { Files.newInputStream(it) },
    private val createTemporaryFile: (Path, String, String) -> Path = Files::createTempFile,
) : CompletionLearningSnapshotFileStore {
    override fun loadSnapshot(): CompletionLearningFileLoadOutcome =
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile) {
                CompletionLearningFileLoadOutcome.Rejected
            } else {
                val decoded = readBoundedLines()?.let(CompletionLearningSnapshotCodec::decode)
                val snapshot = decoded?.sanitizeReplay()
                if (snapshot == null || !snapshotFitsBounds(snapshot)) {
                    CompletionLearningFileLoadOutcome.Rejected
                } else {
                    CompletionLearningFileLoadOutcome.Loaded(snapshot)
                }
            }
        } catch (_: NoSuchFileException) {
            CompletionLearningFileLoadOutcome.Missing
        } catch (failure: Exception) {
            CompletionLearningFileLoadOutcome.Failed(failure)
        }

    override fun persist(snapshot: TerminalCompletionLearningSnapshot) {
        val lines = CompletionLearningSnapshotCodec.encode(boundedSnapshot(snapshot.sanitizeReplay()))
        requireEncodedBounds(lines)
        val absolutePath = path.toAbsolutePath().normalize()
        val parent = requireNotNull(absolutePath.parent) { "persistence path must have a parent: $path" }
        Files.createDirectories(parent)
        val temporary = createTemporaryFile(parent, ".${absolutePath.fileName}.", ".tmp")
        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                for (line in lines) writer.appendLine(line)
            }
            replaceAtomically(temporary, absolutePath)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun requireEncodedBounds(lines: List<String>) {
        var writtenBytes = 0
        for (line in lines) {
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size
            require(lineBytes <= MAX_LINE_BYTES) { "encoded persistence row exceeds $MAX_LINE_BYTES bytes" }
            writtenBytes += lineBytes + MAX_NEWLINE_BYTES
            require(writtenBytes <= MAX_FILE_BYTES) { "encoded persistence snapshot exceeds $MAX_FILE_BYTES bytes" }
        }
    }

    private fun replaceAtomically(
        temporary: Path,
        target: Path,
    ) {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readBoundedLines(): List<String>? {
        val bytes = openInput(path).use { input -> input.readNBytes(MAX_FILE_BYTES + 1) }
        if (bytes.size > MAX_FILE_BYTES) return null

        val lines = ArrayList<String>(minOf(MAX_FILE_LINES, DEFAULT_LINE_CAPACITY))
        var lineStart = 0
        for (index in bytes.indices) {
            if (bytes[index] != NEWLINE_BYTE) continue
            val contentEnd = if (index > lineStart && bytes[index - 1] == CARRIAGE_RETURN_BYTE) index - 1 else index
            if (!lines.addBoundedLine(bytes, lineStart, contentEnd)) return null
            lineStart = index + 1
        }
        if (lineStart < bytes.size && !lines.addBoundedLine(bytes, lineStart, bytes.size)) return null
        return lines
    }

    private fun MutableList<String>.addBoundedLine(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        val byteCount = end - start
        if (size == MAX_FILE_LINES || byteCount > MAX_LINE_BYTES) return false
        add(String(bytes, start, byteCount, StandardCharsets.UTF_8))
        return true
    }

    private fun snapshotFitsBounds(snapshot: TerminalCompletionLearningSnapshot): Boolean {
        if (snapshot.rankingStats.size > MAX_RANKING_ROWS || snapshot.replayCommands.size > MAX_REPLAY_ROWS) return false
        val rankingKeys = snapshot.rankingStats.mapTo(HashSet(snapshot.rankingStats.size)) { it.rowKey() }
        var rankingBytes = 0
        for (row in snapshot.rankingStats) {
            val rowSize = rankingRowSize(row) ?: return false
            rankingBytes += rowSize + MAX_NEWLINE_BYTES
            if (rankingBytes > MAX_RANKING_BYTES) return false
        }
        var replayBytes = 0
        for (row in snapshot.replayCommands) {
            if (row.rowKey() !in rankingKeys) return false
            val rowSize = replayRowSize(row) ?: return false
            replayBytes += rowSize + MAX_NEWLINE_BYTES
            if (replayBytes > MAX_REPLAY_BYTES) return false
        }
        return true
    }

    private fun boundedSnapshot(snapshot: TerminalCompletionLearningSnapshot): TerminalCompletionLearningSnapshot {
        val rankingStats = ArrayList<TerminalCompletionRankingStats>(minOf(snapshot.rankingStats.size, MAX_RANKING_ROWS))
        val rankingKeys = HashSet<PersistedLearningRowKey>()
        var rankingBytes = 0
        for (row in snapshot.rankingStats) {
            val size = rankingRowSize(row) ?: continue
            if (rankingBytes + size + MAX_NEWLINE_BYTES > MAX_RANKING_BYTES) continue
            rankingStats += row
            rankingKeys += row.rowKey()
            rankingBytes += size + MAX_NEWLINE_BYTES
            if (rankingStats.size == MAX_RANKING_ROWS) break
        }

        val replayCommands = ArrayList<TerminalCommandReplay>(minOf(snapshot.replayCommands.size, MAX_REPLAY_ROWS))
        var replayBytes = 0
        for (row in snapshot.replayCommands) {
            if (row.rowKey() !in rankingKeys) continue
            val size = replayRowSize(row) ?: continue
            if (replayBytes + size + MAX_NEWLINE_BYTES > MAX_REPLAY_BYTES) continue
            replayCommands += row
            replayBytes += size + MAX_NEWLINE_BYTES
            if (replayCommands.size == MAX_REPLAY_ROWS) break
        }
        return TerminalCompletionLearningSnapshot(rankingStats, replayCommands)
    }

    private fun rankingRowSize(row: TerminalCompletionRankingStats): Int? =
        encodedRowSize(row.identityDigest, row.profileId, row.workingDirectoryUri) {
            CompletionLearningSnapshotCodec.encodeRankingRow(row)
        }

    private fun replayRowSize(row: TerminalCommandReplay): Int? =
        encodedRowSize(row.identityDigest, row.commandLine, row.profileId, row.workingDirectoryUri) {
            CompletionLearningSnapshotCodec.encodeReplayRow(row)
        }

    private inline fun encodedRowSize(
        vararg values: String?,
        encode: () -> String,
    ): Int? {
        if (!hasBoundedText(values)) return null
        val encodedBytes = encode().toByteArray(StandardCharsets.UTF_8).size
        return encodedBytes.takeIf { it <= MAX_LINE_BYTES }
    }

    private fun hasBoundedText(values: Array<out String?>): Boolean {
        var total = 0
        for (value in values) {
            val length = value?.length ?: continue
            if (length > MAX_TEXT_CHARS || total + length > MAX_ROW_RAW_CHARS) return false
            total += length
        }
        return true
    }

    private fun TerminalCompletionLearningSnapshot.sanitizeReplay(): TerminalCompletionLearningSnapshot {
        val successfulKeys =
            rankingStats
                .asSequence()
                .filter { it.successCount > 0 }
                .mapTo(HashSet()) { it.rowKey() }
        val retained =
            replayCommands.filter {
                it.rowKey() in successfulKeys && TerminalCompletionReplayPolicy.allowsPlaintext(it.commandLine)
            }
        return if (retained.size == replayCommands.size) this else copy(replayCommands = retained)
    }

    private data class PersistedLearningRowKey(
        val identityDigest: String,
        val profileId: String?,
        val workingDirectoryUri: String?,
    )

    private fun TerminalCompletionRankingStats.rowKey(): PersistedLearningRowKey =
        PersistedLearningRowKey(identityDigest, profileId, workingDirectoryUri)

    private fun TerminalCommandReplay.rowKey(): PersistedLearningRowKey =
        PersistedLearningRowKey(identityDigest, profileId, workingDirectoryUri)

    private companion object {
        private const val MAX_RANKING_ROWS = 2_048
        private const val MAX_REPLAY_ROWS = 2_048
        private const val MAX_RANKING_BYTES = 1_000_000
        private const val MAX_REPLAY_BYTES = 1_000_000
        private const val MAX_FILE_BYTES = MAX_RANKING_BYTES + MAX_REPLAY_BYTES + 256
        private const val MAX_FILE_LINES = 1 + MAX_RANKING_ROWS + MAX_REPLAY_ROWS
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_NEWLINE_BYTES = 2
        private const val MAX_ROW_RAW_CHARS = 8 * 1024
        private const val MAX_TEXT_CHARS = 4 * 1024
        private const val DEFAULT_LINE_CAPACITY = 256
        private const val NEWLINE_BYTE: Byte = 0x0A
        private const val CARRIAGE_RETURN_BYTE: Byte = 0x0D
    }
}

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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import kotlinx.coroutines.*
import javax.swing.Timer

internal interface TerminalHyperlinkDiscoveryHost {
    val renderCache: TerminalRenderCache
    val hyperlinkDetector: SwingHyperlinkDetector

    /** Reconciles interaction state after asynchronously detected link IDs have been installed. */
    fun hyperlinksChanged()

    fun repaintHyperlinkSpan(
        startRow: Int,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
    )
}

internal class TerminalHyperlinkDiscoveryController(
    private val host: TerminalHyperlinkDiscoveryHost,
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val snapshotBuilder = TerminalHyperlinkViewportSnapshotBuilder()
    private val overlay = TerminalHyperlinkOverlay()
    private val debounceTimer =
        Timer(DEBOUNCE_MILLIS) {
            startAnalysisOnEdt()
        }.apply {
            isRepeats = false
        }
    private var analysisSequence: Long = 0L
    private var analysisJob: Job? = null
    private var disposed: Boolean = false

    fun reset() {
        analysisSequence++
        analysisJob?.cancel(CancellationException("Hyperlink discovery reset"))
        analysisJob = null
        debounceTimer.stop()
        overlay.clear()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        reset()
    }

    fun scheduleForFrame() {
        if (disposed) return
        analysisSequence++
        val cache = host.renderCache
        if (host.hyperlinkDetector === SwingHyperlinkDetector.NONE) {
            publishOverlay(currentFrameKey(cache), candidate = null)
            debounceTimer.stop()
            return
        }
        hydrateOverlayForCurrentFrame(cache)
        debounceTimer.restart()
    }

    fun hyperlinkIdsFor(cache: TerminalRenderCache): IntArray = overlay.idsFor(cache)

    fun hyperlinkIdAt(
        row: Int,
        column: Int,
        cache: TerminalRenderCache,
    ): Int {
        if (row !in 0 until cache.rows || column !in 0 until cache.columns) return NO_HYPERLINK_ID
        return hyperlinkIdsFor(cache)[cache.rowOffset(row) + column]
    }

    fun isDiscoveredHyperlinkResolvable(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
    ): Boolean = overlay.actionFor(hyperlinkId, cache) != null

    fun openDiscoveredHyperlink(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
    ): Boolean = overlay.actionFor(hyperlinkId, cache)?.open() == true

    private fun startAnalysisOnEdt() {
        if (disposed) return
        val detector = host.hyperlinkDetector
        if (detector === SwingHyperlinkDetector.NONE) {
            publishOverlay(currentFrameKey(host.renderCache), candidate = null)
            return
        }

        val sequence = analysisSequence
        val snapshot = snapshotBuilder.snapshot(host.renderCache)
        analysisJob?.cancel(CancellationException("Hyperlink discovery superseded"))
        analysisJob =
            scope.launch {
                val candidate =
                    withContext(analysisDispatcher) {
                        val accumulator = TerminalHyperlinkDetectionAccumulator()
                        try {
                            detector.detect(snapshot.request, accumulator)
                        } catch (error: VirtualMachineError) {
                            throw error
                        } catch (_: Exception) {
                            return@withContext null
                        }
                        ensureActive()
                        snapshot.buildOverlay(accumulator.detectedLinks)
                    }
                ensureActive()
                if (disposed) return@launch
                if (sequence != analysisSequence) return@launch
                if (!snapshot.key.matches(host.renderCache)) return@launch
                publishOverlay(snapshot.key, candidate)
            }
    }

    private fun publishOverlay(
        key: TerminalHyperlinkFrameKey,
        candidate: TerminalHyperlinkOverlayCandidate?,
    ) {
        val cache = host.renderCache
        if (!key.matches(cache)) return

        val previousIds = overlay.idsFor(cache)
        val nextIds = candidate?.hyperlinkIds ?: cache.hyperlinkIds
        repaintChangedHyperlinkCells(cache, previousIds, nextIds)
        overlay.replace(key, candidate, cache)
        host.hyperlinksChanged()
    }

    private fun hydrateOverlayForCurrentFrame(cache: TerminalRenderCache) {
        val candidate = overlay.hydrateFor(cache) ?: return
        val previousIds = overlay.idsFor(cache)
        repaintChangedHyperlinkCells(cache, previousIds, candidate.hyperlinkIds)
        overlay.replace(currentFrameKey(cache), candidate, cache)
    }

    private fun repaintChangedHyperlinkCells(
        cache: TerminalRenderCache,
        previousIds: IntArray,
        nextIds: IntArray,
    ) {
        var row = 0
        while (row < cache.rows) {
            val rowOffset = cache.rowOffset(row)
            var column = 0
            while (column < cache.columns) {
                val index = rowOffset + column
                if (previousIds[index] == nextIds[index]) {
                    column++
                    continue
                }

                val startColumn = column
                column++
                while (column < cache.columns && previousIds[rowOffset + column] != nextIds[rowOffset + column]) {
                    column++
                }
                host.repaintHyperlinkSpan(row, startColumn, row, column)
            }
            row++
        }
    }

    private companion object {
        private const val DEBOUNCE_MILLIS = 125
        private const val NO_HYPERLINK_ID = 0
    }
}

internal data class TerminalHyperlinkFrameKey(
    val frameGeneration: Long,
    val structureGeneration: Long,
    val scrollbackOffset: Int,
    val discardedCount: Long,
    val activeBuffer: TerminalRenderBufferKind,
    val columns: Int,
    val rows: Int,
)

internal fun currentFrameKey(cache: TerminalRenderCache): TerminalHyperlinkFrameKey =
    TerminalHyperlinkFrameKey(
        frameGeneration = cache.frameGeneration,
        structureGeneration = cache.structureGeneration,
        scrollbackOffset = cache.scrollbackOffset,
        discardedCount = cache.discardedCount,
        activeBuffer = cache.activeBuffer,
        columns = cache.columns,
        rows = cache.rows,
    )

private fun TerminalHyperlinkFrameKey.matches(cache: TerminalRenderCache): Boolean =
    frameGeneration == cache.frameGeneration &&
        structureGeneration == cache.structureGeneration &&
        scrollbackOffset == cache.scrollbackOffset &&
        discardedCount == cache.discardedCount &&
        activeBuffer == cache.activeBuffer &&
        columns == cache.columns &&
        rows == cache.rows

private fun TerminalHyperlinkFrameKey.canCarryRowsFor(cache: TerminalRenderCache): Boolean =
    discardedCount == cache.discardedCount &&
        activeBuffer == cache.activeBuffer &&
        columns == cache.columns

private fun rowFingerprint(
    cache: TerminalRenderCache,
    row: Int,
): Long {
    var hash = ROW_FINGERPRINT_OFFSET
    var hasVisibleCell = false
    val rowOffset = cache.rowOffset(row)
    var column = 0
    while (column < cache.columns) {
        val index = rowOffset + column
        val flags = cache.flags[index]
        val textFlags =
            flags and
                (
                    TerminalRenderCellFlags.CODEPOINT or
                        TerminalRenderCellFlags.CLUSTER or
                        TerminalRenderCellFlags.WIDE_LEADING or
                        TerminalRenderCellFlags.WIDE_TRAILING
                )
        if (textFlags != 0) hasVisibleCell = true

        hash = mixRowFingerprint(hash, textFlags)
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val ref = cache.clusterRefs[index]
            val start = cache.clusterOffset(ref)
            val end = start + cache.clusterLength(ref)
            var clusterIndex = start
            while (clusterIndex < end) {
                hash = mixRowFingerprint(hash, cache.clusterCodepoints[clusterIndex])
                clusterIndex++
            }
        } else {
            hash = mixRowFingerprint(hash, cache.codeWords[index])
        }
        column++
    }
    hash = mixRowFingerprint(hash, if (cache.lineWrapped[row]) 1 else 0)
    return if (hasVisibleCell) hash else EMPTY_ROW_FINGERPRINT
}

private fun mixRowFingerprint(
    hash: Long,
    value: Int,
): Long = (hash xor value.toLong()) * ROW_FINGERPRINT_PRIME

private class TerminalHyperlinkOverlay {
    private var key: TerminalHyperlinkFrameKey? = null
    private var hyperlinkIds: IntArray = IntArray(0)
    private var actions: Array<SwingHyperlinkAction> = emptyArray()
    private var rowEntries: Array<TerminalHyperlinkRowEntry> = emptyArray()
    private var carriedIds = IntArray(0)
    private var matchedRows = IntArray(0)

    fun clear() {
        key = null
        hyperlinkIds = IntArray(0)
        actions = emptyArray()
        rowEntries = emptyArray()
    }

    fun replace(
        nextKey: TerminalHyperlinkFrameKey,
        candidate: TerminalHyperlinkOverlayCandidate?,
        cache: TerminalRenderCache,
    ) {
        if (candidate == null) {
            clear()
            return
        }
        key = nextKey
        hyperlinkIds = candidate.hyperlinkIds
        actions = candidate.actions
        rowEntries = buildRowEntries(candidate, cache)
    }

    fun idsFor(cache: TerminalRenderCache): IntArray =
        if (key?.matches(cache) == true) {
            hyperlinkIds
        } else {
            cache.hyperlinkIds
        }

    fun actionFor(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
    ): SwingHyperlinkAction? {
        if (hyperlinkId >= 0 || key?.matches(cache) != true) return null
        val actionIndex = -hyperlinkId - 1
        return if (actionIndex in actions.indices) actions[actionIndex] else null
    }

    fun hydrateFor(cache: TerminalRenderCache): TerminalHyperlinkOverlayCandidate? {
        val previousKey = key ?: return null
        if (previousKey.matches(cache)) return null
        if (!previousKey.canCarryRowsFor(cache)) {
            clear()
            return null
        }

        var preservedIds: IntArray? = null
        val preservedActions = ArrayList<SwingHyperlinkAction>()
        // Remap each detected link once, even when its cells occupy several row runs.
        // Action identity cannot identify a link: detectors may reuse actions for distinct matches.
        if (carriedIds.size < actions.size) carriedIds = IntArray(actions.size)
        carriedIds.fill(NO_HYPERLINK_ID, 0, actions.size)
        matchLogicalLines(cache)
        var targetRow = 0
        while (targetRow < cache.rows) {
            val sourceRow = matchedRows[targetRow]
            if (sourceRow >= 0) {
                val rowEntry = rowEntries[sourceRow]
                val targetOffset = cache.rowOffset(targetRow)
                for (run in rowEntry.runs) {
                    val actionIndex = -run.hyperlinkId - 1
                    var nextId = carriedIds[actionIndex]
                    var column = run.startColumn
                    val endColumn = minOf(run.endColumn, cache.columns)
                    while (column < endColumn) {
                        if (cache.hyperlinkIds[targetOffset + column] == NO_HYPERLINK_ID) {
                            if (nextId == NO_HYPERLINK_ID) {
                                nextId = -(preservedActions.size + 1)
                                carriedIds[actionIndex] = nextId
                                preservedActions += actions[actionIndex]
                            }
                            val ids =
                                preservedIds ?: cache.hyperlinkIds
                                    .copyOf(cache.rows * cache.columns)
                                    .also { preservedIds = it }
                            ids[targetOffset + column] = nextId
                        }
                        column++
                    }
                }
            }
            targetRow++
        }

        val ids =
            preservedIds ?: run {
                clear()
                return null
            }
        return TerminalHyperlinkOverlayCandidate(ids, preservedActions.toTypedArray())
    }

    private fun buildRowEntries(
        candidate: TerminalHyperlinkOverlayCandidate,
        cache: TerminalRenderCache,
    ): Array<TerminalHyperlinkRowEntry> =
        // Detectors see whole logical lines, including rows outside their returned link spans.
        Array(cache.rows) { row ->
            TerminalHyperlinkRowEntry(
                lineId = cache.lineIds[row],
                lineGeneration = cache.lineGenerations[row],
                fingerprint = rowFingerprint(cache, row),
                wrapped = cache.lineWrapped[row],
                activeBuffer = cache.activeBuffer,
                runs = runsForRow(candidate, cache, row),
            )
        }

    private fun matchLogicalLines(cache: TerminalRenderCache) {
        if (matchedRows.size < cache.rows) matchedRows = IntArray(cache.rows)
        for (row in 0 until cache.rows) {
            matchedRows[row] = matchingRowIndex(cache, row)
        }

        var startRow = 0
        while (startRow < cache.rows) {
            var endRow = startRow + 1
            while (endRow < cache.rows && cache.lineWrapped[endRow - 1]) endRow++

            val sourceStart = matchedRows[startRow]
            val sourceEnd = sourceStart + endRow - startRow
            var unchanged =
                sourceStart >= 0 &&
                    sourceEnd <= rowEntries.size &&
                    (sourceStart == 0 || !rowEntries[sourceStart - 1].wrapped) &&
                    (sourceEnd == rowEntries.size || !rowEntries[sourceEnd - 1].wrapped)
            var row = startRow
            while (unchanged && row < endRow) {
                unchanged = matchedRows[row] == sourceStart + row - startRow
                row++
            }
            // A surviving prefix must never retain an action built from an edited suffix.
            if (!unchanged) matchedRows.fill(-1, startRow, endRow)
            startRow = endRow
        }
    }

    private fun runsForRow(
        candidate: TerminalHyperlinkOverlayCandidate,
        cache: TerminalRenderCache,
        row: Int,
    ): Array<TerminalHyperlinkRowRun> {
        var runs: ArrayList<TerminalHyperlinkRowRun>? = null
        val rowOffset = cache.rowOffset(row)
        var column = 0
        while (column < cache.columns) {
            val hyperlinkId = candidate.hyperlinkIds[rowOffset + column]
            if (hyperlinkId >= NO_HYPERLINK_ID) {
                column++
                continue
            }

            val startColumn = column
            column++
            while (column < cache.columns && candidate.hyperlinkIds[rowOffset + column] == hyperlinkId) {
                column++
            }

            val actionIndex = -hyperlinkId - 1
            if (actionIndex in candidate.actions.indices) {
                val rowRuns = runs ?: ArrayList<TerminalHyperlinkRowRun>().also { runs = it }
                rowRuns += TerminalHyperlinkRowRun(startColumn, column, hyperlinkId)
            }
        }
        return runs?.toTypedArray() ?: EMPTY_ROW_RUNS
    }

    private fun matchingRowIndex(
        cache: TerminalRenderCache,
        targetRow: Int,
    ): Int {
        val lineId = cache.lineIds[targetRow]
        if (lineId != NO_LINE_ID) {
            val lineGeneration = cache.lineGenerations[targetRow]
            val wrapped = cache.lineWrapped[targetRow]
            var index = rowEntries.size - 1
            while (index >= 0) {
                val entry = rowEntries[index]
                if (
                    entry.activeBuffer == cache.activeBuffer &&
                    entry.lineId == lineId &&
                    entry.lineGeneration == lineGeneration &&
                    entry.wrapped == wrapped
                ) {
                    return index
                }
                index--
            }
            return -1
        }

        val fingerprint = rowFingerprint(cache, targetRow)
        if (fingerprint == EMPTY_ROW_FINGERPRINT) return -1
        val wrapped = cache.lineWrapped[targetRow]
        var matchedIndex = -1
        var index = rowEntries.size - 1
        while (index >= 0) {
            val entry = rowEntries[index]
            if (
                entry.activeBuffer == cache.activeBuffer &&
                entry.lineId == NO_LINE_ID &&
                entry.fingerprint == fingerprint &&
                entry.wrapped == wrapped
            ) {
                if (matchedIndex >= 0) return -1
                matchedIndex = index
            }
            index--
        }
        return matchedIndex
    }

    private companion object {
        private const val NO_HYPERLINK_ID = 0
        private const val NO_LINE_ID = 0L
        private val EMPTY_ROW_RUNS = emptyArray<TerminalHyperlinkRowRun>()
    }
}

private data class TerminalHyperlinkRowEntry(
    val lineId: Long,
    val lineGeneration: Long,
    val fingerprint: Long,
    val wrapped: Boolean,
    val activeBuffer: TerminalRenderBufferKind,
    val runs: Array<TerminalHyperlinkRowRun>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TerminalHyperlinkRowEntry

        if (lineId != other.lineId) return false
        if (lineGeneration != other.lineGeneration) return false
        if (fingerprint != other.fingerprint) return false
        if (wrapped != other.wrapped) return false
        if (activeBuffer != other.activeBuffer) return false
        if (!runs.contentEquals(other.runs)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = lineId.hashCode()
        result = 31 * result + lineGeneration.hashCode()
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + wrapped.hashCode()
        result = 31 * result + activeBuffer.hashCode()
        result = 31 * result + runs.contentHashCode()
        return result
    }
}

private data class TerminalHyperlinkRowRun(
    val startColumn: Int,
    val endColumn: Int,
    val hyperlinkId: Int,
)

private const val EMPTY_ROW_FINGERPRINT = 0L
private const val ROW_FINGERPRINT_OFFSET = -3750763034362895579L
private const val ROW_FINGERPRINT_PRIME = 1099511628211L

private class TerminalHyperlinkDetectionAccumulator : SwingHyperlinkDetectionSink {
    val detectedLinks = ArrayList<TerminalDetectedHyperlink>()

    override fun addHyperlink(
        lineIndex: Int,
        startOffset: Int,
        endOffset: Int,
        action: SwingHyperlinkAction,
    ) {
        if (startOffset >= endOffset) return
        detectedLinks += TerminalDetectedHyperlink(lineIndex, startOffset, endOffset, action)
    }
}

internal data class TerminalDetectedHyperlink(
    val lineIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val action: SwingHyperlinkAction,
)

internal data class TerminalHyperlinkOverlayCandidate(
    val hyperlinkIds: IntArray,
    val actions: Array<SwingHyperlinkAction>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TerminalHyperlinkOverlayCandidate

        if (!hyperlinkIds.contentEquals(other.hyperlinkIds)) return false
        if (!actions.contentEquals(other.actions)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hyperlinkIds.contentHashCode()
        result = 31 * result + actions.contentHashCode()
        return result
    }
}

internal class TerminalHyperlinkDetectionSnapshot(
    val key: TerminalHyperlinkFrameKey,
    val request: SwingHyperlinkDetectionRequest,
    private val terminalHyperlinkIds: IntArray,
    private val lineRows: Array<IntArray>,
    private val lineStartColumns: Array<IntArray>,
    private val lineEndColumns: Array<IntArray>,
) {
    fun buildOverlay(detectedLinks: List<TerminalDetectedHyperlink>): TerminalHyperlinkOverlayCandidate? {
        if (detectedLinks.isEmpty()) return null

        var effectiveIds: IntArray? = null
        val actions = ArrayList<SwingHyperlinkAction>(detectedLinks.size)
        for (link in detectedLinks) {
            if (link.lineIndex !in lineRows.indices) continue

            val rows = lineRows[link.lineIndex]
            if (rows.isEmpty()) continue
            val startOffset = link.startOffset.coerceIn(0, rows.size)
            val endOffset = link.endOffset.coerceIn(startOffset, rows.size)
            if (startOffset >= endOffset) continue

            val nextId = -(actions.size + 1)
            val ids = effectiveIds ?: terminalHyperlinkIds.copyOf().also { effectiveIds = it }
            var accepted = false
            var offset = startOffset
            while (offset < endOffset) {
                val row = rows[offset]
                val startColumn = lineStartColumns[link.lineIndex][offset]
                val endColumn = lineEndColumns[link.lineIndex][offset]
                var column = startColumn
                while (column < endColumn) {
                    val index = row * key.columns + column
                    if (terminalHyperlinkIds[index] == NO_HYPERLINK_ID && ids[index] == NO_HYPERLINK_ID) {
                        ids[index] = nextId
                        accepted = true
                    }
                    column++
                }
                offset++
            }

            if (accepted) {
                actions += link.action
            }
        }

        val ids = effectiveIds ?: return null
        if (actions.isEmpty()) return null
        return TerminalHyperlinkOverlayCandidate(ids, actions.toTypedArray())
    }

    private companion object {
        private const val NO_HYPERLINK_ID = 0
    }
}

internal class TerminalHyperlinkViewportSnapshotBuilder {
    private val rowText = StringBuilder(INITIAL_LINE_CAPACITY)
    private var offsetRows = IntArray(INITIAL_LINE_CAPACITY)
    private var offsetStartColumns = IntArray(INITIAL_LINE_CAPACITY)
    private var offsetEndColumns = IntArray(INITIAL_LINE_CAPACITY)
    private var offsetCount = 0

    fun snapshot(cache: TerminalRenderCache): TerminalHyperlinkDetectionSnapshot {
        val lines = ArrayList<String>(cache.rows)
        val lineStartOffsets = ArrayList<Int>(cache.rows)
        val lineEndOffsets = ArrayList<Int>(cache.rows)
        val lineRows = ArrayList<IntArray>(cache.rows)
        val lineStartColumns = ArrayList<IntArray>(cache.rows)
        val lineEndColumns = ArrayList<IntArray>(cache.rows)
        var cumulativeOffset = 0
        var row = 0

        while (row < cache.rows) {
            rowText.setLength(0)
            offsetCount = 0

            var logicalLineComplete = false
            while (!logicalLineComplete && row < cache.rows) {
                appendRow(cache, row)
                logicalLineComplete = !cache.lineWrapped[row] || row + 1 >= cache.rows
                row++
            }

            trimTrailingSpaces()
            val line = rowText.toString() + '\n'
            lines += line
            lineStartOffsets += cumulativeOffset
            cumulativeOffset += line.length
            lineEndOffsets += cumulativeOffset
            lineRows += offsetRows.copyOf(offsetCount)
            lineStartColumns += offsetStartColumns.copyOf(offsetCount)
            lineEndColumns += offsetEndColumns.copyOf(offsetCount)
        }

        return TerminalHyperlinkDetectionSnapshot(
            key = currentFrameKey(cache),
            request =
                SwingHyperlinkDetectionRequest(
                    lines = lines.toTypedArray(),
                    lineStartOffsets = lineStartOffsets.toIntArray(),
                    lineEndOffsets = lineEndOffsets.toIntArray(),
                ),
            terminalHyperlinkIds = cache.hyperlinkIds.copyOf(cache.rows * cache.columns),
            lineRows = lineRows.toTypedArray(),
            lineStartColumns = lineStartColumns.toTypedArray(),
            lineEndColumns = lineEndColumns.toTypedArray(),
        )
    }

    private fun appendRow(
        cache: TerminalRenderCache,
        row: Int,
    ) {
        val rowOffset = cache.rowOffset(row)
        var column = 0
        while (column < cache.columns) {
            val index = rowOffset + column
            val flags = cache.flags[index]
            if (flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
                column++
                continue
            }

            val span = cellSpan(flags)
            val endColumn = minOf(cache.columns, column + span)
            when {
                flags and TerminalRenderCellFlags.CLUSTER != 0 -> appendCluster(cache, index, row, column, endColumn)
                flags and TerminalRenderCellFlags.CODEPOINT != 0 -> appendCodePoint(cache.codeWords[index], row, column, endColumn)
                else -> appendCodePoint(SPACE_CODE_POINT, row, column, endColumn)
            }
            column += span
        }
    }

    private fun appendCluster(
        cache: TerminalRenderCache,
        index: Int,
        row: Int,
        column: Int,
        endColumn: Int,
    ) {
        val ref = cache.clusterRefs[index]
        if (ref == NO_CLUSTER_REF) {
            appendCodePoint(SPACE_CODE_POINT, row, column, endColumn)
            return
        }

        val offset = cache.clusterOffset(ref)
        val end = offset + cache.clusterLength(ref)
        var clusterIndex = offset
        while (clusterIndex < end) {
            appendCodePoint(cache.clusterCodepoints[clusterIndex], row, column, endColumn)
            clusterIndex++
        }
    }

    private fun appendCodePoint(
        codePoint: Int,
        row: Int,
        column: Int,
        endColumn: Int,
    ) {
        val before = rowText.length
        if (Character.isValidCodePoint(codePoint)) {
            rowText.appendCodePoint(codePoint)
        } else {
            rowText.append(REPLACEMENT_CHAR)
        }
        val after = rowText.length
        ensureOffsetCapacity(after)
        var offset = before
        while (offset < after) {
            offsetRows[offset] = row
            offsetStartColumns[offset] = column
            offsetEndColumns[offset] = endColumn
            offset++
        }
        offsetCount = after
    }

    private fun trimTrailingSpaces() {
        while (rowText.isNotEmpty() && rowText[rowText.length - 1] == ' ') {
            rowText.setLength(rowText.length - 1)
        }
        offsetCount = rowText.length
    }

    private fun ensureOffsetCapacity(required: Int) {
        if (required <= offsetRows.size) return
        var capacity = offsetRows.size
        while (capacity < required) {
            capacity *= 2
        }
        offsetRows = offsetRows.copyOf(capacity)
        offsetStartColumns = offsetStartColumns.copyOf(capacity)
        offsetEndColumns = offsetEndColumns.copyOf(capacity)
    }

    private fun cellSpan(flags: Int): Int = if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1

    private companion object {
        private const val INITIAL_LINE_CAPACITY = 256
        private const val NO_CLUSTER_REF = 0L
        private const val SPACE_CODE_POINT = 0x20
        private const val REPLACEMENT_CHAR = '\uFFFD'
    }
}

private fun ArrayList<Int>.toIntArray(): IntArray = IntArray(size) { this[it] }

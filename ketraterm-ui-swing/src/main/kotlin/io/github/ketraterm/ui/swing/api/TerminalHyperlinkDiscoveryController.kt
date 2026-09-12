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

import io.github.ketraterm.render.cache.TerminalRenderCache
import kotlinx.coroutines.*

internal interface TerminalHyperlinkDiscoveryHost {
    val renderCache: TerminalRenderCache
    val hyperlinkDetector: SwingHyperlinkDetector

    /** Reconciles interaction state after asynchronously detected links have been installed. */
    fun hyperlinksChanged()

    fun repaintHyperlinkSpan(
        startRow: Int,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
    )
}

/** EDT-owned discovery lifecycle. A running detector finishes before the latest pending work starts. */
internal class TerminalHyperlinkDiscoveryController(
    private val host: TerminalHyperlinkDiscoveryHost,
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val viewport = TerminalHyperlinkViewport()
    private var analysisJob: Job? = null
    private var epoch = 0L
    private var frameRevision = 0L
    private var disposed = false
    private var detector = host.hyperlinkDetector
    private val repaintSpan = host::repaintHyperlinkSpan

    fun reset() {
        epoch++
        viewport.clear()
        // A synchronous host detector may ignore cancellation. Keep its slot until it returns.
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        reset()
        analysisJob?.cancel(CancellationException("Hyperlink discovery disposed"))
    }

    fun scheduleForFrame() {
        if (disposed) return
        if (detector !== host.hyperlinkDetector) {
            reset()
            detector = host.hyperlinkDetector
        }
        if (detector === SwingHyperlinkDetector.NONE) return
        if (viewport.update(host.renderCache, detector.context)) {
            frameRevision++
            viewport.writeOverlay(host.renderCache, repaintSpan)
        }
        startAnalysis()
    }

    fun hyperlinkIdsFor(cache: TerminalRenderCache): IntArray = viewport.idsFor(cache)

    fun hyperlinkIdAt(
        row: Int,
        column: Int,
        cache: TerminalRenderCache,
    ): Int {
        if (row !in 0 until cache.rows || column !in 0 until cache.columns) return 0
        return hyperlinkIdsFor(cache)[cache.rowOffset(row) + column]
    }

    fun isDiscoveredHyperlinkResolvable(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
    ): Boolean = viewport.actionFor(hyperlinkId, cache) != null

    fun openDiscoveredHyperlink(
        hyperlinkId: Int,
        cache: TerminalRenderCache,
    ): Boolean = viewport.actionFor(hyperlinkId, cache)?.open() == true

    private fun startAnalysis() {
        if (analysisJob != null || disposed || !scope.isActive || !viewport.needsAnalysis) return
        val requestEpoch = epoch
        val requestDetector = detector
        // Lazy start assigns the slot before even an immediate dispatcher can finish the coroutine.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val requestRevision = frameRevision
                var completed = false
                try {
                    if (requestEpoch != epoch) return@launch
                    val lines = viewport.pendingLines(requestDetector.context)
                    if (lines.isEmpty()) return@launch
                    val request = detectionRequest(lines)
                    val result =
                        withContext(analysisDispatcher) {
                            val sink = TerminalHyperlinkDetectionAccumulator(lines, requestDetector.context)
                            try {
                                requestDetector.detect(request, sink)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Host discovery is best-effort; retry only after the text changes.
                                return@withContext List(lines.size) { emptyList<TerminalDetectedHyperlink>() }
                            }
                            ensureActive()
                            sink.links
                        }
                    if (!disposed && requestEpoch == epoch) {
                        viewport.accept(lines, result, requestDetector.context)
                        publishOverlay()
                    }
                    completed = true
                } finally {
                    analysisJob = null
                    if (!disposed &&
                        scope.isActive &&
                        (completed || requestEpoch != epoch || requestRevision != frameRevision)
                    ) {
                        startAnalysis()
                    }
                }
            }
        analysisJob = job
        job.start()
    }

    private fun publishOverlay() {
        viewport.writeOverlay(host.renderCache, repaintSpan)
        host.hyperlinksChanged()
    }
}

internal fun detectionRequest(lines: List<TerminalHyperlinkLineSnapshot>): SwingHyperlinkDetectionRequest {
    var offset = 0
    val starts = IntArray(lines.size)
    val ends = IntArray(lines.size)
    val text =
        Array(lines.size) { index ->
            starts[index] = offset
            offset += lines[index].text.length
            ends[index] = offset
            lines[index].text
        }
    return SwingHyperlinkDetectionRequest(text, starts, ends)
}

private class TerminalHyperlinkDetectionAccumulator(
    private val lines: List<TerminalHyperlinkLineSnapshot>,
    private val context: SwingHyperlinkDetectionContext,
) : SwingHyperlinkDetectionSink {
    val links = List(lines.size) { ArrayList<TerminalDetectedHyperlink>() }

    override fun addHyperlink(
        lineIndex: Int,
        startOffset: Int,
        endOffset: Int,
        action: SwingHyperlinkAction,
        validationStartOffset: Int,
        validationEndOffset: Int,
    ) {
        if (lineIndex !in lines.indices) return
        val text = lines[lineIndex].text
        if (startOffset !in text.indices || endOffset <= startOffset || endOffset > text.length) return
        val start = startOffset
        val end = minOf(endOffset, text.length - 1)
        if (start == end) return
        val defaultContext = validationEndOffset == Int.MAX_VALUE
        val validationEnd = if (defaultContext) text.length else validationEndOffset
        if (validationStartOffset !in 0..start || validationEnd !in end..text.length) return
        links[lineIndex] +=
            TerminalDetectedHyperlink(
                start,
                end,
                action,
                if (defaultContext) 0 else validationStartOffset,
                validationEnd,
                viewportDependent = context == SwingHyperlinkDetectionContext.VIEWPORT && defaultContext,
            )
    }
}

internal class TerminalDetectedHyperlink(
    val startOffset: Int,
    val endOffset: Int,
    val action: SwingHyperlinkAction,
    val validationStartOffset: Int,
    val validationEndOffset: Int,
    val viewportDependent: Boolean = false,
) {
    fun remainsValid(
        previousText: String,
        currentText: String,
    ): Boolean =
        validationEndOffset <= currentText.length &&
            previousText.regionMatches(
                validationStartOffset,
                currentText,
                validationStartOffset,
                validationEndOffset - validationStartOffset,
            )
}

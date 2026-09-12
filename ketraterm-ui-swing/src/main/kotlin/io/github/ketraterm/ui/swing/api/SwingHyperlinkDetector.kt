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

/**
 * Host-provided visible-viewport hyperlink detector.
 *
 * The reusable Swing terminal calls this outside painting and mouse movement,
 * after visible render-cache text has been snapshotted. Implementations may
 * allocate and may call host/IDE link-discovery APIs, but must not touch Swing
 * component state directly. Reported offsets are UTF-16 offsets within the
 * supplied line text.
 *
 * [context] declares whether detection depends on individual logical lines or
 * the complete visible viewport. Detectors must not depend on earlier requests.
 */
fun interface SwingHyperlinkDetector {
    /** Text context required by detection and its actions, fixed for this detector's lifetime. */
    val context: SwingHyperlinkDetectionContext
        get() = SwingHyperlinkDetectionContext.LOGICAL_LINE

    /**
     * Detects hyperlinks in [request] and reports them to [sink].
     *
     * Implementations are called on a background worker owned by the Swing
     * terminal. Host frameworks that require read locks or application
     * dispatching should acquire them inside this method.
     *
     * @param request immutable visible-viewport text snapshot.
     * @param sink receiver for detected ranges and activation actions.
     * @throws java.util.concurrent.CancellationException when detection is
     * cancelled; an interrupted attempt must not be cached as having no links.
     */
    fun detect(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
    )

    companion object {
        /**
         * Detector that reports no links.
         */
        @JvmField
        val NONE: SwingHyperlinkDetector = SwingHyperlinkDetector { _, _ -> }
    }
}

/** Text dependency used to schedule detection and validate cached results. */
enum class SwingHyperlinkDetectionContext {
    /**
     * Each logical line is independent. Requests may contain only changed
     * lines; results must not depend on neighboring lines or request positions.
     */
    LOGICAL_LINE,

    /**
     * Detection requires every visible logical line in viewport order. Results
     * depend on that complete text and order unless they declare an explicit
     * validation range through [SwingHyperlinkDetectionSink.addHyperlink].
     */
    VIEWPORT,
}

/**
 * Immutable visible terminal text snapshot passed to [SwingHyperlinkDetector].
 *
 * Lines are logical terminal lines: soft-wrapped render rows are joined, and a
 * line separator is appended to each line to match IntelliJ-style console
 * filter contracts. Offsets returned by [lineStartOffset] and [lineEndOffset]
 * are cumulative UTF-16 offsets across the supplied lines in this request;
 * they are not offsets in the terminal's complete output.
 */
class SwingHyperlinkDetectionRequest internal constructor(
    private val lines: Array<String>,
    private val lineStartOffsets: IntArray,
    private val lineEndOffsets: IntArray,
) {
    /**
     * Number of logical lines in this visible snapshot.
     */
    val lineCount: Int
        get() = lines.size

    /**
     * Returns the logical line text at [index].
     *
     * @param index zero-based logical line index.
     * @return line text, including a trailing line separator.
     */
    fun lineText(index: Int): String = lines[index]

    /**
     * Returns the cumulative UTF-16 start offset for logical line [index].
     *
     * @param index zero-based logical line index.
     * @return inclusive line start offset.
     */
    fun lineStartOffset(index: Int): Int = lineStartOffsets[index]

    /**
     * Returns the cumulative UTF-16 end offset for logical line [index].
     *
     * @param index zero-based logical line index.
     * @return exclusive line end offset.
     */
    fun lineEndOffset(index: Int): Int = lineEndOffsets[index]
}

/**
 * Receives detected visible-viewport hyperlink ranges.
 */
interface SwingHyperlinkDetectionSink {
    /**
     * Adds a detected hyperlink.
     *
     * By default the result is valid only while its detector's [SwingHyperlinkDetector.context]
     * is unchanged: the logical line or the complete visible viewport. A
     * detector may supply an explicit validation range when the result depends
     * on only part of one line. That range must contain the highlight
     * and every character affecting detection or the action, including token
     * delimiters. Include the trailing line separator when the result depends
     * on the end of the line, so appended text invalidates it.
     *
     * Invalid ranges, including validation ranges that do not contain the
     * highlight, are ignored.
     *
     * @param lineIndex logical line index from the detection request.
     * @param startOffset inclusive UTF-16 offset within the line text.
     * @param endOffset exclusive UTF-16 offset within the line text.
     * @param action host-owned action invoked after explicit user activation.
     * @param validationStartOffset inclusive UTF-16 offset of the text that
     * determines this result; defaults to the start of the logical line.
     * @param validationEndOffset exclusive UTF-16 offset of that text, including
     * any required boundary characters. [Int.MAX_VALUE] retains the detector's
     * default context dependency; explicit ranges use a concrete line offset.
     */
    fun addHyperlink(
        lineIndex: Int,
        startOffset: Int,
        endOffset: Int,
        action: SwingHyperlinkAction,
        validationStartOffset: Int = 0,
        validationEndOffset: Int = Int.MAX_VALUE,
    )
}

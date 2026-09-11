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
package io.github.ketraterm.render.api

/**
 * Receives grapheme cluster text while a row is copied.
 */
fun interface TerminalRenderClusterSink {
    /**
     * Called during `copyLine()` for a [TerminalRenderCellFlags.CLUSTER] cell.
     *
     * [column] is the logical column of the cluster-leading cell in the copied
     * row. [text] is the full Unicode grapheme cluster. The text is only guaranteed
     * to be valid for the duration of the surrounding render frame callback unless an
     * implementation documents a longer lifetime.
     *
     * @param column zero-based logical column of the cluster-leading cell in the copied row.
     * @param text full Unicode grapheme cluster text.
     */
    fun onCluster(
        column: Int,
        text: String,
    )
}

/**
 * Receives grapheme cluster code points while a row is copied.
 *
 * This is the allocation-conscious cluster handoff for render caches. The
 * supplied code-point range is valid only for the duration of the callback;
 * receivers that retain it must copy the primitive range into their own
 * storage before returning.
 */
fun interface TerminalRenderClusterDataSink {
    /**
     * Called during `copyLine()` for a [TerminalRenderCellFlags.CLUSTER] cell.
     *
     * @param column zero-based logical column of the cluster-leading cell in the copied row.
     * @param codepoints source code point buffer.
     * @param offset zero-based buffer index of the cluster's first code point in [codepoints].
     * @param length number of code points in the cluster.
     */
    fun onCluster(
        column: Int,
        codepoints: IntArray,
        offset: Int,
        length: Int,
    )
}

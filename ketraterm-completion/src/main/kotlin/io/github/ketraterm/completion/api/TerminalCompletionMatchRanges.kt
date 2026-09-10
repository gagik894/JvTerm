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
 * Immutable ordered match ranges relative to one completion display string.
 *
 * Ranges use UTF-16 offsets and are stored as packed primitive pairs internally.
 * Every range is non-empty, ranges are ordered and non-overlapping, and endpoints
 * are Unicode-scalar boundaries that do not split surrogate pairs. The empty
 * instance is shared; non-empty factories defensively copy caller-owned arrays.
 */
class TerminalCompletionMatchRanges private constructor(
    private val packedOffsets: IntArray,
) {
    private val contentHashCode = packedOffsets.contentHashCode()

    /** Number of `[start, end)` ranges. */
    val rangeCount: Int
        get() = packedOffsets.size ushr 1

    /** Returns whether no display fragment is matched. */
    fun isEmpty(): Boolean = packedOffsets.isEmpty()

    /**
     * Returns the inclusive UTF-16 start offset of range [index].
     *
     * @throws IndexOutOfBoundsException if [index] is outside `0 until rangeCount`.
     */
    fun startOffset(index: Int): Int = packedOffsets[checkedPackedIndex(index)]

    /**
     * Returns the exclusive UTF-16 end offset of range [index].
     *
     * @throws IndexOutOfBoundsException if [index] is outside `0 until rangeCount`.
     */
    fun endOffset(index: Int): Int = packedOffsets[checkedPackedIndex(index) + 1]

    /**
     * Returns an independent packed `[start0, end0, ...]` array.
     *
     * This is an explicit interoperability copy; painting and ranking code should
     * prefer indexed access to avoid allocation.
     */
    fun copyPackedOffsets(): IntArray = packedOffsets.copyOf()

    internal fun requireValidFor(displayText: String) {
        validate(displayText, packedOffsets)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TerminalCompletionMatchRanges && packedOffsets.contentEquals(other.packedOffsets)

    override fun hashCode(): Int = contentHashCode

    override fun toString(): String = "TerminalCompletionMatchRanges${packedOffsets.contentToString()}"

    private fun checkedPackedIndex(index: Int): Int {
        if (index !in 0 until rangeCount) {
            throw IndexOutOfBoundsException("range index must be in 0 until $rangeCount, was $index")
        }
        return index shl 1
    }

    companion object {
        /** Shared immutable empty range set. */
        @JvmField
        val EMPTY: TerminalCompletionMatchRanges = TerminalCompletionMatchRanges(IntArray(0))

        /**
         * Creates immutable ranges for [displayText] from packed UTF-16 [offsets].
         *
         * @param displayText text whose offsets the ranges address.
         * @param offsets packed `[start0, end0, ...]` offsets; the array is copied.
         * @return [EMPTY] for an empty array, otherwise an immutable validated range set.
         * @throws IllegalArgumentException if offsets are incomplete, empty,
         * unordered, overlapping, outside [displayText], or split a surrogate pair.
         */
        @JvmStatic
        fun fromPackedOffsets(
            displayText: String,
            offsets: IntArray,
        ): TerminalCompletionMatchRanges {
            validate(displayText, offsets)
            return if (offsets.isEmpty()) EMPTY else TerminalCompletionMatchRanges(offsets.copyOf())
        }

        internal fun fromOwnedPackedOffsets(
            displayText: String,
            offsets: IntArray,
        ): TerminalCompletionMatchRanges {
            validate(displayText, offsets)
            return if (offsets.isEmpty()) EMPTY else TerminalCompletionMatchRanges(offsets)
        }

        private fun validate(
            displayText: String,
            offsets: IntArray,
        ) {
            require((offsets.size and 1) == 0) { "match offsets must contain complete start/end pairs" }
            var previousEnd = 0
            var packedIndex = 0
            while (packedIndex < offsets.size) {
                val start = offsets[packedIndex]
                val end = offsets[packedIndex + 1]
                require(start >= previousEnd) {
                    "match ranges must be ordered and non-overlapping, range ${packedIndex ushr 1} starts at $start before $previousEnd"
                }
                require(end > start) {
                    "match range ${packedIndex ushr 1} must be non-empty, was [$start, $end)"
                }
                require(end <= displayText.length) {
                    "match range ${packedIndex ushr 1} ends at $end beyond displayText length ${displayText.length}"
                }
                require(displayText.isScalarBoundary(start)) {
                    "match range ${packedIndex ushr 1} start $start splits a UTF-16 surrogate pair"
                }
                require(displayText.isScalarBoundary(end)) {
                    "match range ${packedIndex ushr 1} end $end splits a UTF-16 surrogate pair"
                }
                previousEnd = end
                packedIndex += 2
            }
        }

        private fun String.isScalarBoundary(offset: Int): Boolean =
            offset in 0..length &&
                (
                    offset == 0 ||
                        offset == length ||
                        !Character.isHighSurrogate(this[offset - 1]) ||
                        !Character.isLowSurrogate(this[offset])
                )
    }
}

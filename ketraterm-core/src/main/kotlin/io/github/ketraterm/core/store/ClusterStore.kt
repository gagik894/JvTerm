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
package io.github.ketraterm.core.store

import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.store.ClusterStore.Companion.LIVE_SLOT
import io.github.ketraterm.core.store.ClusterStore.Companion.NO_FREE
import java.util.*

/**
 * A buffer-scoped arena allocator for multi-codepoint grapheme cluster payloads.
 *
 * - The cell's [IntArray] slot holds a negative **handle** (`<= -2`).
 * - The handle encodes a slot index: `slot = -(handle + 2)`.
 * - This store maps slot → a contiguous run of codepoints in a flat data pool.
 *
 * ## Memory layout
 *
 * clusterData : [ cp0 | cp1 | cp2 | cp0 | cp1 | cp2 | cp3 | ... ]
 *                 \_slot 0_/        \______slot 1_________/
 * slotStarts  : [ 0,  3, ... ]
 * slotLengths : [ 3,  4, ... ]
 *
 * ## Lifecycle
 *
 * A [ClusterStore] is owned by a single [io.github.ketraterm.core.buffer.HistoryRing].
 * When the terminal resizes, the resizer creates a fresh [ClusterStore] for the
 * new ring. Clusters that survive reflow are deep-copied into the new store.
 *
 * ## Freelist
 *
 * Individual slots are returned via [free] and chained into an O(1) singly-linked
 * freelist. Slots retain their data regions for their entire lifetime. Allocation
 * reuses a sufficiently large slot or creates a new one; smaller free regions
 * remain available. Capacities are exact for lengths 1–4 and rounded up to powers
 * of two thereafter. Small allocations preserve the larger capacity classes.
 * Data is not zeroed on free; it is
 * simply overwritten on the next [alloc]. Double-free is rejected: once a slot
 * has been returned to the freelist, a second [free] of the same live handle
 * throws [IllegalStateException] instead of silently corrupting allocator state.
 *
 * ## Thread safety
 *
 * Not thread-safe. All access must be confined to the terminal's write thread.
 */
internal class ClusterStore {
    // Constants
    companion object {
        /** Initial number of cluster slots. Grows by doubling. */
        private const val INITIAL_SLOT_CAPACITY = 64

        /** Initial size of the flat codepoint data pool. Grows by doubling. */
        private const val INITIAL_DATA_CAPACITY = 256

        /** Sentinel meaning "no next free slot". */
        private const val NO_FREE = -1

        /** Marks a slot that owns a live payload instead of linking a free slot. */
        private const val LIVE_SLOT = -2

        /**
         * Handle encoding bias.
         * handle = -(slot + 2)  →  slot = -(handle + 2)
         * First valid handle is -2, keeping -1 free for WIDE_CHAR_SPACER.
         */
        private const val BIAS = 2
    }

    // Slot metadata — parallel arrays, indexed by slot number

    /** Index into [clusterData] where each slot's payload begins. */
    private var slotStarts = IntArray(INITIAL_SLOT_CAPACITY)

    /** Number of codepoints stored in each slot. */
    private var slotLengths = IntArray(INITIAL_SLOT_CAPACITY)

    /** Maximum capacity of codepoints allocated to each slot. */
    private var slotCapacities = IntArray(INITIAL_SLOT_CAPACITY)

    /**
     * Freelist linkage, or [LIVE_SLOT] while the slot owns a live payload.
     * For a freed slot, stores the index of the next free slot, or [NO_FREE].
     */
    private var nextFree = IntArray(INITIAL_SLOT_CAPACITY) { NO_FREE }

    // Flat codepoint pool

    /** Contiguous pool of all cluster codepoints from all live slots. */
    private var clusterData = IntArray(INITIAL_DATA_CAPACITY)

    /** Next free write position in [clusterData]. Never decreases (no compaction). */
    private var dataSize = 0

    // Allocation state

    /** High-water mark: total number of slots ever allocated. */
    private var slotCount = 0

    /** Heads of the O(1) segregated freelists. */
    private val freeHeads = IntArray(33) { NO_FREE }

    // Public API — allocation

    /**
     * Allocates a new slot for the cluster defined by [codepoints][offset..offset+length)
     * and returns its **handle** — a negative [Int] that can be stored directly in
     * a [io.github.ketraterm.core.model.Line]'s codepoint array.
     *
     * The codepoints are copied into the internal pool via [System.arraycopy];
     * the caller may safely reuse or discard the source array immediately.
     *
     * @param codepoints Source array of codepoints.
     * @param offset     Index of the first codepoint in [codepoints].
     * @param length     Number of codepoints to copy. Must be >= 1.
     * @return A negative handle (`<= -2`) encoding the allocated slot.
     */
    fun alloc(
        codepoints: IntArray,
        offset: Int = 0,
        length: Int = codepoints.size,
    ): Int {
        require(length >= 1) { "cluster must have at least 1 codepoint, got $length" }
        Objects.checkFromIndexSize(offset, length, codepoints.size)

        val bucket = bucketForCapacity(length)
        var slot = NO_FREE

        val lastBucket = if (length <= 4) 3 else freeHeads.lastIndex
        for (b in bucket..lastBucket) {
            slot = popSlot(b)
            if (slot != NO_FREE) break
        }

        if (slot == NO_FREE) {
            val capacity = if (bucket < 4) bucket + 1 else (1L shl (bucket - 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val start = reserveData(capacity)
            slot = acquireNewSlot()
            slotStarts[slot] = start
            slotCapacities[slot] = capacity
        }

        System.arraycopy(codepoints, offset, clusterData, slotStarts[slot], length)
        slotLengths[slot] = length
        nextFree[slot] = LIVE_SLOT
        return encodeHandle(slot)
    }

    /**
     * Returns the cluster handle back to the freelist.
     *
     * Calling [free] with a non-cluster value (`>= -1`) is a no-op, making it safe
     * to call unconditionally on any raw cell value.
     *
     * @param handle The handle previously returned by [alloc], or any non-cluster value.
     */
    fun free(handle: Int) {
        if (handle > TerminalConstants.CLUSTER_HANDLE_MAX) return // EMPTY, codepoint, or SPACER
        val slot = decodeSlot(handle)
        if (nextFree[slot] != LIVE_SLOT) {
            throw IllegalStateException("Cluster handle $handle was freed more than once")
        }

        val bucket = bucketForCapacity(slotCapacities[slot])
        nextFree[slot] = freeHeads[bucket]
        freeHeads[bucket] = slot
    }

    /**
     * Bulk-frees all cluster handles found in [array] between [fromIndex] (inclusive)
     * and [toIndex] (exclusive). Non-cluster values are skipped silently.
     *
     * Called by [io.github.ketraterm.core.model.Line] mutation methods ([io.github.ketraterm.core.model.Line.clear], [io.github.ketraterm.core.model.Line.clearFromColumn], etc.)
     * whenever a range of cells is about to be overwritten or discarded.
     *
     * @param array     The raw codepoint array of a [io.github.ketraterm.core.model.Line].
     * @param fromIndex Start of the range to sweep (inclusive).
     * @param toIndex   End of the range to sweep (exclusive).
     */
    fun freeRange(
        array: IntArray,
        fromIndex: Int,
        toIndex: Int,
    ) {
        for (i in fromIndex until toIndex) {
            val v = array[i]
            if (v <= TerminalConstants.CLUSTER_HANDLE_MAX) free(v)
        }
    }

    // Public API — zero-allocation accessors (safe on the hot render path)

    /**
     * Returns the number of codepoints stored in the cluster at [handle].
     *
     * @param handle A valid cluster handle returned by [alloc].
     */
    fun length(handle: Int): Int = slotLengths[decodeSlot(handle)]

    /**
     * Returns the codepoint at position [index] within the cluster at [handle].
     * O(1), zero allocation.
     *
     * @param handle A valid cluster handle returned by [alloc].
     * @param index  Position within the cluster (0-based).
     */
    fun codepointAt(
        handle: Int,
        index: Int,
    ): Int {
        val slot = decodeSlot(handle)
        val length = slotLengths[slot]
        if (index !in 0..<length) {
            throw IndexOutOfBoundsException(
                "index $index out of bounds for cluster of length $length",
            )
        }
        return clusterData[slotStarts[slot] + index]
    }

    /**
     * Convenience shortcut for the first (base) codepoint of a cluster.
     * This is the codepoint used by simple renderers that only need the leading glyph.
     *
     * @param handle A valid cluster handle returned by [alloc].
     */
    fun baseCodepoint(handle: Int): Int = codepointAt(handle, 0)

    /**
     * Copies all codepoints of the cluster at [handle] into [dest] starting at
     * [destOffset] and returns the number of codepoints written.
     *
     * **This is the canonical zero-allocation handoff to the renderer.**
     * Callers should allocate a reusable destination array large enough for the
     * clusters they expect to render and pass it here on every frame. No heap
     * allocation occurs.
     *
     * @param handle     A valid cluster handle returned by [alloc].
     * @param dest       Destination array. Must have capacity >= [length(handle)].
     * @param destOffset Starting index in [dest].
     * @return Number of codepoints written into [dest].
     */
    fun readInto(
        handle: Int,
        dest: IntArray,
        destOffset: Int = 0,
    ): Int {
        val slot = decodeSlot(handle)
        val start = slotStarts[slot]
        val length = slotLengths[slot]
        if (destOffset < 0 || destOffset + length > dest.size) {
            throw IndexOutOfBoundsException(
                "destOffset $destOffset + length $length exceeds dest.size ${dest.size}",
            )
        }
        System.arraycopy(clusterData, start, dest, destOffset, length)
        return length
    }

    // Private helpers

    /** Exact classes for 1–4, then 8, 16, ... with a final Int.MAX_VALUE class. */
    private fun bucketForCapacity(capacity: Int): Int = if (capacity <= 4) capacity - 1 else 33 - Integer.numberOfLeadingZeros(capacity - 1)

    /** Pops a slot index from a specific bucket, returning [NO_FREE] if empty. */
    private fun popSlot(bucket: Int): Int {
        val slot = freeHeads[bucket]
        if (slot != NO_FREE) {
            freeHeads[bucket] = nextFree[slot]
            return slot
        }
        return NO_FREE
    }

    /** Returns a brand new slot index, growing parallel slot tables if full. */
    private fun acquireNewSlot(): Int {
        if (slotCount == slotStarts.size) growSlots()
        return slotCount++
    }

    /** Reserves [length] positions in [clusterData] and returns the start index. */
    private fun reserveData(length: Int): Int {
        if (length > Int.MAX_VALUE - dataSize) throw OutOfMemoryError("Cluster data exceeds maximum array size")
        if (dataSize + length > clusterData.size) growData(length)
        val start = dataSize
        dataSize += length
        return start
    }

    private fun growSlots() {
        val newCap = slotStarts.size * 2
        slotStarts = slotStarts.copyOf(newCap)
        slotLengths = slotLengths.copyOf(newCap)
        slotCapacities = slotCapacities.copyOf(newCap)
        val grown = nextFree.copyOf(newCap)
        for (i in slotCount until newCap) grown[i] = NO_FREE
        nextFree = grown
    }

    private fun growData(needed: Int) {
        val newCap = maxOf(clusterData.size.toLong() * 2, (dataSize + needed).toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        clusterData = clusterData.copyOf(newCap)
    }

    /** Encodes a slot index as a negative handle: handle = -(slot + BIAS). */
    private fun encodeHandle(slot: Int): Int = -(slot + BIAS)

    /** Decodes a negative handle back to its slot index: slot = -(handle + BIAS). */
    private fun decodeSlot(handle: Int): Int = -(handle + BIAS)
}

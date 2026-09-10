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

import io.github.ketraterm.core.model.Line
import io.github.ketraterm.core.model.TerminalConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ClusterStore Test Suite")
class ClusterStoreTest {
    // -------------------------------------------------------------------------
    // Shared assertion helper
    // -------------------------------------------------------------------------

    private fun assertCluster(
        store: ClusterStore,
        handle: Int,
        expected: IntArray,
    ) {
        assertEquals(expected.size, store.length(handle), "Cluster length mismatch")
        assertEquals(expected[0], store.baseCodepoint(handle), "Base codepoint mismatch")
        for (i in expected.indices) {
            assertEquals(expected[i], store.codepointAt(handle, i), "Codepoint mismatch at index $i")
        }
    }

    // =========================================================================
    // alloc()
    // =========================================================================

    @Nested
    @DisplayName("alloc()")
    inner class AllocationTests {
        @Test
        fun `alloc returns negative handles starting from -2`() {
            val store = ClusterStore()
            val h0 = store.alloc(intArrayOf('A'.code))
            val h1 = store.alloc(intArrayOf('B'.code))
            val h2 = store.alloc(intArrayOf('C'.code))

            assertAll(
                { assertEquals(-2, h0, "First handle must be -2") },
                { assertEquals(-3, h1, "Second handle must be -3") },
                { assertEquals(-4, h2, "Third handle must be -4") },
            )
        }

        @Test
        fun `alloc never returns -1 which is reserved for WIDE_CHAR_SPACER`() {
            val store = ClusterStore()
            // Alloc enough clusters that if encoding were off-by-one, handle -1 would appear.
            val handles = (0 until 10).map { store.alloc(intArrayOf(it)) }
            handles.forEach { h ->
                assertNotEquals(
                    TerminalConstants.WIDE_CHAR_SPACER,
                    h,
                    "Handle $h collides with WIDE_CHAR_SPACER sentinel",
                )
            }
        }

        @Test
        fun `alloc never returns 0 which is EMPTY`() {
            val store = ClusterStore()
            val handles = (0 until 10).map { store.alloc(intArrayOf(it)) }
            handles.forEach { h ->
                assertNotEquals(
                    TerminalConstants.EMPTY,
                    h,
                    "Handle $h collides with EMPTY sentinel",
                )
            }
        }

        @Test
        fun `alloc copies source payload so caller can mutate original array`() {
            val store = ClusterStore()
            val source = intArrayOf(0x1F469, 0x200D, 0x1F680)
            val handle = store.alloc(source)
            source[0] = 'X'.code
            source[1] = 'Y'.code
            source[2] = 'Z'.code

            assertCluster(store, handle, intArrayOf(0x1F469, 0x200D, 0x1F680))
        }

        @Test
        fun `alloc honors offset and length slice`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(10, 20, 30, 40, 50), offset = 1, length = 3)

            assertCluster(store, handle, intArrayOf(20, 30, 40))
        }

        @Test
        fun `alloc with offset at last valid position stores single element`() {
            val store = ClusterStore()
            val source = intArrayOf(10, 20, 30)
            // offset=2, length=1 — reads only source[2]
            val handle = store.alloc(source, offset = 2, length = 1)

            assertCluster(store, handle, intArrayOf(30))
        }

        @Test
        fun `alloc single-codepoint cluster stores and retrieves correctly`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(0x1F600))

            assertAll(
                { assertEquals(1, store.length(handle)) },
                { assertEquals(0x1F600, store.baseCodepoint(handle)) },
                { assertEquals(0x1F600, store.codepointAt(handle, 0)) },
            )
        }

        @Test
        fun `alloc maximum realistic cluster of 16 codepoints stores all correctly`() {
            val store = ClusterStore()
            val payload = IntArray(16) { 0x1000 + it }
            val handle = store.alloc(payload)

            assertEquals(16, store.length(handle))
            for (i in 0 until 16) {
                assertEquals(
                    0x1000 + i,
                    store.codepointAt(handle, i),
                    "Codepoint mismatch at index $i",
                )
            }
        }

        @Test
        fun `alloc rejects zero-length cluster`() {
            val store = ClusterStore()
            assertThrows(IllegalArgumentException::class.java) {
                store.alloc(intArrayOf(1, 2, 3), offset = 0, length = 0)
            }
        }

        @Test
        fun `alloc throws for negative offset`() {
            val store = ClusterStore()
            assertThrows(IndexOutOfBoundsException::class.java) {
                store.alloc(intArrayOf(1, 2, 3), offset = -1, length = 2)
            }
        }

        @Test
        fun `alloc throws when slice exceeds array bounds`() {
            val store = ClusterStore()
            assertThrows(IndexOutOfBoundsException::class.java) {
                store.alloc(intArrayOf(1, 2, 3), offset = 1, length = 5)
            }
        }

        @Test
        fun `alloc throws when offset is exactly at array size`() {
            val store = ClusterStore()
            assertThrows(IndexOutOfBoundsException::class.java) {
                store.alloc(intArrayOf(1, 2, 3), offset = 3, length = 1)
            }
        }

        @Test
        fun `multiple allocs store independent payloads without cross-contamination`() {
            val store = ClusterStore()
            val h0 = store.alloc(intArrayOf(0xAAAA, 0xBBBB))
            val h1 = store.alloc(intArrayOf(0xCCCC, 0xDDDD, 0xEEEE))
            val h2 = store.alloc(intArrayOf(0xFFFF))

            assertCluster(store, h0, intArrayOf(0xAAAA, 0xBBBB))
            assertCluster(store, h1, intArrayOf(0xCCCC, 0xDDDD, 0xEEEE))
            assertCluster(store, h2, intArrayOf(0xFFFF))
        }
    }

    // =========================================================================
    // free() and freeRange()
    // =========================================================================

    @Nested
    @DisplayName("free() and freeRange()")
    inner class FreeingTests {
        @Test
        fun `free is no-op for EMPTY`() {
            val store = ClusterStore()
            store.free(TerminalConstants.EMPTY)
            assertEquals(-2, store.alloc(intArrayOf(1)), "Store state must be pristine after no-op free")
        }

        @Test
        fun `free is no-op for WIDE_CHAR_SPACER`() {
            val store = ClusterStore()
            store.free(TerminalConstants.WIDE_CHAR_SPACER)
            assertEquals(-2, store.alloc(intArrayOf(1)))
        }

        @Test
        fun `free is no-op for positive codepoint values`() {
            val store = ClusterStore()
            store.free('A'.code)
            store.free(0x1F600)
            assertEquals(-2, store.alloc(intArrayOf(1)))
        }

        @Test
        fun `free returns slot to freelist and next alloc reuses that slot`() {
            val store = ClusterStore()
            val original = store.alloc(intArrayOf(1, 2, 3))
            store.free(original)
            val reused = store.alloc(intArrayOf(9))

            assertEquals(original, reused, "Freed slot must be reused")
            assertCluster(store, reused, intArrayOf(9))
        }

        @Test
        fun `free same handle twice throws`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(1, 2, 3))

            store.free(handle)

            val error =
                assertThrows(IllegalStateException::class.java) {
                    store.free(handle)
                }

            assertTrue(error.message!!.contains(handle.toString()))
        }

        @Test
        fun `double free of head and tail after growth preserves reuse and live payloads`() {
            val store = ClusterStore()
            val handles = IntArray(130) { store.alloc(intArrayOf(it, it + 1)) }
            val tail = handles.first()
            val head = handles.last()
            store.free(tail)
            store.free(head)

            assertThrows(IllegalStateException::class.java) { store.free(tail) }
            assertThrows(IllegalStateException::class.java) { store.free(head) }

            assertEquals(head, store.alloc(intArrayOf(500, 501)))
            assertEquals(tail, store.alloc(intArrayOf(600, 601)))
            assertCluster(store, head, intArrayOf(500, 501))
            assertCluster(store, tail, intArrayOf(600, 601))
            for (i in 1 until handles.lastIndex) {
                assertCluster(store, handles[i], intArrayOf(i, i + 1))
            }

            assertDoesNotThrow {
                store.free(head)
                store.free(tail)
            }
        }

        @Test
        fun `free preserves all other live handles`() {
            val store = ClusterStore()
            val h0 = store.alloc(intArrayOf(10, 11))
            val h1 = store.alloc(intArrayOf(20, 21))
            val h2 = store.alloc(intArrayOf(30, 31))

            store.free(h1)

            assertCluster(store, h0, intArrayOf(10, 11))
            assertCluster(store, h2, intArrayOf(30, 31))
        }

        @Test
        fun `freelist is LIFO - last freed slot is allocated first`() {
            val store = ClusterStore()
            val h0 = store.alloc(intArrayOf(1))
            val h1 = store.alloc(intArrayOf(2))
            val h2 = store.alloc(intArrayOf(3))

            store.free(h0)
            store.free(h1)
            store.free(h2)

            // h2 freed last, must come out first
            assertEquals(h2, store.alloc(intArrayOf(100)))
            assertEquals(h1, store.alloc(intArrayOf(101)))
            assertEquals(h0, store.alloc(intArrayOf(102)))
        }

        @Test
        fun `alloc after all slots freed does not grow slot table`() {
            val store = ClusterStore()
            val handles = IntArray(5) { store.alloc(intArrayOf(it)) }
            handles.forEach { store.free(it) }

            // Re-alloc 5 — must reuse freed slots, not grow
            val reallocs = IntArray(5) { store.alloc(intArrayOf(100 + it)) }

            // Next fresh slot should still be at high-water mark 5 → handle -(5+2) = -7
            val nextFresh = store.alloc(intArrayOf(999))
            assertEquals(
                -7,
                nextFresh,
                "Slot table must not have grown beyond high-water mark of 5",
            )

            // All re-allocated handles contain correct new data
            for (i in reallocs.indices) {
                assertCluster(store, reallocs[i], intArrayOf(100 + i))
            }
        }

        @Test
        fun `freeRange only frees handles within range in LIFO order`() {
            val store = ClusterStore()
            val h0 = store.alloc(intArrayOf(100))
            val h1 = store.alloc(intArrayOf(200))
            val h2 = store.alloc(intArrayOf(300))

            val line = intArrayOf('A'.code, h0, TerminalConstants.WIDE_CHAR_SPACER, h1, TerminalConstants.EMPTY, h2)
            store.freeRange(line, fromIndex = 1, toIndex = 5) // frees h0 and h1, skips non-handles

            val r1 = store.alloc(intArrayOf(901))
            val r2 = store.alloc(intArrayOf(902))
            val nextFresh = store.alloc(intArrayOf(903))

            assertAll(
                { assertEquals(h1, r1, "Last freed (h1) must be reused first") },
                { assertEquals(h0, r2, "Then h0 must be reused") },
                { assertEquals(-5, nextFresh, "Next fresh slot must follow high-water mark") },
                { assertCluster(store, r1, intArrayOf(901)) },
                { assertCluster(store, r2, intArrayOf(902)) },
                { assertCluster(store, h2, intArrayOf(300)) }, // h2 untouched
            )
        }

        @Test
        fun `freeRange honors exclusive upper bound`() {
            val store = ClusterStore()
            val h0 = store.alloc(intArrayOf(1))
            val h1 = store.alloc(intArrayOf(2))
            val h2 = store.alloc(intArrayOf(3))
            val arr = intArrayOf(h0, h1, h2)

            store.freeRange(arr, fromIndex = 0, toIndex = 2) // frees h0, h1 only

            val r1 = store.alloc(intArrayOf(10))
            val r2 = store.alloc(intArrayOf(11))
            val r3 = store.alloc(intArrayOf(12))

            assertAll(
                { assertEquals(h1, r1) },
                { assertEquals(h0, r2) },
                { assertEquals(-5, r3, "r3 must be a fresh slot since h2 was not freed") },
                { assertCluster(store, h2, intArrayOf(3)) },
            )
        }

        @Test
        fun `freeRange does not double free live shifted handles`() {
            val store = ClusterStore()
            val line = Line(width = 4, store = store)
            val attr = 0L

            line.setCluster(0, intArrayOf(10, 11), 2, attr)
            line.setCluster(1, intArrayOf(20, 21), 2, attr)
            line.setCluster(2, intArrayOf(30, 31), 2, attr)
            val survivingHandle = line.rawCodepoint(2)

            assertDoesNotThrow {
                line.deleteCells(col = 0, count = 2, defaultAttr = attr)
                line.clear(attr)
            }

            val reused = IntArray(3) { store.alloc(intArrayOf(100 + it, 200 + it)) }

            assertAll(
                { assertEquals(survivingHandle, reused[0], "Shifted live handle should be freed exactly once") },
                { assertCluster(store, reused[0], intArrayOf(100, 200)) },
                { assertCluster(store, reused[1], intArrayOf(101, 201)) },
                { assertCluster(store, reused[2], intArrayOf(102, 202)) },
            )
        }

        @Test
        fun `freeRange on empty range is a no-op`() {
            val store = ClusterStore()
            val h = store.alloc(intArrayOf(42))
            val arr = intArrayOf(h)

            store.freeRange(arr, fromIndex = 0, toIndex = 0) // empty range

            // Handle must still be alive
            assertCluster(store, h, intArrayOf(42))
        }

        @Test
        fun `freeRange on array with no cluster handles is a no-op`() {
            val store = ClusterStore()
            val h = store.alloc(intArrayOf(1))
            val arr =
                intArrayOf(
                    TerminalConstants.EMPTY,
                    TerminalConstants.WIDE_CHAR_SPACER,
                    'A'.code,
                    0x1F600,
                )

            store.freeRange(arr, fromIndex = 0, toIndex = arr.size)

            // h was never in arr, must still be live
            assertCluster(store, h, intArrayOf(1))
            // Next alloc must NOT reuse a slot (nothing was freed)
            assertEquals(-3, store.alloc(intArrayOf(2)))
        }

        @Test
        fun `interleaved alloc-free-alloc preserves live handles and reuses freed slots`() {
            val store = ClusterStore()
            val handles = IntArray(20) { store.alloc(intArrayOf(it + 1)) }

            // Free every even-indexed handle
            for (i in handles.indices step 2) store.free(handles[i])

            // Re-alloc 10 — must land in freed slots
            val reallocs = IntArray(10) { store.alloc(intArrayOf(100 + it)) }

            // Odd handles must still return original data
            for (i in 1 until handles.size step 2) {
                assertCluster(store, handles[i], intArrayOf(i + 1))
            }

            // Re-allocated handles return new data
            for (i in reallocs.indices) {
                assertCluster(store, reallocs[i], intArrayOf(100 + i))
            }

            // No slot table growth — next fresh slot must be at high-water mark 20 → handle -(20+2) = -22
            val nextFresh = store.alloc(intArrayOf(999))
            assertEquals(
                -22,
                nextFresh,
                "Slot table must not have grown beyond high-water mark of 20",
            )
        }
    }

    // =========================================================================
    // Accessors: length, codepointAt, baseCodepoint
    // =========================================================================

    @Nested
    @DisplayName("accessors: length, codepointAt, baseCodepoint")
    inner class AccessorTests {
        @Test
        fun `length codepointAt and baseCodepoint expose stored data`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(0x0915, 0x094D, 0x0937))

            assertAll(
                { assertEquals(3, store.length(handle)) },
                { assertEquals(0x0915, store.baseCodepoint(handle)) },
                { assertEquals(0x0915, store.codepointAt(handle, 0)) },
                { assertEquals(0x094D, store.codepointAt(handle, 1)) },
                { assertEquals(0x0937, store.codepointAt(handle, 2)) },
            )
        }

        @Test
        fun `baseCodepoint equals codepointAt index 0`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(0xABCD, 0x1234))

            assertEquals(store.codepointAt(handle, 0), store.baseCodepoint(handle))
        }

        @Test
        fun `accessors throw for EMPTY (0)`() {
            val store = ClusterStore()
            assertThrows(IndexOutOfBoundsException::class.java) {
                store.length(TerminalConstants.EMPTY)
            }
        }

        @Test
        fun `accessors throw for WIDE_CHAR_SPACER (-1)`() {
            val store = ClusterStore()
            assertThrows(IndexOutOfBoundsException::class.java) {
                store.baseCodepoint(TerminalConstants.WIDE_CHAR_SPACER)
            }
        }

        @Test
        fun `codepointAt throws for out-of-range index`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(1, 2, 3))

            assertThrows(IndexOutOfBoundsException::class.java) {
                store.codepointAt(handle, 3) // valid indices are 0..2
            }
        }
    }

    // =========================================================================
    // readInto()
    // =========================================================================

    @Nested
    @DisplayName("readInto()")
    inner class ReadIntoTests {
        @Test
        fun `readInto with default destOffset 0 copies payload from start`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(1, 2, 3))
            val dest = IntArray(3)

            val written = store.readInto(handle, dest)

            assertAll(
                { assertEquals(3, written) },
                { assertEquals(1, dest[0]) },
                { assertEquals(2, dest[1]) },
                { assertEquals(3, dest[2]) },
            )
        }

        @Test
        fun `readInto with non-zero destOffset copies payload at correct position`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(7, 8, 9))
            val dest = intArrayOf(100, 100, 100, 100, 100)

            val written = store.readInto(handle, dest, destOffset = 1)

            assertAll(
                { assertEquals(3, written) },
                { assertEquals(100, dest[0], "Slot before destOffset must be untouched") },
                { assertEquals(7, dest[1]) },
                { assertEquals(8, dest[2]) },
                { assertEquals(9, dest[3]) },
                { assertEquals(100, dest[4], "Slot after payload must be untouched") },
            )
        }

        @Test
        fun `readInto for single-codepoint cluster writes exactly 1 value`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(0x1F600))
            val dest = intArrayOf(0, 0, 0)

            val written = store.readInto(handle, dest)

            assertAll(
                { assertEquals(1, written) },
                { assertEquals(0x1F600, dest[0]) },
                { assertEquals(0, dest[1], "Second slot must remain untouched") },
            )
        }

        @Test
        fun `readInto for maximum 16-codepoint cluster writes all values`() {
            val store = ClusterStore()
            val payload = IntArray(16) { 0x1000 + it }
            val handle = store.alloc(payload)
            val dest = IntArray(16)

            val written = store.readInto(handle, dest)

            assertEquals(16, written)
            for (i in 0 until 16) {
                assertEquals(0x1000 + i, dest[i], "Mismatch at index $i")
            }
        }

        @Test
        fun `readInto does not allocate - reusing same dest array produces correct result`() {
            val store = ClusterStore()
            val h0 = store.alloc(intArrayOf(10, 20))
            val h1 = store.alloc(intArrayOf(30, 40))
            val dest = IntArray(4)

            // First call
            store.readInto(h0, dest, destOffset = 0)
            assertEquals(10, dest[0])
            assertEquals(20, dest[1])

            // Reuse same array — second call must overwrite cleanly
            store.readInto(h1, dest, destOffset = 0)
            assertEquals(30, dest[0])
            assertEquals(40, dest[1])
        }

        @Test
        fun `readInto throws when dest does not have enough capacity`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(1, 2, 3))
            val dest = IntArray(3)

            // destOffset = 1 leaves only 2 slots, but cluster has 3 codepoints
            assertThrows(IndexOutOfBoundsException::class.java) {
                store.readInto(handle, dest, destOffset = 1)
            }
        }
    }

    // =========================================================================
    // Capacity growth
    // =========================================================================

    @Nested
    @DisplayName("capacity growth")
    inner class GrowthTests {
        @Test
        fun `growing slot table preserves all old handles and data`() {
            val store = ClusterStore()
            // 130 > initial slot capacity of 64, forces at least one doubling
            val handles = IntArray(130) { store.alloc(intArrayOf(1000 + it)) }

            assertAll(
                { assertEquals(-2, handles.first(), "First handle must be -2") },
                { assertEquals(-(handles.lastIndex + 2), handles.last(), "Last handle encoding must be correct") },
            )
            for (i in handles.indices) {
                assertCluster(store, handles[i], intArrayOf(1000 + i))
            }
        }

        @Test
        fun `growing data pool preserves all previously written cluster payloads`() {
            val store = ClusterStore()
            // 80 × 5 = 400 codepoints > initial data capacity of 256, forces doubling
            val handles =
                IntArray(80) { i ->
                    store.alloc(IntArray(5) { j -> i * 10 + j })
                }

            for (i in handles.indices) {
                assertCluster(store, handles[i], IntArray(5) { j -> i * 10 + j })
            }
        }

        @Test
        fun `slot table grows multiple times and handles remain valid throughout`() {
            val store = ClusterStore()
            // 300 > 64 → 128 → 256 → 512, forces multiple doublings
            val handles = IntArray(300) { store.alloc(intArrayOf(it)) }

            for (i in handles.indices) {
                assertCluster(store, handles[i], intArrayOf(i))
            }
        }

        @Test
        fun `data pool grows multiple times and payloads remain valid`() {
            val store = ClusterStore()
            // 50 × 10 = 500 > 256, then 100 × 10 = 1000 > 512 → two doublings minimum
            val handles =
                IntArray(100) { i ->
                    store.alloc(IntArray(10) { j -> i * 100 + j })
                }

            for (i in handles.indices) {
                val expected = IntArray(10) { j -> i * 100 + j }
                assertCluster(store, handles[i], expected)
            }
        }

        @Test
        fun `alloc-free cycle under growth does not corrupt slot metadata`() {
            val store = ClusterStore()
            // Fill past initial capacity, then free half and refill
            val phase1 = IntArray(70) { store.alloc(intArrayOf(it)) }
            for (i in phase1.indices step 2) store.free(phase1[i])

            val phase2 = IntArray(35) { store.alloc(intArrayOf(1000 + it)) }

            // Odd phase1 handles must be intact
            for (i in 1 until phase1.size step 2) {
                assertCluster(store, phase1[i], intArrayOf(i))
            }
            // Phase2 handles must contain their new data
            for (i in phase2.indices) {
                assertCluster(store, phase2[i], intArrayOf(1000 + i))
            }
        }
    }

    // =========================================================================
    // Handle encoding contract
    // =========================================================================

    @Nested
    @DisplayName("handle encoding contract")
    inner class HandleEncodingTests {
        @Test
        fun `all handles are strictly less than CLUSTER_HANDLE_MAX`() {
            val store = ClusterStore()
            val handles = IntArray(20) { store.alloc(intArrayOf(it + 1)) }

            handles.forEach { h ->
                assertTrue(h <= TerminalConstants.CLUSTER_HANDLE_MAX) {
                    "Handle $h is not <= CLUSTER_HANDLE_MAX (${TerminalConstants.CLUSTER_HANDLE_MAX})"
                }
            }
        }

        @Test
        fun `handles are unique across concurrent live allocations`() {
            val store = ClusterStore()
            val handles = (0 until 50).map { store.alloc(intArrayOf(it)) }

            assertEquals(handles.size, handles.toSet().size, "All handles must be unique")
        }

        @Test
        fun `reused handles after free are the same negative values as originally issued`() {
            val store = ClusterStore()
            val original = store.alloc(intArrayOf(1, 2, 3))

            store.free(original)
            val reissued = store.alloc(intArrayOf(9, 8, 7))

            assertEquals(
                original,
                reissued,
                "Reissued handle must have the same numeric value as the freed one",
            )
        }
    }

    // =========================================================================
    // Segregated Freelist and Capacity Re-use
    // =========================================================================

    @Nested
    @DisplayName("segregated freelist and capacity re-use")
    inner class SegregatedFreelistTests {
        @Test
        fun `reusing slot does not shrink its physical capacity`() {
            val store = ClusterStore()
            // Allocate a large cluster (length 4) -> should allocate slot 0 with capacity 4
            val hLarge = store.alloc(intArrayOf(1, 2, 3, 4))
            store.free(hLarge)

            // Allocate a small cluster (length 2).
            // A larger free region can serve a smaller cluster without shrinking.
            val hSmall = store.alloc(intArrayOf(5, 6))
            assertEquals(hLarge, hSmall, "Should reuse the large slot index")

            // Freeing uses the physical capacity, not the current payload length.
            store.free(hSmall)

            // Allocate another cluster of length 4.
            // It should reuse the original region.
            val hLarge2 = store.alloc(intArrayOf(7, 8, 9, 10))
            assertEquals(hLarge, hLarge2, "Should reuse the same slot and fit its capacity of 4")
        }

        @Test
        fun `small allocations preserve large free regions`() {
            val store = ClusterStore()
            // Large regions remain available for longer clusters.
            val h5 = store.alloc(intArrayOf(1, 2, 3, 4, 5))
            store.free(h5)

            // Allocate a small cluster of length 2.
            // Small allocations only search capacities up to four.
            val h2 = store.alloc(intArrayOf(6, 7))
            assertNotEquals(h5, h2, "Should preserve the larger region for longer clusters")
        }

        @Test
        fun `larger allocations preserve smaller free regions across capacity boundaries`() {
            for (smallLength in intArrayOf(1, 2, 3, 4, 8, 16, 32, 64)) {
                val store = ClusterStore()
                val small = IntArray(smallLength) { 0x300 + it }
                val large = IntArray(smallLength + 1) { 0x400 + it }
                val smallHandle = store.alloc(small)
                store.free(smallHandle)

                val largeHandle = store.alloc(large)
                assertNotEquals(smallHandle, largeHandle, "Must preserve capacity $smallLength")
                assertEquals(smallHandle, store.alloc(small), "Smaller region must remain reusable")
                assertCluster(store, smallHandle, small)
                assertCluster(store, largeHandle, large)
            }
        }

        @Test
        fun `large capacity classes reuse fitting regions without losing smaller ones`() {
            val store = ClusterStore()
            val small = store.alloc(IntArray(5) { it })
            val large = store.alloc(IntArray(9) { it })
            store.free(large)
            store.free(small)

            val payload = IntArray(16) { 100 + it }
            assertEquals(large, store.alloc(payload))
            assertEquals(small, store.alloc(IntArray(8) { 200 + it }))
            assertCluster(store, large, payload)
            assertCluster(store, small, IntArray(8) { 200 + it })
        }

        @Test
        fun `repeated mixed size overwrites stop growing reserved storage`() {
            val store = ClusterStore()
            val sizes = intArrayOf(1, 2, 3, 4, 5, 8, 9, 16, 17, 32, 33, 64)
            val handles = IntArray(sizes.size)
            for (i in sizes.indices) {
                handles[i] = store.alloc(IntArray(sizes[i]) { it })
                store.free(handles[i])
            }
            val dataSize = ClusterStore::class.java.getDeclaredField("dataSize").apply { isAccessible = true }
            val reserved = dataSize.getInt(store)

            repeat(100) { cycle ->
                for (i in sizes.indices) {
                    val payload = IntArray(sizes[i]) { cycle + it }
                    val handle = store.alloc(payload)
                    assertEquals(handles[i], handle)
                    assertCluster(store, handle, payload)
                    store.free(handle)
                }
            }
            assertEquals(reserved, dataSize.getInt(store), "Overwrites must reuse existing data regions")
        }

        @Test
        fun `invalid source slice leaves free regions available`() {
            val store = ClusterStore()
            val handle = store.alloc(intArrayOf(1, 2))
            store.free(handle)

            for (offset in intArrayOf(-1, 1, Int.MAX_VALUE)) {
                assertThrows(IndexOutOfBoundsException::class.java) {
                    store.alloc(intArrayOf(3, 4), offset, 2)
                }
            }
            assertEquals(handle, store.alloc(intArrayOf(5, 6)))
            assertCluster(store, handle, intArrayOf(5, 6))
        }
    }
}

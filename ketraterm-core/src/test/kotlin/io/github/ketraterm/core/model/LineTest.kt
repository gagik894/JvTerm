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
package io.github.ketraterm.core.model

import io.github.ketraterm.core.store.ClusterStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("Line Test Suite")
class LineTest {
    // Every test gets a fresh store. Lines within the same test share one store
    // to reflect the real ownership model (all lines in a ring share one store).
    private fun store() = ClusterStore()

    private fun line(
        width: Int,
        store: ClusterStore = store(),
    ) = Line(width, store)

    // =========================================================================
    // Initialization
    // =========================================================================

    @Nested
    @DisplayName("Initialization")
    inner class InitializationTests {
        @ParameterizedTest(name = "Create line with width={0}")
        @ValueSource(ints = [1, 10, 80, 1000])
        fun `valid widths construct successfully`(width: Int) {
            val l = line(width)
            assertAll(
                { assertEquals(width, l.width) },
                { assertFalse(l.wrapped) },
            )
        }

        @Test
        fun `all cells start as EMPTY with zeroed attrs`() {
            val l = line(5)
            for (col in 0 until 5) {
                assertAll(
                    { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col), "codepoint at $col") },
                    { assertEquals(0L, l.getPackedAttr(col), "attr at $col") },
                )
            }
        }

        @ParameterizedTest(name = "Reject invalid width={0}")
        @ValueSource(ints = [0, -1, -100])
        fun `non-positive widths throw`(invalidWidth: Int) {
            assertThrows<IllegalArgumentException> { line(invalidWidth) }
        }
    }

    // =========================================================================
    // setCell / getCodepoint / getPackedAttr — plain cells
    // =========================================================================

    @Nested
    @DisplayName("setCell / getCodepoint / getPackedAttr")
    inner class CellOperationsTests {
        @ParameterizedTest(name = "set and get at col={0}")
        @CsvSource("0", "5", "9")
        fun `valid columns round-trip codepoint and attr`(col: Int) {
            val l = line(10)
            l.setCell(col, 'A'.code, 12345)
            assertAll(
                { assertEquals('A'.code, l.getCodepoint(col)) },
                { assertEquals(12345L, l.getPackedAttr(col)) },
            )
        }

        @Test
        fun `setCell on a cluster cell frees the old handle`() {
            val store = store()
            val l = Line(5, store)
            l.setCluster(2, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 0)
            assertTrue(l.isCluster(2))

            // Overwrite with plain cell — cluster handle must be freed
            l.setCell(2, 'X'.code, 0)

            assertFalse(l.isCluster(2))
            assertEquals('X'.code, l.getCodepoint(2))

            // The freed slot must be reused by the next alloc
            val handle = store.alloc(intArrayOf(99))
            assertEquals(-2, handle, "Freed cluster slot must be reused")
        }
    }

    // =========================================================================
    // setCluster / getCodepoint / isCluster / readCluster
    // =========================================================================

    @Nested
    @DisplayName("setCluster / isCluster / readCluster")
    inner class ClusterCellTests {
        @Test
        fun `setCluster stores multi-codepoint cluster and isCluster returns true`() {
            val l = line(5)
            l.setCluster(1, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 42)

            assertTrue(l.isCluster(1))
            assertEquals(42L, l.getPackedAttr(1))
        }

        @Test
        fun `getCodepoint on cluster cell returns base codepoint`() {
            val l = line(5)
            l.setCluster(0, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 0)

            assertEquals(0x1F468, l.getCodepoint(0))
        }

        @Test
        fun `readCluster copies all codepoints into dest and returns length`() {
            val l = line(5)
            l.setCluster(2, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 0)
            val dest = IntArray(8)

            val written = l.readCluster(2, dest)

            assertAll(
                { assertEquals(3, written) },
                { assertEquals(0x1F468, dest[0]) },
                { assertEquals(0x200D, dest[1]) },
                { assertEquals(0x1F469, dest[2]) },
            )
        }

        @Test
        fun `readCluster on plain cell returns 0 and writes nothing`() {
            val l = line(5)
            l.setCell(2, 'A'.code, 0)
            val dest = IntArray(8) { 999 }

            val written = l.readCluster(2, dest)

            assertAll(
                { assertEquals(0, written) },
                { assertEquals(999, dest[0], "dest must be untouched for plain cell") },
            )
        }

        @Test
        fun `isCluster returns false for plain cells EMPTY and SPACER`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 0)
            l.setCell(1, TerminalConstants.EMPTY, 0)
            l.setCell(2, TerminalConstants.WIDE_CHAR_SPACER, 0)

            assertAll(
                { assertFalse(l.isCluster(0)) },
                { assertFalse(l.isCluster(1)) },
                { assertFalse(l.isCluster(2)) },
            )
        }

        @Test
        fun `overwriting cluster with cluster frees old handle before allocating new`() {
            val store = store()
            val l = Line(5, store)

            l.setCluster(0, intArrayOf(0xAAAA, 0xBBBB), 2, 0)
            val firstHandle = l.rawCodepoint(0)
            assertTrue(firstHandle <= TerminalConstants.CLUSTER_HANDLE_MAX)

            // Overwrite with a different cluster
            l.setCluster(0, intArrayOf(0xCCCC, 0xDDDD, 0xEEEE), 3, 0)

            // The larger replacement needs a different region.
            val secondHandle = l.rawCodepoint(0)
            assertNotEquals(firstHandle, secondHandle, "The smaller region must remain available")
            l.setCluster(1, intArrayOf(0x1111, 0x2222), 2, 0)
            assertEquals(firstHandle, l.rawCodepoint(1), "Overwrite must free the old region for reuse")

            // New data must be visible
            val dest = IntArray(4)
            val written = l.readCluster(0, dest)
            assertAll(
                { assertEquals(3, written) },
                { assertEquals(0xCCCC, dest[0]) },
                { assertEquals(0xDDDD, dest[1]) },
                { assertEquals(0xEEEE, dest[2]) },
            )
            assertEquals(2, l.readCluster(1, dest))
            assertArrayEquals(intArrayOf(0x1111, 0x2222), dest.copyOf(2))
        }

        @Test
        fun `reusable dest array across multiple readCluster calls produces correct results`() {
            val l = line(5)
            l.setCluster(0, intArrayOf(10, 20), 2, 0)
            l.setCluster(1, intArrayOf(30, 40, 50), 3, 0)
            val dest = IntArray(8)

            val w0 = l.readCluster(0, dest)
            assertEquals(2, w0)
            assertEquals(10, dest[0])
            assertEquals(20, dest[1])

            val w1 = l.readCluster(1, dest)
            assertEquals(3, w1)
            assertEquals(30, dest[0])
            assertEquals(40, dest[1])
            assertEquals(50, dest[2])
        }
    }

    // =========================================================================
    // rawCodepoint / setRawCell — internal accessors used by TerminalResizer
    // =========================================================================

    @Nested
    @DisplayName("rawCodepoint / setRawCell (internal reflow contract)")
    inner class RawAccessorTests {
        @Test
        fun `rawCodepoint returns stored Int without resolving cluster handle`() {
            val store = store()
            val l = Line(5, store)
            l.setCluster(0, intArrayOf(0x1F468, 0x200D), 2, 0)

            val raw = l.rawCodepoint(0)
            assertTrue(
                raw <= TerminalConstants.CLUSTER_HANDLE_MAX,
                "rawCodepoint must return the negative handle, not the base codepoint",
            )
        }

        @Test
        fun `setRawCell transplants a handle without double-allocating`() {
            val store = store()
            val src = Line(5, store)
            src.setCluster(0, intArrayOf(0x1F469, 0x200D, 0x1F680), 3, 7)

            val raw = src.rawCodepoint(0)
            val attr = src.getPackedAttr(0)

            val dst = Line(5, store)
            dst.setRawCell(0, raw, attr)

            // dst must resolve the same cluster data via the shared store
            val dest = IntArray(4)
            val written = dst.readCluster(0, dest)
            assertAll(
                { assertEquals(3, written) },
                { assertEquals(0x1F469, dest[0]) },
                { assertEquals(0x200D, dest[1]) },
                { assertEquals(0x1F680, dest[2]) },
                { assertEquals(7L, dst.getPackedAttr(0)) },
            )
        }

        @Test
        fun `setRawCell with plain codepoint works identically to setCell`() {
            val l = line(5)
            l.setRawCell(3, 'Z'.code, 55)

            assertAll(
                { assertEquals('Z'.code, l.getCodepoint(3)) },
                { assertEquals(55L, l.getPackedAttr(3)) },
                { assertFalse(l.isCluster(3)) },
            )
        }
    }

    // =========================================================================
    // clear()
    // =========================================================================

    @Nested
    @DisplayName("clear()")
    inner class ClearTests {
        @Test
        fun `clear frees all cluster handles so store slots are reused`() {
            val store = store()
            val l = Line(3, store)
            l.setCluster(0, intArrayOf(0xAAAA, 0xBBBB), 2, 0)
            l.setCluster(1, intArrayOf(0xCCCC), 1, 0)

            l.clear(0)

            // Both slots freed — next two allocs must reuse them
            val h1 = store.alloc(intArrayOf(1))
            val h2 = store.alloc(intArrayOf(2))
            // Freelist is LIFO: slot 1 freed last → reused first
            assertAll(
                { assertEquals(-3, h1, "First freed-slot reuse") },
                { assertEquals(-2, h2, "Second freed-slot reuse") },
            )
        }
    }

    // =========================================================================
    // clearFromColumn()
    // =========================================================================

    @Nested
    @DisplayName("clearFromColumn()")
    inner class ClearFromColumnTests {
        @Test
        fun `clears from specified column to end leaving prefix intact`() {
            val l = line(10)
            for (col in 0 until 10) l.setCell(col, 'A'.code + col, col.toLong())

            l.clearFromColumn(5, 99)

            for (col in 0 until 5) {
                assertAll(
                    { assertEquals('A'.code + col, l.getCodepoint(col)) },
                    { assertEquals(col.toLong(), l.getPackedAttr(col)) },
                )
            }
            for (col in 5 until 10) {
                assertAll(
                    { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col)) },
                    { assertEquals(99L, l.getPackedAttr(col)) },
                )
            }
        }

        @Test
        fun `startCol 0 clears the entire line`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearFromColumn(0, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `negative startCol is clamped to 0 and clears entire line`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearFromColumn(-5, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `startCol beyond width is a no-op`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearFromColumn(10, 42)
            for (col in 0 until 5) assertEquals('X'.code, l.getCodepoint(col))
        }

        @Test
        fun `frees cluster handles in the cleared range`() {
            val store = store()
            val l = Line(5, store)
            l.setCluster(3, intArrayOf(0x1F600, 0x200D), 2, 0)

            l.clearFromColumn(3, 0)

            val h = store.alloc(intArrayOf(1))
            assertEquals(-2, h, "Cluster handle in cleared range must be freed")
        }
    }

    // =========================================================================
    // clearToColumn()
    // =========================================================================

    @Nested
    @DisplayName("clearToColumn()")
    inner class ClearToColumnTests {
        @Test
        fun `clears from start through specified column leaving suffix intact`() {
            val l = line(10)
            for (col in 0 until 10) l.setCell(col, 'A'.code + col, col.toLong())

            l.clearToColumn(4, 99)

            for (col in 0..4) {
                assertAll(
                    { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col)) },
                    { assertEquals(99L, l.getPackedAttr(col)) },
                )
            }
            for (col in 5 until 10) {
                assertAll(
                    { assertEquals('A'.code + col, l.getCodepoint(col)) },
                    { assertEquals(col.toLong(), l.getPackedAttr(col)) },
                )
            }
        }

        @Test
        fun `endCol of width-1 clears the entire line`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearToColumn(4, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `negative endCol is a no-op`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearToColumn(-1, 42)
            for (col in 0 until 5) assertEquals('X'.code, l.getCodepoint(col))
        }

        @Test
        fun `endCol beyond width clears entire line`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'X'.code, col.toLong())
            l.clearToColumn(100, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `frees cluster handles in the cleared range`() {
            val store = store()
            val l = Line(5, store)
            l.setCluster(1, intArrayOf(0x1F600, 0x200D), 2, 0)

            l.clearToColumn(2, 0)

            val h = store.alloc(intArrayOf(1))
            assertEquals(-2, h, "Cluster handle in cleared range must be freed")
        }
    }

    // =========================================================================
    // fill()
    // =========================================================================

    @Nested
    @DisplayName("fill()")
    inner class FillTests {
        @Test
        fun `fills all cells with given codepoint and attr`() {
            val l = line(5)
            l.fill('-'.code, 99)
            for (col in 0 until 5) {
                assertAll(
                    { assertEquals('-'.code, l.getCodepoint(col)) },
                    { assertEquals(99L, l.getPackedAttr(col)) },
                )
            }
        }

        @Test
        fun `fill with EMPTY zeroes all codepoints`() {
            val l = line(5)
            for (col in 0 until 5) l.setCell(col, 'A'.code + col, col.toLong())
            l.fill(TerminalConstants.EMPTY, 42)
            for (col in 0 until 5) assertEquals(TerminalConstants.EMPTY, l.getCodepoint(col))
        }

        @Test
        fun `fill frees all cluster handles before overwriting`() {
            val store = store()
            val l = Line(4, store)
            l.setCluster(0, intArrayOf(0xAAAA, 0xBBBB), 2, 0)
            l.setCluster(2, intArrayOf(0xCCCC), 1, 0)

            l.fill('Z'.code, 0)

            val h1 = store.alloc(intArrayOf(1))
            val h2 = store.alloc(intArrayOf(2))
            // Both cluster slots freed; new allocs reuse them
            assertAll(
                { assertEquals(-3, h1) },
                { assertEquals(-2, h2) },
            )
        }
    }

    // =========================================================================
    // insertCells()
    // =========================================================================

    @Nested
    @DisplayName("insertCells()")
    inner class InsertCellsTests {
        @Test
        fun `inserts blanks and shifts content right`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 1)
            l.setCell(1, 'B'.code, 2)
            l.setCell(2, 'C'.code, 3)

            l.insertCells(col = 1, count = 2, defaultAttr = 99)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(2)) },
                { assertEquals('B'.code, l.getCodepoint(3)) },
                { assertEquals('C'.code, l.getCodepoint(4)) },
                { assertEquals(99L, l.getPackedAttr(1)) },
                { assertEquals(99L, l.getPackedAttr(2)) },
            )
        }

        @Test
        fun `cells pushed off the right edge are freed if they are cluster handles`() {
            val store = store()
            val l = Line(3, store)
            l.setCluster(2, intArrayOf(0x1F600, 0x200D), 2, 0) // will fall off on insert at 0

            l.insertCells(col = 0, count = 1, defaultAttr = 0)

            // Cluster at col 2 was shifted off and freed
            val h = store.alloc(intArrayOf(99))
            assertEquals(-2, h, "Cluster shifted off the edge must be freed")
        }

        @Test
        fun `insert at col 0 shifts entire line right`() {
            val l = line(4)
            l.setCell(0, 'A'.code, 0)
            l.setCell(1, 'B'.code, 0)
            l.setCell(2, 'C'.code, 0)

            l.insertCells(col = 0, count = 1, defaultAttr = 0)

            assertAll(
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(0)) },
                { assertEquals('A'.code, l.getCodepoint(1)) },
                { assertEquals('B'.code, l.getCodepoint(2)) },
                { assertEquals('C'.code, l.getCodepoint(3)) },
            )
        }

        @Test
        fun `insert count larger than remaining space clamps correctly`() {
            val l = line(4)
            l.setCell(0, 'A'.code, 0)
            l.setCell(1, 'B'.code, 0)

            // inserting 10 at col 1 on a width-4 line: only 3 free cells to the right
            l.insertCells(col = 1, count = 10, defaultAttr = 55)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(3)) },
            )
        }

        @Test
        fun `no-op for negative col`() {
            val l = line(3)
            l.setCell(0, 'X'.code, 7)
            l.insertCells(col = -1, count = 1, defaultAttr = 9)
            assertEquals('X'.code, l.getCodepoint(0))
        }

        @Test
        fun `no-op for zero count`() {
            val l = line(3)
            l.setCell(0, 'X'.code, 7)
            l.insertCells(col = 0, count = 0, defaultAttr = 9)
            assertEquals('X'.code, l.getCodepoint(0))
        }
    }

    // =========================================================================
    // deleteCells()
    // =========================================================================

    @Nested
    @DisplayName("deleteCells()")
    inner class DeleteCellsTests {
        @Test
        fun `deletes middle range, shifts tail left, and fills trailing attrs`() {
            val l = line(6)
            l.setCell(0, 'A'.code, 10)
            l.setCell(1, 'B'.code, 11)
            l.setCell(2, 'C'.code, 12)
            l.setCell(3, 'D'.code, 13)
            l.setCell(4, 'E'.code, 14)
            l.setCell(5, 'F'.code, 15)

            l.deleteCells(col = 1, count = 2, defaultAttr = 99)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals('D'.code, l.getCodepoint(1)) },
                { assertEquals('E'.code, l.getCodepoint(2)) },
                { assertEquals('F'.code, l.getCodepoint(3)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(4)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(5)) },
                { assertEquals(13L, l.getPackedAttr(1)) },
                { assertEquals(14L, l.getPackedAttr(2)) },
                { assertEquals(15L, l.getPackedAttr(3)) },
                { assertEquals(99L, l.getPackedAttr(4)) },
                { assertEquals(99L, l.getPackedAttr(5)) },
            )
        }

        @Test
        fun `delete from col zero shifts whole line left`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 1)
            l.setCell(1, 'B'.code, 2)
            l.setCell(2, 'C'.code, 3)

            l.deleteCells(col = 0, count = 1, defaultAttr = 77)

            assertAll(
                { assertEquals('B'.code, l.getCodepoint(0)) },
                { assertEquals('C'.code, l.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(3)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(4)) },
                { assertEquals(77L, l.getPackedAttr(4)) },
            )
        }

        @Test
        fun `delete at last column only clears that cell`() {
            val l = line(4)
            l.setCell(0, 'A'.code, 1)
            l.setCell(1, 'B'.code, 2)
            l.setCell(2, 'C'.code, 3)
            l.setCell(3, 'D'.code, 4)

            l.deleteCells(col = 3, count = 1, defaultAttr = 55)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals('B'.code, l.getCodepoint(1)) },
                { assertEquals('C'.code, l.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(3)) },
                { assertEquals(55L, l.getPackedAttr(3)) },
            )
        }

        @Test
        fun `count larger than remaining cells is clamped`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 0)
            l.setCell(1, 'B'.code, 0)
            l.setCell(2, 'C'.code, 0)
            l.setCell(3, 'D'.code, 0)

            l.deleteCells(col = 2, count = 99, defaultAttr = 42)

            assertAll(
                { assertEquals('A'.code, l.getCodepoint(0)) },
                { assertEquals('B'.code, l.getCodepoint(1)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(2)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(3)) },
                { assertEquals(TerminalConstants.EMPTY, l.getCodepoint(4)) },
                { assertEquals(42L, l.getPackedAttr(2)) },
                { assertEquals(42L, l.getPackedAttr(4)) },
            )
        }

        @Test
        fun `no-op for invalid arguments`() {
            val l = line(4)
            l.setCell(0, 'X'.code, 7)
            l.setCell(1, 'Y'.code, 8)

            l.deleteCells(col = -1, count = 1, defaultAttr = 99)
            l.deleteCells(col = 4, count = 1, defaultAttr = 99)
            l.deleteCells(col = 0, count = 0, defaultAttr = 99)
            l.deleteCells(col = 0, count = -2, defaultAttr = 99)

            assertAll(
                { assertEquals('X'.code, l.getCodepoint(0)) },
                { assertEquals('Y'.code, l.getCodepoint(1)) },
                { assertEquals(7L, l.getPackedAttr(0)) },
                { assertEquals(8L, l.getPackedAttr(1)) },
            )
        }

        @Test
        fun `cluster handle in deleted range is freed`() {
            val store = store()
            val l = Line(5, store)
            l.setCell(0, 'A'.code, 0)
            l.setCluster(1, intArrayOf(0x1F600, 0x200D), 2, 0) // handle -2

            l.deleteCells(col = 1, count = 1, defaultAttr = 0)

            val reused = store.alloc(intArrayOf(1234))
            assertEquals(-2, reused, "Deleted cluster handle must be returned to free list")
        }

        @Test
        fun `shifted cluster handle remains live after delete`() {
            val store = store()
            val l = Line(5, store)
            l.setCluster(1, intArrayOf(0xAAAA), 1, 0) // handle -2 (to be deleted)
            l.setCluster(3, intArrayOf(0xBBBB, 0x200D), 2, 0) // handle -3 (should shift left)

            l.deleteCells(col = 1, count = 1, defaultAttr = 0)

            // Deleted handle is reusable.
            val first = store.alloc(intArrayOf(1))
            assertEquals(-2, first)

            // Shifted handle remains readable at its new column.
            val shiftedCol = 2
            assertTrue(l.isCluster(shiftedCol))
            val dest = IntArray(4)
            val len = l.readCluster(shiftedCol, dest)
            assertAll(
                { assertEquals(2, len) },
                { assertEquals(0xBBBB, dest[0]) },
                { assertEquals(0x200D, dest[1]) },
            )
        }
    }

    // =========================================================================
    // toText / toTextTrimmed
    // =========================================================================

    @Nested
    @DisplayName("toText / toTextTrimmed")
    inner class ToTextTests {
        @Test
        fun `plain ascii line renders correctly`() {
            val l = line(5)
            "Hello".forEachIndexed { i, c -> l.setCell(i, c.code, 0) }
            assertEquals("Hello", l.toText())
        }

        @Test
        fun `empty cells render as spaces in toText`() {
            val l = line(5)
            l.setCell(0, 'A'.code, 0)
            l.setCell(4, 'B'.code, 0)
            assertEquals("A   B", l.toText())
        }

        @Test
        fun `toTextTrimmed strips trailing empty cells`() {
            val l = line(10)
            l.setCell(0, 'H'.code, 0)
            l.setCell(1, 'i'.code, 0)
            assertEquals("Hi        ", l.toText())
            assertEquals("Hi", l.toTextTrimmed())
        }

        @Test
        fun `fully empty line returns spaces for toText and empty string for toTextTrimmed`() {
            val l = line(5)
            assertEquals("     ", l.toText())
            assertEquals("", l.toTextTrimmed())
        }

        @Test
        fun `wide char spacer is skipped in rendered output`() {
            val l = line(4)
            l.setCell(0, '你'.code, 0)
            l.setCell(1, TerminalConstants.WIDE_CHAR_SPACER, 0)
            l.setCell(2, 'X'.code, 0)
            assertAll(
                { assertEquals("你X ", l.toText()) },
                { assertEquals("你X", l.toTextTrimmed()) },
            )
        }

        @Test
        fun `cluster cell emits all codepoints in sequence`() {
            val l = line(5)
            // Family emoji: man + ZWJ + woman
            l.setCluster(0, intArrayOf(0x1F468, 0x200D, 0x1F469), 3, 0)
            l.setCell(1, 'X'.code, 0)

            val text = l.toTextTrimmed()
            // Must contain the base codepoint and all combining codepoints
            assertTrue(text.codePoints().toArray().contains(0x1F468), "Base must be present")
            assertTrue(text.codePoints().toArray().contains(0x200D), "ZWJ must be present")
            assertTrue(text.codePoints().toArray().contains(0x1F469), "Second emoji must be present")
            assertTrue(text.contains('X'), "'X' after cluster must be present")
        }

        @Test
        fun `cluster at last column is not trimmed by toTextTrimmed`() {
            val l = line(5)
            l.setCluster(4, intArrayOf(0x1F600, 0x200D), 2, 0)

            val trimmed = l.toTextTrimmed()
            assertTrue(
                trimmed.codePoints().toArray().contains(0x1F600),
                "Cluster at last column must not be trimmed",
            )
        }
    }
}

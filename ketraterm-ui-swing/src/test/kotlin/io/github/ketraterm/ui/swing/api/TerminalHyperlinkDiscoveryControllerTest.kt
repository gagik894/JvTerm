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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.painter.TerminalTextRunStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

class TerminalHyperlinkDiscoveryControllerTest {
    @ParameterizedTest
    @CsvSource("true, false", "false, false", "true, true", "false, true")
    fun `frame carry keeps wrapped hover and activation intact without merging distinct links`(
        stableLineIds: Boolean,
        scroll: Boolean,
    ) {
        val cache = TerminalRenderCache(24, 4)
        val rows = arrayOf("go https://example.com/ab", "cdefghijklmnopqrstuvwxyz", "012345 end")

        fun frame(generation: Long): StaticTextFrame {
            val shifted = scroll && generation > 1L
            return StaticTextFrame(
                frameGeneration = generation,
                structureGeneration = if (shifted) 2L else 1L,
                rowTexts = if (shifted) rows + "after" else arrayOf("before") + rows,
                lineIds =
                    when {
                        !stableLineIds -> LongArray(4)
                        shifted -> longArrayOf(2, 3, 4, 5)
                        else -> longArrayOf(1, 2, 3, 4)
                    },
                wrappedRows =
                    if (shifted) booleanArrayOf(true, true, false, false) else booleanArrayOf(false, true, true, false),
            )
        }
        cache.accept(frame(1L))
        val detectorCalls = AtomicInteger()
        var opened = 0
        val sharedAction =
            SwingHyperlinkAction {
                opened++
                true
            }
        val repaintObserved = CountDownLatch(1)
        val host =
            TestDiscoveryHost(
                cache,
                SwingHyperlinkDetector { _, sink ->
                    detectorCalls.incrementAndGet()
                    sink.addHyperlink(1, 3, 54, sharedAction)
                    sink.addHyperlink(1, 55, 58, sharedAction)
                },
                repaintObserved,
            )
        val controller = TerminalHyperlinkDiscoveryController(host, testScope())
        val repaints = ArrayList<CellSelection>()
        val hoverHost =
            object : TerminalHyperlinkHost {
                override val renderCache = cache
                override var cursor: Cursor = Cursor.getDefaultCursor()

                override fun cellAt(
                    x: Int,
                    y: Int,
                ): Long = (x.toLong() shl 32) or y.toLong()

                override fun hyperlinkIdAt(
                    row: Int,
                    column: Int,
                ): Int = controller.hyperlinkIdAt(row, column, cache)

                override fun isHyperlinkResolvable(hyperlinkId: Int): Boolean =
                    controller.isDiscoveredHyperlinkResolvable(hyperlinkId, cache)

                override fun openHyperlink(hyperlinkId: Int): Boolean = controller.openDiscoveredHyperlink(hyperlinkId, cache)

                override fun repaintHyperlinkSpan(
                    startRow: Int,
                    startColumn: Int,
                    endRow: Int,
                    endColumn: Int,
                ) {
                    repaints += CellSelection(startColumn, startRow, endColumn, endRow)
                }
            }
        val hover = TerminalHyperlinkController(hoverHost)
        host.onHyperlinksChanged = hover::refreshHyperlinkHover
        val source = JButton()
        val style = TerminalTextRunStyle()
        val activationForeground = 0xFF4DA3FF.toInt()
        try {
            SwingUtilities.invokeAndWait {
                hover.handleMouseMoved(MouseEvent(source, MouseEvent.MOUSE_MOVED, 0L, MouseEvent.CTRL_DOWN_MASK, 4, 1, 0, false))
                assertEquals(0, hover.hoveredHyperlinkId)
                controller.scheduleForFrame()
            }
            awaitRepaintAndDrainEdt(repaintObserved)
            SwingUtilities.invokeAndWait {
                assertTrue(hover.hoveredHyperlinkId < 0, "Async detection must activate hover without another mouse event")
                assertTrue(hover.hyperlinkActivationHover)
                repeat(3) { iteration ->
                    cache.accept(frame(iteration + 2L))
                    controller.scheduleForFrame()
                    val ids = controller.hyperlinkIdsFor(cache)
                    val firstRow = if (scroll) 0 else 1
                    val offset = cache.rowOffset(firstRow)
                    val linkId = ids[offset + 3]
                    assertTrue(linkId < 0)
                    for (index in 3 until 54) {
                        assertEquals(linkId, ids[offset + index], "cell $index after carry $iteration")
                    }
                    assertEquals(0, ids[offset + 2])
                    assertEquals(0, ids[offset + 54])
                    val otherId = ids[offset + 55]
                    assertTrue(otherId < 0)
                    assertNotEquals(linkId, otherId, "Distinct matches may share one action")
                    assertTrue(controller.openDiscoveredHyperlink(otherId, cache))
                    assertEquals(1, detectorCalls.get())

                    hover.clearHyperlinkHover()
                    repaints.clear()
                    val expectedSpan = CellSelection(3, firstRow, 6, firstRow + 2)
                    for (row in firstRow..firstRow + 2) {
                        hover.handleMouseMoved(MouseEvent(source, MouseEvent.MOUSE_MOVED, 0L, 0, 4, row, 0, false))
                        assertEquals(linkId, hover.hoveredHyperlinkId)
                        assertEquals(firstRow, hover.hoveredHyperlinkStartRow)
                        assertEquals(3, hover.hoveredHyperlinkStartColumn)
                        assertEquals(firstRow + 2, hover.hoveredHyperlinkEndRow)
                        assertEquals(6, hover.hoveredHyperlinkEndColumn)
                        val click =
                            MouseEvent(
                                source,
                                MouseEvent.MOUSE_PRESSED,
                                0L,
                                MouseEvent.CTRL_DOWN_MASK,
                                4,
                                row,
                                1,
                                false,
                                MouseEvent.BUTTON1,
                            )
                        assertTrue(hover.handleMousePressed(click))
                        assertTrue(click.isConsumed)
                    }
                    assertEquals(
                        listOf(expectedSpan),
                        repaints,
                        "Moving between segments must not repaint separate row spans",
                    )
                    hover.updateHyperlinkActivationHover(true)
                    assertEquals(listOf(expectedSpan, expectedSpan), repaints)
                    for (row in firstRow..firstRow + 2) {
                        style.configureRow(
                            row,
                            true,
                            ids,
                            hover.hoveredHyperlinkId,
                            hover.hoveredHyperlinkStartRow,
                            hover.hoveredHyperlinkStartColumn,
                            hover.hoveredHyperlinkEndRow,
                            hover.hoveredHyperlinkEndColumn,
                            hover.hyperlinkActivationHover,
                            activationForeground,
                        )
                        for (column in 0 until cache.columns) {
                            val index = cache.rowOffset(row) + column
                            style.begin(cache, cache.palette, cache.rowOffset(row), column)
                            assertEquals(ids[index] == linkId, style.hovered)
                            val expectedForeground =
                                if (ids[index] == linkId) activationForeground else cache.palette.defaultForeground
                            assertEquals(expectedForeground, style.foreground)
                        }
                    }
                }
                assertEquals(12, opened, "Every carried segment and the separate match must retain its action")
            }
        } finally {
            SwingUtilities.invokeAndWait { controller.dispose() }
        }
    }

    @Test
    fun `scheduled analysis publishes discovered url overlay after debounce`() {
        val cache = TerminalRenderCache(24, 1)
        writeText(cache, row = 0, text = "https://example.com")
        val opened = AtomicBoolean(false)
        val repaintObserved = CountDownLatch(1)
        val host =
            TestDiscoveryHost(
                renderCache = cache,
                hyperlinkDetector =
                    SwingHyperlinkDetector { request, sink ->
                        assertEquals("https://example.com\n", request.lineText(0))
                        sink.addHyperlink(
                            lineIndex = 0,
                            startOffset = 0,
                            endOffset = "https://example.com".length,
                            action =
                                SwingHyperlinkAction {
                                    opened.set(true)
                                    true
                                },
                        )
                    },
                repaintObserved = repaintObserved,
            )
        val controller = TerminalHyperlinkDiscoveryController(host, testScope())

        SwingUtilities.invokeAndWait {
            controller.scheduleForFrame()
        }

        awaitRepaintAndDrainEdt(repaintObserved)

        val ids = controller.hyperlinkIdsFor(cache)
        assertEquals(-1, ids[0])
        assertEquals(-1, ids["https://example.com".lastIndex])
        assertEquals(0, ids["https://example.com".length])
        assertTrue(controller.isDiscoveredHyperlinkResolvable(-1, cache))
        assertTrue(controller.openDiscoveredHyperlink(-1, cache))
        assertTrue(opened.get())
    }

    @Test
    fun `scroll preserves discovered links for matching visible row identities before rescan`() {
        val cache = TerminalRenderCache(24, 3)
        cache.accept(
            StaticTextFrame(
                frameGeneration = 1L,
                structureGeneration = 1L,
                rowTexts = arrayOf("alpha", "https://example.com", "omega"),
                lineIds = longArrayOf(0L, 0L, 0L),
            ),
        )
        val opened = AtomicBoolean(false)
        val detectorCalls = AtomicInteger()
        val repaintObserved = CountDownLatch(1)
        val host =
            TestDiscoveryHost(
                renderCache = cache,
                hyperlinkDetector =
                    SwingHyperlinkDetector { _, sink ->
                        detectorCalls.incrementAndGet()
                        sink.addHyperlink(
                            lineIndex = 1,
                            startOffset = 0,
                            endOffset = "https://example.com".length,
                            action =
                                SwingHyperlinkAction {
                                    opened.set(true)
                                    true
                                },
                        )
                    },
                repaintObserved = repaintObserved,
            )
        val controller = TerminalHyperlinkDiscoveryController(host, testScope())

        SwingUtilities.invokeAndWait {
            controller.scheduleForFrame()
        }
        awaitRepaintAndDrainEdt(repaintObserved)
        assertEquals(1, detectorCalls.get())

        cache.accept(
            StaticTextFrame(
                frameGeneration = 2L,
                structureGeneration = 2L,
                rowTexts = arrayOf("new", "alpha", "https://example.com"),
                lineIds = longArrayOf(0L, 0L, 0L),
            ),
        )
        val carry = scheduleForFrameAndReadIds(controller, cache, detectorCalls)

        assertEquals(0, carry.ids[cache.rowOffset(1)])
        assertEquals(-1, carry.ids[cache.rowOffset(2)])
        assertEquals(-1, carry.ids[cache.rowOffset(2) + "https://example.com".lastIndex])
        assertEquals(1, carry.detectorCalls)
        assertTrue(controller.openDiscoveredHyperlink(-1, cache))
        assertTrue(opened.get())
    }

    @Test
    fun `scroll carries discovered links across repeated render-cache row shifts without detector rescan`() {
        val cache = TerminalRenderCache(24, 3)
        cache.accept(
            StaticTextFrame(
                frameGeneration = 1L,
                structureGeneration = 1L,
                rowTexts = arrayOf("https://example.com", "alpha", "omega"),
                lineIds = longArrayOf(0L, 0L, 0L),
            ),
        )
        val detectorCalls = AtomicInteger()
        val repaintObserved = CountDownLatch(1)
        val host =
            TestDiscoveryHost(
                renderCache = cache,
                hyperlinkDetector =
                    SwingHyperlinkDetector { _, sink ->
                        detectorCalls.incrementAndGet()
                        sink.addHyperlink(
                            lineIndex = 0,
                            startOffset = 0,
                            endOffset = "https://example.com".length,
                            action = SwingHyperlinkAction.NONE,
                        )
                    },
                repaintObserved = repaintObserved,
            )
        val controller = TerminalHyperlinkDiscoveryController(host, testScope())

        SwingUtilities.invokeAndWait {
            controller.scheduleForFrame()
        }
        awaitRepaintAndDrainEdt(repaintObserved)

        cache.accept(
            StaticTextFrame(
                frameGeneration = 2L,
                structureGeneration = 2L,
                rowTexts = arrayOf("new-1", "https://example.com", "alpha"),
                lineIds = longArrayOf(0L, 0L, 0L),
            ),
        )
        var carry = scheduleForFrameAndReadIds(controller, cache, detectorCalls)
        assertEquals(-1, carry.ids[cache.rowOffset(1)])
        assertEquals(1, carry.detectorCalls)

        cache.accept(
            StaticTextFrame(
                frameGeneration = 3L,
                structureGeneration = 3L,
                rowTexts = arrayOf("new-2", "new-1", "https://example.com"),
                lineIds = longArrayOf(0L, 0L, 0L),
            ),
        )
        carry = scheduleForFrameAndReadIds(controller, cache, detectorCalls)

        assertEquals(0, carry.ids[cache.rowOffset(1)])
        assertEquals(-1, carry.ids[cache.rowOffset(2)])
        assertEquals(-1, carry.ids[cache.rowOffset(2) + "https://example.com".lastIndex])
        assertEquals(1, carry.detectorCalls)
    }

    @Test
    fun `scroll carries discovered links when render-cache overscan row count changes`() {
        val cache = TerminalRenderCache(24, 3)
        cache.accept(
            StaticTextFrame(
                frameGeneration = 1L,
                structureGeneration = 1L,
                rowTexts = arrayOf("alpha", "https://example.com", "omega"),
                lineIds = longArrayOf(0L, 0L, 0L),
            ),
        )
        val detectorCalls = AtomicInteger()
        val repaintObserved = CountDownLatch(1)
        val host =
            TestDiscoveryHost(
                renderCache = cache,
                hyperlinkDetector =
                    SwingHyperlinkDetector { _, sink ->
                        detectorCalls.incrementAndGet()
                        sink.addHyperlink(
                            lineIndex = 1,
                            startOffset = 0,
                            endOffset = "https://example.com".length,
                            action = SwingHyperlinkAction.NONE,
                        )
                    },
                repaintObserved = repaintObserved,
            )
        val controller = TerminalHyperlinkDiscoveryController(host, testScope())

        SwingUtilities.invokeAndWait {
            controller.scheduleForFrame()
        }
        awaitRepaintAndDrainEdt(repaintObserved)

        cache.accept(
            StaticTextFrame(
                frameGeneration = 2L,
                structureGeneration = 2L,
                rowTexts = arrayOf("new", "alpha", "https://example.com", "omega"),
                lineIds = longArrayOf(0L, 0L, 0L, 0L),
            ),
        )
        val carry = scheduleForFrameAndReadIds(controller, cache, detectorCalls)

        assertEquals(0, carry.ids[cache.rowOffset(1)])
        assertEquals(-1, carry.ids[cache.rowOffset(2)])
        assertEquals(-1, carry.ids[cache.rowOffset(2) + "https://example.com".lastIndex])
        assertEquals(1, carry.detectorCalls)
    }

    @Test
    fun `render-cache bounded carry drops discovered link after it leaves the cache`() {
        val cache = TerminalRenderCache(24, 2)
        cache.accept(
            StaticTextFrame(
                frameGeneration = 1L,
                structureGeneration = 1L,
                rowTexts = arrayOf("https://example.com", "alpha"),
                lineIds = longArrayOf(0L, 0L),
            ),
        )
        val detectorCalls = AtomicInteger()
        val repaintObserved = CountDownLatch(1)
        val host =
            TestDiscoveryHost(
                renderCache = cache,
                hyperlinkDetector =
                    SwingHyperlinkDetector { _, sink ->
                        detectorCalls.incrementAndGet()
                        sink.addHyperlink(
                            lineIndex = 0,
                            startOffset = 0,
                            endOffset = "https://example.com".length,
                            action = SwingHyperlinkAction.NONE,
                        )
                    },
                repaintObserved = repaintObserved,
            )
        val controller = TerminalHyperlinkDiscoveryController(host, testScope())

        SwingUtilities.invokeAndWait {
            controller.scheduleForFrame()
        }
        awaitRepaintAndDrainEdt(repaintObserved)

        cache.accept(
            StaticTextFrame(
                frameGeneration = 2L,
                structureGeneration = 2L,
                rowTexts = arrayOf("beta", "gamma"),
                lineIds = longArrayOf(0L, 0L),
            ),
        )
        var carry = scheduleForFrameAndReadIds(controller, cache, detectorCalls)
        assertEquals(0, carry.ids[cache.rowOffset(0)])

        cache.accept(
            StaticTextFrame(
                frameGeneration = 3L,
                structureGeneration = 3L,
                rowTexts = arrayOf("https://example.com", "alpha"),
                lineIds = longArrayOf(0L, 0L),
            ),
        )
        carry = scheduleForFrameAndReadIds(controller, cache, detectorCalls)

        assertEquals(0, carry.ids[cache.rowOffset(0)])
        assertEquals(1, carry.detectorCalls)
    }

    @Test
    fun `scroll does not preserve discovered links when row generation changes`() {
        val cache = TerminalRenderCache(24, 2)
        cache.accept(
            StaticTextFrame(
                frameGeneration = 1L,
                structureGeneration = 1L,
                rowTexts = arrayOf("https://example.com", "omega"),
                lineIds = longArrayOf(10L, 11L),
            ),
        )
        val detectorCalls = AtomicInteger()
        val repaintObserved = CountDownLatch(1)
        val host =
            TestDiscoveryHost(
                renderCache = cache,
                hyperlinkDetector =
                    SwingHyperlinkDetector { _, sink ->
                        detectorCalls.incrementAndGet()
                        sink.addHyperlink(
                            lineIndex = 0,
                            startOffset = 0,
                            endOffset = "https://example.com".length,
                            action = SwingHyperlinkAction.NONE,
                        )
                    },
                repaintObserved = repaintObserved,
            )
        val controller = TerminalHyperlinkDiscoveryController(host, testScope())

        SwingUtilities.invokeAndWait {
            controller.scheduleForFrame()
        }
        awaitRepaintAndDrainEdt(repaintObserved)

        cache.accept(
            StaticTextFrame(
                frameGeneration = 2L,
                structureGeneration = 2L,
                rowTexts = arrayOf("new", "https://example.com"),
                lineIds = longArrayOf(9L, 10L),
                lineGenerations = longArrayOf(1L, 2L),
            ),
        )
        val carry = scheduleForFrameAndReadIds(controller, cache, detectorCalls)

        assertEquals(0, carry.ids[cache.rowOffset(1)])
        assertEquals(1, carry.detectorCalls)
    }

    @Test
    fun `preserved discovered links do not overwrite current OSC8 ids`() {
        val cache = TerminalRenderCache(24, 2)
        cache.accept(
            StaticTextFrame(
                frameGeneration = 1L,
                structureGeneration = 1L,
                rowTexts = arrayOf("https://example.com", "omega"),
                lineIds = longArrayOf(0L, 0L),
            ),
        )
        val repaintObserved = CountDownLatch(1)
        val host =
            TestDiscoveryHost(
                renderCache = cache,
                hyperlinkDetector =
                    SwingHyperlinkDetector { _, sink ->
                        sink.addHyperlink(
                            lineIndex = 0,
                            startOffset = 0,
                            endOffset = "https://example.com".length,
                            action = SwingHyperlinkAction.NONE,
                        )
                    },
                repaintObserved = repaintObserved,
            )
        val controller = TerminalHyperlinkDiscoveryController(host, testScope())

        SwingUtilities.invokeAndWait {
            controller.scheduleForFrame()
        }
        awaitRepaintAndDrainEdt(repaintObserved)

        cache.accept(
            StaticTextFrame(
                frameGeneration = 2L,
                structureGeneration = 2L,
                rowTexts = arrayOf("new", "https://example.com"),
                lineIds = longArrayOf(0L, 0L),
                hyperlinkIds =
                    arrayOf(
                        IntArray(24),
                        IntArray(24).apply { this[0] = 7 },
                    ),
            ),
        )
        val ids = scheduleForFrameAndReadIds(controller, cache).ids

        assertEquals(7, ids[cache.rowOffset(1)])
        assertEquals(-1, ids[cache.rowOffset(1) + 1])
        assertFalse(controller.isDiscoveredHyperlinkResolvable(7, cache))
    }

    @Test
    fun `snapshot joins soft-wrapped rows into one logical filter line`() {
        val cache = TerminalRenderCache(5, 2)
        writeText(cache, row = 0, text = "https")
        writeText(cache, row = 1, text = "://a ")
        cache.lineWrapped[0] = true

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals(1, snapshot.request.lineCount)
        assertEquals("https://a\n", snapshot.request.lineText(0))
        assertEquals(10, snapshot.request.lineEndOffset(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 0, 9, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(-1, ids[cache.rowOffset(0)])
        assertEquals(-1, ids[cache.rowOffset(0) + 4])
        assertEquals(-1, ids[cache.rowOffset(1)])
        assertEquals(-1, ids[cache.rowOffset(1) + 3])
        assertEquals(0, ids[cache.rowOffset(1) + 4])
    }

    @Test
    fun `discovered links do not overwrite terminal-authored OSC8 ids`() {
        val cache = TerminalRenderCache(4, 1)
        writeText(cache, row = 0, text = "abcd")
        cache.hyperlinkIds[cache.rowOffset(0) + 1] = 7

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)
        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 0, 4, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(-1, ids[0])
        assertEquals(7, ids[1])
        assertEquals(-1, ids[2])
        assertEquals(-1, ids[3])
    }

    @Test
    fun `utf16 offsets map to full wide-cell spans`() {
        val cache = TerminalRenderCache(4, 1)
        writeCodePoint(cache, row = 0, column = 0, codePoint = 'A'.code)
        writeCodePoint(
            cache = cache,
            row = 0,
            column = 1,
            codePoint = 0x4E2D,
            flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
        )
        cache.flags[2] = TerminalRenderCellFlags.WIDE_TRAILING
        writeCodePoint(cache, row = 0, column = 3, codePoint = 'B'.code)

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals("A\u4E2DB\n", snapshot.request.lineText(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 1, 2, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(0, ids[0])
        assertEquals(-1, ids[1])
        assertEquals(-1, ids[2])
        assertEquals(0, ids[3])
    }

    @Test
    fun `surrogate pair offsets map to the same terminal cell span`() {
        val cache = TerminalRenderCache(3, 1)
        writeCodePoint(
            cache = cache,
            row = 0,
            column = 0,
            codePoint = 0x1F600,
            flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
        )
        cache.flags[1] = TerminalRenderCellFlags.WIDE_TRAILING
        writeCodePoint(cache, row = 0, column = 2, codePoint = '!'.code)

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals("\uD83D\uDE00!\n", snapshot.request.lineText(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 0, 2, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(-1, ids[0])
        assertEquals(-1, ids[1])
        assertEquals(0, ids[2])
    }

    @Test
    fun `combining cluster offsets map to the same terminal cell span`() {
        val cache = TerminalRenderCache(3, 1)
        cache.accept(CombiningClusterFrame())

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals("e\u0301!\n", snapshot.request.lineText(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 1, 2, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(-1, ids[0])
        assertEquals(0, ids[1])
        assertEquals(0, ids[2])
    }

    @Test
    fun `line separators do not map outside the viewport`() {
        val cache = TerminalRenderCache(2, 1)
        writeText(cache, row = 0, text = "a ")

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals("a\n", snapshot.request.lineText(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 1, 2, SwingHyperlinkAction.NONE)),
            )

        assertNull(overlay)
    }

    private fun writeText(
        cache: TerminalRenderCache,
        row: Int,
        text: String,
    ) {
        var column = 0
        for (ch in text) {
            writeCodePoint(cache, row, column, ch.code)
            column++
        }
    }

    private fun writeCodePoint(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
        codePoint: Int,
        flags: Int = TerminalRenderCellFlags.CODEPOINT,
    ) {
        val index = cache.rowOffset(row) + column
        cache.flags[index] = flags
        cache.codeWords[index] = codePoint
    }

    private fun scheduleForFrameAndReadIds(
        controller: TerminalHyperlinkDiscoveryController,
        cache: TerminalRenderCache,
        detectorCalls: AtomicInteger? = null,
    ): FrameScheduleResult {
        var ids = IntArray(0)
        var calls = 0
        SwingUtilities.invokeAndWait {
            controller.scheduleForFrame()
            ids = controller.hyperlinkIdsFor(cache).copyOf(cache.rows * cache.columns)
            calls = detectorCalls?.get() ?: 0
        }
        return FrameScheduleResult(ids, calls)
    }

    private fun awaitRepaintAndDrainEdt(repaintObserved: CountDownLatch) {
        assertTrue(repaintObserved.await(3, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {
        }
    }

    private fun testScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        SwingUtilities.invokeLater(block)
                    }
                },
        )

    private data class FrameScheduleResult(
        val ids: IntArray,
        val detectorCalls: Int,
    )

    private class TestDiscoveryHost(
        override val renderCache: TerminalRenderCache,
        override val hyperlinkDetector: SwingHyperlinkDetector,
        private val repaintObserved: CountDownLatch = CountDownLatch(0),
    ) : TerminalHyperlinkDiscoveryHost {
        var onHyperlinksChanged: () -> Unit = {}

        override fun hyperlinksChanged() = onHyperlinksChanged()

        override fun repaintHyperlinkSpan(
            startRow: Int,
            startColumn: Int,
            endRow: Int,
            endColumn: Int,
        ) {
            repaintObserved.countDown()
        }
    }

    private class StaticTextFrame(
        override val frameGeneration: Long,
        override val structureGeneration: Long,
        private val rowTexts: Array<String>,
        private val lineIds: LongArray,
        private val lineGenerations: LongArray = LongArray(rowTexts.size) { 1L },
        private val wrappedRows: BooleanArray = BooleanArray(rowTexts.size),
        private val hyperlinkIds: Array<IntArray> = Array(rowTexts.size) { IntArray(DEFAULT_COLUMNS) },
    ) : TerminalRenderFrame {
        override val columns: Int = DEFAULT_COLUMNS
        override val rows: Int = rowTexts.size
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 0L,
            )

        override fun lineGeneration(row: Int): Long = lineGenerations[row]

        override fun lineId(row: Int): Long = lineIds[row]

        override fun lineWrapped(row: Int): Boolean = wrappedRows[row]

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = 0
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, this.hyperlinkIds[row][column])
                column++
            }

            val text = rowTexts[row]
            column = 0
            while (column < text.length && column < columns) {
                codeWords[codeOffset + column] = text[column].code
                flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                column++
            }
        }
    }

    private class CombiningClusterFrame : TerminalRenderFrame {
        override val columns: Int = 3
        override val rows: Int = 1
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 0L,
            )

        override fun lineGeneration(row: Int): Long = 1L

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = 0
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }

            flags[flagOffset] = TerminalRenderCellFlags.CLUSTER
            val clusterCodePoints = intArrayOf('e'.code, 0x0301)
            clusterDataSink?.onCluster(0, clusterCodePoints, 0, clusterCodePoints.size)

            codeWords[codeOffset + 1] = '!'.code
            flags[flagOffset + 1] = TerminalRenderCellFlags.CODEPOINT
        }
    }

    private companion object {
        private const val DEFAULT_COLUMNS = 24
    }
}

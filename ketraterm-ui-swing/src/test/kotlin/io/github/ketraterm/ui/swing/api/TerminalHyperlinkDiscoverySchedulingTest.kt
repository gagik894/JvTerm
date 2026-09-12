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
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.Runnable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalHyperlinkDiscoverySchedulingTest {
    @Test
    fun `newly visible links are detected without advancing time`() {
        Fixture().use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            assertEquals(1, fixture.worker.pendingTasks)

            fixture.completeAnalysis()

            fixture.onEdt {
                assertTrue(fixture.controller.hyperlinkIdAt(0, 0, fixture.cache) < 0)
                assertTrue(fixture.openAt(0))
            }
            assertEquals(listOf(URL), fixture.opened)
            assertEquals(0L, fixture.uiDispatcher.scheduler.currentTime)
        }
    }

    @Test
    fun `frames arriving during analysis coalesce into the latest pending text`() {
        Fixture().use { fixture ->
            fixture.show("https://old.example")
            fixture.drainUi()
            assertEquals(1, fixture.worker.pendingTasks)

            fixture.show("https://intermediate.example")
            fixture.show("https://latest.example")
            fixture.drainUi()
            assertEquals(1, fixture.worker.pendingTasks, "Only the original analysis is queued")

            fixture.completeAnalysis()
            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
            }
            assertEquals(1, fixture.worker.pendingTasks, "The latest text is analyzed after completion")
            fixture.completeAnalysis()

            assertEquals(
                listOf(listOf("https://old.example\n"), listOf("https://latest.example\n")),
                fixture.requests,
            )
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf("https://latest.example"), fixture.opened)
        }
    }

    @Test
    fun `completed analysis publishes an unchanged line while another line is updating`() {
        Fixture().use { fixture ->
            fixture.show(URL, "building 10%")
            fixture.drainUi()

            fixture.show(URL, "building 11%")
            fixture.show(URL, "building 12%")
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.onEdt { assertTrue(fixture.openAt(0), "Unrelated output must not reject this result") }
            assertEquals(listOf(URL), fixture.opened)
            fixture.completeAnalysis()
            assertEquals(listOf("building 12%\n"), fixture.requests.last())
        }
    }

    @Test
    fun `unchanged text reuses both hyperlinks and empty detection results across generations`() {
        Fixture().use { fixture ->
            fixture.show(URL, "no link here")
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(1, fixture.requests.size)

            fixture.show(URL, "no link here")
            fixture.drainUi()
            assertEquals(0, fixture.worker.pendingTasks)
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(1, fixture.requests.size)

            fixture.show(URL, "still no link here")
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(listOf("still no link here\n"), fixture.requests.last())
        }
    }

    @Test
    fun `scroll reuses visible line results and immediately analyzes newly exposed text`() {
        Fixture().use { fixture ->
            fixture.show(URL, "old bottom", lineIds = longArrayOf(10, 11))
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show("https://new.example", URL, lineIds = longArrayOf(9, 10))
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
            fixture.drainUi()
            assertEquals(1, fixture.worker.pendingTasks)
            fixture.completeAnalysis()

            assertEquals(listOf("https://new.example\n"), fixture.requests.last())
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf(URL, "https://new.example"), fixture.opened)
        }
    }

    @Test
    fun `duplicating identityless text keeps each row independently clickable`() {
        Fixture().use { fixture ->
            fixture.show(URL, "other text", lineIds = longArrayOf(0, 0))
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show(URL, URL, lineIds = longArrayOf(0, 0))
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.onEdt {
                val first = fixture.controller.hyperlinkIdAt(0, 0, fixture.cache)
                val second = fixture.controller.hyperlinkIdAt(1, 0, fixture.cache)
                assertTrue(first < 0, "The first duplicate row must retain its own overlay position")
                assertTrue(second < 0)
                assertNotEquals(first, second)
                assertTrue(fixture.openAt(0))
                assertTrue(fixture.openAt(1))
            }
            assertEquals(listOf(URL, URL), fixture.opened)
        }
    }

    @Test
    fun `identical identityless rows accept their own snapshots without repeated detection`() {
        Fixture().use { fixture ->
            fixture.show(URL, URL, lineIds = longArrayOf(0, 0))
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.onEdt {
                assertTrue(fixture.openAt(0))
                assertTrue(fixture.openAt(1))
                assertNotEquals(
                    fixture.controller.hyperlinkIdAt(0, 0, fixture.cache),
                    fixture.controller.hyperlinkIdAt(1, 0, fixture.cache),
                )
            }
            assertEquals(listOf(URL, URL), fixture.opened)
            assertEquals(0, fixture.worker.pendingTasks, "Each exact snapshot has a known current line")
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `explicit URL dependency keeps an existing action usable during percentage changes`() {
        Fixture(validateUrlToken = true).use { fixture ->
            fixture.show("$URL 10%")
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show("$URL 11%")
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            fixture.drainUi()
            fixture.show("$URL 12%")
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }

            assertEquals(listOf(URL, URL, URL), fixture.opened)
        }
    }

    @Test
    fun `explicit URL dependency accepts an in-flight result after the percentage changes`() {
        Fixture(validateUrlToken = true).use { fixture ->
            fixture.show("$URL 10%")
            fixture.drainUi()
            fixture.show("$URL 11%")
            fixture.show("$URL 12%")
            fixture.completeAnalysis()

            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf(URL), fixture.opened)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://example.com/next 11%", "https://example.comx 11%", "https://changed.com 11%"])
    fun `editing URL text or its token boundary immediately invalidates its previous action`(changedText: String) {
        Fixture(validateUrlToken = true).use { fixture ->
            fixture.show("$URL 10%")
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show(changedText)

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.openAt(0))
            }
            assertTrue(fixture.opened.isEmpty())
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf(changedText.substringBefore(' ')), fixture.opened)
        }
    }

    @Test
    fun `URL extension at the previous end of line invalidates a token-scoped action`() {
        Fixture(validateUrlToken = true).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show("$URL/next")

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.openAt(0))
            }
        }
    }

    @Test
    fun `opaque actions depend on the whole logical line by default`() {
        Fixture().use { fixture ->
            fixture.show("$URL 10%")
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show("$URL 11%")

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.openAt(0))
            }
        }
    }

    @Test
    fun `reset waits for an executing detector and discards its obsolete result`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val opened = ArrayList<String>()
        val detector =
            SwingHyperlinkDetector { request, sink ->
                val activeCount = active.incrementAndGet()
                maximumActive.accumulateAndGet(activeCount, ::maxOf)
                try {
                    val invocation = invocations.incrementAndGet()
                    if (invocation == 1) {
                        entered.countDown()
                        assertTrue(release.await(3, TimeUnit.SECONDS), "Test did not release the original detector")
                    }
                    assertEquals("$URL\n", request.lineText(0))
                    sink.addHyperlink(0, 0, URL.length, SwingHyperlinkAction { opened.add("request $invocation") })
                } finally {
                    active.decrementAndGet()
                }
            }
        Fixture(detector = detector).use { fixture ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.show(URL)
                fixture.drainUi()
                val original = executor.submit { fixture.worker.runCurrent() }
                assertTrue(entered.await(3, TimeUnit.SECONDS), "Detector did not start")

                fixture.onEdt { fixture.controller.reset() }
                fixture.show(URL)
                fixture.drainUi()
                assertEquals(0, fixture.worker.pendingTasks, "Reset must not overlap a synchronous detector")

                release.countDown()
                original.get(3, TimeUnit.SECONDS)
                fixture.drainUi()
                fixture.onEdt {
                    assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                }
                fixture.completeAnalysis()

                fixture.onEdt { assertTrue(fixture.openAt(0)) }
                assertEquals(listOf("request 2"), opened)
                assertEquals(2, invocations.get())
                assertEquals(1, maximumActive.get())
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `dispose prevents queued results and future frames from restoring links`() {
        Fixture().use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            val publicationsBeforeDispose = fixture.publications
            fixture.onEdt { fixture.controller.dispose() }
            fixture.completeAnalysis()

            fixture.show(URL)
            fixture.drainUi()

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.openAt(0))
            }
            assertEquals(0, fixture.worker.pendingTasks)
            assertEquals(publicationsBeforeDispose, fixture.publications)
        }
    }

    @Test
    fun `cancelled host detection does not continuously reschedule unchanged work`() {
        val invocations = AtomicInteger()
        val detector =
            SwingHyperlinkDetector { _, sink ->
                if (invocations.incrementAndGet() == 1) throw CancellationException("Host read was cancelled")
                sink.addHyperlink(0, 0, URL.length, SwingHyperlinkAction { true })
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()

            assertEquals(0, fixture.worker.pendingTasks, "Cancellation must not create an immediate retry loop")
            assertEquals(1, invocations.get())

            fixture.show("$URL/next")
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(2, invocations.get())
        }
    }

    @Test
    fun `cancelled detection still drains a newer frame that arrived while it was running`() {
        val requests = ArrayList<String>()
        val opened = ArrayList<String>()
        val detector =
            SwingHyperlinkDetector { request, sink ->
                val url = request.lineText(0).trimEnd('\n')
                requests += url
                if (requests.size == 1) throw CancellationException("Host read was cancelled")
                sink.addHyperlink(0, 0, url.length, SwingHyperlinkAction { opened.add(url) })
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.show("$URL/next")

            fixture.completeAnalysis()
            assertEquals(listOf(URL), requests)
            assertEquals(1, fixture.worker.pendingTasks, "Cancellation must not lose the already pending frame")

            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf(URL, "$URL/next"), requests)
            assertEquals(listOf("$URL/next"), opened)
            assertEquals(0, fixture.worker.pendingTasks)
        }
    }

    @Test
    fun `viewport detection retains preceding context when only a later line changes`() {
        val requests = ArrayList<List<String>>()
        val detector =
            viewportDetector { request, sink ->
                requests += List(request.lineCount) { request.lineText(it) }
                if (request.lineText(0) == "exception context\n") {
                    sink.addHyperlink(1, 0, 6, SwingHyperlinkAction { true })
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show("exception context", "source.kt:10")
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.show("exception context", "source.kt:11")
            fixture.drainUi()
            fixture.completeAnalysis()

            assertEquals(
                listOf(
                    listOf("exception context\n", "source.kt:10\n"),
                    listOf("exception context\n", "source.kt:11\n"),
                ),
                requests,
            )
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
        }
    }

    @Test
    fun `preceding context changes invalidate contextual actions while explicit URL dependencies survive`() {
        val opened = ArrayList<String>()
        val detector =
            viewportDetector { request, sink ->
                val context = request.lineText(0)
                sink.addHyperlink(1, 0, 6, SwingHyperlinkAction { opened.add(context) })
                emitUrls(request, sink, opened, validateUrlToken = true)
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show("old context", "source.kt:10", URL)
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
            assertEquals(listOf("old context\n"), opened)
            opened.clear()

            fixture.show("new context", "source.kt:10", URL)

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(1, 0, fixture.cache))
                assertFalse(fixture.openAt(1))
                assertTrue(fixture.openAt(2))
            }
            assertEquals(listOf(URL), opened)
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
            assertEquals(listOf(URL, "new context\n"), opened)
        }
    }

    @Test
    fun `in-flight viewport results reject changed context but accept independent URLs`() {
        val opened = ArrayList<String>()
        val detector =
            viewportDetector { request, sink ->
                val context = request.lineText(0)
                sink.addHyperlink(1, 0, 6, SwingHyperlinkAction { opened.add(context) })
                emitUrls(request, sink, opened, validateUrlToken = true)
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show("old context", "source.kt:10", URL)
            fixture.drainUi()
            fixture.show("new context", "source.kt:10", URL)
            fixture.completeAnalysis()

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(1, 0, fixture.cache))
                assertFalse(fixture.openAt(1))
                assertTrue(fixture.openAt(2))
            }
            assertEquals(listOf(URL), opened)
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
            assertEquals(listOf(URL, "new context\n"), opened)
        }
    }

    private class Fixture(
        validateUrlToken: Boolean = false,
        detector: SwingHyperlinkDetector? = null,
    ) : AutoCloseable {
        val cache = TerminalRenderCache(COLUMNS, 1)
        val uiDispatcher = StandardTestDispatcher()
        val worker = QueuedDispatcher()
        val opened = ArrayList<String>()
        val requests = ArrayList<List<String>>()
        var publications = 0
            private set
        private var frameGeneration = 0L
        private val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
        private val host =
            object : TerminalHyperlinkDiscoveryHost {
                override val renderCache = cache
                override val hyperlinkDetector =
                    detector ?: SwingHyperlinkDetector { request, sink ->
                        requests += List(request.lineCount) { request.lineText(it) }
                        emitUrls(request, sink, opened, validateUrlToken)
                    }

                override fun hyperlinksChanged() {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    publications++
                }

                override fun repaintHyperlinkSpan(
                    startRow: Int,
                    startColumn: Int,
                    endRow: Int,
                    endColumn: Int,
                ) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                }
            }
        val controller = TerminalHyperlinkDiscoveryController(host, scope, worker)

        fun show(
            vararg lines: String,
            lineIds: LongArray = LongArray(lines.size) { it + 1L },
        ) = onEdt {
            cache.accept(TextFrame(++frameGeneration, lines, lineIds))
            controller.scheduleForFrame()
        }

        fun openAt(row: Int): Boolean = controller.openDiscoveredHyperlink(controller.hyperlinkIdAt(row, 0, cache), cache)

        fun drainUi() = onEdt { uiDispatcher.scheduler.runCurrent() }

        fun completeAnalysis() {
            worker.runCurrent()
            drainUi()
        }

        fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

        override fun close() {
            onEdt {
                controller.dispose()
                scope.cancel()
            }
            worker.runCurrent()
            drainUi()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        val pendingTasks: Int
            get() = queue.size

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
        }

        fun runCurrent() {
            while (true) {
                val next = queue.poll() ?: return
                next.run()
            }
        }
    }

    private class TextFrame(
        override val frameGeneration: Long,
        private val lines: Array<out String>,
        private val lineIds: LongArray,
    ) : TerminalRenderFrame {
        override val structureGeneration = 1L
        override val columns = COLUMNS
        override val rows = lines.size
        override val activeBuffer = TerminalRenderBufferKind.PRIMARY
        override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 0)

        override fun lineGeneration(row: Int): Long = frameGeneration

        override fun lineId(row: Int): Long = lineIds[row]

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
            val text = lines[row]
            for (column in 0 until columns) {
                codeWords[codeOffset + column] = text.getOrNull(column)?.code ?: 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = if (column < text.length) TerminalRenderCellFlags.CODEPOINT else 0
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
            }
        }
    }

    private companion object {
        private const val COLUMNS = 64
        private const val URL = "https://example.com"

        private fun viewportDetector(
            onDetect: (SwingHyperlinkDetectionRequest, SwingHyperlinkDetectionSink) -> Unit,
        ): SwingHyperlinkDetector =
            object : SwingHyperlinkDetector {
                override val context = SwingHyperlinkDetectionContext.VIEWPORT

                override fun detect(
                    request: SwingHyperlinkDetectionRequest,
                    sink: SwingHyperlinkDetectionSink,
                ) = onDetect(request, sink)
            }

        private fun emitUrls(
            request: SwingHyperlinkDetectionRequest,
            sink: SwingHyperlinkDetectionSink,
            opened: MutableList<String>,
            validateUrlToken: Boolean,
        ) {
            assertFalse(SwingUtilities.isEventDispatchThread(), "Host detection must stay outside the EDT")
            for (lineIndex in 0 until request.lineCount) {
                val text = request.lineText(lineIndex)
                if (!text.startsWith("https://")) continue
                val url = text.takeWhile { !it.isWhitespace() }
                sink.addHyperlink(
                    lineIndex = lineIndex,
                    startOffset = 0,
                    endOffset = url.length,
                    action = SwingHyperlinkAction { opened.add(url) },
                    validationStartOffset = 0,
                    validationEndOffset = if (validateUrlToken) url.length + 1 else Int.MAX_VALUE,
                )
            }
        }
    }
}

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
package io.github.ketraterm.benchmark

import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.SwingHyperlinkAction
import io.github.ketraterm.ui.swing.api.SwingHyperlinkDetector
import io.github.ketraterm.ui.swing.api.TerminalHyperlinkDiscoveryController
import io.github.ketraterm.ui.swing.api.TerminalHyperlinkDiscoveryHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.openjdk.jmh.annotations.*
import java.lang.Runnable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

/**
 * EDT frame refresh with an already detected 80-by-24 viewport. Worker analysis
 * is paused after setup so the measurement isolates frame copying, link carry
 * and scheduling. EDT dispatch is amortized across [FRAMES_PER_BATCH] frames.
 */
@State(Scope.Benchmark)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalHyperlinkDiscoveryBenchmark {
    @Param("false", "true")
    @JvmField
    var changingProgress: Boolean = false

    private lateinit var cache: TerminalRenderCache
    private lateinit var frame: UpdatingFrame
    private lateinit var controller: TerminalHyperlinkDiscoveryController
    private lateinit var scope: CoroutineScope
    private lateinit var analysisDispatcher: PausingDispatcher
    private var result = 0
    private val updateBatch =
        Runnable {
            var checksum = 0
            repeat(FRAMES_PER_BATCH) {
                frame.generation++
                cache.accept(frame)
                if (changingProgress) {
                    cache.codeWords[(ROWS - 1) * COLUMNS + PROGRESS_COLUMN] = '0'.code + (frame.generation % 10).toInt()
                }
                controller.scheduleForFrame()
                checksum += controller.hyperlinkIdAt(0, 0, cache)
            }
            result = checksum
        }

    @Setup(Level.Trial)
    open fun setup() {
        val installed = CountDownLatch(1)
        SwingUtilities.invokeAndWait {
            frame = UpdatingFrame(changingProgress)
            cache = TerminalRenderCache(COLUMNS, ROWS).apply { accept(frame) }
            analysisDispatcher = PausingDispatcher()
            scope = CoroutineScope(SupervisorJob() + EdtDispatcher)
            val action = SwingHyperlinkAction { true }
            val host =
                object : TerminalHyperlinkDiscoveryHost {
                    override val renderCache: TerminalRenderCache = cache
                    override val hyperlinkDetector =
                        SwingHyperlinkDetector { request, sink ->
                            for (line in 0 until request.lineCount) {
                                sink.addHyperlink(
                                    line,
                                    0,
                                    URL.length,
                                    action,
                                    validationStartOffset = 0,
                                    validationEndOffset = URL.length + 1,
                                )
                            }
                        }

                    override fun hyperlinksChanged() {
                        if (controller.hyperlinkIdAt(0, 0, cache) < 0) installed.countDown()
                    }

                    override fun repaintHyperlinkSpan(
                        startRow: Int,
                        startColumn: Int,
                        endRow: Int,
                        endColumn: Int,
                    ) = Unit
                }
            controller = TerminalHyperlinkDiscoveryController(host, scope, analysisDispatcher)
            controller.scheduleForFrame()
        }
        check(installed.await(5, TimeUnit.SECONDS)) { "Initial hyperlink detection did not finish" }
        SwingUtilities.invokeAndWait {
            check(controller.hyperlinkIdAt(0, 0, cache) < 0)
            analysisDispatcher.paused = true
        }
    }

    @Benchmark
    @OperationsPerInvocation(FRAMES_PER_BATCH)
    open fun refreshFrame(): Int {
        SwingUtilities.invokeAndWait(updateBatch)
        return result
    }

    @TearDown(Level.Trial)
    open fun tearDown() {
        SwingUtilities.invokeAndWait {
            controller.dispose()
            scope.cancel()
            analysisDispatcher.drainCancelledWork()
        }
    }

    private class UpdatingFrame(
        private val changingProgress: Boolean,
    ) : TerminalRenderFrame by TerminalRenderBenchmarkFrame(List(ROWS) { "$URL 0% row $it".padEnd(COLUMNS) }) {
        var generation = 1L
        override val frameGeneration: Long get() = generation
        override val contentGeneration: Long get() = if (changingProgress) generation else 1L

        override fun lineGeneration(row: Int): Long = if (changingProgress && row == ROWS - 1) generation else 1L
    }

    private class PausingDispatcher : CoroutineDispatcher() {
        var paused = false
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            check(SwingUtilities.isEventDispatchThread())
            if (paused) pending.addLast(block) else Dispatchers.Default.dispatch(context, block)
        }

        fun drainCancelledWork() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    private object EdtDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !SwingUtilities.isEventDispatchThread()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = SwingUtilities.invokeLater(block)
    }

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 24
        const val URL = "https://example.com/build"
        val PROGRESS_COLUMN = URL.length + 1
        const val FRAMES_PER_BATCH = 256
    }
}

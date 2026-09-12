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

import io.github.ketraterm.ui.swing.api.TerminalViewportListener
import io.github.ketraterm.ui.swing.viewport.SwingViewportController
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Publishes primitive viewport snapshots on the EDT; each batch includes its dispatch cost. */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalViewportPublicationBenchmark {
    private lateinit var controller: SwingViewportController
    private var callbackChecksum = 0L
    private val publishBatch =
        Runnable {
            repeat(PUBLICATIONS_PER_BATCH) {
                controller.publishViewportState(
                    historySize = 100,
                    visibleRows = 24,
                    renderRows = 25,
                    viewportHeightPixels = 480,
                    contentHeightPixels = 500,
                    notifyListener = false,
                    notifyPrimitiveListener = true,
                )
            }
        }

    @Setup
    open fun setup() {
        SwingUtilities.invokeAndWait {
            val listener =
                object : TerminalViewportListener {
                    override fun viewportChanged(
                        historySize: Int,
                        scrollbackOffset: Double,
                        renderOffset: Int,
                        visibleRows: Int,
                        requestedRows: Int,
                    ) {
                        callbackChecksum += historySize + scrollbackOffset.toLong() + renderOffset + visibleRows + requestedRows
                    }
                }
            controller = SwingViewportController(listener) { _, _ -> }
            controller.updateCellHeight(20)
            controller.scrollTo(12.5, 100)
            publishBatch.run()
            check(controller.viewportStateSnapshot().scrollbackOffset == 12.5)
        }
    }

    @Benchmark
    @OperationsPerInvocation(PUBLICATIONS_PER_BATCH)
    open fun publishPrimitiveSnapshotsOnEdt(): Long {
        SwingUtilities.invokeAndWait(publishBatch)
        return callbackChecksum
    }

    @TearDown
    open fun tearDown() {
        SwingUtilities.invokeAndWait { controller.cancelScroll() }
    }

    private companion object {
        const val PUBLICATIONS_PER_BATCH = 1024
    }
}

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

import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.viewport.SwingRepaintPlanner
import io.github.ketraterm.ui.swing.viewport.TerminalRepaintSink
import org.openjdk.jmh.annotations.*
import java.awt.Insets
import java.util.concurrent.TimeUnit

/** Isolates repaint planning with copied frames and primitive damage collection. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalRepaintBenchmark {
    private lateinit var shortCache: TerminalRenderCache
    private lateinit var tallCache: TerminalRenderCache
    private lateinit var planner: SwingRepaintPlanner
    private lateinit var highlights: TerminalSearchViewportHighlights
    private val metrics = SwingMetrics(10, 20, 15, 16, 10, 0, 1)
    private val padding = Insets(0, 0, 0, 0)
    private val sink = CountingRepaintSink()
    private var active = false

    @Setup
    open fun setup() {
        shortCache = TerminalRenderCache(80, 24).apply { accept(TerminalRenderBenchmarkFrame(List(24) { "A".repeat(80) })) }
        tallCache = TerminalRenderCache(80, 25).apply { accept(TerminalRenderBenchmarkFrame(List(25) { "A".repeat(80) })) }
        planner = SwingRepaintPlanner()
        highlights = TerminalSearchViewportHighlights()
        updateSearchProjectionAndPlan()
        planOverscanFramesAndReset()
    }

    /** One frame projects 192 search spans, changes their active styling, and plans row damage. */
    @Benchmark
    open fun updateSearchProjectionAndPlan(): Long {
        active = !active
        highlights.reset(24)
        for (row in 0 until 24) {
            for (column in 0 until 80 step 10) highlights.add(row, column, column + 3, active)
        }
        highlights.finish()
        planner.requestFrameRepaint(shortCache, metrics, 800, 480, padding, sink, searchHighlights = highlights)
        return sink.checksum
    }

    /** One operation contains two frame plans and one reset. */
    @Benchmark
    open fun planOverscanFramesAndReset(): Long {
        planner.requestFrameRepaint(shortCache, metrics, 800, 480, padding, sink)
        planner.requestFrameRepaint(tallCache, metrics, 800, 480, padding, sink)
        planner.reset()
        return sink.checksum
    }

    private class CountingRepaintSink : TerminalRepaintSink {
        var checksum = 0L

        override fun requestFullRepaint() {
            checksum++
        }

        override fun requestRegionRepaint(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            checksum += x + y + width + height
        }
    }
}

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
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout
import io.github.ketraterm.ui.swing.render.forEachVisualCellSpan
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Measures retained bidi lookups and overscan transitions on a worker-owned cache. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalBidiBenchmark {
    @Param("ltr", "rtl", "mixed")
    lateinit var direction: String

    private lateinit var frames: Array<TerminalRenderBenchmarkFrame>
    private lateinit var cache: TerminalRenderCache
    private lateinit var layout: TerminalBidiLayout
    private var nextFrame = 0

    @Setup
    open fun setup() {
        val text =
            when (direction) {
                "ltr" -> "A".repeat(80)
                "rtl" -> "א".repeat(80)
                "mixed" -> "AB אבג".padEnd(80)
                else -> error("Unknown direction: $direction")
            }
        frames = arrayOf(TerminalRenderBenchmarkFrame(List(24) { text }), TerminalRenderBenchmarkFrame(List(25) { text }))
        cache = TerminalRenderCache(80, 24, rowCapacityReserve = 1)
        layout = TerminalBidiLayout()
        cache.accept(frames[1])
        for (row in 0 until cache.rows) layout.row(cache, row)
        cache.accept(frames[0])
    }

    @Benchmark
    open fun cachedMappingAndRangeProjection(): Int {
        val row = layout.row(cache, 0)
        var checksum = row?.logicalColumn(3) ?: 3
        forEachVisualCellSpan(row, 1, 4) { start, end -> checksum += end - start }
        return checksum
    }

    /** Includes accepting the frame; compare with [copyOverscanFrame] to separate that work. */
    @Benchmark
    open fun copyOverscanFrameAndResolveBidi(): Int {
        cache.accept(frames[nextFrame])
        nextFrame = nextFrame xor 1
        return layout.row(cache, cache.rows - 1)?.logicalColumn(3) ?: 3
    }

    @Benchmark
    open fun copyOverscanFrame(): Int {
        cache.accept(frames[nextFrame])
        nextFrame = nextFrame xor 1
        return cache.rows
    }
}

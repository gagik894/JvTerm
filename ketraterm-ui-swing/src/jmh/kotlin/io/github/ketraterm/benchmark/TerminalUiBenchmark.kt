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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingSettingsProvider
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalLargeInputBenchmark {
    @Param("ascii", "styled", "cjk", "emoji")
    lateinit var workload: String

    private lateinit var bytes: ByteArray
    private lateinit var terminal: TerminalBuffer
    private lateinit var renderReader: io.github.ketraterm.render.api.TerminalRenderFrameReader
    private lateinit var publisher: TerminalRenderPublisher

    @Setup(Level.Trial)
    open fun setupBytes() {
        bytes =
            when (workload) {
                "ascii" -> buildAsciiInput(LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
                "styled" -> buildStyledInput(LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
                "cjk" -> buildCjkInput(LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
                "emoji" -> buildEmojiInput(LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
                else -> error("unknown workload: $workload")
            }
    }

    @Setup(Level.Trial)
    open fun setupTerminal() {
        terminal =
            TerminalBuffers.create(
                width = LARGE_INPUT_COLUMNS,
                height = LARGE_INPUT_ROWS,
                maxHistory = LARGE_INPUT_LINES,
            )
        renderReader = terminal as io.github.ketraterm.render.api.TerminalRenderFrameReader
        publisher = TerminalRenderPublisher(LARGE_INPUT_COLUMNS, LARGE_INPUT_ROWS)
    }

    @Setup(Level.Invocation)
    open fun clearTerminal() {
        terminal.clearAll()
    }

    @Benchmark
    open fun parseCoreAndPublishLargeInput(blackhole: Blackhole) {
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept(bytes, 0, bytes.size)
        publisher.updateAndPublish(renderReader)
        blackhole.consume(bytes.size)
        blackhole.consume(publisher.current()?.frameGeneration)
    }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalRenderPublishBenchmark {
    @Param("0", "5000", "15000")
    var scrollbackOffset: Int = 0

    private lateinit var renderReader: io.github.ketraterm.render.api.TerminalRenderFrameReader
    private lateinit var publisher: TerminalRenderPublisher

    @Setup(Level.Trial)
    open fun setupTerminal() {
        val terminal =
            TerminalBuffers.create(
                width = LARGE_INPUT_COLUMNS,
                height = LARGE_INPUT_ROWS,
                maxHistory = LARGE_INPUT_LINES,
            )
        writeScrollbackContent(terminal, LARGE_INPUT_LINES, LARGE_INPUT_COLUMNS)
        renderReader = terminal as io.github.ketraterm.render.api.TerminalRenderFrameReader
    }

    @Setup(Level.Trial)
    open fun setupPublisher() {
        publisher = TerminalRenderPublisher(LARGE_INPUT_COLUMNS, LARGE_INPUT_ROWS)
    }

    @Benchmark
    open fun publishViewportFromLargeScrollback(blackhole: Blackhole) {
        publisher.updateAndPublish(renderReader, scrollbackOffset)
        val cache = publisher.current()
        blackhole.consume(cache?.scrollbackOffset)
        val generations = cache?.lineGenerations
        if (generations != null) {
            var checksum = 0L
            var row = 0
            while (row < generations.size) {
                checksum = checksum xor generations[row]
                row++
            }
            blackhole.consume(checksum)
        }
    }
}

/** Complete component painting on the EDT, including amortized dispatch and Java2D costs. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
open class SwingPaintBenchmark {
    @Param("80", "160", "240")
    var columns: Int = 0

    @Param("24", "48")
    var rows: Int = 0

    @Param("ascii", "styled", "clusters")
    lateinit var workload: String

    private lateinit var terminal: TerminalBuffer
    private lateinit var session: TerminalSession
    private lateinit var component: SwingTerminal
    private lateinit var image: BufferedImage
    private lateinit var graphics: Graphics2D
    private var paintedPixel = 0
    private val paintBatch =
        Runnable {
            repeat(PAINT_BATCH_SIZE) {
                component.paint(graphics)
            }
            paintedPixel = image.getRGB(image.width - 1, image.height - 1)
        }

    @Setup
    open fun setup() {
        terminal = TerminalBuffers.create(columns, rows, maxHistory = rows)
        when (workload) {
            "ascii" -> writeAsciiViewport(terminal, rows, columns)
            "styled" -> writeStyledViewport(terminal, rows, columns)
            "clusters" -> writeClusterViewport(terminal, rows, columns)
            else -> error("unknown workload: $workload")
        }

        session = benchmarkSession(terminal)
        session.renderPublisher.updateAndPublish(terminal as io.github.ketraterm.render.api.TerminalRenderFrameReader)

        SwingUtilities.invokeAndWait {
            component =
                SwingTerminal(
                    SwingSettingsProvider {
                        SwingSettings(
                            columns = columns,
                            rows = rows,
                            cursorBlinkMillis = 0,
                            useSystemFallbackFonts = false,
                        )
                    },
                )
            component.size = component.preferredSize
            val grid = component.visibleGridSize()
            check(grid.width == columns && grid.height == rows)
            image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
            graphics = image.createGraphics()
            component.paint(graphics)
            val emptyPixels = image.getRGB(0, 0, image.width, image.height / 2, null, 0, image.width)
            component.bind(session)
            component.paint(graphics)
            val contentPixels = image.getRGB(0, 0, image.width, image.height / 2, null, 0, image.width)
            check(!emptyPixels.contentEquals(contentPixels)) { "Benchmark frame was not painted" }
        }
    }

    @TearDown
    open fun tearDown() {
        try {
            SwingUtilities.invokeAndWait {
                component.dispose()
                graphics.dispose()
            }
        } finally {
            session.close()
        }
    }

    @Benchmark
    @OperationsPerInvocation(PAINT_BATCH_SIZE)
    open fun paintPublishedFrame(): Int {
        SwingUtilities.invokeAndWait(paintBatch)
        return paintedPixel
    }
}

private const val PAINT_BATCH_SIZE = 64
private const val LARGE_INPUT_COLUMNS = 160
private const val LARGE_INPUT_ROWS = 48
private const val LARGE_INPUT_LINES = 20_000

private fun buildAsciiInput(
    lines: Int,
    columns: Int,
): ByteArray {
    val builder = StringBuilder(lines * (columns + 2))
    repeat(lines) { row ->
        appendLinePayload(builder, row, columns)
    }
    return builder.toString().toByteArray(StandardCharsets.UTF_8)
}

private fun buildStyledInput(
    lines: Int,
    columns: Int,
): ByteArray {
    val builder = StringBuilder(lines * (columns + 16))
    repeat(lines) { row ->
        builder.append("\u001b[3").append(row % 8).append('m')
        appendLinePayload(builder, row, columns)
        builder.append("\u001b[0m")
    }
    return builder.toString().toByteArray(StandardCharsets.UTF_8)
}

private fun appendLinePayload(
    builder: StringBuilder,
    row: Int,
    columns: Int,
) {
    var column = 0
    while (column < columns) {
        val codepoint = 'A'.code + ((row + column) % 26)
        builder.append(codepoint.toChar())
        column++
    }
    builder.append('\r').append('\n')
}

private fun writeScrollbackContent(
    terminal: TerminalBuffer,
    lines: Int,
    columns: Int,
) {
    repeat(lines) { row ->
        var column = 0
        while (column < columns) {
            terminal.writeCodepoint('0'.code + ((row + column) % 10))
            column++
        }
        terminal.carriageReturn()
        terminal.newLine()
    }
}

private fun writeAsciiViewport(
    terminal: TerminalBuffer,
    rows: Int,
    columns: Int,
) {
    for (row in 0 until rows) {
        terminal.positionCursor(0, row)
        var column = 0
        while (column < columns) {
            terminal.writeCodepoint('a'.code + ((row + column) % 26))
            column++
        }
    }
}

private fun writeStyledViewport(
    terminal: TerminalBuffer,
    rows: Int,
    columns: Int,
) {
    for (row in 0 until rows) {
        terminal.positionCursor(0, row)
        terminal.setPenAttributes(
            fg = row % 16 + 1,
            bg = (row / 2) % 16 + 1,
            bold = row % 3 == 0,
            italic = row % 5 == 0,
            strikethrough = row % 7 == 0,
        )
        var column = 0
        while (column < columns) {
            terminal.writeCodepoint('A'.code + ((row + column) % 26))
            column++
        }
    }
    terminal.resetPen()
}

/** CJK 3-byte UTF-8 characters — wide char width calculation end-to-end. */
private fun buildCjkInput(
    lines: Int,
    columns: Int,
): ByteArray {
    val sb = StringBuilder(lines * (columns + 2))
    repeat(lines) { row ->
        var col = 0
        while (col < columns) {
            val cp = 0x4E00 + (row + col) % (0x9FFF - 0x4E00)
            sb.appendCodePoint(cp)
            col++
        }
        sb.append('\r').append('\n')
    }
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

/** Emoji with ZWJ sequences — grapheme segmenter and cluster store end-to-end. */
private fun buildEmojiInput(
    lines: Int,
    columns: Int,
): ByteArray {
    val sb = StringBuilder(lines * (columns * 2 + 2))
    repeat(lines) { row ->
        var col = 0
        while (col < columns) {
            if ((row + col) % 8 == 0) {
                sb.appendCodePoint(0x1F468)
                sb.appendCodePoint(0x200D)
                sb.appendCodePoint(0x1F469)
                sb.appendCodePoint(0x200D)
                sb.appendCodePoint(0x1F467)
            } else {
                sb.append(('a'.code + (row + col) % 26).toChar())
            }
            col++
        }
        sb.append('\r').append('\n')
    }
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

private fun writeClusterViewport(
    terminal: TerminalBuffer,
    rows: Int,
    columns: Int,
) {
    val accentedE = intArrayOf('e'.code, 0x0301)
    for (row in 0 until rows) {
        terminal.positionCursor(0, row)
        var column = 0
        while (column < columns) {
            if ((row + column) % 8 == 0) {
                terminal.writeCluster(accentedE)
            } else {
                terminal.writeCodepoint('a'.code + ((row + column) % 26))
            }
            column++
        }
    }
}

internal fun benchmarkSession(terminal: TerminalBuffer): TerminalSession =
    TerminalSession.create(
        terminal = terminal,
        connector = NoOpTerminalConnector,
    )

private object NoOpTerminalConnector : TerminalConnector {
    override fun start(listener: TerminalConnectorListener) = Unit

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = Unit

    override fun resize(
        columns: Int,
        rows: Int,
    ) = Unit

    override fun close() = Unit
}

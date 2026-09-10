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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/** Temporary deterministic reproductions for the rendering audit. */
class ReviewRenderContractReproTest {
    @Test
    fun bindingBeforeFirstPublicationMustNotPaintPreviousSession() {
        val previous = session("alpha")
        val dispatcher = StandardTestDispatcher()
        val replacement = TerminalSession.create(
            TerminalBuffers.create(width = 12, height = 1, maxHistory = 0),
            NoOpConnector,
            workerDispatcher = dispatcher,
        )
        try {
            SwingUtilities.invokeAndWait {
                val settings = SwingSettings(columns = 12, rows = 1, padding = SwingPadding(),
                    shellIntegrationDecorationGutterWidth = 0, cursorBlinkMillis = 0)
                val reused = SwingTerminal(settingsProvider = { settings })
                val fresh = SwingTerminal(settingsProvider = { settings })
                fun pixels(component: SwingTerminal): IntArray {
                    val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
                    val graphics = image.createGraphics()
                    try {
                        component.paint(graphics)
                    } finally {
                        graphics.dispose()
                    }
                    return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                }
                try {
                    reused.size = reused.preferredGridSize(12, 1)
                    fresh.size = fresh.preferredGridSize(12, 1)
                    reused.bind(previous)
                    pixels(reused)
                    reused.bind(replacement)
                    fresh.bind(replacement)
                    assertNull(replacement.renderPublisher.current())
                    assertArrayEquals(pixels(fresh), pixels(reused),
                        "A replacement awaiting its first frame must not display the old source")
                } finally {
                    reused.dispose()
                    fresh.dispose()
                }
            }
        } finally {
            previous.close()
            replacement.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun reverseVideoMustInvalidateCachedHistoryAttributes() {
        val terminal = TerminalBuffers.create(width = 1, height = 1, maxHistory = 1)
        terminal.writeCodepoint('x'.code)
        terminal.scrollUp()
        val session = TerminalSession.create(terminal, NoOpConnector, workerDispatcher = Dispatchers.Unconfined)
        try {
            val reused = TerminalRenderCache(1, 1)
            reused.updateFrom(session, scrollbackOffset = 1)
            assertEquals('x'.code, reused.codeWords[0])
            terminal.setReverseVideo(true)
            reused.updateFrom(session, scrollbackOffset = 1)
            val fresh = TerminalRenderCache(1, 1)
            fresh.updateFrom(session, scrollbackOffset = 1)
            assertTrue(TerminalRenderAttrs.isInverse(fresh.attrWords[0]))
            assertEquals(fresh.attrWords[0], reused.attrWords[0],
                "Existing history view must repaint the same reverse-video attrs as a fresh view")
        } finally {
            session.close()
        }
    }

    @Test
    fun rebindingMustDiscardPreviouslySearchedCells() {
        val first = session("alpha")
        val second = session("bravo")
        try {
            SwingUtilities.invokeAndWait {
                val component = SwingTerminal(settingsProvider = {
                    SwingSettings(columns = 12, rows = 1, padding = SwingPadding(),
                        shellIntegrationDecorationGutterWidth = 0, cursorBlinkMillis = 0)
                })
                try {
                    component.size = component.preferredGridSize(12, 1)
                    component.bind(first)
                    component.search("alpha")
                    assertEquals(1, component.currentSearchState().resultCount)
                    component.bind(second)
                    component.search("bravo")
                    assertEquals(1, component.currentSearchState().resultCount,
                        "Search must inspect the replacement session's text")
                } finally {
                    component.dispose()
                }
            }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun cacheMustNotAliasEqualMetadataAcrossReaders() {
        val first = session("alpha")
        val second = session("bravo")
        try {
            val cache = TerminalRenderCache(12, 1)
            cache.updateFrom(first)
            val oldGeneration = cache.lineGenerations[0]
            val oldId = cache.lineIds[0]
            val oldStructure = cache.structureGeneration
            second.readRenderFrame { frame ->
                assertEquals(oldGeneration, frame.lineGeneration(0))
                assertEquals(oldId, frame.lineId(0))
                assertEquals(oldStructure, frame.structureGeneration)
            }
            cache.updateFrom(second)
            assertEquals("bravo", String(cache.codeWords, 0, 5))
        } finally {
            first.close()
            second.close()
        }
    }

    private fun session(text: String): TerminalSession {
        val terminal = TerminalBuffers.create(width = 12, height = 1, maxHistory = 0)
        for (character in text) terminal.writeCodepoint(character.code)
        return TerminalSession.create(terminal, NoOpConnector, workerDispatcher = Dispatchers.Unconfined).also {
            it.renderPublisher.updateAndPublish(it)
        }
    }

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        override fun resize(columns: Int, rows: Int) = Unit
        override fun close() = Unit
    }
}

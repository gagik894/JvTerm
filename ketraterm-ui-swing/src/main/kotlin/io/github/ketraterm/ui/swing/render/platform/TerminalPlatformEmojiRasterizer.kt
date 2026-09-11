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
package io.github.ketraterm.ui.swing.render.platform

import java.awt.image.BufferedImage
import java.util.*

/**
 * Rasterizes emoji with the host platform text stack when available.
 */
internal interface TerminalPlatformEmojiRasterizer {
    /**
     * Returns whether this rasterizer can attempt native emoji rendering.
     */
    val available: Boolean

    /**
     * Rasterizes [text] into a transparent image no larger than [pixelSize].
     * Returns null when this text and size cannot be rendered natively. Callers
     * may cache that result for this rasterizer's lifetime and use text fallback.
     */
    fun rasterize(
        text: String,
        pixelSize: Int,
    ): BufferedImage?

    companion object {
        fun create(): TerminalPlatformEmojiRasterizer {
            val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            return if ("windows" in osName) {
                WindowsColorEmojiRasterizer.create()
            } else {
                UnavailablePlatformEmojiRasterizer
            }
        }
    }
}

private object UnavailablePlatformEmojiRasterizer : TerminalPlatformEmojiRasterizer {
    override val available: Boolean = false

    override fun rasterize(
        text: String,
        pixelSize: Int,
    ): BufferedImage? = null
}

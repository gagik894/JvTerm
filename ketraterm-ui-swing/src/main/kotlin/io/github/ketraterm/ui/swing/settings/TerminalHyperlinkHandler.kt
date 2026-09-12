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
package io.github.ketraterm.ui.swing.settings

import java.awt.Desktop
import java.net.URI
import java.util.*

/**
 * Host-facing policy for explicit terminal hyperlink activation.
 *
 * Swing UI invokes this handler only after explicit activation.
 * Hosts can replace it to show prompts, route links through an
 * IDE, audit activations, or deny all links in locked-down environments.
 */
fun interface TerminalHyperlinkHandler {
    /**
     * Activates [uri] after explicit user intent.
     *
     * @param uri target URI supplied by terminal output metadata.
     * @return `true` when activation was handled.
     */
    fun openHyperlink(uri: String): Boolean

    companion object {
        /**
         * Conservative system-browser handler for common user-facing URI schemes.
         */
        @JvmField
        val SYSTEM: TerminalHyperlinkHandler = SystemTerminalHyperlinkHandler

        /**
         * Handler that denies every activation request.
         */
        @JvmField
        val NONE: TerminalHyperlinkHandler = TerminalHyperlinkHandler { false }
    }
}

private object SystemTerminalHyperlinkHandler : TerminalHyperlinkHandler {
    override fun openHyperlink(uri: String): Boolean {
        if (!Desktop.isDesktopSupported()) return false
        val desktop =
            try {
                Desktop.getDesktop()
            } catch (_: SecurityException) {
                return false
            } catch (_: UnsupportedOperationException) {
                return false
            }
        val browseSupported =
            try {
                desktop.isSupported(Desktop.Action.BROWSE)
            } catch (_: SecurityException) {
                return false
            }
        if (!browseSupported) return false

        val parsed =
            try {
                URI(uri)
            } catch (_: IllegalArgumentException) {
                return false
            }
        return isAllowedScheme(parsed.scheme) &&
            try {
                desktop.browse(parsed)
                true
            } catch (_: SecurityException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            } catch (_: java.io.IOException) {
                false
            }
    }

    private fun isAllowedScheme(scheme: String?): Boolean =
        when (scheme?.lowercase(Locale.ROOT)) {
            "http", "https", "mailto" -> true
            else -> false
        }
}

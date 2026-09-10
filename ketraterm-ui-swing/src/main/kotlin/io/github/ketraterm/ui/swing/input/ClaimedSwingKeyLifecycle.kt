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
package io.github.ketraterm.ui.swing.input

import java.awt.event.KeyEvent

/** UI layer that owns a claimed physical key until release. */
internal enum class ClaimedSwingKeyOwner {
    /** Shell-suggestion handling owns the key and may receive auto-repeat presses. */
    SUGGESTION,

    /** Host shortcut handling owns the key and suppresses auto-repeat presses. */
    HOST,
}

/**
 * Tracks physical keys claimed by UI actions until their matching release.
 *
 * Swing delivers presses, typed characters, and releases independently. A UI
 * action that hides its own surface on press must therefore retain ownership
 * until release, or the remaining events can leak into terminal input.
 */
internal class ClaimedSwingKeyLifecycle {
    private val claimedKeys = IntArray(MAX_SIMULTANEOUS_CLAIMS)
    private val claimedOwners = arrayOfNulls<ClaimedSwingKeyOwner>(MAX_SIMULTANEOUS_CLAIMS)
    private var claimedCount = 0
    private var suppressNextTypedEvent = false

    /** Returns the owner when [event] repeats an already-claimed physical key. */
    fun repeatedPressOwner(event: KeyEvent): ClaimedSwingKeyOwner? {
        val key = physicalKey(event)
        for (index in 0 until claimedCount) {
            if (claimedKeys[index] != key) continue
            suppressNextTypedEvent = !event.isActionKey
            return claimedOwners[index]
        }
        suppressNextTypedEvent = false
        return null
    }

    /** Claims [event] for [owner] through its matching release. */
    fun claim(
        event: KeyEvent,
        owner: ClaimedSwingKeyOwner,
    ) {
        val key = physicalKey(event)
        for (index in 0 until claimedCount) {
            if (claimedKeys[index] == key) return
        }
        if (claimedCount < claimedKeys.size) {
            claimedKeys[claimedCount] = key
            claimedOwners[claimedCount] = owner
            claimedCount++
        }
        suppressNextTypedEvent = !event.isActionKey
    }

    /** Returns whether the next Swing typed event belongs to a claimed press. */
    fun ownsTypedEvent(): Boolean {
        if (!suppressNextTypedEvent) return false
        suppressNextTypedEvent = false
        return true
    }

    /** Releases and returns ownership of [event], if claimed. */
    fun release(event: KeyEvent): Boolean {
        val key = physicalKey(event)
        for (index in 0 until claimedCount) {
            if (claimedKeys[index] != key) continue
            claimedCount--
            claimedKeys[index] = claimedKeys[claimedCount]
            claimedOwners[index] = claimedOwners[claimedCount]
            claimedKeys[claimedCount] = 0
            claimedOwners[claimedCount] = null
            suppressNextTypedEvent = false
            return true
        }
        return false
    }

    /** Clears ownership when focus loss invalidates the pending lifecycle. */
    fun clear() {
        claimedKeys.fill(0, 0, claimedCount)
        claimedOwners.fill(null, 0, claimedCount)
        claimedCount = 0
        suppressNextTypedEvent = false
    }

    private fun physicalKey(event: KeyEvent): Int = (event.keyCode shl KEY_LOCATION_BITS) or (event.keyLocation and KEY_LOCATION_MASK)

    private companion object {
        private const val MAX_SIMULTANEOUS_CLAIMS = 16
        private const val KEY_LOCATION_BITS = 3
        private const val KEY_LOCATION_MASK = (1 shl KEY_LOCATION_BITS) - 1
    }
}

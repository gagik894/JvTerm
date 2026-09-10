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
package io.github.ketraterm.intellij.services

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class IntellijCompletionLifecycleTest {
    @Test
    fun `close waits for in-flight creation and rejects every later creation`() {
        val lifecycle = IntellijCompletionLifecycle()
        val enteredCreation = CountDownLatch(1)
        val releaseCreation = CountDownLatch(1)
        val enteredClose = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val creation =
                executor.submit {
                    lifecycle.requireOpen {
                        enteredCreation.countDown()
                        assertTrue(releaseCreation.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(enteredCreation.await(5, TimeUnit.SECONDS))
            val close =
                executor.submit<Boolean> {
                    enteredClose.countDown()
                    lifecycle.beginClose()
                }
            assertTrue(enteredClose.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { close.get(100, TimeUnit.MILLISECONDS) }

            releaseCreation.countDown()
            creation.get(5, TimeUnit.SECONDS)
            assertTrue(close.get(5, TimeUnit.SECONDS))
            assertFalse(lifecycle.beginClose())
            assertThrows(IllegalStateException::class.java) { assertTrue(lifecycle.requireOpen { true }) }
        } finally {
            releaseCreation.countDown()
            executor.shutdownNow()
        }
    }
}

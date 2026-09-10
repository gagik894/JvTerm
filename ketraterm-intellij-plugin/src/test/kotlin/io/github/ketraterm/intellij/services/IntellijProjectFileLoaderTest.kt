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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresBlockingContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** IntelliJ fixture tests for project-file completion through Go to File. */
class IntellijProjectFileLoaderTest : BasePlatformTestCase() {
    /** Verifies that a non-contiguous query uses IntelliJ's project-file search. */
    fun testNonContiguousQueryFindsProjectFile() {
        myFixture.addFileToProject("settings.gradle.kts", "")
        myFixture.addFileToProject("build.gradle.kts", "")
        val entries = load("sgk")

        assertTrue(entries.toString(), entries.any { it.path == "settings.gradle.kts" })
    }

    /** Verifies that IntelliJ applies an explicit fuzzy directory qualifier. */
    fun testDirectoryQualifierScopesMatch() {
        myFixture.addFileToProject("src/main/.generated/Hidden.kt", "")
        myFixture.addFileToProject("other/.generated/Hidden.kt", "")

        val entries = load("src/main/.g")

        assertEquals(
            listOf("src/main/.generated"),
            entries.map { it.path },
        )
        assertTrue(entries.single().isDirectory)
    }

    /** Verifies that a pending write cancels and transparently restarts the IntelliJ read action. */
    fun testWriteActionRestartsProjectFileRead() {
        myFixture.addFileToProject("settings.gradle.kts", "")
        val projectDirectory = Path.of(requireNotNull(project.basePath))
        val virtualProjectDirectory = ProjectRootManager.getInstance(project).contentRoots.single()
        val enteredRead = CountDownLatch(1)
        val writeCompleted = AtomicBoolean()
        val resolutions = AtomicInteger()
        val loader =
            IntellijProjectFileLoader(project) { file ->
                resolutions.incrementAndGet()
                enteredRead.countDown()
                while (!writeCompleted.get()) ProgressManager.checkCanceled()
                projectDirectory.resolve(file.path.removePrefix("${virtualProjectDirectory.path}/"))
            }
        val loading =
            AppExecutorUtil.getAppExecutorService().submit(
                Callable { runBlocking { loader.load(projectDirectoryUri(), "sgk") } },
            )

        assertTrue("project-file read did not start", enteredRead.await(10, TimeUnit.SECONDS))
        completeWriteAction(writeCompleted)
        val entries = loading.get(10, TimeUnit.SECONDS)

        assertTrue(entries.toString(), entries.any { it.path == "settings.gradle.kts" })
        assertTrue("read action was not restarted", resolutions.get() >= 2)
    }

    /** Verifies that the engine's 256-candidate cap does not truncate the host query. */
    fun testProjectFileQueryUsesItsOwnHostBudget() {
        repeat(300) { index -> myFixture.addFileToProject("BoundedFile$index.kt", "") }

        val entries = load("BoundedFile")

        assertTrue(entries.size > 256)
    }

    private fun load(prefix: String) = runBlocking { loader().load(projectDirectoryUri(), prefix) }

    private fun loader(): IntellijProjectFileLoader {
        val projectDirectory = Path.of(requireNotNull(project.basePath))
        val virtualProjectDirectory = ProjectRootManager.getInstance(project).contentRoots.single()
        return IntellijProjectFileLoader(project) { file ->
            projectDirectory.resolve(file.path.removePrefix("${virtualProjectDirectory.path}/"))
        }
    }

    private fun projectDirectoryUri(): String = Path.of(requireNotNull(project.basePath)).toUri().toString()

    @RequiresBlockingContext
    private fun completeWriteAction(writeCompleted: AtomicBoolean) {
        ApplicationManager.getApplication().runWriteAction { writeCompleted.set(true) }
    }
}

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

import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalGradleTask
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path

/** Minimal imported Gradle task data required by completion projection. */
internal data class GradleTaskReadModel(
    val name: String,
    val description: String,
    val linkedProjectPath: String,
)

/** Lazy external-system node view consumed inside the owning IntelliJ read action. */
internal interface GradleModelNode {
    val moduleId: String?
    val task: GradleTaskReadModel?
    val children: Iterable<GradleModelNode>
}

/** Executes Gradle model traversal inside an implementation-owned read action. */
internal fun interface GradleModelReadPort {
    suspend fun read(collector: (Iterable<GradleModelNode>) -> List<TerminalGradleTask>): List<TerminalGradleTask>
}

private class IntellijGradleModelReadPort(
    private val project: Project,
) : GradleModelReadPort {
    override suspend fun read(collector: (Iterable<GradleModelNode>) -> List<TerminalGradleTask>): List<TerminalGradleTask> {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        if (project.isDisposed) return emptyList()
        return readAction {
            cancellationContext.ensureActive()
            ProgressManager.checkCanceled()
            if (project.isDisposed) return@readAction emptyList()
            val roots =
                ProjectDataManager
                    .getInstance()
                    .getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
                    .asSequence()
                    .mapNotNull { projectInfo -> projectInfo.externalProjectStructure }
                    .map(::IntellijGradleModelNode)
                    .asIterable()
            collector(roots)
        }
    }
}

private class IntellijGradleModelNode(
    private val node: DataNode<*>,
) : GradleModelNode {
    override val moduleId: String?
        get() = if (node.key == ProjectKeys.MODULE) (node.data as? ModuleData)?.id else null

    override val task: GradleTaskReadModel?
        get() {
            if (node.key != ProjectKeys.TASK) return null
            val taskData = node.data as? TaskData ?: return null
            return GradleTaskReadModel(
                name = taskData.name,
                description = taskData.description.orEmpty(),
                linkedProjectPath = taskData.linkedExternalProjectPath,
            )
        }

    override val children: Iterable<GradleModelNode>
        get() =
            node.children
                .asSequence()
                .map(::IntellijGradleModelNode)
                .asIterable()
}

/**
 * Loads bounded Gradle tasks from IntelliJ's imported external-system model.
 *
 * The loader never invokes Gradle. It reads the public IntelliJ External System
 * model built by a successful Gradle import.
 */
internal class IntellijGradleTaskLoader(
    private val readPort: GradleModelReadPort,
) {
    constructor(project: Project) : this(IntellijGradleModelReadPort(project))

    /**
     * Loads the imported Gradle tasks visible to a terminal working directory.
     *
     * @param workingDirectoryUri local `file` URI used to relativize `-p` project directories.
     * @return a visit-bounded deterministic task snapshot, or an empty list when the
     * project is disposed, the directory is unavailable, or no Gradle model is imported.
     */
    suspend fun load(workingDirectoryUri: String?): List<TerminalGradleTask> {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        val workingDirectory = TerminalLocalFileUriResolver.resolve(workingDirectoryUri) ?: return emptyList()
        return readPort.read { modelRoots ->
            val retained = ArrayList<TerminalGradleTask>(INITIAL_TASK_CAPACITY)
            var visitedTasks = 0
            val checkpoint = {
                cancellationContext.ensureActive()
                ProgressManager.checkCanceled()
            }
            val roots = modelRoots.asSequence().map { root -> PendingGradleNode(root, inheritedModuleId = null) }.asIterable()
            visitBoundedDepthFirst(
                roots = roots,
                maxVisited = MAX_VISITED_MODEL_NODES,
                cancellationCheckpoint = checkpoint,
                children = PendingGradleNode::children,
            ) { pendingNode ->
                val task = pendingNode.node.task
                if (task != null) {
                    visitedTasks++
                    val moduleId = pendingNode.moduleId
                    val entry = if (moduleId == null) null else task.toCompletionTask(workingDirectory, moduleId)
                    if (entry != null) retained += entry
                }
                visitedTasks < MAX_VISITED_TASKS
            }
            retained.sortWith(TASK_ORDER)
            retained
        }
    }

    private fun GradleTaskReadModel.toCompletionTask(
        workingDirectory: Path,
        moduleId: String,
    ): TerminalGradleTask? {
        val taskPath = fullyQualifiedTaskPath(moduleId, name) ?: return null
        val linkedProjectDirectory = runCatching { Path.of(linkedProjectPath) }.getOrNull() ?: return null
        val projectDirectory = relativeProjectDirectory(workingDirectory, linkedProjectDirectory)
        return TerminalGradleTask(
            path = taskPath,
            description = description,
            projectDirectory = projectDirectory,
        )
    }

    private fun relativeProjectDirectory(
        workingDirectory: Path,
        projectDirectory: Path,
    ): String = toRelativeCompletionPath(workingDirectory, projectDirectory).ifBlank { "." }

    private fun fullyQualifiedTaskPath(
        moduleId: String,
        taskName: String,
    ): String? = IntellijGradleTaskPath.fullyQualified(moduleId, taskName)

    private companion object {
        private const val MAX_VISITED_MODEL_NODES = 16_384
        private const val MAX_VISITED_TASKS = 8_192
        private const val INITIAL_TASK_CAPACITY = 256
        private val TASK_ORDER =
            compareBy<TerminalGradleTask, String>(String.CASE_INSENSITIVE_ORDER) { it.path }
                .thenBy { it.path }
                .thenBy { it.projectDirectory }
    }

    private data class PendingGradleNode(
        val node: GradleModelNode,
        private val inheritedModuleId: String?,
    ) {
        val moduleId: String? = node.moduleId ?: inheritedModuleId

        fun children(): Iterable<PendingGradleNode> =
            node.children
                .asSequence()
                .map { child -> PendingGradleNode(child, moduleId) }
                .asIterable()
    }
}

/** Converts public External System module and task data into a Gradle invocation path. */
internal object IntellijGradleTaskPath {
    /** Returns a canonical Gradle task path, or `null` when the imported data is unusable. */
    fun fullyQualified(
        moduleId: String,
        taskName: String,
    ): String? {
        val normalizedTaskName = taskName.trim()
        if (normalizedTaskName.isBlank()) return null
        if (normalizedTaskName.startsWith(':')) return normalizedTaskName.takeIf { it.length > 1 }

        val projectPath = projectPath(moduleId) ?: return null
        return if (projectPath == ":") ":$normalizedTaskName" else "$projectPath:$normalizedTaskName"
    }

    private fun projectPath(moduleId: String): String? {
        val normalizedModuleId = moduleId.trim()
        if (normalizedModuleId.isBlank()) return null
        val segments = normalizedModuleId.split(':').filter(String::isNotBlank)
        val projectSegments = if (normalizedModuleId.startsWith(':')) segments else segments.drop(1)
        if (projectSegments.isEmpty()) return ":"
        return ":" + projectSegments.joinToString(":")
    }
}

/** Creates imported-Gradle-task completion without exposing IntelliJ model APIs to the shared engine. */
internal fun intellijGradleTaskCompletionSource(loader: suspend (String?) -> List<TerminalGradleTask>) =
    TerminalCompletionSources.gradleTask(
        sourceId = "intellij-gradle-task",
        tasksProvider = { request, _ -> loader(request.workingDirectoryUri) },
    )

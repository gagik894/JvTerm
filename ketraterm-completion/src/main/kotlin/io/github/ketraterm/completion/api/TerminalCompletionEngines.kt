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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.engine.MergedCompletionEngine
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs

/**
 * Factories for production completion engines.
 */
object TerminalCompletionEngines {
    /**
     * Creates a deterministic merged engine from prioritized completion sources.
     *
     * Provider-local ranks are fused by projected command outcome with bounded
     * source priors, command-line context, and learned statistics.
     *
     * @param sources prioritized source registrations.
     * @param commandSpecs immutable command catalog used to classify the active
     * command-line position for ranking. Callers must not mutate this list while
     * the engine is alive.
     * @param learningStore optional shared in-memory learning store. The engine
     * reads its immutable published snapshot and performs no host I/O.
     * @param sourceFailureHandler diagnostic sink for isolated non-cancellation
     * source failures.
     * @return merged completion engine.
     */
    @JvmStatic
    @JvmOverloads
    fun fromSources(
        sources: List<TerminalCompletionSourceEntry>,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
        learningStore: TerminalCompletionLearningStore? = null,
        sourceFailureHandler: TerminalCompletionSourceFailureHandler = TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER,
    ): TerminalCompletionEngine =
        MergedCompletionEngine(
            sources = sources,
            commandSpecs = commandSpecs,
            learningStore = learningStore,
            sourceFailureHandler = sourceFailureHandler,
        )
}

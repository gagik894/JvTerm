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

/**
 * Reviewed cold-start priors for the built-in completion source families.
 *
 * Priors are deliberately small because [TerminalCompletionSourceEntry.priority]
 * is only one bounded input to global evidence fusion. Hosts should use these
 * values when composing the corresponding built-in sources so standalone and
 * IDE integrations start from the same policy before learned evidence applies.
 */
object TerminalCompletionSourcePrior {
    /** Prior for imported Gradle task candidates. */
    const val GRADLE_TASK: Int = 15

    /** Prior for Git branch, tag, and commit candidates captured from one repository. */
    const val GIT_REFERENCE: Int = 15

    /** Prior for changed or untracked Git path candidates. */
    const val GIT_STATUS_PATH: Int = 15

    /** Prior for direct children returned by the active directory provider. */
    const val DIRECTORY_PATH: Int = 12

    /** Prior for fuzzy paths from a bounded project index. */
    const val PROJECT_FUZZY_PATH: Int = 10
}

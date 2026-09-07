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
package io.github.ketraterm.intellij.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel

class KetraTermSettingsConfigurableTest : BasePlatformTestCase() {
    fun testHiddenSuggestionPreferencesSurviveApply() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val configurable = KetraTermSettingsConfigurable()
        try {
            settings.replaceState(
                original.copy(
                    smartSuggestionsEnabled = true,
                    shellSuggestionsEnabled = false,
                    acceptSelectedSuggestionWithEnter = false,
                    completionLearningPersistenceEnabled = true,
                ),
            )
            val component = configurable.createComponent()
            configurable.reset()
            val texts =
                descendants(component).mapNotNull {
                    when (it) {
                        is AbstractButton -> it.text
                        is JLabel -> it.text
                        else -> null
                    }
                }
            assertFalse(texts.any { it.contains("suggest", ignoreCase = true) || it.contains("learning", ignoreCase = true) })
            configurable.apply()
            assertTrue(settings.state.smartSuggestionsEnabled)
            assertFalse(settings.state.shellSuggestionsEnabled)
            assertFalse(settings.state.acceptSelectedSuggestionWithEnter)
            assertTrue(settings.state.completionLearningPersistenceEnabled)
        } finally {
            configurable.disposeUIResources()
            settings.replaceState(original)
        }
    }

    fun testPersistencePreferenceIsEffectiveOnlyWithMasterEnabled() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        try {
            settings.replaceState(original.copy(smartSuggestionsEnabled = false, completionLearningPersistenceEnabled = true))
            assertFalse(settings.completionLearningPersistenceEnabled())
            settings.replaceState(settings.state.copy(smartSuggestionsEnabled = true))
            assertTrue(settings.completionLearningPersistenceEnabled())
        } finally {
            settings.replaceState(original)
        }
    }

    private fun descendants(component: Component): Sequence<Component> =
        sequence {
            yield(component)
            if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
        }
}

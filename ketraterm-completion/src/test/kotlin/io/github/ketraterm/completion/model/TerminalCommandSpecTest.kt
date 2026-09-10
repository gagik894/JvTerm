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
package io.github.ketraterm.completion.model

import kotlin.test.*

class TerminalCommandSpecTest {
    @Test
    fun `spec models retain structural value semantics`() {
        val first =
            TerminalCommandSpec(
                name = "tool",
                aliases = listOf("t"),
                options = listOf(TerminalOptionSpec(listOf("--mode"), valueCandidates = listOf("fast"))),
                positionalArguments = listOf(TerminalArgumentSpec("profile", valueCandidates = listOf("debug"))),
            )
        val second =
            TerminalCommandSpec(
                name = "tool",
                aliases = listOf("t"),
                options = listOf(TerminalOptionSpec(listOf("--mode"), valueCandidates = listOf("fast"))),
                positionalArguments = listOf(TerminalArgumentSpec("profile", valueCandidates = listOf("debug"))),
            )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.toString(), second.toString())
    }

    @Test
    fun `default catalog reuses one immutable spec graph`() {
        val first = TerminalCommandSpecs.defaults()
        val second = TerminalCommandSpecs.defaults()
        val firstGit = first.single { it.name == "git" }
        val secondGit = second.single { it.name == "git" }

        assertSame(first, second)
        assertSame(firstGit, secondGit)
        assertSame(firstGit.subcommands, secondGit.subcommands)
        assertImmutable(first, TerminalCommandSpec("blocked"))
        first.forEach(::assertImmutableSpec)
    }

    private fun assertImmutableSpec(spec: TerminalCommandSpec) {
        assertImmutable(spec.aliases, "blocked")
        assertImmutable(spec.subcommands, TerminalCommandSpec("blocked"))
        assertImmutable(spec.options, TerminalOptionSpec(listOf("--blocked")))
        assertImmutable(spec.positionalArguments, TerminalArgumentSpec("blocked"))
        spec.options.forEach { option ->
            assertImmutable(option.names, "--blocked")
            assertImmutable(option.valueCandidates, "blocked")
            assertImmutable(option.exclusiveGroupIds, "blocked")
        }
        spec.positionalArguments.forEach { argument -> assertImmutable(argument.valueCandidates, "blocked") }
        spec.subcommands.forEach(::assertImmutableSpec)
    }

    private fun <T> assertImmutable(
        values: List<T>,
        value: T,
    ) {
        val addFailure =
            assertFails {
                @Suppress("UNCHECKED_CAST")
                (values as MutableList<T>).add(value)
            }
        assertTrue(addFailure is UnsupportedOperationException || addFailure is ClassCastException)
        if (values.isNotEmpty()) {
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (values as MutableList<T>)[0] = value
            }
        }
    }
}

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
package io.github.ketraterm.completion.engine

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class MergedCompletionEngineTest {
    @Test
    fun `fast source publishes before slow source and final emission reranks all results`(): Unit =
        runBlocking {
            val fastCompleted = CompletableDeferred<Unit>()
            val slowStarted = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    fastCompleted.complete(Unit)
                                    listOf(candidate("fast", source = "fast"))
                                },
                                priority = 0,
                            ),
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    slowStarted.complete(Unit)
                                    releaseSlow.await()
                                    listOf(candidate("slow", source = "slow"))
                                },
                                priority = 100,
                            ),
                        ),
                    commandSpecs = emptyList(),
                )

            val collected = async { engine.completions(request()).toList() }
            fastCompleted.await()
            slowStarted.await()
            yield()
            assertFalse(collected.isCompleted)
            releaseSlow.complete(Unit)

            val emissions = collected.await()
            assertEquals(listOf("fast"), emissions.first().map { it.replacementText })
            assertEquals(listOf("slow", "fast"), emissions.last().map { it.replacementText })
        }

    @Test
    fun `specs publish directly before suspending host sources`() =
        runBlocking {
            val hostStarted = CompletableDeferred<Unit>()
            val releaseHost = CompletableDeferred<Unit>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    hostStarted.complete(Unit)
                                    releaseHost.await()
                                    listOf(
                                        candidate(
                                            replacement = "storage",
                                            end = 2,
                                            source = "host",
                                            kind = TerminalCompletionCandidateKind.COMMAND,
                                        ),
                                    )
                                },
                                priority = 0,
                            ),
                        ),
                    commandSpecs = listOf(TerminalCommandSpec("status")),
                )

            val collected = async { engine.completions(request("st")).toList() }
            hostStarted.await()
            releaseHost.complete(Unit)

            val emissions = collected.await()
            assertEquals(listOf("status"), emissions.first().map { it.replacementText })
            assertEquals("spec", emissions.first().single().source)
            assertTrue(emissions.last().any { it.source == "host" })
        }

    @Test
    fun `source failure is isolated from progressive results`() =
        runBlocking {
            val failedSource = TerminalCompletionSource { _, _, _ -> error("failed source") }
            val failureEvents = mutableListOf<RecordedSourceFailure>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(failedSource, 100),
                            entry(source(candidate("available")), 0),
                        ),
                    commandSpecs = emptyList(),
                    sourceFailureHandler =
                        TerminalCompletionSourceFailureHandler { index, source, failure ->
                            failureEvents += RecordedSourceFailure(index, source, failure)
                        },
                )

            assertEquals(listOf("available"), engine.complete(request()).map { it.replacementText })
            assertEquals(1, failureEvents.size)
            assertEquals(0, failureEvents.single().sourceIndex)
            assertSame(failedSource, failureEvents.single().source.source)
            assertEquals("failed source", failureEvents.single().failure.message)
        }

    @Test
    fun `path provider failure reaches the centralized source diagnostic`() =
        runBlocking {
            val failure = IOException("directory access failed")
            val pathSource = TerminalCompletionSources.path(TerminalFileSystemProvider { throw failure })
            val failureEvents = mutableListOf<RecordedSourceFailure>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(pathSource, 0)),
                    commandSpecs = emptyList(),
                    sourceFailureHandler =
                        TerminalCompletionSourceFailureHandler { index, source, reportedFailure ->
                            failureEvents += RecordedSourceFailure(index, source, reportedFailure)
                        },
                )
            val request =
                TerminalCompletionRequest(
                    commandLine = "./r",
                    cursorOffset = 3,
                    workingDirectoryUri = "file:///project",
                )

            assertEquals(emptyList(), engine.complete(request))
            assertEquals(1, failureEvents.size)
            assertEquals(0, failureEvents.single().sourceIndex)
            assertSame(pathSource, failureEvents.single().source.source)
            assertSame(failure, failureEvents.single().failure)
        }

    @Test
    fun `malformed candidate ranges are filtered before ranking and publication`() =
        runBlocking {
            val commandLine = "echo \uD83D\uDE02"
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                source(
                                    candidate("valid", start = 5, end = commandLine.length),
                                    candidate("outside", start = 5, end = commandLine.length + 1),
                                    candidate("unrelated", start = 0, end = 1),
                                    candidate("split", start = 6, end = commandLine.length),
                                ),
                                0,
                            ),
                        ),
                    commandSpecs = emptyList(),
                )

            val candidates = engine.complete(request(commandLine))

            assertEquals(listOf("valid"), candidates.map { it.replacementText })
        }

    @Test
    fun `isolated source cancellation does not cancel siblings or abort request`() =
        runBlocking {
            val siblingStarted = CompletableDeferred<Unit>()
            val cancelSource = CompletableDeferred<Unit>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    cancelSource.await()
                                    throw CancellationException("isolated source cancelled")
                                },
                                priority = 0,
                            ),
                            entry(
                                TerminalCompletionSource { _, _, _ ->
                                    siblingStarted.complete(Unit)
                                    listOf(candidate("available", source = "sibling"))
                                },
                                priority = 10,
                            ),
                        ),
                    commandSpecs = emptyList(),
                )

            val collection = async { engine.completions(request()).toList() }
            siblingStarted.await()
            cancelSource.complete(Unit)

            val emissions = collection.await()
            assertEquals(listOf("available"), emissions.last().map { it.replacementText })
        }

    @Test
    fun `cancelling collection cancels every source child`(): Unit =
        runBlocking {
            val bothStarted = CompletableDeferred<Unit>()
            val bothCancelled = CompletableDeferred<Unit>()
            var started = 0
            var cancelled = 0
            val source =
                TerminalCompletionSource { _, _, _ ->
                    started++
                    if (started == 2) bothStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled++
                        if (cancelled == 2) bothCancelled.complete(Unit)
                    }
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(source, 0), entry(source, 0)),
                    commandSpecs = emptyList(),
                )

            val collection = launch { engine.completions(request()).collect {} }
            bothStarted.await()
            collection.cancelAndJoin()

            bothCancelled.await()
            assertEquals(2, cancelled)
        }

    @Test
    fun `merged engine starts sources in parallel within the request scope`(): Unit =
        runBlocking {
            val bothStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var started = 0
            val source =
                TerminalCompletionSource { _, _, _ ->
                    started++
                    if (started == 2) bothStarted.complete(Unit)
                    release.await()
                    emptyList()
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(source, 0), entry(source, 0)),
                    commandSpecs = emptyList(),
                )

            val completion = async { engine.complete(request()) }
            bothStarted.await()
            assertEquals(2, started)
            release.complete(Unit)
            completion.await()
        }

    @Test
    fun `source priority ranks ahead of candidate score`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(source(candidate("status", source = "spec", score = 900)), priority = 0),
                        entry(source(candidate("switch", source = "learned", score = 1)), priority = 100),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("switch", "status"), candidates.map { it.replacementText })
            assertEquals(listOf("learned", "spec"), candidates.map { it.source })
        }

    @Test
    fun `deduplicates by replacement range and replacement text using highest ranked candidate`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(source(candidate("status", detail = "static", source = "spec", score = 900)), priority = 0),
                        entry(source(candidate("status", detail = "recent", source = "learned", score = 1)), priority = 100),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("status"), candidates.map { it.replacementText })
            assertEquals("recent", candidates.single().detail)
            assertEquals("learned", candidates.single().source)
        }

    @Test
    fun `keeps same replacement text when replacement range differs`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(source(candidate("status", start = 0, end = 5, source = "left")), priority = 0),
                            entry(source(candidate("status", start = 4, end = 5, source = "right")), priority = 0),
                        ),
                    commandSpecs = emptyList(),
                )

            val candidates = engine.complete(request(commandLine = "git s"))

            assertEquals(listOf("left", "right"), candidates.map { it.source })
            assertEquals(listOf(0, 4), candidates.map { it.replacementStartOffset })
        }

    @Test
    fun `returns the complete bounded source union after merging and sorting`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate("alpha", score = 10),
                                candidate("charlie", score = 30),
                            ),
                            priority = 0,
                        ),
                        entry(source(candidate("bravo", score = 20)), priority = 0),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("bravo", "charlie", "alpha"), candidates.map { it.replacementText })
        }

    @Test
    fun `collects fixed source budget before global context ranking`() =
        runBlocking {
            val rawCandidates =
                buildList {
                    repeat(8) { index ->
                        add(
                            candidate(
                                replacement = "git remembered-${index + 1}",
                                start = 0,
                                end = 5,
                                source = "mixed",
                                kind = TerminalCompletionCandidateKind.ARGUMENT,
                                score = 1_000 - index,
                            ),
                        )
                    }
                    add(
                        candidate(
                            replacement = "status",
                            start = 4,
                            end = 5,
                            source = "mixed",
                            kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                            score = 900,
                        ),
                    )
                }
            var collectionLimit = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            TerminalCompletionSource { _, _, limit ->
                                collectionLimit = limit
                                rawCandidates.take(limit)
                            },
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "git s"))

            assertEquals(256, collectionLimit)
            assertEquals(12, candidates.size)
            assertEquals("spec", candidates.first().source)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidates.first().kind)
        }

    @Test
    fun `each source receives the fixed safety budget and ranked union is not UI limited`() =
        runBlocking {
            var suppliedLimit = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                TerminalCompletionSource { _, _, limit ->
                                    suppliedLimit = limit
                                    List(300) { candidate("candidate-$it", score = 300 - it) }
                                },
                                0,
                            ),
                        ),
                    commandSpecs = emptyList(),
                )

            val candidates = engine.complete(request())

            assertEquals(256, suppliedLimit)
            assertEquals(256, candidates.size)
        }

    @Test
    fun `request result enforces top K after bounded per-source ingestion`() =
        runBlocking {
            val suppliedLimits = ArrayList<Int>()
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            entry(
                                TerminalCompletionSource { _, _, limit ->
                                    suppliedLimits += limit
                                    List(300) { index ->
                                        candidate(
                                            replacement = "left-$index",
                                            score = if (index < 256) 1_000 - index else 10_000,
                                        )
                                    }
                                },
                                0,
                            ),
                            entry(
                                TerminalCompletionSource { _, _, limit ->
                                    suppliedLimits += limit
                                    List(300) { index -> candidate("right-$index", score = 900 - index) }
                                },
                                0,
                            ),
                        ),
                    commandSpecs = emptyList(),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf(256, 256), suppliedLimits)
            assertEquals(256, candidates.size)
            assertTrue(candidates.none { it.replacementText == "left-256" })
            assertEquals("left-0", candidates.first().replacementText)
        }

    @Test
    fun `large provider result preserves exact deterministic ordering`() =
        runBlocking {
            val candidates =
                ArrayList<TerminalCompletionCandidate>(1_024).apply {
                    var index = 0
                    while (index < 1_024) {
                        add(
                            candidate(
                                replacement = "candidate-$index",
                                display = "display-${1_024 - index}",
                                score = (index * 37) % 101,
                            ),
                        )
                        index++
                    }
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(entry(TerminalCompletionSource { _, _, _ -> candidates }, priority = 0)),
                )

            val actual = engine.complete(request())
            val expected =
                candidates
                    .take(256)
                    .sortedWith(
                        compareByDescending<TerminalCompletionCandidate> { it.score }
                            .thenBy { it.displayText }
                            .thenBy { it.replacementText },
                    )

            assertEquals(expected.map { it.replacementText }, actual.map { it.replacementText })
            assertEquals(actual.map { it.score }.sortedDescending(), actual.map { it.score })
        }

    @Test
    fun `equal ranking falls back to display text for deterministic order`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate("zeta", display = "zeta", score = 10),
                                candidate("alpha", display = "alpha", score = 10),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request())

            assertEquals(listOf("alpha", "zeta"), candidates.map { it.replacementText })
        }

    @Test
    fun `path candidates outrank generic arguments in cd positional path position`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "cd remembered",
                                    start = 0,
                                    end = 3,
                                    source = "learned",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "src/",
                                    start = 3,
                                    end = 3,
                                    source = "path",
                                    kind = TerminalCompletionCandidateKind.PATH,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "cd "))

            assertEquals(listOf("src/", "cd remembered"), candidates.map { it.replacementText })
        }

    @Test
    fun `subcommand candidates outrank generic arguments and paths in subcommand position`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "git stash",
                                    start = 0,
                                    end = 5,
                                    source = "learned",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "src/",
                                    start = 4,
                                    end = 5,
                                    source = "path",
                                    kind = TerminalCompletionCandidateKind.PATH,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "status",
                                    start = 4,
                                    end = 5,
                                    source = "spec",
                                    kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "git s"))

            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidates.first().kind)
        }

    @Test
    fun `static option values prefer narrow edits over generic whole-command edits`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "aws --output table",
                                    start = 0,
                                    end = 14,
                                    source = "learned",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "text",
                                    start = 13,
                                    end = 14,
                                    source = "spec",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "aws --output t"))

            assertEquals(listOf("table", "text"), candidates.map { it.replacementText })
            assertTrue(candidates.all { it.source == "spec" })
        }

    @Test
    fun `option names outrank generic arguments and paths in option name position`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "git status",
                                    start = 0,
                                    end = 5,
                                    source = "learned",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "src/",
                                    start = 4,
                                    end = 5,
                                    source = "path",
                                    kind = TerminalCompletionCandidateKind.PATH,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "--help",
                                    start = 4,
                                    end = 5,
                                    source = "spec",
                                    kind = TerminalCompletionCandidateKind.OPTION,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "git -"))

            assertEquals("--help", candidates.first().replacementText)
        }

    @Test
    fun `dynamic positional domain candidates outrank paths and generic arguments`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "main/",
                                    start = 11,
                                    end = 14,
                                    source = "path",
                                    kind = TerminalCompletionCandidateKind.PATH,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "git switch main",
                                    start = 0,
                                    end = 14,
                                    source = "learned",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "main",
                                    start = 11,
                                    end = 14,
                                    source = "git-branches",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "git switch mai"))

            assertEquals("main", candidates.first().replacementText)
            assertEquals("git-branches", candidates.first().source)
        }

    @Test
    fun `dynamic option domain candidates outrank generic argument candidates`() =
        runBlocking {
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            source(
                                candidate(
                                    replacement = "default-history",
                                    start = 20,
                                    end = 23,
                                    source = "stats",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                ),
                            ),
                            priority = 100,
                        ),
                        entry(
                            source(
                                candidate(
                                    replacement = "default",
                                    start = 20,
                                    end = 23,
                                    source = "kubernetes",
                                    kind = TerminalCompletionCandidateKind.ARGUMENT,
                                    valueDomain = TerminalCompletionValueDomain.KUBERNETES_NAMESPACE,
                                ),
                            ),
                            priority = 0,
                        ),
                    ),
                )

            val candidates = engine.complete(request(commandLine = "kubectl --namespace def"))

            assertEquals(listOf("default", "default-history"), candidates.map { it.replacementText })
        }

    @Test
    fun `empty host source list still composes static specifications`() =
        runBlocking {
            val engine = TerminalCompletionEngines.fromSources(emptyList<TerminalCompletionSourceEntry>())

            assertEquals(
                listOf("show", "status", "switch", "stash"),
                engine.complete(request(commandLine = "git s")).map { it.replacementText },
            )
        }

    @Test
    fun `operator region suppresses every source before source evaluation`() =
        runBlocking {
            var sourceCalls = 0
            val engine =
                TerminalCompletionEngines.fromSources(
                    listOf(
                        entry(
                            TerminalCompletionSource { _, _, _ ->
                                sourceCalls++
                                listOf(candidate("unexpected"))
                            },
                            priority = 0,
                        ),
                    ),
                )
            val commandLine = "git status && cd"

            val candidates =
                engine.complete(
                    TerminalCompletionRequest(
                        commandLine = commandLine,
                        cursorOffset = commandLine.indexOf("&&") + 1,
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    ),
                )

            assertTrue(candidates.isEmpty())
            assertEquals(0, sourceCalls)
        }

    @Test
    fun `sources with the same command specs share one resolved context`() =
        runBlocking {
            val contexts = ArrayList<TerminalCompletionContext>()
            val specs = TerminalCommandSpecs.defaults()
            val source =
                TerminalCompletionSource { _, context, _ ->
                    contexts += context
                    emptyList()
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(source, 0), entry(source, 0)),
                    commandSpecs = specs,
                )

            engine.complete(request(commandLine = "git --"))

            assertEquals(2, contexts.size)
            assertSame(contexts[0], contexts[1])
        }

    @Test
    fun `learning mutation during a request is visible only to the next request`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult(
                commandLine = "tool old",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1L,
            )
            var mutationTimestamp = 2L
            val mutatingSource =
                TerminalCompletionSource { _, _, _ ->
                    learningStore.recordCommandResult(
                        commandLine = "tool new",
                        successful = true,
                        profileId = null,
                        workingDirectoryUri = null,
                        usedAtEpochMillis = mutationTimestamp++,
                    )
                    emptyList()
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(mutatingSource, priority = 0)),
                    commandSpecs = emptyList(),
                    learningStore = learningStore,
                )

            val firstRequest = engine.complete(request("tool "))
            val secondRequest = engine.complete(request("tool "))

            assertEquals(listOf("old"), firstRequest.map { it.replacementText })
            assertEquals(setOf("old", "new"), secondRequest.map { it.replacementText }.toSet())
        }

    @Test
    fun `rapid typing bursts cleanly cancel obsolete queries and converge to final request`(): Unit =
        runBlocking {
            var activeCancelledCount = 0
            val delayedSource =
                TerminalCompletionSource { req, _, _ ->
                    try {
                        delay(20.milliseconds)
                        listOf(
                            TerminalCompletionCandidate(
                                replacementText = req.commandLine + "-done",
                                replacementStartOffset = 0,
                                replacementEndOffset = req.commandLine.length,
                                source = "async-io",
                                kind = TerminalCompletionCandidateKind.COMMAND,
                            ),
                        )
                    } catch (cancellation: CancellationException) {
                        activeCancelledCount++
                        throw cancellation
                    }
                }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(delayedSource, priority = 0)),
                    commandSpecs = emptyList(),
                )

            val keystrokes = listOf("g", "gi", "git", "git ", "git s", "git st", "git sta", "git status")
            var currentJob: Job? = null
            var lastEmitted: List<TerminalCompletionCandidate>? = null

            for (keystroke in keystrokes) {
                currentJob?.cancelAndJoin()
                val request = TerminalCompletionRequest(keystroke, keystroke.length)
                currentJob =
                    launch {
                        engine.completions(request).collect {
                            lastEmitted = it
                        }
                    }
                delay(2.milliseconds) // simulate fast keystroke typing interval (2ms)
            }

            currentJob?.join()
            assertTrue(activeCancelledCount > 0, "Obsolete background coroutines must be cancelled during typing bursts")
            assertEquals(listOf("git status-done"), lastEmitted?.map { it.replacementText })
        }

    @Test
    fun `concurrent multi-source slow I_O yields progressive updates without race conditions`(): Unit =
        runBlocking {
            val sourceA =
                TerminalCompletionSource { _, _, _ ->
                    delay(5.milliseconds)
                    listOf(candidate("alpha", source = "sourceA", score = 10))
                }
            val sourceB =
                TerminalCompletionSource { _, _, _ ->
                    delay(15.milliseconds)
                    listOf(candidate("beta", source = "sourceB", score = 20))
                }
            val sourceC =
                TerminalCompletionSource { _, _, _ ->
                    delay(25.milliseconds)
                    listOf(candidate("gamma", source = "sourceC", score = 30))
                }

            val engine =
                TerminalCompletionEngines.fromSources(
                    sources = listOf(entry(sourceA, 0), entry(sourceB, 10), entry(sourceC, 20)),
                    commandSpecs = emptyList(),
                )

            val emissions = engine.completions(request("s")).toList()

            assertTrue(emissions.size in 1..3, "Should receive progressive snapshots as sources complete")
            val finalSnapshot = emissions.last().map { it.replacementText }
            assertEquals(listOf("gamma", "beta", "alpha"), finalSnapshot)
        }

    private data class RecordedSourceFailure(
        val sourceIndex: Int,
        val source: TerminalCompletionSourceEntry,
        val failure: Throwable,
    )

    private fun entry(
        source: TerminalCompletionSource,
        priority: Int,
    ): TerminalCompletionSourceEntry =
        TerminalCompletionSourceEntry(
            source = source,
            priority = priority,
        )

    private fun source(vararg candidates: TerminalCompletionCandidate): TerminalCompletionSource =
        TerminalCompletionSource { _, _, _ -> candidates.toList() }

    private fun candidate(
        replacement: String,
        start: Int = 0,
        end: Int = 1,
        display: String = replacement,
        detail: String = "",
        source: String = "test",
        kind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.ARGUMENT,
        score: Int = 0,
        valueDomain: TerminalCompletionValueDomain = TerminalCompletionValueDomain.NONE,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacement,
            replacementStartOffset = start,
            replacementEndOffset = end,
            source = source,
            kind = kind,
            displayText = display,
            detail = detail,
            score = score,
            valueDomain = valueDomain,
        )

    private fun request(commandLine: String = "s"): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
        )
}

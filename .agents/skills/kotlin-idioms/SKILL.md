---
name: kotlin-idioms
description: Use when creating or substantially refactoring Kotlin implementation or public APIs, especially to replace Java-shaped Kotlin, prevent speculative abstraction, or choose among coroutines, Flow, state, and listeners. Do not invoke for documentation, Gradle-only changes, generated tables, or trivial edits.
---

# Idiomatic Kotlin for KetraTerm

Follow nearby code and the repository formatter. Improve semantics and ownership;
do not churn code merely to use more Kotlin features.

## Design restraint

- Kotlin concision is not permission to add concepts. Use sealed/value types,
  extensions, DSLs, generics, delegates, coroutines, and Flow only when they make
  a current requirement simpler than the direct implementation.
- Prefer a concrete class or function until a real boundary or variation earns
  another type. Avoid `Interface` + `Default*`, pass-through wrappers, and generic
  pipelines whose only consumer is the code that created them.

## Language and API shape

- Prefer `val`, immutable public views, narrow visibility, constructor
  dependencies, and private mutable backing state.
- Use exhaustive `when` and enums or sealed hierarchies for genuinely closed
  alternatives. Use data/value classes only when their semantics justify them.
- Prefer default parameters, named arguments where calls are ambiguous, cohesive
  top-level or narrow extensions, and resource `use`. Do not recreate Java
  utility classes, static factories, builders, or getters.
- Make a value a property only when it is cheap, stable, and unsurprising. Use
  expression bodies and scope functions only when clearer; avoid nested receivers.
- Avoid `!!`. Use nullable control flow for absence and `require`/`check` for
  contract or invariant violations.
- In parser, core, rendering, and input hot paths, prefer explicit loops,
  primitive arrays, and reusable buffers over boxing or collection pipelines.

## Repository-observed traps

- Do not grow a coordinator or facade with another responsibility merely because
  it already has access to the needed objects. Extract only cohesive state and
  lifecycle; do not replace a large class with one-method forwarding delegates.
  Generated tables and protocol state machines are not violations based on size.
- When the same argument cohort is forwarded through several rendering or host
  calls, replace the parameter train with a cohesive context only if the context
  can be reused outside inner loops. Keep ABI, wire, and measured hot-path
  primitives explicit when bundling would allocate or obscure semantics.
- Repeated `!!` after sentinel or nullable-accumulator checks signals an unclear
  state model. Restructure the branch or validate once at the invariant boundary;
  do not scatter assertions through the algorithm.
- Avoid production `lateinit` as a solution to circular construction. Prefer
  constructor ownership or a one-way callback; framework and JMH lifecycle
  injection are valid exceptions.
- Catch the narrowest useful failure. Broad catches are allowed only at explicit
  provider, listener, OS, plugin, or cleanup isolation boundaries: preserve
  coroutine and platform cancellation, report operational failure, and discard
  errors only for documented best-effort cleanup.
- Default no-op interface methods are for genuinely optional observer or host
  hooks. Required semantic commands remain abstract so missing wiring fails at
  compile time instead of silently doing nothing.
- A narrow internal interface may isolate time, threading, or framework state for
  deterministic tests. For a single operation, prefer a function type unless a
  named contract, identity, lifecycle, or Java SAM boundary needs an interface.

## Concurrency and observation

- Keep synchronous work synchronous. Add coroutines or Flow only for a concrete
  suspension, cancellation, lifecycle, or asynchronous-composition need.
- Use `suspend` for one asynchronous result; cold `Flow` for values produced per
  collector; `StateFlow` for state with a meaningful current value; and
  `SharedFlow` for multicast events with explicit replay and overflow semantics.
  Keep mutable flows private and expose read-only types.
- Keep listeners for synchronous framework or EDT boundaries. Adapt them to Flow
  only when consumers need asynchronous composition or cancellation, unregister
  on cancellation, and keep exactly one authoritative state representation.
- Lifecycle owners own and cancel their scopes. Use structured children, never
  `GlobalScope` or hidden fire-and-forget work. Supervise only intentionally
  independent failures.
- Accept dispatchers or scopes where ownership and testing require it; composition
  roots choose concrete dispatchers. Move blocking I/O off UI/session mutation
  threads and do not use `runBlocking` inside production suspend paths.
- Preserve `CancellationException`; make long coroutine CPU loops cancellable.
- Mutate Swing components on the EDT. Collect UI flows in a component-owned scope
  and cancel them on unbind or disposal.

## Deeper design work

For a public API or abstraction review, coroutine/Flow architecture,
callback-to-Flow adapter, observer redesign, or Kotlin-idiom audit, read
[JetBrains Kotlin guidance](references/jetbrains-kotlin-guidance.md).

## Verification

Test ownership, cancellation, failure propagation, and ordering with coroutine
test facilities where relevant. Retain EDT assertions and hot-path performance
checks.

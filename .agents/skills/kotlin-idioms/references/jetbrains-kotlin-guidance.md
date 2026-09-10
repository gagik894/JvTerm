    # JetBrains Kotlin Guidance Adapted for KetraTerm

Read this reference for non-trivial API or concurrency design. It distills
official Kotlin and `kotlinx.coroutines` guidance into repository decisions; it
is not a second architecture map or a requirement to use every Kotlin feature.

## Abstraction gate

Before adding an interface, abstract base, factory, registry, adapter, mapper,
DSL, generic pipeline, observable, or new model, identify the present caller and
the concrete problem it solves. Reject it when the only justification is future
flexibility, symmetry, style, or easier mocking.

- An interface needs a real module boundary, required independent behavior, or
  a test/lifecycle seam that cannot be expressed more simply.
- A factory needs construction choice or policy; an adapter needs an actual
  semantic/protocol boundary; a mapper needs models with different ownership or
  meaning; a registry needs dynamic lookup. The pattern name alone proves nothing.
- Count the call sites and concepts after the refactor. An abstraction that adds
  types, forwarding, configuration, and navigation without deleting greater
  complexity has not paid for itself.
- Extract duplicated knowledge or behavior only after the shared invariant is
  understood. Do not unify code that merely looks similar today.
- Prefer evolving a local concrete design when a second real use appears over
  predicting that use in advance. Before completion, delete unused parameters,
  extension points, wrappers, and indirection exposed by the final implementation.

## Public API decisions

- Make public intent explicit: declare visibility and stable return/property
  types, document contracts, and expose the smallest useful surface. Do not let
  an inferred implementation type accidentally become API.
- Keep each abstraction's member set centered on its core concept. Put derived
  operations in narrowly visible extensions when they can be expressed entirely
  through the public contract; do not create `*Util` dumping grounds.
- Reuse standard Kotlin types when they carry the right meaning. Introduce a
  domain type when it enforces invariants or prevents confusion, not merely to
  avoid `String`, `Long`, or another familiar type.
- Avoid opaque Boolean parameters in new public APIs. Prefer a domain enum,
  options value, or separate operation when callers otherwise cannot understand
  the call without parameter hints. A private/local Boolean is not a problem.
- Use data classes in public APIs only when structural equality, `copy`,
  destructuring, and constructor shape are intentional parts of the contract.
  Do not expose them by reflex for mutable or compatibility-sensitive models.
- Prefer default arguments over redundant Kotlin-only overloads. Add JVM
  annotations or overloads only for an actual Java/framework consumer, and do
  not assume a new default parameter preserves binary compatibility.
- At Java boundaries, give platform-typed expressions explicit Kotlin nullability
  before exposing or storing them. Keep Java-specific adapters and annotations
  at the boundary rather than weakening the Kotlin-facing model.
- Create a DSL only for repeated domain configuration that becomes clearer as a
  declarative block. Pass required values explicitly and use `@DslMarker` when
  nested receivers could be confused. A builder is not the default replacement
  for a constructor.
- Avoid global mutable state and stateful top-level accessors. Inject clocks,
  dispatchers, stores, and other environmental dependencies when tests or
  ownership need control.

## Idiom choices

- Choose scope functions by intent: `apply` configures an object, `also` adds a
  side effect, and `let`/`run`/`with` compute a result. Do not nest or chain them
  when an explicit variable or `if` makes the receiver and control flow clearer.
- Use collection operators for readable value transformations. Use explicit
  loops for early exit, mutation-heavy logic, reusable scratch storage, or a
  measured hot path.
- Prefer Kotlin properties and SAM lambdas when consuming Java APIs. Do not
  reproduce Java accessor calls or anonymous classes unless overload resolution,
  identity, or lifecycle cleanup requires an object.
- Make illegal input and illegal state distinguishable with `require` and
  `check`; use nullable results only when absence is a normal outcome.

## Coroutine ownership

- `suspend` means a function may suspend; it neither creates concurrency nor
  selects a dispatcher. Keep CPU work sequential unless parallelism is useful,
  and move blocking work at the owning boundary.
- Prefer `coroutineScope` for concurrent child work. Use `async` only when a
  result will be awaited; use `launch` for owned side effects. Do not insert a
  replacement `Job` into a child launch context because that detaches it from
  the caller's lifecycle.
- A manually created scope belongs to one lifecycle-bearing object, contains a
  `Job`, and is cancelled when that object closes or disposes. A
  `CoroutineExceptionHandler` is last-resort handling for root coroutines, not a
  substitute for handling expected failures where they occur.
- Use supervision only when sibling work is genuinely independent and one
  failure must not cancel the others. Define how each failed child is observed;
  supervision must not turn failures into silence.
- Cancellation is normal control flow. Re-throw `CancellationException`, use
  `finally` for cleanup, and add suspension or `ensureActive` checks to long CPU
  loops. Reserve `NonCancellable` for short cleanup that truly must suspend.
- Keep `runBlocking` at blocking entry points and tests. Calling it from a
  suspend function or an EDT/session worker can starve or deadlock its thread.

## Flow and observer selection

- Use a regular suspending function for one result. Convert a one-shot callback
  with `suspendCancellableCoroutine`; use `callbackFlow` for multi-shot callbacks
  and always unregister through `awaitClose`.
- A cold `Flow` reruns its producer for every collector. Make repeated work and
  side effects intentional. Change upstream execution with `flowOn`; do not emit
  from an arbitrary `withContext` inside `flow`.
- Buffering, conflation, debounce, and dropping are semantic decisions. Specify
  them where latency can exceed consumption and test which values may be lost.
- `StateFlow` is hot, always has a current value, never completes normally, and
  conflates equal updates. Use immutable snapshots with meaningful equality.
  Materialize failure/completion in the state model when consumers need them;
  do not use it for lossless event history.
- `SharedFlow` is for broadcast events or shared streams. Define replay, buffer
  capacity, overflow behavior, start policy, and scope lifetime rather than
  accepting defaults accidentally.
- Keep a listener for immediate synchronous notification, framework integration,
  or a simple single-consumer boundary. Use Flow when consumers benefit from
  suspension, operators, cancellation, or multiple asynchronous values. Do not
  publish both as independently mutable sources of truth.

## Shared state and KetraTerm constraints

- Pick one ownership strategy per state: coarse thread/EDT confinement,
  atomics for simple independent operations, a lock for short synchronous
  invariants, or `Mutex` when contenders must suspend. `volatile` alone does not
  make compound mutations atomic.
- Preserve parser/core determinism and session serialization. Do not introduce
  coroutines, Flow, or fine-grained dispatcher hopping into byte, grid, Unicode,
  input, or paint hot loops merely for architectural uniformity.
- Keep Swing state confined to the EDT in coarse operations. Background work may
  produce immutable results, but applying them and managing component listeners
  remains lifecycle-bound UI work.
- Test cold-flow recollection, hot-flow initial/replay behavior, slow collectors,
  cancellation cleanup, child failure, and disposal. Use virtual time and test
  dispatchers instead of sleeps where the module already depends on coroutine
  test support.

## Official sources

- [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- [Kotlin API guidelines: simplicity](https://kotlinlang.org/docs/api-guidelines-simplicity.html)
- [Kotlin API guidelines: readability](https://kotlinlang.org/docs/api-guidelines-readability.html)
- [Kotlin API guidelines: testability](https://kotlinlang.org/docs/api-guidelines-testability.html)
- [Scope functions](https://kotlinlang.org/docs/scope-functions.html)
- [Java interoperability](https://kotlinlang.org/docs/java-interop.html)
- [`CoroutineScope` lifecycle](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-scope/)
- [Flow](https://kotlinlang.org/docs/coroutines-flow.html)
- [`StateFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/)
- [`callbackFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/callback-flow.html)
- [Shared mutable state and concurrency](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html)

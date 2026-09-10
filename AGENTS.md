# Terminal Pipeline Agent Guide

KetraTerm is a modern, secure terminal pipeline for Kotlin/JVM 25. It targets
contemporary shells and TUIs, not literal xterm parity.

Before changing a module, read its nearest `AGENTS.md`. This root file defines
cross-project boundaries; module guides own local implementation detail.

## Layer ownership

- `ketraterm-protocol`: dependency-free shared protocol vocabulary.
- `ketraterm-parser`: bytes to semantic commands, including UTF-8, ANSI state
  machines, CSI/OSC/DCS, charsets, and grapheme assembly.
- `ketraterm-core`: headless grid/state mutation, cursor physics, margins,
  wrapping, scrollback, attributes, modes, storage, and width policy.
- `ketraterm-host`: parser-command to core-API mapping only.
- `ketraterm-input`: keyboard, paste, focus, mouse, and other host-bound
  encoding from stable mode state.
- `ketraterm-completion`: parse-once completion evaluation, structured
  concurrency, ranking, and bounded learning; no host I/O.
- `ketraterm-completion-host`: host-neutral path and directory access.
- `ketraterm-completion-persistence`: sanitized, versioned learning snapshots.
- `ketraterm-render-api`: dependency-free primitive render contracts.
- `ketraterm-render-cache`: copied render data for consumers, without UI policy.
- `ketraterm-transport-api`: ordered raw-byte connector contracts.
- `ketraterm-session`: parser/core synchronization and serialized outbound
  writes for input plus terminal responses.
- `ketraterm-ui-swing`: reusable rendering and interaction, transport-agnostic.
- `ketraterm-ui-swing-host`: host-neutral Swing actions and completion adapters,
  not product policy.
- `ketraterm-app`: standalone window, tabs, product wiring, and PTY hosting.
- `ketraterm-pty`: local PTY process/connector lifecycle and convenience
  session creation.
- `ketraterm-testkit` and `ketraterm-benchmarks`: reusable fakes and JMH
  benchmarks.
- `ketraterm-workspace`: host-neutral profiles, tabs, and local-session
  workspace state.
- `ketraterm-intellij-plugin`: independent nested build containing IntelliJ-only
  product integration.

Keep ownership strict:

- Parser parses; core mutates and stores; host maps; input encodes; UI displays.
- Width calculation belongs in core. Parser may assemble graphemes but must not
  assign terminal cell width.
- Transport never parses protocols. PTY owns local process/connector lifecycle
  and its convenience session factory, but never encodes input or mutates core.
- Session is the synchronization and outbound-write boundary.
- Swing UI sends intent through `TerminalSession`; it contains no PTY or
  IntelliJ-specific behavior.
- Render API/cache never choose fonts, paint Swing, or expose parser/core
  internals.
- Completion source I/O and persistence remain outside the completion engine.

## Scope and engineering invariants

Preserve SRP and existing module APIs. Feature scope, non-goals, support status,
and deferred work belong only in the canonical feature and gap maps; never copy
those inventories into `AGENTS.md` files.

Prefer the smallest design that satisfies current behavior:

- Add an abstraction only when it enforces a real boundary or invariant, removes
  repeated stable behavior, supports a required independent implementation, or
  provides a necessary lifecycle/test seam.
- Do not add speculative interfaces, factories, registries, adapters, models,
  mappers, builders, configuration, coroutines, or event streams for possible
  future use. One implementation may use an interface at a genuine module
  boundary; otherwise prefer the concrete type.
- Keep one authoritative representation of each fact. A pass-through layer that
  only renames or forwards calls is a liability, not separation of concerns.
- Apply DRY to duplicated knowledge and behavior, not coincidentally similar
  syntax.
- Before finishing, remove unused extension points and collapse one-use wrappers
  when doing so preserves ownership and makes the data flow easier to follow.

- Query/response features must update the explicit security allowlist and return
  protocol-defined failure responses for unsupported or unauthorized queries.
- Keep parser/core hot paths allocation-minimal; avoid regex, ICU,
  `BreakIterator`, and object-heavy parsing there.
- Prefer table-driven protocol and Unicode classification.
- Use the exact TODO ownership taxonomy defined by the gap map. Do not duplicate
  that taxonomy in module guides.
- Avoid broad refactors during protocol changes.
- Public APIs need useful KDoc. Remove stale compatibility surfaces and comments;
  do not add comments that merely restate code.

Supported behavior lives in `docs/terminal-feature-map.md`; deferred and
policy-gated behavior lives in `docs/terminal-feature-gap-map.md`.
`AGENTS.md` files define stable ownership, invariants, and local workflow only.

## Validation

Tests assert terminal semantics, not implementation quirks. For behavior changes:

- Add focused tests in the responsible module.
- Add real byte-stream host tests when parser behavior changes.
- Cover defaults, malformed input, bounds/overflow, recovery, and chunking where
  relevant; never loosen assertions to accommodate broken behavior.
- Run `./gradlew spotlessApply`, then the narrowest relevant tests. Use
  `./gradlew :<module>:test` or `./gradlew test` when broad verification is
  warranted.
- Update feature/gap maps when capability or scope changes.
- Leave no silent no-ops, unrelated formatting churn, or architecture drift.

Useful entry points: `ketraterm-core/docs/terminal-core-contract.md`,
`docs/agent-skills.md`, and the touched module's `AGENTS.md`.

## Graphify

The project graph is `graphify-out/graph.json`. No Graphify skill is installed;
use the CLI directly.

- Use Graphify for architecture, cross-module data flow, dependency paths, and
  unfamiliar multi-file relationships.
- Use `rg` and targeted source reads for exact symbols, known files, localized
  behavior, and small edits.
- Prefer `graphify explain "<concept>"`, then `graphify path "<A>" "<B>"`;
  use `graphify query "<question>" --budget 750` only for broad traversal.
- Never rebuild merely to answer a question. For an explicit code graph build,
  use `graphify extract <path> --code-only`; semantic extraction is opt-in.
- Dirty graph outputs are expected. Use `graphify-out/wiki/index.md` or
  `GRAPH_REPORT.md` only for broad navigation/review.
- Save results only for reusable cross-file findings, confirmed dead ends, or
  corrections.
- After structural or cross-file code changes, run `graphify update .`. Skip it
  for documentation-only or localized changes that cannot affect the graph
  structure.

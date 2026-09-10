# Module ketraterm-completion-persistence

## KetraTerm Completion Persistence (`:ketraterm-completion-persistence`)

The `ketraterm-completion-persistence` module provides optional local-file
storage for compact exact-command completion-learning snapshots. Version 3
stores opaque ranking counters separately from optional positive plaintext replay rows.
The file boundary rechecks replay eligibility, enforces byte/line/row bounds,
uses a strict versioned codec, and replaces files atomically when supported.

`TerminalCompletionLearningCoordinator` is the runtime owner shared by product
hosts. It applies each bounded learning mutation synchronously and signals one
conflated worker when clean state first becomes dirty. That worker hydrates the
fixed file once, observes last-value enablement, and checkpoints the latest
immutable snapshot every 30 seconds while dirty. Ranking observes in-memory
learning without waiting for disk, sustained traffic cannot postpone a
checkpoint, and shutdown forces and awaits the final dirty write.

Each host supplies one fixed product path at construction and may only enable or
disable persistence for that path. The module deliberately has no runtime path
switching or cross-file import contract. It does not rank suggestions, parse
command lines, inspect shell history, or depend on workspace and UI modules.

The coordinator talks directly to one bounded file store. There is no
repository, separate writer, control-command actor, arbitrary flush barrier,
nullable path, or per-event persistence request. Hydration merges the fixed
file with live rows in the supplied store; callers must not separately preload
the same aggregate file. Legacy schemas are rejected rather than imported. A
rejected or unreadable file blocks overwrite for that lifecycle and invokes one
host-supplied diagnostic callback with the original read exception when one exists.

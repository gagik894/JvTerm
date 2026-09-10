# Module ketraterm-completion

## KetraTerm Completion (`:ketraterm-completion`)

The `ketraterm-completion` module defines the dependency-free command-line
completion engine foundation shared by standalone and IDE hosts.

It owns pure request/candidate/spec models, command-line tokenization, bounded
in-process spec evaluation, ranking, and bounded exact-command learning. The
built-in command catalog is shared and immutable rather than reconstructed for
each consumer.

The module does not spawn shells, perform I/O, depend on UI frameworks, or parse
terminal output.

Its best-effort replay policy decides whether bounded plaintext may enter
retained history or observed-token learning; approval is not a guarantee that a
command contains no secret. Ranking evidence uses a case-sensitive opaque command
identity and remains available even when plaintext replay is rejected.
Only successful executions can retain that replay projection. Suggestion
acceptance and dismissal remain opaque ranking evidence and never create
history candidates. Replay
history and observed-token inference require an exact profile and canonical
working-directory match at request time, including exact null context; opaque
ranking evidence retains its context-fallback semantics.
Optional local-file storage and write scheduling are supplied by the separate
`ketraterm-completion-persistence` module, while asynchronous provider
infrastructure belongs to `ketraterm-completion-host`.

Completion sources such as curated command specs, Fig-style spec importers,
path providers, and IDE context providers should adapt into this module's stable
model rather than leaking their source-specific representation into terminal UI
code. Query-aware host loaders apply independent enumeration budgets, while
sources retain only their bounded top-ranked candidate set. One bounded learning aggregate publishes separate opaque ranking and
positive, policy-approved replay projections; the latter alone feeds history and
observed-token candidates.

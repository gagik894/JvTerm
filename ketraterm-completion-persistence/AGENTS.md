# Terminal Completion Persistence Agent Guide

`ketraterm-completion-persistence` owns bounded local filesystem persistence for split exact-command learning snapshots.

## Responsibility

This module may:

- encode and decode opaque ranking evidence plus optional plaintext replay rows through public completion contracts.
- recheck replay eligibility at the storage boundary.
- perform versioned, atomic local-file replacement.
- apply bounded in-memory learning synchronously in one lifecycle coordinator.
- checkpoint dirty snapshots periodically through one conflated worker.
- expose bounded fixed-path file-store I/O.
- force and await one final dirty write during lifecycle shutdown.
- notify one host callback when hydration is rejected or fails, without adding a health-event subsystem.

## Boundary

This module must not:

- parse command lines, rank candidates, or compose completion sources.
- depend on Swing, IntelliJ Platform, session, workspace, PTY, or app modules.
- choose product-specific storage directories or persistence settings.
- create or implicitly own coroutine scopes.
- persist any learning family beyond opaque exact-command evidence and its optional replay projection.

The persistence coordinator may create one worker in its caller-supplied scope;
it must not create an executor or independent scope. Learning must become
visible before a recording call returns and must not wait for hydration or
disk. The worker owns hydration, last-value enablement, periodic dirty
checkpoints, and final shutdown persistence. It uses one conflated wakeup and
must not enqueue per-event snapshots or controls.

Product hosts choose one non-null fixed destination at coordinator
construction, supply the scope, and own enablement, lifecycle, and user-facing
settings. Hosts also own a bounded final-durability wait and must cancel their
scope and continue shutdown when that budget expires. Do not add runtime path switching, snapshot-import semantics,
arbitrary flush barriers, a repository layer, or a separate writer. The
dependency-free completion engine remains free of filesystem and scheduling
concerns.

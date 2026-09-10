---
name: terminal-naming-convention
description: Use when creating or renaming classes/files, introducing a public API, or choosing names for new application components and adapters. Do not invoke for ordinary edits that preserve existing names.
---

# KetraTerm Naming

Prefer established vocabulary in the owning module. Naming consistency serves
API clarity; it is not a reason to churn correct existing code.

## Stable conventions

- Cross-module terminal abstractions generally use `Terminal*`, such as
  `TerminalSession` or `TerminalBuffer`.
- Strong module/domain qualifiers replace `Terminal` when clearer:
  `Swing*` for reusable Swing APIs, `Pty*` for PTY primitives, `Cell*` for
  cell styling, and `Host*` for integration metadata or policy.
- Reserve `KetraTerm*` for product/application entry points and branding.
- Internal types use the shortest unambiguous local name. Use `Default` or
  `Impl` only when it truthfully distinguishes an implementation.
- Preserve an established public factory or internal name unless the requested
  change materially improves ownership or clarity.
- Match a file name to its primary top-level type.

## Rename checklist

Before renaming, inspect nearby names and all usages. Update the declaration,
file, tests, KDoc, imports, reflection/configuration strings, and public
documentation together. Do not add deprecated aliases or compatibility wrappers
unless the user explicitly requires a migration path.

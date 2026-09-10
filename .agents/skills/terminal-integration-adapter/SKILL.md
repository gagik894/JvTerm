---
name: terminal-integration-adapter
description: Use for HostCommandAdapter and parser-to-core mapping changes, including coordinate conversion, host metadata routing, or an explicitly unwired integration edge. Do not invoke for parser recognition or core implementation alone.
---

# Terminal Integration Adapter

Read the root and `ketraterm-host/AGENTS.md` first.

## Mapping rules

- Map semantic sink calls to public core APIs without parsing protocol syntax.
- Convert coordinate conventions explicitly at the boundary.
- Preserve the full semantic value when the destination supports it; otherwise
  leave an ownership-marked gap instead of clamping or fabricating behavior.
- Keep host metadata and policy decisions explicit and outside core storage
  unless the core contract assigns ownership there.
- Do not inspect core internals or duplicate parser dispatch logic.
- Determine current support from implementation plus the canonical feature/gap
  maps; never maintain a watch-list of supposedly missing attributes here.

## Verification

Prefer real byte streams through the parser and `HostCommandAdapter`, asserting
public `TerminalBuffer` state or host events. Adapter-only tests may cover a
documented no-op, but must not bless degraded semantics.

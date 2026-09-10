---
name: terminal-test-suite
description: Use when the primary task is designing, reviewing, hardening, or refactoring terminal tests or test infrastructure. Do not invoke merely because an implementation change requires routine accompanying tests.
---

# Terminal Test Design

Read the root and owning module guide. This skill supplements their validation
rules for test-focused work.

## Review rules

- Assert public terminal semantics or a named internal invariant.
- Keep expected events, bytes, cells, and state transitions visible in the test.
- Use fixtures for setup and recording, not to hide the expectation.
- Prefer the narrowest deterministic layer that proves the contract.
- Add a full byte-stream or parser-to-core test only when the behavior crosses
  that boundary.
- Replace vague `doesNotThrow` coverage with observable outcomes.
- Do not preserve a known bug by weakening or restating assertions.
- Keep hostile, boundary, recovery, and lifecycle cases proportional to the
  contract under review.

Refactor repetition only when the resulting tests remain easier to understand
and failures still identify the broken semantic rule.

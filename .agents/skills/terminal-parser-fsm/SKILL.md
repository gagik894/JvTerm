---
name: terminal-parser-fsm
description: Use for parser finite-state-machine, byte classification, action engine, CSI/ESC/OSC/DCS dispatch, UTF-8 recovery, charset, or string-termination changes. Do not invoke for core-only terminal behavior.
---

# Terminal Parser FSM

Read the root and `ketraterm-parser/AGENTS.md` first.

## Parser invariants

- Keep byte classification, transition tables, actions, and completed-sequence
  dispatch separate.
- Dispatch CSI by structural signature, not final byte alone.
- Handle controls inside string states according to string-local semantics.
- Bound parameters and string payloads before semantic dispatch.
- Recover from unknown, malformed, aborted, or overflowing sequences without
  printing structural bytes or dispatching a different command.
- Keep UTF-8 recovery synchronized so a following ESC or control byte is routed
  structurally.

## Verification

Test the narrowest changed component and the full byte-stream parser when the
behavior is externally observable. Cover relevant abort/termination forms,
omitted and overflowing parameters, malformed UTF-8 recovery, payload bounds,
and chunk boundaries around structural bytes. Capability status belongs in the
canonical maps, not this skill.

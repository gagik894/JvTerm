---
name: terminal-input-encoder
description: Use for changes to ketraterm-input event vocabulary or keyboard, paste, focus, mouse, host-output, and mode-dependent byte encoding. Do not invoke for UI event collection or terminal-output parsing.
---

# Terminal Input Encoder

Read the root guide, `ketraterm-input/AGENTS.md`, and
`ketraterm-input/docs/terminal-input-contract.md`.

## Encoding rules

- Accept normalized input events and emit host-bound bytes only.
- Read the packed input-facing mode state once per event through public core
  helpers; do not decode bit positions locally.
- Keep dynamic CSI/SS3 construction allocation-conscious with reusable scratch
  storage.
- Write through `TerminalHostOutput`. Its byte-range consumer must synchronously
  consume or copy data because buffers may be reused immediately.
- Keep UI toolkit types, parser state, grid storage, cursor internals, and
  renderer state outside this module.
- Add shared vocabulary to the owning protocol/core API rather than inventing
  input-local semantics.

## Verification

Assert exact bytes and explicit validation failures. Cover only the modes,
modifiers, event phases, coordinates, UTF-8, and policy branches affected by the
change, plus one real public core-mode integration case when mode-dependent.
Consult the canonical maps for capability status; do not record it here.

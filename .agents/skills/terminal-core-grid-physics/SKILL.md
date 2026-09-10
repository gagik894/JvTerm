---
name: terminal-core-grid-physics
description: Use for core grid-mutation physics involving wide or clustered cells, wrapping, scrolling, resize/reflow, margins, tab stops, or width policy. Do not invoke for unrelated core APIs.
---

# Terminal Core Grid Physics

Read the root and `ketraterm-core/AGENTS.md` first. This skill adds a mutation
invariant checklist; it does not define feature-support status.

## Invariants

Every affected mutation must preserve:

- complete leader/spacer spans for wide cells.
- valid cluster handles across overwrite, erase, scroll, resize, and eviction.
- wrapping and pending-wrap state.
- cursor, margin, and origin-mode bounds.
- primary/alternate screen and scrollback ownership.

Width remains a core policy decision. Parser may assemble graphemes but cannot
assign cell width.

## Implementation checks

- Keep cell storage primitive and flat; avoid object-per-cell designs.
- Clear the complete previous span before partially overwriting wide or
  clustered content.
- Reuse storage in mutation hot paths unless the operation inherently stores a
  cluster or resizes.
- Preserve bounded cluster lifecycles tied to screen/history retention.

## Verification

Exercise the changed operation with narrow, wide, combining, and clustered
content where applicable. Include boundary columns, margins, wrap transitions,
scrollback, alternate screen, and resize/reflow interactions relevant to the
change. Use canonical feature/gap maps only when capability status changes.

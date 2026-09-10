---
name: terminal-gap-map
description: Use when the task explicitly classifies, adds, closes, or rewrites entries in the terminal feature map or feature gap map. Do not invoke for ordinary implementation work whose scope status is unchanged.
---

# Terminal Feature and Gap Maps

The only capability-status sources are
`docs/terminal-feature-map.md` and
`docs/terminal-feature-gap-map.md`. Read the relevant sections before editing.
Never reproduce their inventories or ownership taxonomy in skills or
`AGENTS.md` files.

## Maintenance rules

- Use the exact ownership marker already defined by the gap map.
- Keep one authoritative entry per capability; consolidate overlaps.
- Mark work done only when the claimed slice has implementation and meaningful
  tests.
- Describe partial support precisely rather than promoting the whole feature.
- Keep policy-gated and intentional non-goals explicit.
- When status moves, update the feature and gap maps together so they cannot
  contradict each other.
- Do not add speculative backlog items without a concrete product or
  compatibility rationale.

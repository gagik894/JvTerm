---
name: terminal-ui-rendering
description: Use for reusable terminal rendering or interaction work involving Java2D painting, render-frame/cache consumption, text runs, fonts, cursor, selection, viewport geometry, or Swing threading. Excludes ordinary standalone/IDE host wiring.
---

# Terminal UI Rendering

Read the root guide and the guide for the affected render/UI module. Those files
own boundaries and contracts; this skill is a focused rendering review
checklist.

## Review checklist

- Put primitive frame vocabulary in render API, copied state in render cache,
  reusable presentation in Swing UI, and product wiring in host modules.
- Keep painting run-based. Reject per-cell objects, strings, colors, font
  resolution, or one-`drawString`-per-cell designs.
- Preserve an isolated fast path for ASCII/simple text and a bounded shaping
  fallback for complex clusters.
- Snapshot settings, metrics, palette, and frame data before rendering. Swing
  state stays on the EDT; off-EDT buffers require immutable publication or clear
  ownership transfer.
- Use one geometry model for paint, hit testing, selection, cursor, viewport,
  and terminal mouse coordinates.
- Keep host services behind narrow interfaces; do not fork renderer logic in
  app or IDE modules.

## Verification

Prefer deterministic model and render-frame replay tests. Exercise the changed
geometry, cache-generation, selection, cursor, text-shaping, color, or threading
contract without requiring a live PTY or IDE runtime.

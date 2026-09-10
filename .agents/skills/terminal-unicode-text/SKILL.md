---
name: terminal-unicode-text
description: Use for Unicode-specific changes to UTF-8 decoding, charset mapping, grapheme segmentation, generated Unicode classification, cluster assembly, or core width policy. Do not invoke for ordinary parser/core work without Unicode semantics.
---

# Terminal Unicode and Text

Read the root plus the affected parser/core module guides.

## Ownership and hot paths

- Parser owns UTF-8 recovery, charset mapping, grapheme segmentation, and
  cluster assembly.
- Core owns cell width and ambiguous-width policy.
- Keep classification table-shaped, primitive, and allocation-free in hot
  paths; do not use regex, ICU, or `BreakIterator`.
- Do not add another UTF-8 decoder to `PrintableProcessor`.
- Clear grapheme context completely after flush, abort, reset, or end-of-input.

## Verification

Cover the changed Unicode rule with ASCII, valid multi-byte input, malformed
recovery, and relevant combining, variation-selector, ZWJ, regional-indicator,
Hangul, charset-shift, and chunk-boundary cases. The critical recovery invariant
is that malformed UTF-8 followed by ESC emits replacement text and then routes
ESC structurally.

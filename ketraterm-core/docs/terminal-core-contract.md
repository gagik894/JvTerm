# Terminal Core Contract

This document defines the public behavioral contract of `:ketraterm-core`.

It is the handoff boundary for future `:ketraterm-parser` and `:ketraterm-input`
modules. If code outside the core depends on behavior not described here, that
behavior is not yet guaranteed.

## Scope

The core owns:

- grid mutation and overwrite physics
- cursor state and movement
- deferred wrap / `pendingWrap`
- scroll regions (`DECSTBM`, `DECLRMM`)
- erase operations (`ED`, `EL`, `ECH`, selective erase)
- line and character editing (`ICH`, `DCH`, `IL`, `DL`)
- tab stops (`HT`, `CHT`, `CBT`, `HTS`, `TBC`)
- protected cells (`DECSCA`, `DECSED`, `DECSEL`)
- primary / alternate buffer lifecycle
- resize and reflow
- `DECCOLM`
- durable terminal mode state
- attribute packing and pen state, including indexed/RGB colors, underline
  color/style, common text attributes, hyperlink ids, and inverse
- cluster storage and history storage

The core does not own:

- escape-sequence parsing
- grapheme segmentation
- charset designation / shift-state translation (`G0..G3`, `SO`, `SI`)
- mouse event routing
- host-bound input encoding
- rendering
- font measurement

## Public API surfaces

`TerminalBuffer` composes these narrower contracts:

- `TerminalWriter`
- `TerminalCursor`
- `TerminalModeController`
- `TerminalModeReader`
- `TerminalReader`
- `TerminalInspector`

External parser and input code should depend on the narrowest interface they
need instead of the full facade.

### Render frame generations

Render frames distinguish stored content from its global presentation:

- `contentGeneration` advances for retained cell or row-mapping changes. A
  reverse-video transition does not change stored text, attributes, or wrapping,
  so it preserves this generation and permits text-search result reuse.
- `lineGeneration(row)` covers the rendered row, including global reverse-video
  interpretation. Enabling or disabling reverse video invalidates every row,
  including retained history and rows in the inactive buffer. Soft and full
  reset use the same invalidation when they restore normal video.
- Global attribute invalidation advances one counter without walking retained
  rows. Render row generations combine this counter with each line's own
  generation; callers compare for equality, including across counter overflow.
- `structureGeneration` changes for terminal-owned shape or row-mapping changes.
  Reverse video preserves row identities and this generation.

## Writer contract

### Printable ingress

- `writeCodepoint(codepoint)` writes one Unicode scalar value.
- `writeText(text)` writes the string as a sequence of scalar codepoints.
- `writeCluster(codepoints, length)` writes one pre-segmented visual cluster.
  The core computes its grid width from the active width policy.
- `appendToPreviousCluster(codepoint)` appends a parser-identified grapheme
  continuation to the most recently written printable cell without moving the
  cursor. This supports live rendering at host read boundaries while preserving
  chunk-boundary combining marks and ZWJ continuations.

Guaranteed behavior:

- wide-cell and cluster overwrite is shape-safe
- no orphaned wide spacers are left behind
- insert mode and replace mode use the same overwrite invariants
- deferred wrap follows terminal semantics
- clusters are stored in the active buffer arena and survive reflow by
  deep-copy into the new arena
- parser-identified grapheme continuations preserve the cursor position and
  wide spacer invariants

Not guaranteed:

- grapheme segmentation
- deciding whether a codepoint is a combining mark, variation selector, or ZWJ
  continuation

Those belong to `:ketraterm-parser`.

### Erase and edit commands

The writer surface owns:

- `newLine`, `reverseLineFeed`, `carriageReturn`
- `setScrollRegion`, `resetScrollRegion`
- `setLeftRightMargins`
- `scrollUp`, `scrollDown`
- `insertLines`, `deleteLines`
- `insertBlankCharacters`, `deleteCharacters`
- `eraseCharacters`
- all line/screen erase variants
- all selective erase variants
- `clearScreen`, `clearAll`
- pen mutation and selective-erase protection state

Guaranteed behavior:

- structural edits cancel `pendingWrap`
- `ECH` erases without shifting
- `ICH` / `DCH` are constrained by active horizontal margins
- `IL` / `DL` are constrained by the active vertical region and are no-op when
  the cursor is outside it
- normal erase ignores cell protection
- selective erase respects cell protection
- hard clears ignore cell protection

## Cursor contract

The cursor surface owns:

- absolute and relative cursor motion
- `DECSC` / `DECRC`
- tab stop commands

### `DECSC` / `DECRC`

The core-owned save slot includes:

- cursor column
- cursor row
- pen attributes
- `pendingWrap`
- origin mode (`DECOM`)

The core does not save or restore:

- charset designation
- locking shifts
- parser-owned shift state

Those remain parser state and must be handled outside `:ketraterm-core`.

If no save slot exists, `restoreCursor()` falls back to the core's documented
absolute home plus pen reset behavior.

### Tabs

Guaranteed behavior:

- `HT`, `CHT`, and `CBT` never wrap
- forward tab movement clamps at the active right boundary
- backward tab movement clamps at the active left boundary
- `HTS`, `TBC 0`, and `TBC 3` cancel `pendingWrap`
- resize preserves surviving tab stops, drops truncated ones, and seeds newly
  exposed columns with default 8-column stops
- hard reset and `DECCOLM` reset tab stops destructively to the default rhythm

## Mode-state contract

`TerminalModeController` is the public write surface for durable mode state.

`TerminalModeReader.getModeSnapshot()` is the public read surface.

Durable mode state currently exposed by core:

- insert mode
- auto-wrap
- application cursor keys
- application keypad
- origin mode
- newline mode
- left/right margin mode
- reverse video
- cursor visible
- cursor blinking
- bracketed paste enabled
- focus reporting enabled
- East Asian ambiguous-width policy
- mouse tracking mode
- mouse encoding mode
- modify-other-keys mode

Guaranteed behavior:

- mode setters that home or otherwise alter cursor physics cancel `pendingWrap`;
  input, reporting, presentation, palette, and other non-cursor modes preserve it
- public mode reads are immutable snapshots
- input/UI code cannot mutate internal mode storage directly

## Reader contract

`TerminalReader` and `TerminalLineApi` provide safe, allocation-light access to
stored state.

Guaranteed behavior:

- out-of-bounds line reads return a void line
- out-of-bounds codepoint reads return `0`
- packed cell attributes are exposed as primary and extended `Long` words
  decoded together by `AttributeCodec`
- blank cells read as `0`
- wide spacers read as `-1`
- cluster cells return the leading/base codepoint through `getCodepointAt`
- full cluster contents are available through `readCluster`

Not guaranteed:

- raw storage handles
- direct access to internal arrays

## Buffer lifecycle contract

### Primary and alternate screens

- primary and alternate buffers have separate arenas
- alternate buffer has no scrollback history
- `47` entry switches to the alternate buffer without clearing or cursor save/restore
- `1047` entry clears the alternate buffer, homes its cursor, and resets its margins
- `1048` saves/restores cursor state without switching buffers
- `1049` combines cursor save/restore with a clearing alternate-buffer switch
- leaving alternate screen returns to primary as it was left unless the selected
  variant explicitly restores the saved cursor
- resize reflows primary and wipes alt

### Resize

`resize(newWidth, newHeight)` guarantees:

- primary logical-line reconstruction and rewrap
- cursor relocation into the reflowed primary content
- deep-copy of surviving clusters into a fresh primary store
- alternate-screen wipe and recreation at the new dimensions
- scroll margins reset/clamped to the new viewport
- left/right margins reset to full width
- tab stops resized non-destructively
- saved-cursor state clamped to valid bounds

### Soft reset

`softReset()` implements DECSTR (`CSI ! p`).

Guaranteed behavior:

- visible content and scrollback are preserved
- dimensions, tab stops, and active screen selection are preserved
- insert, origin, application cursor/keypad, cursor presentation, left/right
  margin mode, and modify-other-keys state reset to defaults
- bracketed paste, focus reporting, and mouse reporting modes are preserved
- top/bottom and left/right margins reset to the full viewport on both buffers
- current cursor position is preserved, but pending wrap is cleared
- saved-cursor slots restore to home with default pen state
- active pen attributes, hyperlink id, and selective-erase write protection
  reset to defaults

### `DECCOLM`

`executeDeccolm(newWidth)` accepts only `80` or `132`.

Guaranteed sequence:

1. resize both buffers to the new width
2. clear the active display and its history
3. home the active cursor to absolute `(0, 0)`
4. reset active scroll margins
5. reset active left/right margins
6. reset tab stops to the default 8-column rhythm
7. cancel `pendingWrap`
8. preserve both saved-cursor slots

When alt is active, the core follows the xterm-style policy:

- alternate screen is wiped at the new width
- primary screen is reflowed in the background

## Protection contract

Protection is erase-only.

Guaranteed behavior:

- `DECSCA` stamps protection onto future written cells
- normal writes overwrite protected cells
- normal erase ignores protection
- selective erase skips protected cells
- wide spacers inherit the protection status of their leader
- protection survives reflow
- hard clear ignores protection

## Invariants

The core maintains these invariants on public mutation paths:

- no orphaned wide spacers
- horizontal-margin line mutations annihilate destination occupants crossing
  either slice boundary and never copy an incomplete wide source span
- no stale live cluster handles after erase/shift/resize paths
- per-buffer cluster-store ownership is never shared
- tab-stop array width always matches terminal width
- public non-printing operations clear `pendingWrap`

## Intentional deviations

These are intentional boundary choices, not accidental gaps:

- no grapheme segmentation in core
- no charset save/restore in `DECSC` / `DECRC`
- no rendering behavior beyond storing renderer-facing flags
- no host-bound mouse / keyboard encoding

## Pre-1.0 change surface

Likely to evolve before 1.0:

- parser/input handoff docs
- mode snapshot growth if more host-controlled flags are surfaced
- parser-facing docs around charset ownership and Unicode ingestion

The runtime semantics described in this document are the current intended
contract for integrating `:ketraterm-parser` and `:ketraterm-input`.

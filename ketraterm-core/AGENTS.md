# Terminal Core Agent Guide

`ketraterm-core` is the headless terminal state engine. It owns grid mutation,
cursor physics, scrollback, modes, tab stops, pen attributes, width policy, and
cluster storage.

It must not parse escape sequences, decode UTF-8, segment graphemes, encode
input events, or render UI.

## Core Boundary

Core receives semantic operations from parser/host-facing APIs such as
`writeCodepoint`, `writeCluster`, cursor movement, erase/edit commands, mode
setters, and pen setters.

Core owns:

- cursor bounds and clamping
- origin mode and margin-relative movement
- wrapping and pending-wrap behavior
- scroll regions and scrollback
- tab-stop state
- primary and alternate buffers
- cell width decisions
- durable terminal modes
- packed cell attributes and cluster handles

Parser owns what text/protocol was received. Core owns where that text lands and
how many cells it occupies.

## Data-Oriented Rules

- Keep grid storage flat and primitive.
- Preserve cache-friendly layouts: `IntArray` cells, packed attributes, bounded
  cluster stores, and explicit sentinel values.
- Do not introduce object-per-cell storage.
- Do not allocate in mutation hot paths unless the operation inherently stores a
  grapheme cluster or resizes/reflows.
- Keep complex cluster data tied to bounded screen/history lifecycles.

## Cell Invariants

Every grid mutation must preserve cell invariants:

- `0` means empty.
- Positive values represent direct scalar codepoints when locally encoded that
  way by the current storage model.
- Wide spacers and cluster handles must never be orphaned.
- Overwriting any cell in a wide or clustered span must clear the full previous
  span before writing the new content.

If a mutation touches wrapping, insert/delete, erase, scroll, resize, margins, or
wide clusters, add tests that prove no corrupted leaders, spacers, or stale
cluster references remain.

## Width Ownership

Width calculation belongs here, not in `ketraterm-parser`.

Core width policy must account for:

- East Asian wide/full-width codepoints.
- combining and zero-width codepoints.
- emoji presentation and ZWJ clusters.
- variation selectors.
- ambiguous-width policy from terminal mode/configuration.

Width data is generated from Unicode data files. Keep width APIs table-shaped so
future Unicode upgrades remain mechanical and do not move width policy out of
core.

## Attribute Ownership

Core must represent pen attributes truthfully; host must never fake or degrade
unsupported values. Do not maintain an attribute-support inventory here. Use
the core contract plus the canonical feature and gap maps for current status.

## Response Channel Security

`TerminalResponseChannel` owns capability-allowlist enforcement. Any change to
queryable core state must update that policy boundary; global response-security
requirements remain defined by the root guide.

## Testing

Core tests should focus on invariants and terminal physics:

- cursor movement with margins, origin mode, and bounds
- wrap and pending-wrap behavior
- insert/delete/erase with wide and clustered cells
- scroll regions and scrollback retention
- alternate buffer behavior
- resize/reflow with cluster preservation
- tab stops
- mode snapshots
- pen attribute storage and reset behavior

Prefer small unit tests for exact mechanics and broader invariant tests around
mutation engines.

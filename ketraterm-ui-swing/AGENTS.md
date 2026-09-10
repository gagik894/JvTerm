# Terminal UI Swing Agent Guide

`ketraterm-ui-swing` owns the reusable Swing terminal UI. It converts terminal
render frames and user input into a desktop component without knowing which
transport produced the byte stream.

This module is the shared UI foundation for standalone desktop usage, IDE
host, and other Swing hosts.

## Package Layout

- `io.github.ketraterm.ui.swing.api`: public host-facing Swing component APIs.
- `io.github.ketraterm.ui.swing.settings`: public immutable settings and palette
  snapshots, plus internal frozen metrics.
- `io.github.ketraterm.ui.swing.input`: Swing-to-terminal input event adapters.
- `io.github.ketraterm.ui.swing.render`: Java2D painters and renderer-local
  primitive caches.

Keep reusable public surfaces narrow. Implementation packages should stay
internal unless hosts genuinely need the type as part of the UI contract.

## Responsibilities

`ketraterm-ui-swing` owns:

- public Swing terminal component APIs.
- internal Swing component layering.
- terminal content painting.
- cursor presentation.
- selection and mouse handling.
- keyboard event handling.
- clipboard abstraction.
- font resolving abstraction.
- immutable settings snapshot abstraction.
- viewport and scrollbar model.
- Swing-side render-frame consumption.

When a public terminal component is introduced, it should own its internal
layers. Host modules should configure the public component through narrow
interfaces rather than assembling internal painting/cursor pieces themselves.

## Allowed Dependencies

`ketraterm-ui-swing` may depend on:

- `ketraterm-session`
- `ketraterm-input`
- `ketraterm-render-api`
- `ketraterm-render-cache`

It may also use standard JDK/Swing/AWT APIs.

## Forbidden Dependencies

`ketraterm-ui-swing` must not depend on:

- IntelliJ Platform APIs.
- `ketraterm-pty`.
- SSH, WebSocket, or other transport implementations.
- standalone application modules.
- plugin modules.

Transport selection belongs outside this module.

## Boundary Rules

`ketraterm-ui-swing` must not:

- parse ANSI, VT, OSC, DCS, or terminal output protocols.
- implement terminal grid mutation rules.
- mutate terminal core internals directly.
- duplicate render cache responsibilities.
- know whether bytes come from PTY, SSH, WebSocket, mock connectors, or tests.
- own process/session creation policy.
- own IDE lifecycle policy.
- own standalone app configuration policy.

It consumes render-frame state and sends user intent through session/input
boundaries.

## Rendering Contract

Rendering should be terminal-native and run-based, not cell-object based.

The renderer should operate in terms of:

- background runs.
- text runs.
- decoration runs.
- selection ranges.
- cursor presentation.
- complex cluster fallback.

Avoid `drawString` once per cell.

ASCII and simple text must stay on the allocation-conscious run path. Complex
non-ASCII cells and grapheme clusters may use a slower Java2D shaping path such
as `TextLayout`, but that path must remain isolated from ASCII rendering.

Avoid per-cell allocation of:

- `Color`
- `String`
- `Font`
- coordinate objects
- temporary cell wrappers

Renderer hot paths should use primitive packed values where practical.

Rendering must consume immutable frame/settings state. It must not observe
half-applied theme, font, metric, scale, or palette changes while painting.

## Settings Contract

Rendering uses an immutable settings snapshot.

The snapshot should contain:

- terminal fonts.
- cell metrics.
- resolved color palette.
- text rendering hints.
- HiDPI scale.
- cursor settings.
- selection colors.

Cell metrics must include:

- cell width.
- cell height.
- baseline.
- underline position.
- strikethrough position.
- overline position.
- cursor stroke width.

Do not compute font metrics ad hoc during row painting.

## Color Contract

Use a resolved palette with packed ARGB values in hot paths.

The color model must explicitly define:

- default foreground/background.
- ANSI 16 colors.
- indexed 256-color palette.
- truecolor RGB.
- bold-as-bright behavior.
- faint/dim behavior.
- inverse.
- conceal.
- selection foreground/background.
- cursor foreground/background.

## Font and Text Contract

ASCII must have a fast path.

Font fallback must be cached and resolved by runs where possible. Configured
host fallback fonts should be tried before any system-font scan. If system-font
fallback is enabled, scanning must be cached and must not run inside the ASCII
hot path.

Simple text should render as contiguous runs grouped by compatible font,
foreground color, and style.

Complex clusters may use a slower fallback/shaping path, but that path must not
pollute the ASCII/simple-text hot path.

## Threading Contract

Swing component state belongs to the EDT.

Any off-EDT rendering must:

- publish immutable frame or strip snapshots.
- never mutate images or buffers currently visible to the EDT.
- use double buffering or equivalent ownership transfer.
- avoid touching Swing component state off the EDT.
- snapshot settings before rendering work begins.

## Host Integration Contract

Host modules provide environment-specific adapters.

Examples:

- IDE host can provide settings, clipboard, font fallback, tool-window
  lifecycle, actions, and disposal hooks.
- Standalone host can provide window assembly, system clipboard, system
  font fallback, local config, PTY startup, and app lifecycle.

Those host concepts must enter `ketraterm-ui-swing` only through small
interfaces.

## Testing

Prefer deterministic Swing model and render-frame replay tests.

Tests should cover:

- viewport math.
- selection ranges.
- keyboard and mouse translation.
- cursor visibility and repaint bounds.
- color resolution.
- resize-to-columns/rows calculation.
- render cache consumption.
- render cache resize and generation-based repaint behavior.

Avoid tests that require a real PTY or IntelliJ runtime.

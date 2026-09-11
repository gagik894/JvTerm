# KetraTerm Library Changelog

Release notes for library consumers and embedders. Product-specific changes are recorded in the [IntelliJ plugin changelog](ketraterm-intellij-plugin/CHANGELOG.md) and [standalone application changelog](ketraterm-app/CHANGELOG.md).

## [0.2.2] - 2026-09-11

- Raised the minimum build and runtime requirement from Java 21 to Java 25. Library consumers must provide Java 25 or newer.
- Refactored session and render orchestration around coroutine-based lifecycle and bounded latest-frame publication, reducing redundant frame extraction during heavy output while keeping Swing/workspace rendering off core read paths.
- Made Swing settings immutable: padding uses `SwingPadding` instead of AWT `Insets`, and fallback font lists use `ImmutableList`. Hosts should convert mutable font lists with `toImmutableList()`; font-cache configuration changes now reliably invalidate cached resolutions. Consolidated duplicate string-keyed font LRU implementations while retaining separate bounded caches.
- Fixed concealed text becoming visible through block cursors, hyperlink foreground overrides, or native emoji. ASCII foreground runs now clip glyph ink at style and visibility boundaries. Resetting a hidden blink phase now repaints unchanged cursor and blinking-text pixels.
- Aligned bidirectional text with backgrounds, decorations, cursor, selection, search overlays, and hit testing. Contextually shaped glyph groups occupy their terminal cells; cursor repaint and bounded shaping windows preserve neighboring Arabic forms, combining marks, and ligatures. Mixed bidi rows retain native emoji and geometric primitive rendering.
- Prevented ordinary Unicode from initializing the native emoji rasterizer. Warm scalar and cluster image-cache lookups reuse primitive keys and slices; successful and unsupported rasterizations share a bounded cache, preventing repeated native work for unsupported text until eviction.
- Unified native emoji dispatch and font-preference classification. Arabic joiners and text-presentation selectors no longer give emoji fonts or host emoji overrides precedence over a capable primary text font, including platforms whose emoji fonts advertise native substitute glyphs.
- Added explicit render-cache source lifetimes and frame availability. Session replacement invalidates retained render, bidi, and search data, and an unpublished session displays an empty surface. Direct `TerminalRenderCache.accept` consumers must call `reset()` when changing sources; allocated storage is retained.
- Added render `contentGeneration` tracking so cursor-only and viewport-only publications can reuse active search results without rescanning retained history. Repaint planning includes previous and current search highlights, including matches spanning changed and unchanged wrapped rows.
- Fixed rectangular selection across bidi rows by storing visual horizontal bounds and mapping each row for text extraction. Vertical viewport clipping preserves block-selection columns; linear selections retain logical-column semantics.
- Fixed global reverse video and relevant reset transitions leaving cached history attributes stale. Global render invalidation now covers retained history without walking every history row to mark it dirty.
- Consolidated smooth-scroll position, animation, and history anchoring. Output rebases active motion without restarting its deadline; direct scrollbar dragging applies immediately, and translation stays within installed cache coverage while replacement frames are pending. Resize and buffer transitions preserve coherent viewport coordinates through `TerminalSession.resizeViewport` and its atomic history/discard baseline.
- Reused bidi and repaint metadata capacity across smooth-scroll overscan changes, preserving unchanged row layouts. Scrollbar painting retains geometry and palette colors, and handles tracks shorter or narrower than the normal thumb size.
- Fixed public snapshot threading: `currentSelection()` reads selection and viewport together on the EDT, while `viewportState()` copies one complete publication under a short monitor. Viewport callbacks run outside synchronization, and primitive animation publication and EDT scrollbar reads avoid snapshot allocations.
- Clarified rendering and API contracts for logical cluster columns, font resolution, bounded shaping, cache lifetimes, and EDT-only `currentSearchState()` and `preferredGridSize()` access. Documented synchronous first-use native emoji initialization and the scope of allocation measurements; these measurements do not establish whole-frame allocation or latency guarantees.
- Moved Swing allocation measurements from unit-test counters into the existing JMH suite with GC profiling. Unit tests retain cache and rendering semantics, and scrollback/selection fixtures control frame publication and release timers reliably. Whole-component painting benchmarks now bind, paint, and dispose on the EDT; CI compiles the benchmark harnesses.

## [0.2.1] - 2026-07-14

- Fixed held Backspace and Delete keys so every OS key-repeat event reaches the shell.
- Improved legacy shell and TUI compatibility for Backspace mode, Ctrl-number shortcuts, modified base keys, and extended function keys.
- Aligned PTY and device-attribute identity with KetraTerm's implemented VT420-level capabilities.
- Added VT420 rectangular-area checksums (`DECRQCRA`) across the parser, core, and host pipeline. Responses are policy-gated, bounded to the active page, and preserve DEC origin/margin semantics.

## [0.2.0] - 2026-07-13

- Fixed multiline paste framing across local PTY sessions: bracketed paste preserves the payload, while unbracketed paste uses CR line boundaries so PowerShell and other interactive shells do not receive duplicate or reordered continuation input.
- Made selection copy history-stable by resolving absolute row ranges under the session mutation lock, preventing stale-cache copies of unrelated scrollback text.
- Fixed selection extraction to preserve soft-wrapped lines and omit trailing empty terminal cells without leaving stale clipboard contents.
- Fixed scrollback admission for top-anchored scroll regions and alternate-screen transitions so TUIs using region-aware scrolling no longer get stuck at live rows and can scroll historical output correctly.
- Fixed prompt gutter selection in the terminal UI: it now selects the full command block (prompt + output) without leaking into the next prompt row.

## [0.1.3] - 2026-07-08

- Moved search chrome and shortcut policy into host-owned action wiring.
- Added shared Swing host utilities for floating search UI and default host shortcuts.
- Added host key handling so shortcuts can be handled or passed through to the PTY.
- Added host-owned context menus that respect terminal mouse reporting, with Shift-right-click as a menu override.
- Expanded search and shortcut coverage.

## [0.1.2] - 2026-07-03

- Added terminal process close confirmation and enhance command lifecycle tracking.
- Added render-cache-bounded IDE hyperlink overlays so host-discovered links can be highlighted and activated without scanning during paint, hover, parser, or core hot paths.
- Improved hyperlink rendering with a cleaner solid underline and immediate preservation of discovered link highlights while scrolling through cached rows.
- Added a `scrollOnOutput` configuration policy and UI settings toggle to lock viewport scroll position or snap to bottom when new process output arrives.
- Added host-provided terminal font fallback resolution through `TerminalFontResolver`, including IntelliJ integration backed by `ComplementaryFontsRegistry`.
- Added bounded script-run shaping for complex Swing text rendering so Arabic, Indic, and connected-script runs are shaped with full run context while the ASCII path stays fast.
- Replaced eager system fallback font retention with a bounded `SystemFontLru` cache to reduce AWT native font memory pressure.
- Overhauled `TerminalSelectionController` to copy multi-line selection text across the entire terminal history by fetching custom render frames instead of clamping to the active visible viewport.
- Fixed first-run TUI wrapping corruption by drawing the scrollback scrollbar inside a reserved terminal gutter instead of letting scrollbar visibility change the available terminal width.

## [0.1.1] - 2026-07-02

- Fixed AltGr character input and terminal grid corruption on AZERTY keyboards for Windows/Linux.

## [0.1.0] - 2026-06-30

- First public KetraTerm standalone desktop application release.
- Added a native desktop window hosting the KetraTerm terminal component.
- Added support for multiple terminal tabs with custom titles.
- Added keyboard shortcuts for tab navigation (Ctrl+T for new tab, Ctrl+W to close tab, etc.).
- Integrated local pseudo-terminal (PTY) transport for zsh, zli/zsh, bash, sh, PowerShell, and cmd.exe.
- Configurable typography, default colors, palettes, and scrollback capacity.

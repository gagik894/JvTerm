# Terminal Feature Gap Map

This is the living backlog of gaps, TODOs, and policy constraints in the KetraTerm parser, core, host, input, and UI layers. 

For a complete directory of supported features, see the [Terminal Feature Map](terminal-feature-map.md).

The target is a modern, secure, xterm-compatible terminal pipeline for contemporary shells and TUIs. Obsolete or risky legacy protocols remain excluded unless they earn their place.

## Status Labels

- `TODO(parser)`: byte/protocol recognition or semantic dispatch is missing.
- `TODO(core)`: terminal state, grid physics, pen storage, or public API is missing.
- `TODO(host)`: parser and core both have enough shape, but the integration bridge is incomplete.
- `TODO(session)`: runtime synchronization, host-side state, or session-owned metadata is missing.
- `TODO(ui)`: reusable UI presentation, interaction, or rendering behavior is missing.
- `TODO(input)`: host-bound keyboard/mouse/paste encoding is missing.
- `TODO(host/profile)`: product host, profile, or settings defaults are not exposed even though the lower-level terminal mechanism exists.
- `TODO(policy)`: feature needs an explicit security or compatibility policy before implementation.

---

## Product Targets & Priority

### Tier 1: Required (Gaps in core modern features)
- *No outstanding CSI cursor, SGR color, alternate screen, or basic input gaps.*

### Tier 2: Useful (Under consideration / partial gaps)
- *No outstanding SAFE query-response, DECRQSS/XTGETTCAP, push/pop title stack, or host-adapter allow/deny policy-surface gaps (all implemented and verified).*
- `FIXED(alpha-blocker)`: Kitty keyboard capability advertising is per-session and admits only progressive flags backed by complete active-host metadata. The portable Swing profile, including IntelliJ-hosted Swing, exposes `1` (disambiguate escape codes) and `8` (report all keys as escape codes); richer flags stay unadvertised.

### Tier 3: Optional (Graphics & advanced features)
- Sixel or modern graphics protocols (e.g. Kitty graphics protocol).
- Richer hyperlink, title, palette, and notification host callbacks.

---

## Intentional Non-Goals

These are not badges of compatibility for this project. They expand attack surface or maintenance cost without meaningful modern terminal value.

- Tektronix 4014 emulation.
- Media Copy / printer passthrough (`CSI i`).
- X11-specific font loading protocols.
- Blind OSC 52 clipboard writes. Clipboard writes are supported only after bounded parsing, size checks, configured policy allowance, and product-host clipboard callback routing.
- Unbounded or unaudited DCS/OSC responses.
- Literal "everything xterm ever accepted" parity.
- Tertiary Device Attributes (`DA3` / `CSI = c`) query response (excluded to prevent unique hardware serial number leak/user fingerprinting).
- Window position reporting (`CSI 13 t`) query response (excluded to prevent screen pixel coordinate leak/clickjacking).

---

## Parser Gaps

### CSI Protocols
- `TODO(parser/core)`: conditional ANSI SCP compatibility for parameterless `CSI s` / `CSI u`. KetraTerm currently reserves plain `CSI s` for DECSLRM; xterm-style save/restore requires mode-aware disambiguation so parameterless DECSLRM continues to reset horizontal margins when DECLRMM is active.
- `TODO(parser/core)`: broader DEC-specific status reports beyond the safe DSR/CPR/DA baseline.
- `TODO(parser)`: full tab-stop and margin variants beyond the current common set.
- `DONE(parser/core/host)`: rectangular erase (`DECERA`), selective erase (`DECSERA`), fill (`DECFRA`), copy (`DECCRA`), and checksum response (`DECRQCRA`) preserve active margin/origin coordinates; mutation operations preserve wide/cluster span integrity and `DECCRA` uses overlap-safe snapshot semantics. Copy/checksum intentionally support only the active single page (`0` omitted or `1`); checksum responses are terminal-response-policy gated and use the VT420 default 16-bit algorithm.
- `TODO(policy)`: xterm `XTCHECKSUM` extensions (`CSI Ps # y`) are not implemented. The stable DECRQCRA path deliberately uses the base VT420 behavior: erased/spacer cells omitted, base glyph values masked to eight bits, and supported legacy video attributes included; no color, combining-sequence, or alternate xterm extension semantics are claimed.
- `DONE(parser/core/host)`: DECSACE, DECCARA, and DECRARA implement VT420's stream-versus-exact-rectangle extent, ordered visual SGR subset, blank materialization policy, and atomic wide/cluster attribute updates without changing glyph payloads, protection, hyperlinks, or the current pen.
- `DONE(parser/core/host)`: DECIC and DECDC insert/delete columns across the active vertical scroll region, honor horizontal margins, preserve cursor position and active-buffer isolation, and repair wide/cluster span boundaries before each row shift.
- `TODO(parser)`: insert/delete/erase variants with selective protection and rectangular bounds.
- `TODO(parser)`: scroll variants and xterm extensions not yet routed:
  - left/right-margin-aware variants need broader host tests.

### ESC Protocols
- `TODO(parser)`: broader ISO 2022 national replacement sets:
  - UK, US, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish, Swedish, Swiss, Portuguese.
- `TODO(parser)`: 8-bit C1 equivalents for ESC-prefixed controls if raw C1 mode is supported later.
- `TODO(parser)`: save/restore state parity between DEC and SCO cursor save forms, if compatibility requires it.

### OSC Protocols
- `DONE(parser/host/session/pty/workspace/ui/policy)`: OSC 52 clipboard write requests are bounded, parsed, denied by default, size-checked, origin-aware, surfaced as content-free host audit events, forwarded as decoded text to product-host clipboard callbacks only when policy allows the write, and forwarded to compact standalone/IntelliJ confirmation dialogs when policy requires a prompt. Product prompts use terminal profile names, character counts for text writes, and clear-clipboard wording for empty writes instead of exposing raw OSC 52 selection tokens.
- `TODO(host/ui/policy)`: OSC 52 allowlist management UI, non-clipboard selection mapping, and any read/query response path remain unimplemented until product hosts explicitly opt in. Standalone and IntelliJ settings intentionally hide the `allowlist` option until a product-owned allowlist can persist entries and set `TerminalClipboardPolicy.allowlisted`.
- `TODO(parser)`: OSC 1337/iTerm2 extensions, if desired.
- `TODO(parser)`: OSC query responses. Requires terminal-to-host output.
- `TODO(parser)`: payload encoding policy for non-UTF-8 or invalid UTF-8 OSC data.

### DCS Protocols
- `TODO(parser)`: Sixel graphics, if the emulator will support inline graphics.
- `TODO(parser)`: Kitty graphics protocol, commonly sent as `ESC _ G ... ESC \`. This is more relevant to modern TUIs than legacy graphics.
- `TODO(parser)`: ReGIS / DEC vector graphics (likely out of scope).
- `TODO(policy)`: any DCS that can exfiltrate host capabilities needs a response policy and terminal-to-host channel.

### Text and Unicode
- `DONE(parser)`: malformed UTF-8 recovery is exercised immediately before and inside ESC, CSI,
  OSC (BEL/ST/CAN/SUB), DCS ST, and end-of-input, with every split boundary proving that malformed
  bytes do not print or complete stale structural commands.
- `TODO(parser)`: configurable replacement policy if needed by host applications.
- `TODO(parser)`: broader ISO 2022 charset mapping.

---

## Core Gaps

### Reset and Mode Semantics
- `TODO(core/host)`: richer event API for hyperlink metadata, palette changes, terminal notifications, and any future host-observable state that should not be read from render frames.

### Grid Operations
- DONE(core): deterministic randomized left/right-margin properties cover ICH/DCH, selective erase, IL/DL, and partial-region scroll up/down. They model guard-column movement, preserve rows outside the scroll region, and verify protected wide spans plus wide/cluster storage invariants.
- `TODO(core)`: scrollback policy under alternate-screen and private-mode combinations beyond tested full-viewport and top-anchored primary-screen regions.
- `TODO(core)`: soft-wrap metadata compatibility with copy/paste/export.

### Unicode Width
- `TODO(core)`: invalid/unassigned codepoint width policy.

### Query and Response Channel
- `DONE(core/host/policy)`: terminal-to-host response channel exists for DA, DSR/CPR, safe window reports, palette queries, `DECRQSS`, and allowlisted `XTGETTCAP`; host policy can deny terminal responses before they enqueue bytes.
- `TODO(core/parser/host/policy)`: OSC query responses and future query/response protocols need explicit response shape, allowlist, and host policy before implementation.
- `TODO(core/host)`: event API for hyperlinks, palette updates, and terminal notifications if these move out of host or render-frame metadata.

---

## Integration Gaps

- `DONE(host/policy)`: host-adapter allow/deny policy surface for title updates, OSC 8 hyperlinks, OSC 7 current-working-directory reports, desktop notifications, window manipulation requests, palette controls, terminal response channels, and OSC 52 clipboard request auditing.
- `TODO(host)`: richer host callbacks for palette updates, terminal notifications, mouse-report policy, and future clipboard decisions when those product surfaces need UI or embedding feedback.

---

## Input Module Gaps

- `DONE(input/policy)`: paste encoding, bracketed-paste wrapping, and `TerminalInputPolicy` paste sanitization are implemented and tested. The generic encoder preserves payloads by default, can strip C0 controls except TAB/CR/LF, can canonicalize CRLF/CR/LF through an explicit host policy, and wraps with `CSI 200~` / `CSI 201~` when bracketed paste mode is active. Bracketed payloads preserve their original line endings; unbracketed local PTY input canonicalizes newline forms to CR.
- `DONE(host/profile)`: standalone/workspace local PTY profiles persist `paste_sanitization` (`raw`, `strip-c0`, or `normalize-line-endings`) and apply it to newly opened tabs and splits through `TerminalWorkspaceOpenOptions` and `PtyOptions.inputPolicy`.
- `TODO(host/profile)`: expose paste policy defaults for SSH and IDE/workspace embedding profiles when those product surfaces are wired; input already provides the mechanism.
- `TODO(input)`: broader modified-key encoding:
  - xterm modifyOtherKeys subparameter mask support such as `CSI > 4 : 1 m`; this factors modifiers out of the source keysym and therefore remains deferred with rich layout-aware input metadata.
- `TODO(parser/core/input)`: xterm modified-key policy surface for `modifyCursorKeys`, `modifyFunctionKeys`, and `modifyKeypadKeys`.
- `TODO(input/policy)`: additional xterm-compatible key policies when a real ambiguity exists, such as Delete behavior and optional eight-bit Meta output.
- `DONE(protocol/core/host/input/ui)`: DECBKM mode 67, conventional Ctrl+2 through Ctrl+8 control bytes, xterm modified F3, legacy F13-F35 aliases, and lossless Shift/Ctrl fallback for base Enter/Escape/Backspace/keypad keys are implemented through allocation-free packed mode state and primitive lookup tables.
- `TODO(parser/core/input)`: xterm highlight mouse tracking (`?1001`) if full xterm mouse parity is required.

### Deferred Kitty Keyboard Protocol Scope
- `DONE(protocol/core/pty)`: terminal capability identity contract centralizes `$TERM`, `COLORTERM`, DA/DA2, XTGETTCAP terminal-name/color claims, and the implemented Kitty keyboard flag mask.
- `DONE(protocol/core/host/session/input)`: Kitty capability handling distinguishes the full encoder mask from the conservative portable-host mask. The parser-to-core adapter applies a per-session host mask to replace/set/clear/push operations, so query responses reflect only capabilities the active host has explicitly declared.
- `DONE(input)`: `TerminalKeyEvent` can carry an optional unshifted printable-key scalar, and Kitty CSI-u encoding uses it instead of produced text when present.
- `DONE(parser/core/host/policy)`: parameterless `CSI ? u` reports only active Kitty keyboard progressive flags admitted by the session's host capability mask through the existing terminal-response policy.
- `TODO(host, deferred)`: native rich-input adapters for layout-aware physical-key identity, IME text, and complete lifecycle metadata. This requires host-specific native integration; no portable Swing or IntelliJ-hosted Swing session may advertise flags `2`, `4`, or `16` until that work is implemented and verified.
- `DONE(input)`: normalized keyboard events carry an explicit press/repeat/release lifecycle phase without host-toolkit dependencies or encoder hot-path allocation.
- `DONE(input)`: the Kitty encoder formats the lifecycle phase in the `modifier:event-type` subfield; without flag `2`, releases are suppressed while repeats retain normal press encoding.
- `DONE(ui)`: Swing preserves press/repeat/release for AWT-visible non-text physical keys through a fixed preallocated pressed-key table; unmatched releases are not invented or emitted.
- `DONE(protocol/input/ui)`: the complete Kitty functional-key table is represented by normalized input vocabulary and allocation-free PUA lookup tables; Swing maps the subset exposed by AWT, while richer hosts can supply the remaining keys.
- `DONE(input)`: normalized modifiers preserve Kitty's independent Super, Hyper, Meta, Caps Lock, and Num Lock bits while legacy CSI encodings retain their compatible four-modifier representation. Modifier-only key reports are gated by flag `8`.
- `DONE(input)`: normalized printable events carry validated Kitty shifted and base-layout alternate key scalars; the encoder formats both fields, including the required empty shifted field when only a base-layout scalar exists.
- `DONE(input)`: host-owned associated text is scalar-validated and encoded as Kitty colon-separated text codepoints without encoder-side allocation.

---

## Session, Transport, Rendering, and Host Integration Gaps

- `TODO(host/profile)`: Restore suggestion settings when they are ready for product exposure. Both settings forms retain commented `SUGGESTION_SETTINGS` blocks for the master switch, automatic popups, Enter acceptance, and persistence. Uncomment each form's fields, layout, change tracking where applicable, and Apply/Reset bindings together; uncomment the matching IntelliJ message key and update the hidden-controls tests. Keep the master default off. Learning-reset buttons also require reconnecting the removed host callbacks before restoring their confirmation UI (the previous wiring is in the parent of commit `4ef4d3f4`).
- `DONE(policy/host/ui)`: OSC 52 clipboard permission model covers local vs remote origin, deny/prompt/allowlist/allow write decisions, disabled read/query behavior, decoded payload limits, malformed payload rejection, content-free audit events, allowed write execution through product-host clipboard callbacks, and prompt-mode write confirmation in standalone plus IntelliJ hosts. Standalone and IntelliJ expose only currently actionable write/read modes in settings while retaining low-level allowlist enforcement for future product allowlists.
- `TODO(host/ui/policy)`: OSC 52 allowlist management UI, selection-specific clipboard targets beyond the host clipboard, and read/query responses remain intentionally absent.
- `TODO(policy)`: richer hyperlink validation and display policy beyond host resource limits, host allow/deny gating, and Swing's explicit-activation handler.
- `DONE(host/policy)`: host-owned metadata and response controls have explicit policy gates and per-feature host caps for titles, hyperlinks, OSC 7 current-working-directory reports, notifications, palette controls, window manipulation, and terminal response channels.
- `TODO(parser/policy)`: protocol-family-specific raw OSC/DCS parser payload ceilings beyond the parser's generic bound, especially before large graphics or clipboard protocols are enabled.
- `DONE(host/policy)`: title/icon updates are host-gated through `HostPolicy.titlePolicy`, which models local vs remote session origin, local/remote allow decisions, and configurable oversized-title handling (`clamp` by default for standalone compatibility, or `reject` for stricter profiles).
- `DONE(policy)`: terminal capability identity policy is explicit in `TerminalCapabilityIdentity` and consumed by PTY launch defaults plus core terminal-to-host query responses.

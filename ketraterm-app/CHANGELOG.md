# KetraTerm Standalone Changelog

## [Unreleased]

- Fixed alignment of Hebrew, Arabic, and mixed-direction text with cell backgrounds, the cursor, selection and search highlights, and mouse targets.
- Fixed Arabic letter forms changing under the block cursor and missing text in very long lines that require contextual shaping.
- Fixed Arabic text containing joiners unexpectedly switching fonts, particularly on macOS.
- Preserved native emoji rendering and solid block graphics on lines containing right-to-left text.
- Fixed concealed text and emoji becoming visible under the block cursor or when hovering over links. Prevented neighboring characters from spilling into concealed cells.
- Fixed rectangular selections widening across mixed-direction lines or when part of the selection scrolls out of view.
- Fixed a terminal view retaining the previous session's text or search results when switching to a new session.
- Fixed blinking text and the cursor remaining hidden after unrelated output updates.
- Fixed inverted colors in scrollback failing to update when reverse-video mode changes or resets.
- Fixed gaps during smooth scrolling while new content is loading, scroll-position jumps during resizing, and stale scroll animations after screen switches.

## [0.2.1] - 2026-07-14

- Fixed holding Backspace or Delete deleting only one character.
- Improved compatibility for Ctrl-number shortcuts, modified keys, extended function keys, and applications that configure Backspace behavior.
- Reported terminal capabilities more accurately to shells and terminal applications.
- Improved compatibility with full-screen terminal applications that verify rectangular screen regions.

## [0.2.0] - 2026-07-13

- Fixed multiline paste.
- Fixed terminal selection copying that could intermittently copy the wrong text or leave the previous clipboard contents unchanged.
- Improved copying of scrollback selections, wrapped lines, and trailing empty cells.
- Fixed scrollback behavior in terminal apps (including TUIs like codex logs) so historical output is scrollable again.
- Fixed prompt gutter clicks selecting the wrong text under certain circumstances.

## [0.1.3] - 2026-07-08

- Improved focused-terminal shortcuts for copy, paste, search, and page scrolling.
- Added a compact floating search bar with result navigation and match-case control.
- Added a terminal context menu that respects TUI mouse tracking.
- Improved macOS shortcut behavior with standard Cmd+C, Cmd+V, and Cmd+F bindings.

## [0.1.2] - 2026-07-03

- Added terminal process close confirmation.
- Added a "Scroll on output" setting to keep the viewport in place or follow new output.
- Fixed selection copying to support text spanning the entire terminal scrollback history.
- Fixed first-run TUI wrapping corruption.

## [0.1.1] - 2026-07-02

- Fixed AltGr character input and terminal grid corruption on AZERTY keyboards for Windows/Linux.

## [0.1.0] - 2026-06-30

- First public KetraTerm standalone desktop application release.
- Added a native desktop window with multiple terminal tabs, custom titles, and tab-management shortcuts.
- Added local terminal sessions for zsh, bash, sh, PowerShell, and cmd.exe.
- Added configurable typography, default colors, palettes, and scrollback capacity.

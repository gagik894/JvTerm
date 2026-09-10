# Terminal Parser Agent Guide

`ketraterm-parser` turns a terminal host byte stream into semantic terminal
commands. It is a parser and text segmenter, not a grid engine.

## Parser Boundary

Parser owns:

- UTF-8 decoding and malformed-byte recovery.
- ANSI byte classification and finite-state transitions.
- CSI, ESC, OSC, DCS, charset, and mode protocol recognition.
- SGR, OSC, and command dispatch vocabulary.
- grapheme cluster segmentation.
- semantic calls into `TerminalCommandSink`.

Parser must not own:

- terminal width or height.
- cursor clamping or grid bounds.
- cell width calculation.
- scrollback storage.
- rendering state.
- core mode persistence beyond parser-local sequence assembly.

If a behavior needs grid state, expose a semantic sink method or defer it to
core/host. Do not smuggle core decisions into parser code.

## FSM and Dispatch Rules

- Keep byte classification, state transitions, and semantic actions separate.
- The FSM matrix owns routing; `ActionEngine` performs actions; dispatchers map
  completed sequences to terminal commands.
- CSI dispatch should use structural signatures, not final-byte-only switches.
- OSC/DCS payloads must be bounded. Overflow-sensitive commands should be
  ignored unless a clear policy says otherwise.
- Do not use global control execution inside string states when terminal
  semantics require string-local handling.
- Unsupported sequences are swallowed or ignored according to terminal
  semantics; they must not accidentally print or dispatch as another sequence.

## Unicode and Text Rules

- `TerminalParser` owns UTF-8 decoding. Do not add a second decoder inside
  `PrintableProcessor`.
- `PrintableProcessor` accepts already-decoded codepoints or ASCII fast-path
  bytes and forwards text through charset mapping and grapheme assembly.
- Grapheme segmentation belongs in parser/unicode.
- Cell width belongs in core.
- Use generated-table-shaped Unicode APIs even while seed data is curated:
  `UnicodeClass.graphemeBreakClass`, `UnicodeClass.isExtendedPictographic`,
  and future generated tables.

## Protocol Security

Parser recognizes and bounds protocols; it does not choose host-affecting or
outbound-response policy. Route semantic intent to the owning layer and follow
the root response-security rule. Current support, deferrals, and policy gates
belong only in the canonical feature and gap maps.

## Testing

Parser tests must be semantic, exhaustive, and hostile-input aware. They should
fail if parser logic disagrees with real terminal behavior.

Cover these layers independently and together:

- `ByteClass`, `AnsiState`, `ParserState`, and generated dispatch tables.
- `AnsiStateMachine` matrix transitions, including string termination.
- `ActionEngine` side effects, parameter invariants, payload bounds, and
  recovery.
- `CommandDispatcher`, SGR, OSC, mode, charset, and Unicode components.
- `TerminalParser` full byte-stream host.

Required edge classes:

- omitted, empty, colon, and overflowing parameters.
- CAN/SUB abort behavior.
- malformed UTF-8 followed by ASCII, ESC, CSI, and string terminators.
- OSC/DCS termination by BEL/ST/CAN/SUB.
- unsupported and malformed sequences.
- chunk boundaries at every interesting byte.
- maximum params, intermediates, payloads, and grapheme cluster length.

Use recording sinks and harnesses, but keep expected events explicit.

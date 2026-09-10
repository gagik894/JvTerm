# Terminal PTY Agent Guide

`ketraterm-pty` owns local pseudo-terminal process lifecycle and stream wiring.
It exposes local PTY processes through `ketraterm-transport-api` connectors and
convenience factories that return the shared `ketraterm-session` runtime.

## Boundary

PTY owns:

- spawning and closing PTY-backed terminal processes.
- pumping raw process output bytes to `TerminalConnectorListener`.
- writing host-bound byte ranges to PTY stdin.
- resizing the PTY process.
- reporting BEL and title metadata through `PtyEventListener` when the
  convenience session factory wires host events.

PTY must not:

- parse escape sequences or inspect parser state.
- mutate grid/cursor state directly.
- encode keyboard, paste, focus, or mouse bytes itself.
- duplicate `ketraterm-host` command mapping.
- expose concurrent access to `DefaultTerminalInputEncoder`.

## Testing

Unit tests should use fake process streams for connector lifecycle and wiring
behavior. Session-level behavior should go through `TerminalSession` plus a
`PtyConnector`, not a PTY-specific session class.

Native PTY smoke tests are opt-in because PTY4J startup is platform-sensitive:

```text
./gradlew :ketraterm-pty:test --tests "io.github.ketraterm.pty.PtyRealProcessTest" "-Dterminal.pty.host=true"
```

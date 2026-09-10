# Persistent Terminal Storage Layout

This document describes how KetraTerm stores local configuration, backups, and
command-completion learning data. Stored command data is used only for local
ranking and policy-approved replay suggestions; raw terminal stdout/stderr is
never saved.

## Directory Resolution Hierarchy

The workspace configuration path is resolved by
`TerminalWorkspaceConfigManager` in this order:

1. System property override:
   `-Dketraterm.config.path=/path/to/config.toml`.
2. Environment variable override:
   `KetraTerm_CONFIG_PATH=/path/to/config.toml`.
3. OS-specific default directories:
   Windows uses `%APPDATA%\KetraTerm\config.toml`, with a fallback to
   `%USERPROFILE%\.config\ketraterm\config.toml`.
   macOS uses `~/Library/Application Support/KetraTerm/config.toml`.
   Linux/Unix uses `$XDG_CONFIG_HOME/ketraterm/config.toml`, with a fallback to
   `~/.config/ketraterm/config.toml`.

Standalone backup and completion-learning files are stored next to the resolved
`config.toml`. The IntelliJ plugin stores its application-level completion index under the IDE system directory at
`ketraterm/command-completion-learning-v3.tsv`.

## Files

### `config.toml`

Stores load-time profiles, theme/font preferences, terminal sizing, behavior
settings, and security policy settings. Missing or invalid values fall back to
safe defaults. If no config exists, KetraTerm creates a default file with
comments.

### `config.toml.broken`

If the config manager encounters a fatal parse error, it copies the malformed
file to `config.toml.broken`, then writes clean defaults so the app can start.

### `command-completion-learning-v3.tsv`

For both standalone and IntelliJ this is an opt-in compact suggestion-learning
index rather than a terminal transcript. It stores opaque exact-command
ranking evidence separately from the smaller set of commands approved for
plaintext replay:

```tsv
KetraTerm_COMMAND_COMPLETION_LEARNING	3
R	<identityDigest>	<profileBase64>	<cwdBase64>	<useCount>	<successCount>	<failureCount>	<acceptedCount>	<dismissedCount>	<lastUsedEpochMillis>
H	<identityDigest>	<commandBase64>	<profileBase64>	<cwdBase64>
```

`R` rows contain no command text. Their Base64URL SHA-256 identity preserves
the exact single-line command, including case and trailing whitespace. A deterministic digest is not directly
decodable, but common commands remain guessable by hashing candidate strings.
Failure counts remain observational and never penalize completion ordering.
`H` rows contain successful, policy-approved plaintext for history replay and
observed-token inference; each must match an `R` row with the same identity and
context. Text fields are Base64URL-encoded without padding so tabs and Unicode
do not corrupt the TSV layout. Base64URL is not encryption. The decoder accepts
only the version 3 header, followed by `R` rows and then `H` rows. Legacy,
unsupported, malformed, or internally inconsistent files are rejected as a
whole.

`TerminalCompletionLearningCoordinator` is the single runtime owner. Each
bounded learning event updates memory before its recording call returns, so
ranking does not wait for hydration or disk. Hydration merges atomically with
live rows before any snapshot is written. One worker observes a conflated dirty
signal and checkpoints the latest immutable snapshot every 30 seconds while
dirty. Continuous command activity does not postpone that checkpoint, and
shutdown forces the final dirty write. The coordinator talks directly to one
bounded fixed-path file store, whose strict codec and atomic replacement run on
the I/O dispatcher. There is no repository, child writer, control-command
queue, per-event snapshot, or general flush operation.

Each product completion registry supplies one fixed destination when it creates
the coordinator and only toggles persistence on or off. Runtime path switching
and cross-file import semantics are intentionally unsupported. Loads, rows, line
sizes, and total file bytes are bounded before decoding or encoding, so neither
startup nor settings changes read this file on the Swing event-dispatch thread.
Derived matching keys are recomputed by completion models rather than stored as
additional fields.
The coordinator merges this one file with live in-memory learning; callers must
not separately preload the same aggregate file.

## Security And Secret Filtering

Standalone persistent suggestion learning is disabled by default. It can be enabled with
`suggestion_learning_persistence_enabled = true` under `[behavior]` in
`config.toml`. KetraTerm does not store a raw command-history file.
IntelliJ exposes **Remember learned suggestions across IDE restarts** and keeps
it disabled by default. When a product starts disabled, it neither loads nor
writes this file. Disabling at runtime synchronously prevents new writes and
invalidates a pending checkpoint; an atomic file operation already in progress
may finish. Hydration that already started may also finish and merge because
disabling does not clear in-memory learning. The first enable hydrates the fixed
file once, and later toggles do not reload it. A rejected or unreadable file is
not overwritten during that lifecycle, and the product logs one diagnostic with
the original read exception when available. Product-lifetime in-memory learning remains active. The plugin's
enabled store lives in the IDE system directory described above. During shutdown,
each product waits at most 500 ms for final completion persistence before cancelling
its persistence scope and continuing shutdown. Standalone performs that bounded wait
on a non-daemon shutdown thread so the Swing event thread can dispose the window immediately;
IntelliJ applies the same budget to its synchronous disposal callback.

Before plaintext enters retained learning, `TerminalCompletionLearningStore`
applies `TerminalCompletionReplayPolicy`. The file store rechecks the same
eligibility before encoding:

1. Commands starting with a space or tab are ignored, matching the common shell
   `HISTCONTROL=ignorespace` convention.
2. Blank, multi-line, and malformed UTF-16 commands are not learned.
3. Text containing ISO controls other than internal tabs, more than 4,096
   UTF-16 code units, or more than 8,192 UTF-8 bytes is excluded from replay.
4. Commands containing sensitive substrings are excluded from replay,
   including password/passwd, secret, token, apikey/api_key, private_key,
   access_key, secret_key, bearer, authorization, credential/credentials,
   passcode, passphrase, jwt, key markers, and auth markers.
5. Common credential-bearing option forms for Curl, MySQL/MariaDB, Docker
   login, Redis, and `sshpass` are excluded.
6. URI authority user-info containing a password is excluded from replay.

Well-formed commands rejected by the replay policy can still update and persist
opaque ranking counters, but they cannot be replayed or used for observed-token inference. Approved replay rows
retain command text and optional profile and working-directory context. The
filters block common accidental disclosures but cannot recognize every
argument, path, URL, credential, or user-defined secret. Treat
`command-completion-learning-v3.tsv` as sensitive local command-derived data
and protect it with normal filesystem permissions.

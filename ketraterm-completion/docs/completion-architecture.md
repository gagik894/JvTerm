# Completion Module Architecture

`ketraterm-completion` is a suspending completion engine module. It owns
request/candidate contracts, static command specs, command-line parsing,
structured parallel source evaluation, ranking, and bounded in-memory learning indexes. It does not own UI popup
behavior, persistence, shell processes, PTY/session lifecycle, or IntelliJ APIs.

## Public Surface

External modules should import only:

- `io.github.ketraterm.completion.api`
- `io.github.ketraterm.completion.model`

The `api` package exposes host-facing engines, source factories, request and
candidate contracts, and one concrete bounded learning store. Product registries
compose their path and dynamic providers directly and pass the shared learning
store to the engine. The engine automatically evaluates its
command specs as one static source, so hosts cannot wire parsing specs and
static candidates inconsistently. One bounded aggregate owns learning. It
publishes opaque ranking evidence plus an optional, positive, policy-approved plaintext
replay projection; learning never becomes a second independent provider vote.

`TerminalCompletionMatchRanges` is the immutable primitive-backed display-range
contract carried by completion candidates. Construction validates ordered,
non-overlapping UTF-16 scalar boundaries and takes ownership through defensive
copying at public boundaries; hosts use indexed access when adapting or painting
to avoid exposing mutable array state.

`TerminalCompletionSourcePrior` is the single reviewed cold-start policy for
built-in source families. Standalone and IntelliJ composition use these named
values instead of maintaining duplicated numeric constants. They remain small
inputs to evidence fusion and never become a priority-first sorting layer.

The `model` package contains durable public data models that hosts may persist
or construct:

- `TerminalCommandSpec` and `TerminalOptionSpec`
- `TerminalPathArgumentKind`
- `TerminalCompletionValueDomain`
- `TerminalCommandSpecs`
- `TerminalCompletionFeedbackKind`
- `TerminalCompletionRankingStats`
- `TerminalCommandReplay`
- `TerminalCompletionLearningSnapshot`

`TerminalCompletionReplayPolicy` is a best-effort conservative plaintext
eligibility filter, not proof that a command contains no secret. Malformed
UTF-16 is rejected before any evidence is recorded. For otherwise recordable
commands, the policy rejects multiline text, ISO controls other than internal
tabs, more than 4,096 UTF-16 code units, or more than 8,192 UTF-8 bytes. It then
applies leading-whitespace privacy and a small credential classifier. Those
well-formed commands may still update ranking through a stable, case-sensitive
SHA-256 identity, but only successful commands approved by the
filter can enter retained replay history or the observed-token compiler. Persistence
rechecks the same eligibility at its storage boundary. The digest is not
directly decodable, but common command strings can still be guessed and hashed
for comparison. At request time, plaintext history and observed tokens require
the recorded profile and canonical working-directory context to equal the
request context exactly, including null. Unknown replay context is not a
wildcard. Opaque ranking evidence keeps its existing context fallback.

Model constructors expose durable host-owned fields only. Derived matching
values, such as lowercase command text, are computed by completion internals
instead of being caller-owned constructor state. Exact command identity
preserves the full single-line command, including case, leading whitespace, and
syntactically meaningful trailing whitespace;
lowercase values are only for case-insensitive prefix search.

Types used only to tokenize, classify, rank, merge, or index suggestions belong
in implementation packages and must stay `internal`.

`TerminalCompletionSources.valueDomain(...)` adapts a suspending host loader for one declared
`TerminalCompletionValueDomain`. It resolves the active spec context through the shared tokenizer, applies the request's
shell quoting policy, and emits domain-tagged argument candidates. A provider may perform bounded host I/O and must
cooperate with cancellation. The loader receives the immutable request and resolved context, and owns an independent
input, visit, or time budget. It returns that complete host-bounded snapshot; matching and the final candidate limit remain
inside the shared source. A provider may additionally restrict itself to canonical command/subcommand names when a value
domain has command-specific validity.
Aggregate host sources that load several provider groups in one operation use
`TerminalCompletionSources.valueDomainCandidates(...)` to project each already-loaded group through the identical
matching, quoting, replacement, and scoring policy without constructing nested source adapters per request.

`TerminalCompletionSources.fuzzyPath(...)` accepts one query-aware `TerminalFuzzyPathProvider`. The host receives the
immutable request and resolved context and returns already matched, relevance-ordered entries within its own query,
input, visit, or time budget. It never receives the engine's final candidate limit. The shared source applies path-kind,
hidden-path, replacement, and shell-quoting rules before enforcing that limit, so ineligible early matches cannot hide
eligible later matches. Providers use the request's captured working-directory URI instead of resampling mutable session
state. Fuzzy path completion requires typed path text by default; a small, context-specific provider such as Git status
paths may opt in to empty-prefix suggestions.

`TerminalCompletionSources.gradleTask(...)` follows the snapshot contract as well. The host loader receives the request
and resolved Gradle context, traverses an imported model under its own visit budget, and returns the complete bounded task
snapshot. Canonical Gradle matching and the final candidate limit are shared-engine responsibilities.

`TerminalCompletionContextResolver` is the shared internal command-line context
resolver. The merged engine parses and resolves once from its one command-spec set, then passes
that same immutable context to every source and the global ranker instead of independently
guessing command position, subcommand position, option-name position,
option-value position, positional-argument position, active option metadata,
expected path kind, expected dynamic value domain, repeatable subcommand source,
static value candidates, replacement offsets,
or active quote state from raw command text.

Swing hosts share one `SwingLiveCompletionBinding`. Its debounced,
text-only predicate is deliberately a cheap UX gate: it never tokenizes,
resolves command specs, or duplicates source eligibility. The merged engine is
the sole semantic authority and returns an empty result when completion is not
valid. The binding retains only the last primitive request identity so equal
render frames do not republish work.

`TerminalShellCapabilities` is the single host-to-engine dialect contract. It
contains `TerminalShellSyntax` for segment lexing and
`TerminalShellQuotingPolicy` for replacement text. The shared engine never
infers either capability from command text or a profile id: hosts select the
tested `POSIX` or `POWERSHELL` capability set from authoritative profile
metadata, and use `PLAIN` for every shell without an implemented contract.

## Internal Implementation

These packages are implementation detail and must not be imported by app,
workspace, Swing UI, or future plugin code:

- `commandline`
- `engine`
- `history`
- `internal`
- `ranking`
- `source`
- `spec`
- `stats`

Top-level declarations in those packages should be `internal` unless a product
decision explicitly promotes a type into `api` or `model`.

Implementation files follow the completion request directly. One semantic
token pass resolves command paths, inherited options, repeatable subcommands,
option values, and positional arguments for live completion. The merged engine
captures one compiled learning-index set per request and uses it for positive
history candidates, observed tokens for unknown commands, and exact ranking
evidence. All three views therefore observe the same immutable mutation state
and contribute as one learned source. Ranking compiles directly from opaque
rows. Replay rows are joined to their matching evidence and tokenized once for
both history and observed-token indexes. The shared learning store publishes
immutable split snapshots and owns the identity-aware compiled-index cache.
Snapshot models remain pure persistence data. One scoring
policy owns bounded counter math. Global fusion owns
outcome grouping, explicit score components, semantic relevance,
representative selection, and deterministic final ordering.
Public directory path resolution, scan contracts, and bounded scan
implementations each live in their matching file in `ketraterm-completion-host`.

## Host Ownership

Hosts are responsible for choosing whether and where persistence is enabled. `TerminalCompletionLearningStore` applies
`TerminalCompletionReplayPolicy` before plaintext enters retained memory and merges its one
persisted hydration snapshot and accepts live feedback events, but it is not a completion source and never contributes a second visible candidate. Completion components never
read files, scan raw shell history, spawn shells, or talk to UI frameworks.

Optional disk I/O belongs to the separately published
`ketraterm-completion-persistence` module. Its
`TerminalCompletionLearningCoordinator` owns the public fixed-path lifecycle. Learning mutates its bounded in-memory
store synchronously; one conflated worker hydrates once, observes last-value enablement, and checkpoints the latest dirty
snapshot every 30 seconds. The file store persists opaque evidence and only
positive, policy-approved replay rows, rechecking replay eligibility before encoding. It
talks directly to one bounded file store and forces the final dirty write during shutdown. A user-requested reset clears
the shared in-memory store synchronously, supersedes any in-flight hydration, and sends an immediate empty replacement
through that same worker even when routine persistence is disabled.
There is no runtime path switching, repository lifecycle, separate writer, control actor, or arbitrary flush barrier.
Product hosts own the fixed destination, enablement policy, one load-failure diagnostic callback, and a bounded
shutdown durability budget. If final Java NIO does not finish within that budget, the host cancels its persistence
scope and stops waiting; the timeout is not presented as an interrupt guarantee for the filesystem operation.
Completion persistence is not a workspace responsibility.

The standalone app and IntelliJ plugin should compose completion sources through
`TerminalCompletionSources` and `TerminalCompletionEngines`, then adapt returned
candidates to their own UI presentation.

The public API should not grow by convenience. New public functions must be
durable host contracts, used by standalone/plugin integration, or explicitly
documented persistence/model contracts.
Kotlin `internal` visibility and Gradle module dependencies enforce the
implementation boundary. Do not recreate source-scanning architecture tests or
mirror permitted declarations in a second manual allowlist. Public constructors
and methods must document parameters/properties, return values, observable
failure behavior, ownership, threading, and I/O expectations where those
concepts apply.

## Command-Line Context Policy

Completion treats trailing space as a semantic boundary. With the cursor inside
`cd`, sources complete the command token. With the cursor after `cd `, sources
complete a new empty argument at the cursor. Matching learned commands are
projected to that argument, so `cd IdeaProjects/KetraTerm/` is presented and
inserted as `IdeaProjects/KetraTerm/`; the executable is not repeated in the
popup. A full command is retained only when the executable itself is active;
history rows that cannot be projected safely into the active context are not
offered.

The learning compiler derives a bounded observed-token index from successful,
policy-approved replay rows for executables that have no static `TerminalCommandSpec`. Commands
such as `abc de -g`, `abc de -f`, and `abc as` can therefore offer `de` and
`as` after `abc `, and `-g`/`-f` after `abc de `. These are `ARGUMENT`
candidates labeled as learned observations, not inferred subcommands or a
claimed command grammar. The index learns only the first non-option token after
an unknown executable and option names; it never learns later positional values
or option values. It is a derived view of replay rows joined to opaque evidence,
not a second mutable or
persisted learning family.

For supported POSIX and PowerShell syntax, the tokenizer uses one bounded
single-pass lexical scan per merged-engine request to select the cursor's
command segment. Operators inside quotes or escaped by the dialect do not split
segments. A cursor at the start of an operator belongs to the left segment; a
cursor inside a multi-character operator is an `OPERATOR` region and returns no
candidates; a cursor after the operator belongs to a new right segment.
Unclosed quotes and incomplete command lines remain tokenizable and resolve to
their closest logical segment. Learned-history candidates are suppressed in
segments following an operator until segment-local history ownership is
implemented; replacing across an operator boundary is never inferred.

The exact shell option terminator `--` is a command-context boundary once the
cursor has moved beyond the token, such as after a following space. While the
cursor remains attached to `--`, it is a long-option prefix and may suggest
options such as `--help`. Tokens after a passed terminator remain positional: they do
not resolve as options, option values, or subcommands, and static spec option
completion stops. An incomplete prefix such as `--v` remains an option prefix
until the terminator token has been passed.

Path completion is intentionally conservative. In command position it only
returns candidates for explicitly path-like prefixes. In argument position it
returns bare current-directory entries only when the resolved
`TerminalCommandSpec` or `TerminalOptionSpec` declares path metadata through
`TerminalPathArgumentKind`. Directory-changing commands such as `cd`, `chdir`,
`pushd`, and PowerShell `Set-Location` aliases receive directory-only
candidates, while commands such as `git add` and `kubectl apply` may request
file-or-directory candidates. Dot-prefixed entries are hidden for an empty path
prefix and appear once the user types `.` by default. Command positional and
option path metadata may instead set `TerminalHiddenPathPolicy.INCLUDE` to
always expose hidden entries, or `EXCLUDE` to keep them hidden even after a dot
prefix. When the active path token begins
with a quote, path candidates replace the whole token with a matching quoted
replacement instead of dropping the quote. Unquoted path replacements are
escaped according to `TerminalCompletionRequest.shellCapabilities.quoting`.
POSIX uses backslash escaping, PowerShell uses single-quoted literals where
escaping is necessary, and `PLAIN` omits replacements that would require
dialect-specific escaping. Existing single- and double-quote styles are
preserved when that style can safely represent the candidate.

In command position, an existing path candidate whose replacement resolves to
a declared top-level command alias is presented with that specification's
command kind and description. The path source remains the existence authority
and retains its ranking contribution; the specification supplies semantic
presentation only. Raw and shell-encoded alias spellings are indexed when the
engine is created, so this enrichment does not re-tokenize candidates or probe
the filesystem.

Path interpretation is host-owned. The pure source emits a
`TerminalDirectoryListingRequest` containing the authoritative working-directory
URI, a transport-neutral lexical directory prefix, and the active entry-name
prefix. It does not discard URI authorities, expand `~`, or interpret drive and
UNC roots. Hosts must reject remote authorities they cannot map safely and
return bounded results from a suspending provider that cooperates with request
cancellation.

Live trigger policy is a cheap presentation check, not a second completion
parser. A small non-whitespace threshold plus common trigger characters keeps
typing responsive, while the merged engine parses once and suppresses invalid
operator, command, option, path, and value-domain requests authoritatively.

Swing hosts share `SwingLiveCompletionBinding` and one EDT-confined
one-shot `Timer` for debouncing. `SwingTerminal` owns exactly one replaceable
`suggestionJob`; a new request or popup hide cancels it. The provider and engine
remain suspending end to end. Provider construction and flow collection execute
off the EDT, and progressive rankings are conflated before the latest immutable
snapshot is published back to Swing.

Presentation is intentionally platform-owned. The standalone host custom-paints
a compact completion list; the IntelliJ plugin owns a separate native `JBList`.
Both consume `SwingShellSuggestionViewSnapshot` and the same authoritative
display text, detail, source label, semantic accent role, and matched ranges.
The Swing adapter maps the products' stable source identities through one private
exact table; unknown identities use a bounded human-readable fallback.
Physical renderers may follow their platform's visuals and mechanics but do not
reparse engine kinds or provider identifiers.

Static bounded option domains belong in `TerminalOptionSpec.valueCandidates`.
Examples are output formats, log levels, or other values that are stable and do
not require host I/O. Dynamic domains are declared with
`TerminalCompletionValueDomain` through `TerminalOptionSpec.valueDomain`,
`TerminalCommandSpec.positionalArgumentValueDomain`, and
`TerminalCompletionCandidate.valueDomain`. Git branches, Docker contexts,
Kubernetes namespaces, IDE run configurations, project files, or indexed symbols
must still come from host-owned providers; the shared module only models and
ranks those values.

Options that require a value support both separate and attached forms. For
example, `aws --output text` and `aws --output=text` resolve to the same option
value context. Attached completion replaces only the text after `=`, preserving
the option name and separator. This applies to static values, path values, and
host-provided dynamic domains; a quoted attached path value preserves its quote
style through the normal path replacement policy.

Task-style CLIs that accept several sibling command values on one line should
set `TerminalCommandSpec.repeatableSubcommands`. Gradle is the built-in example:
after `./gradlew clean bu`, the context remains attached to the root Gradle
task set and the spec source can suggest `build` while omitting already-used tasks such as `clean`. A host may add a
suspending Gradle-task source for the same context. It completes imported root tasks, canonical module tasks such as
`:app:run`, and short names after `-p app` or `--project-dir app`; `-p`
uses a project directory, not a Gradle colon path.

`TerminalOptionSpec.exclusiveGroupIds` models mutually exclusive option sets
without coupling one option to another option name. Once a completed option
before the cursor claims a group, spec completion suppresses every option that
claims that group. Aliases resolve to the same option and therefore claim the
same groups.

`TerminalArgumentSpec` models ordered positional arguments. It supports static
value candidates, path and dynamic-domain metadata, optional arguments, and a
variadic final argument that applies to every remaining positional token.
`TerminalCommandSpec.positionalArguments` takes precedence when present; the
scalar positional fields remain the fallback for compact specs.

## Host Dynamic Providers

Reusable local-path resolution and bounded directory-scanning machinery belongs to `ketraterm-completion-host`; it
may perform bounded host work but does not parse, rank, prioritize, or schedule completion candidates. Standalone
and IntelliJ retain only environment-specific suspending loaders and scanners. There is no snapshot service, TTL,
publication callback, refresh-after-publication pass, semaphore, or provider-owned job. Blocking local filesystem access
moves to an injected IO dispatcher.
Enumeration has visit, result, and elapsed-time caps. The defaults (8,192 visited entries, 256 matches, and a 50 ms scan
budget) are an explicit desktop baseline covered by JMH directory-scan benchmarks; change them only with representative
local and remote-filesystem measurements.
The direct local NIO scanner performs one bounded scan per request and retains no
directory cache. IntelliJ's project-VFS scanner alone may retain one replace-only
raw snapshot when its directory URL, VFS modification stamp, and project-roots
modification count still match. That VFS snapshot is capped at 8,192 sorted
entries and filtered per prefix to at most 256 source candidates. Incomplete,
cancelled, failed, or version-changing VFS scans are never cached. There is no
TTL, refresh callback, worker, or merged-candidate cache.
`runInterruptible` makes local directory scans cooperatively interruptible. The app resolves
local and `localhost` file URIs, explicit home paths,
Windows drive roots, and Windows UNC roots while rejecting non-local OSC 7 authorities. The IntelliJ plugin uses
write-allowing suspending read actions for project-aware VFS queries and bounded local scanning elsewhere. One Git
source selects the repository for the terminal working directory and reads local branches, remote branches, and tags in
one IntelliJ read action. Local branches apply to `git switch`, `checkout`, `merge`, and `rebase`; remote branches and
tags apply to `checkout`, `merge`, and `rebase`, so `git switch` remains local-branch-only.
Whole-project fuzzy paths use a prefix-keyed suspending query through IntelliJ's Go to File model and item provider.
IntelliJ owns indexed discovery, fuzzy matching, path qualification, and result ordering; the plugin only converts PSI
items into shell-facing paths, while the shared source applies terminal path semantics. These queries use IntelliJ's
suspending `readAction`, so pending write actions restart the read without a blocking-context bridge. Fuzzy paths activate only in declared or
explicitly path-like terminal positions, while direct directory completion remains higher priority for immediate
children. Changelists, SDKs, and run configurations remain follow-up work. IntelliJ also reads its already-imported Gradle external-system
model into a bounded task result; it never starts Gradle from a completion request. Every IntelliJ loader uses the
working-directory URI captured in that immutable request. A separate Git status loader
supplies changed and
untracked paths for `git add`, `restore`, `rm`, and `diff` without starting a Git process.

IntelliJ dynamic completion is composed from ordinary source-producing functions and explicit prioritized source
entries. There is no provider-factory or registration framework. Each product completion registry composes its
providers with the shared learning store, maps host events into the learning coordinator, and
owns the persistence shutdown boundary, so completion files are never loaded on the Swing event-dispatch thread.

The engine-to-Swing request/candidate bridge and Swing-feedback-to-statistics mapping live in `ketraterm-ui-swing-host`.
Product hosts inject context, privacy, scheduling, and persistence policy instead of copying the vocabulary conversion
logic.

Standalone owns one stateless completion engine and local-filesystem provider per application registry. Pane resources
add only their request-context supplier and feedback binding.

Both hosts should map their data into the shared request/candidate/source
contracts and let the shared engine resolve outcomes, fuse provider evidence,
deduplicate, and rank candidates.
`ketraterm-completion` must stay pure: it should not shell out to Git, read IDE
indexes, watch files, or block on host I/O.

Learned events mutate one bounded exact aggregate and publish its immutable split snapshot lazily on the next completion,
persistence, or explicit snapshot read. Multiple events before that read therefore avoid rebuilding the full row
list. No-op or rejected events retain the current snapshot identity. There is no
second row-snapshot cache inside the mutable index.

On first use of a snapshot identity and shell syntax, one compiler tokenizes
each replay row once and feeds that parsed context to the positive-history
prefix index and observed-token index. The direct ranking lookup consumes only
opaque evidence and never requires plaintext. A completion request
captures that index set once before source evaluation and uses it throughout the
request even if learning mutates while sources run. One flat syntax-indexed cache
reuses the result for subsequent requests. History lookup groups rows
by exact canonical host context and normalized tokens before the active position,
then binary-searches the active token prefix. Observed-token success counts are
retained per exact host context, so neither visibility nor score can borrow
evidence from another profile or directory. A hot request does not rescan the
bounded 2,048-row snapshot.

The standalone host currently maps PowerShell to `POWERSHELL`, its tested
POSIX-profile categories to `POSIX`, and Command Prompt, Fish, Nushell, and
unknown profiles to `PLAIN`. Native shell completion callbacks and dialect
adapters remain host-owned future work; they must supply authoritative
replacement ranges and never be called from the shared completion hot path.

The compatibility contract is deliberately explicit:

| Capability                   | POSIX                                 | PowerShell                                  | Plain fallback                        |
|------------------------------|---------------------------------------|---------------------------------------------|---------------------------------------|
| Command separators           | `;`, `&`, `&&`, `\|`, `\|\|`          | `;`, `&&`, `\|`, `\|\|`                     | none inferred                         |
| Escape outside single quotes | backslash                             | backtick                                    | backslash tokenization only           |
| Quote recovery               | single and double                     | single and double, including doubled quotes | conservative tokenization             |
| Safe unquoted path escaping  | backslash                             | single-quoted literal                       | only values needing no dialect escape |
| Native shell callbacks       | host-owned, not invoked synchronously | host-owned, not invoked synchronously       | unavailable                           |

Command Prompt, Fish, Nushell, and unknown dialects remain on the plain
fallback until each has a tested lexical and quoting contract. This avoids
silently applying POSIX rules to incompatible shells.

## Ranking Policy

The merged engine ranks each provider locally, projects every candidate onto the
command it would produce, and groups source-independent outcomes. Shell quoting
is tokenized away for comparison. Declared path values additionally ignore only
a redundant trailing separator, allowing `cd build`, `cd build/`, and a safely
quoted equivalent to share evidence without resolving `..`, symlinks,
authorities, environment variables, or filesystem case.

Provider support uses reciprocal-rank fusion. Candidate scores are meaningful
only within their producing source; a learned score is never compared numerically
with a path or specification score. Each distinct semantic or learned source
entry contributes its best local rank for an outcome and a source prior clamped to
`[-20, 20]`. Exact learning evidence does not constitute another source entry,
so one learned command cannot gain a second provider vote from its ranking row.
Duplicate candidates from the same source do not multiply support.

The global ranker applies the strongest semantic adjustment among contributors.
Exact outcome statistics then add bounded execution usage, accepted/dismissed
feedback, recency, profile, and working-directory evidence. Nonzero command
exits never penalize ordering. Only explicit dismissal is negative; passive
popup closure is neutral. Popup feedback never creates replay history.

The edit representative favors semantic fit, a narrow replacement range, the
bounded prior, local rank, and stable declaration order. Presentation selection
cannot change that edit. The engine marks its derived learned batch as an
internal fallback and applies a small bounded penalty, increased for path edits
where live filesystem results are authoritative. Among contributors with an
identical edit, non-learned metadata wins after semantic fit and before the
ordinary prior and stable tie-breakers. The selected contributor supplies the
complete candidate atomically; the engine never mixes display text, match
ranges, detail, kind, or source labels from different candidates. Learned
evidence still strengthens matching specification or provider outcomes, and
unique learned outcomes remain visible. This fallback policy is not a public
source role or host configuration surface.

Returned candidate scores are the fused global score. Final ordering continues to
use the edit representative, so presentation ownership cannot change ranking.
Ordering and all tie-breakers are deterministic.

Source safety and presentation are independent. Every source receives a fixed
256-candidate output budget. Sources apply it only after their owned matching, eligibility, and encoding rules.
Fuzzy-path, Gradle-task, and value-domain host loaders do not receive that count; they use explicit independent query,
input, visit, history, or time budgets. Fuzzy-path providers return ready ordered matches, while Gradle-task and
value-domain providers return complete host-bounded snapshots for shared matching. There is no universal deadline or
provider-budget protocol. The engine globally fuses the complete bounded
union and has no popup-size parameter; the Swing controller alone presents an
eight-row sliding viewport across the ranked snapshot. The snapshot also carries
absolute overflow metadata so each physical renderer can expose range and scroll
position without gaining access to ranking state.

Source collection uses one cold structured `channelFlow`. The engine parses
once, resolves one context, evaluates its internal spec source directly, launches
one child per host source under a supervisor, and serially incorporates
completed-source events in the parent. Each changed
global ranking is emitted immediately, so a slow Git or index source cannot
block a fast spec, learned, or direct-path result. Individual sources remain
ordinary suspending functions and never own scopes or child jobs. A non-cancellation source failure is reported through
`TerminalCompletionSourceFailureHandler` and contributes an empty result; request cancellation reaches every child,
and source declaration order remains the deterministic final-fusion tie-breaker.
Host adapters therefore propagate operational failures, including abnormal filesystem access, through their source.
Only normal absence or unsupported host context becomes an empty provider result; adapters do not duplicate diagnostic
callbacks or silently convert failures into "no matches."

## Ranking Calibration

Ranking constants are policy, not universal truth. Changes to priors, smoothing,
recency buckets, or evidence clamps must pass the deterministic representative
replay in `CompletionRankingReplayTest`. The replay reports top-one rate,
top-three rate, and mean reciprocal rank for accepted outcomes covering paths,
Git branches, and imported Gradle tasks. New anonymized failure cases should be
added before tuning a weight so calibration cannot optimize only one provider.

Performance changes must also run `TerminalCompletionBenchmark`. The benchmark
includes eight-provider fusion, 2,048 learned rows, duplicate-heavy evidence,
hostile collection-cap input, and a real learned-history lookup backed by the
full snapshot. The learned-history case is prewarmed deliberately: it
measures the normal learning-store-owned compiled-index cache hit, while index
construction stays bounded to first use of a new snapshot or shell syntax.

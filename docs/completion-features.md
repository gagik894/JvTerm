# Smart Terminal Autocompletion

KetraTerm features an intelligent, IDE-grade autocompletion popup designed for modern developers. It provides instant, context-aware suggestions for your favorite CLI tools, files, Git branches, and Gradle tasks with zero keystroke latency.

---

## Highlights for Users

### 1. Instant Autocompletion for 18+ Developer Tools
Start typing any common command, and KetraTerm immediately provides subcommands, flags, and arguments:
- **Git**: `commit`, `checkout`, `switch`, `merge`, `rebase`, `diff`, `log`, `stash`, `restore`, and all common flags.
- **Gradle**: Universal tasks (`build`, `test`, `clean`, `tasks`, `run`, `assemble`) and flags (`--configuration-cache`, `--daemon`, `--parallel`, `--scan`).
- **Containers & Orchestration**: `docker`, `docker-compose`, and `kubectl` (with Kubernetes resources like `pods`, `services`, `deployments`, `namespaces`).
- **Rust, Go, Python, Node**: `cargo`, `go`, `pip`, `npm`, `pnpm`, `yarn`, `bun`.
- **Kotlin & Android**: `kotlin`, `kotlinc`, and `adb` (with `devices`, `logcat`, `install`, `shell`).
- **Cloud & Tooling**: `gh` (GitHub CLI), `aws` CLI, and `ketra`.

### 2. Smart CamelHump & Acronym Shortcuts
You don't need to type full command or task names. KetraTerm understands uppercase capitals and word abbreviations:
- Type **`ctk`** to instantly match **`compileTestKotlin`**.
- Type **`sA`** to match **`spotlessApply`**.
- Type **`bRel`** to match **`buildRelease`**.
- Type **`dc`** or **`d-c`** to match **`docker-compose`**.
- Type **`kg`** to match **`kubectl get`**.

### 3. Deep IntelliJ Platform Integration (When Running in IntelliJ)
When running inside IntelliJ IDEA or Android Studio, KetraTerm taps directly into the IDE's live project indices:
- **Whole-Project File Search**: Type any part of a filename to find and complete files across the entire project (powered by IntelliJ's *Search Everywhere* engine).
- **Live Git Branches & Tags**: Typing `git switch <TAB>` or `git checkout <TAB>` lists your repository's local and remote branches in real time.
- **Recent Git Commits**: Typing `git cherry-pick <TAB>`, `git revert <TAB>`, or `git show <TAB>` lists up to 50 recent commits with their short hashes and subjects; accepting one inserts its full hash.
- **Live Git Staged & Modified Files**: Typing `git add <TAB>` or `git restore <TAB>` lists the exact modified and untracked files from your IDE changelist.
- **Dynamic Gradle Tasks**: Completes all multi-module tasks (e.g. `:app:assembleDebug`, `:core:test`) directly from your imported project model.
- **Native Keymap Integration**: The popup responds to your familiar IntelliJ keybindings (e.g. Tab, Enter, Up/Down arrows, Escape).

### 4. Intelligent Path Completion
- **Subdirectory Navigation**: Fast directory scanning when typing `cd`, `ls`, or path arguments (`dir/sub<TAB>`).
- **Clean Results**: Hidden files and dot-folders (`.git`, `.idea`) stay hidden unless you explicitly type a leading dot (`.`).
- **Safe Quoting**: Paths containing spaces or special characters are automatically quoted or escaped according to your active shell.

### 5. Multi-Command Chaining Support
Works seamlessly across complex command lines with operators:
- `git status && git add <TAB>` completes files for `git add`, without getting confused by the previous `git status`.
- Supports pipes (`|`), `&&`, `||`, and `;` in Bash, Zsh, and PowerShell.
- Typing `--` safely switches to file completion, suppressing irrelevant flags.

### 6. Visual Match Highlighting
Characters that match your search query are highlighted in **bold** with your accent color, so you always see exactly why a suggestion was offered.

### 7. Local, Opt-In Learning
- Suggestion learning works in memory by default. Both standalone KetraTerm and the IntelliJ plugin require an explicit setting before learned statistics are loaded from or written to disk.
- When enabled, the version 3 local file stores opaque exact-command ranking counters separately from optional plaintext replay rows. Replay is limited to successful commands in the exact recorded profile and working-directory context; accepting an existing suggestion changes ranking only.
- Leading whitespace, malformed or control-bearing text, oversized commands, and common credential patterns are excluded from plaintext replay. Filtering is best-effort rather than a guarantee: unrecognized sensitive values may still appear in an approved replay row.
- The file uses Base64URL encoding, not encryption, and deterministic command digests remain guessable. Commands rejected for replay may still contribute opaque ranking evidence, but cannot produce history or observed-token suggestions.

---

## Keyboard Controls

| Key | Action |
| :--- | :--- |
| **Tab** / **Enter** | Accept the selected suggestion. |
| **Up** / **Down Arrow** | Navigate through suggestions. |
| **Page Up** / **Page Down** | Jump through suggestions one page at a time. |
| **Escape** | Dismiss the suggestion popup. |
| **Ctrl + Space** | Explicitly request suggestions at the current cursor position. |

---

*For technical architecture, Kotlin engine implementation details, and contribution guidelines, see the [Developer Completion Architecture Guide](completion-guide.md).*

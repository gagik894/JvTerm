# Agent Skills

Project-specific skill packages live in `.agents/skills`.

These files are versioned with the repository so future sessions can reuse the
same terminal-emulator workflows instead of rediscovering the architecture.

Available skills:

- `.agents/skills/kotlin-idioms/SKILL.md`
- `.agents/skills/terminal-architecture-review/SKILL.md`
- `.agents/skills/terminal-feature-work/SKILL.md`
- `.agents/skills/terminal-parser-fsm/SKILL.md`
- `.agents/skills/terminal-core-grid-physics/SKILL.md`
- `.agents/skills/terminal-integration-adapter/SKILL.md`
- `.agents/skills/terminal-input-encoder/SKILL.md`
- `.agents/skills/terminal-unicode-text/SKILL.md`
- `.agents/skills/terminal-test-suite/SKILL.md`
- `.agents/skills/terminal-gap-map/SKILL.md`
- `.agents/skills/terminal-ui-rendering/SKILL.md`
- `.agents/skills/terminal-naming-convention/SKILL.md`

`AGENTS.md` and module-level `AGENTS.md` files remain the always-read source of
ownership and local invariants. Skills add task-specific workflows and must
reference those rules rather than copying them.

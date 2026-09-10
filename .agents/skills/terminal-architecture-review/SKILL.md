---
name: terminal-architecture-review
description: Use for an explicit KetraTerm architecture or maintainability review, a module-boundary redesign, or a change to Gradle dependency direction or cross-module ownership. Do not invoke for ordinary feature delivery, localized refactoring, or file-size cleanup.
---

# KetraTerm Architecture Review

Read the root guide and the guides for the modules in scope. They are the
canonical source of ownership rules; do not reproduce their module map,
feature status, or local invariants in the review.

## Establish the real architecture

- Inspect `settings.gradle.kts`, relevant `build.gradle.kts` files, public API
  types, and imports. Treat prose diagrams as claims to verify.
- Use Graphify for broad coupling and cross-file paths when its graph is
  current enough; confirm important findings in source.
- Trace both data flow and lifecycle flow, including construction, threading,
  cancellation, shutdown, failure, and final-event ordering.

## Evaluate design pressure

- Compare actual ownership with the root and module guides. Report duplicated
  policy, state, parsing, mapping, or lifecycle control at the boundary where
  it occurs.
- Treat file size, function count, and graph degree as investigation signals,
  not violations. Generated tables, protocol surfaces, and cohesive state
  machines may legitimately be large.
- For a coordinator or facade, identify its independent reasons to change and
  state owners. Recommend extraction only when a cohesive responsibility can
  own behavior and lifecycle without becoming a forwarding layer.
- Apply the root abstraction gate and, for substantial Kotlin redesign,
  `kotlin-idioms`. Do not create architecture-specific substitutes for those
  rules.
- Review `api(project(...))` exposure and public declarations as compatibility
  commitments. Acyclic dependencies alone do not prove a narrow API.

## Evidence and conclusions

- Separate confirmed defects and boundary violations from maintainability
  risks and optional improvements.
- Inspect tests, skipped/opt-in suites, CI coverage, and failure paths. Passing
  tests increase confidence but never establish that the system is bug-free.
- Prefer the smallest corrective change that restores one clear owner. Do not
  propose new modules, interfaces, or patterns without a current independent
  responsibility or dependency boundary.
- For review-only requests, report findings without editing production code.
  When changes are requested, verify the affected owner modules and the
  relevant end-to-end path.

Present findings in severity order with concrete source evidence, impact, and
the smallest maintainable correction. Explicitly say when the architecture is
healthy and avoid manufacturing findings to justify a review.

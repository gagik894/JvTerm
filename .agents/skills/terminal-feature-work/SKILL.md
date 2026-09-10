---
name: terminal-feature-work
description: Use for a new or changed terminal capability that crosses two or more parser, core, host, session, input, or rendering layers, or explicitly closes a documented feature gap. Do not invoke for localized single-module edits.
---

# Cross-Layer Terminal Feature Work

Read the root guide, each affected module guide, and only the relevant entries
in the canonical feature and gap maps.

## Workflow

1. Define the exact supported slice and its observable semantics.
2. Assign each responsibility to an existing layer before changing APIs.
3. Trace the end-to-end path only through layers the feature actually touches.
4. Add focused owner-layer tests and a full-path test when bytes or host output
   cross module boundaries.
5. Implement the smallest complete slice without compatibility shims or fake
   degradation.
6. Update the canonical feature/gap entry when support or scope changes.

For query/response behavior, follow the root security rule and update the owning
allowlist. Do not copy capability status, TODO taxonomies, or module boundaries
into this skill.

# Module ketraterm-intellij-plugin

## KetraTerm IntelliJ Plugin

The `ketraterm-intellij-plugin` build is the IntelliJ Platform host for KetraTerm.
It is a product integration build, not a terminal implementation module.

This build stays independent from the root KetraTerm Gradle build so IntelliJ
Platform plugin tooling, repositories, sandbox tasks, verification, signing,
and publishing do not leak into the terminal library build.

## Role

The plugin adapts reusable KetraTerm modules to IntelliJ IDE services:

- terminal rendering and input through `ketraterm-ui-swing`.
- completion parsing and ranking through `ketraterm-completion`.
- bounded completion snapshots through `ketraterm-completion-host`.
- source-count-aware project-file, Git, and Gradle loaders that stop after the
  shared engine's requested number of usable rows.
- sanitized completion statistics through `ketraterm-completion-persistence`.
- completion/Swing adaptation through `ketraterm-ui-swing-host`.
- workspace and session state through `ketraterm-workspace` and
  `ketraterm-session`.
- local process lifecycle through `ketraterm-pty`.
- IDE lifecycle, actions, settings, notifications, tool windows, and disposal
  through IntelliJ Platform APIs.

## Boundary

The plugin must not parse terminal protocols, mutate core terminal state,
implement reusable renderer behavior, encode host-bound input bytes directly,
or add IntelliJ dependencies to reusable KetraTerm modules.

## Current Scope

This build contains the IntelliJ host integration: a bottom KetraTerm tool
window, local terminal tab startup, shell profile launch actions, settings
bridges, IDE notifications, clipboard policy prompts, icons, and focused
host-adapter tests. New runtime IDE features should still be added in small
slices with focused tests and clear ownership boundaries.

# Changelog

All notable changes to **Claude Relay** are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/); versioning is
[SemVer](https://semver.org/).

## [Unreleased]

## [1.0.7] - 2026-07-01

### Changed
- Compatibility: replaced another platform API scheduled for removal
  (`SimpleListCellRenderer.create(String, Function)` → the `Customizer`-based
  `create` overload), keeping the chooser-popup renderer compatible with
  upcoming IntelliJ releases.
- The context bar **Rescan** button now uses a distinct scan icon so it's no
  longer confused with the toolbar **Refresh** action — Rescan re-discovers
  `.claude` assets only, while Refresh also reloads models and usage limits.

### Removed
- Replaced the plugin logo and in-app marks with an original double-chevron
  "relay" glyph; the previous Claude-branded starburst is no longer used.

## [1.0.3] - 2026-06-30

### Changed
- Compatibility: replaced a platform API scheduled for removal
  (`FileChooserDescriptor` constructor → `FileChooserDescriptorFactory`), so the
  plugin stays compatible with upcoming IntelliJ releases (verified against
  2026.2 EAP).
- Set the final plugin id to `com.chelayel.relay.claude`.

## [1.0.2] - 2026-06-30

### Changed
- Relicensed under the **MIT License** — Claude Relay is now open source.
- Plugin listing & README note the open-source license, repository link, and a
  Getting Started guide.

### Fixed
- **Shift+Enter** now inserts a newline in the composer (previously did nothing).

## [1.0.1] - 2026-06-29

### Fixed
- Intermittent "Project context" bar visibility — scan project assets
  synchronously at startup and skip redundant relayouts.

## [1.0.0] - 2026-06-29

First public release. A native **Claude Code** chat panel for JetBrains IDEs.

### Added
- Native **Claude Code** chat tool window that drives the `claude` CLI and
  renders a rich, themed, streaming transcript with live tool activity.
- **Agent & Ask** modes — *Agent* reads/edits/runs; *Ask* is read-only
  (enforced via `--disallowedTools`).
- **Single-select agent persona** via the CLI `--agent` flag.
- **Editor-aware context** — auto-attaches the current selection; attach files
  and **paste images** straight into the prompt.
- **Project assets surfaced** — `CLAUDE.md`, `.claude/agents`, `.claude/skills`,
  `.claude/commands` auto-discovered, with a **↻ Rescan** button, slash-command
  completion, and clear source-folder labels.
- **Session history & resume** from a built-in picker.
- **Model & permission** selectors and live **usage / limits**.
- Runs against IntelliJ Platform 2024.2 (242) and newer.

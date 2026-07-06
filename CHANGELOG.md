# Changelog

All notable changes to **Claude Relay** are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/); versioning is
[SemVer](https://semver.org/).

## [Unreleased]

## [1.0.11] - 2026-07-06

### Changed
- Each conversation now runs through a single long-lived `claude` process using
  the realtime streaming protocol (`--input-format stream-json`), instead of
  spawning a fresh `claude -p --resume` per message. This keeps the prompt cache
  warm across turns the way the interactive CLI does — the old per-turn resume
  re-sent the whole transcript and, once the ~5-minute cache went cold, re-billed
  it as new input every turn. Result: dramatically lower token usage on
  multi-turn chats. The process only restarts when a launch setting changes
  (model / permission mode / agent) or the session changes (New Chat, resume).

## [1.0.10] - 2026-07-02

### Changed
- The tool-window stripe icon is now the Claude accent red (`#D97757`) to match
  the plugin icon, instead of the muted gray that looked white in dark themes.

## [1.0.9] - 2026-07-01

### Fixed
- Model picker sometimes stayed on the bare fallback list (no version numbers)
  when the `/model` scrape lost a race against a slow `claude` start-up (e.g.
  while MCP servers initialize). The scrape now waits longer for the CLI and
  retries opening the menu up to three times before giving up.

### Changed
- Added diagnostic logging around the TUI scrape and model-list update
  (`TUI scrape …`, `applyModels …`) to make picker issues traceable from
  `idea.log`.

## [1.0.8] - 2026-07-01

### Added
- **Auto-Test to Coverage** toolbar action: pick a target line-coverage
  percentage and Claude autonomously discovers the build/test tooling, runs the
  suite with coverage, and keeps writing meaningful tests round after round
  until the target is met (or a safety cap of 12 rounds is reached). Runs with
  tool permissions bypassed; **Stop** or **New Chat** ends the loop.

### Changed
- The model picker now shows the concrete Claude version for each choice
  (e.g. **Opus 4.8**, **Sonnet 4.6**, **Haiku 4.5**) instead of the bare alias,
  scraped from the `/model` menu. The `--model` alias passed to the CLI is
  unchanged, and your current pick is preserved across the relabel.

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

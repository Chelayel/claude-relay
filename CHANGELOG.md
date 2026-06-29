# Changelog

All notable changes to **Claude Relay** are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/); versioning is
[SemVer](https://semver.org/).

## [Unreleased]

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

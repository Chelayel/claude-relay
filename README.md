<div align="center">

# Claude Relay

### Claude Code, native in your JetBrains IDE.

**Your `claude` CLI, relayed into a polished side-panel chat — no terminal juggling, no leaving your IDE.**

**Free & open source (MIT).** · *Unofficial, independent — not affiliated with Anthropic.*

</div>

> [!WARNING]
> **Claude Relay is deprecated and gets no further updates.** JetBrains now does
> this better: [JetBrains Air](https://www.jetbrains.com/help/air/quick-start-with-air.html)
> runs Claude natively, alongside other agents. If you also need Gemini through an
> Apigee gateway, or your Gemini as a model inside GitHub Copilot, use
> [AI Relay](https://github.com/Chelayel/ai-relay), which also drives Claude Code.
> The plugin keeps working for now; the code stays here under MIT.

---

Claude Relay brings the [Claude Code](https://claude.com/claude-code) experience you get in VS Code
into IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider and the rest of the JetBrains family — as a
first-class tool window that drives the real `claude` CLI in the context of your open project.

> Part of the **Relay** family by **Chelayel**, now carried forward by
> [AI Relay](https://github.com/Chelayel/ai-relay).

## Why it exists

Claude Code is excellent, but inside JetBrains it lived in a terminal tab — copy-pasting paths,
losing scrollback, no sense of the conversation. The official rich integration was VS Code-first.
Claude Relay closes that gap: a native chat panel that speaks the CLI's streaming JSON protocol and
renders a real transcript, while staying aware of the file and lines you're actually looking at.

## Features

- **Streaming chat** with live tool activity — edits, commands, and file reads as they happen.
- **Agent & Ask modes** — *Agent* reads, edits and runs; *Ask* is strictly read-only Q&A.
- **Editor-aware context** — auto-attaches your current selection (`Foo.kt:40–55`), so *"refactor this"* has a referent. Project-wide refactors work because Claude operates on the whole project.
- **Attach files & images** — pick a file or **paste a screenshot** straight into the prompt.
- **Project assets, surfaced** — your `CLAUDE.md`, `.claude/agents`, `.claude/skills` and `.claude/commands` are auto-discovered and one click away (type `/` for commands).
- **Session history & resume** — pick up any past conversation from the built-in picker.
- **Model & permission** selectors, plus live **usage / limits**.

## Requirements

- A JetBrains IDE, build **2024.2 (242)** or newer.
- The **Claude Code CLI**, installed and authenticated:
  ```bash
  npm install -g @anthropic-ai/claude-code
  claude        # sign in once
  ```
  Auto-detected in `~/.local/bin`, `~/.claude/local`, `/opt/homebrew/bin`, `/usr/local/bin`, or on `PATH`.

## Install

**From a release zip:** `Settings → Plugins → ⚙ → Install Plugin from Disk…` → pick the zip from
`build/distributions/`, then restart. Open the **Claude Relay** tool window on the right.

**Build it yourself:**
```bash
./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew buildPlugin    # -> build/distributions/claude-relay-<version>.zip
```

## Getting started

1. **Install the Claude Code CLI** and sign in once:
   ```bash
   npm install -g @anthropic-ai/claude-code
   claude        # authenticate
   ```
2. **Install Claude Relay** — from the JetBrains Marketplace, or a release zip
   (`Settings → Plugins → ⚙ → Install Plugin from Disk…`). Restart the IDE.
3. **Open the tool window** — **Claude Relay** on the right edge of your IDE.
4. **Ask away** — type a message and press **Enter** (Shift+Enter for a newline). Then:
   - Switch **Agent** (reads, edits & runs) ↔ **Ask** (read-only Q&A) in the composer.
   - **Select code** in the editor and it's auto-attached — ask *“explain/refactor this.”*
   - Hit **＋** to attach files/images, pick a project **agent**/**skill**, or rescan.
   - Type **`/`** to run a project slash command.
   - Reopen a past chat from the **history** (clock) button; start fresh with **＋ New Chat**.

That's it — Claude Relay drives the CLI in your project's context, so it can read, edit, and refactor across the whole codebase (in Agent mode).

## How it works

Each message spawns `claude -p "<prompt>" --output-format stream-json --verbose` (plus `--resume`
after the first turn) in the project directory, and the plugin renders the streamed assistant text,
thinking, tool calls and results. *Ask* mode adds `--disallowedTools` so Claude can read and answer
but never modifies your files.

## Screenshots

<p align="center">
  <img src="docs/screenshots/agent-turn.png" alt="An agent turn with tool activity and the answer" width="380">
  &nbsp;&nbsp;
  <img src="docs/screenshots/editor-selection.png" alt="A question about the editor selection" width="380">
</p>
<p align="center">
  <em>A real, themed transcript with live tool activity: reads, edits and a test run (left) — and a question about the lines selected in the editor (right).</em>
</p>

<p align="center">
  <img src="docs/screenshots/streaming.png" alt="A turn in progress after a failing test run" width="380"><br>
  <em>Mid-turn: a failing command's output, Claude's reply streaming in, and the next tool call on its way.</em>
</p>

---

<div align="center">
Built by <a href="https://www.chelayel.com"><b>Chelayel</b></a> · Unofficial, not affiliated with Anthropic<br>
Released under the <a href="LICENSE">MIT License</a> · © 2026 Chelayel
</div>

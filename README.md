# Claude Code GUI for JetBrains

An unofficial native chat GUI for [Claude Code](https://claude.com/claude-code) inside
JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, GoLand, …) — a side-panel experience
similar to the official VS Code extension, instead of just the terminal.

> Independent project, not affiliated with Anthropic.

## Features

- **Chat tool window** ("Claude Code", docked right) that drives the `claude` CLI
- **Streaming responses** with live tool activity (edits, commands, file reads)
- **Multi-turn memory** via session resume
- **Permission mode** selector (Default / Accept edits / Plan / Bypass)
- **Model** selector (Default / Opus / Sonnet / Haiku)
- Runs in the context of the **currently open project**

## Requirements

- A JetBrains IDE, build 242 (2024.2) or newer
- **JDK 21** (to build)
- The **Claude Code CLI** installed and authenticated:
  ```bash
  npm install -g @anthropic-ai/claude-code
  claude        # sign in once
  ```
  The plugin auto-detects the binary in `~/.local/bin`, `~/.claude/local`,
  `/opt/homebrew/bin`, `/usr/local/bin`, or on `PATH`.

## Build & run

```bash
# Launch a sandbox IDE with the plugin loaded:
./gradlew runIde

# Or build an installable zip:
./gradlew buildPlugin
# -> build/distributions/claude-code-gui-0.1.0.zip
```

## Install into your IDE

`Settings → Plugins → ⚙ → Install Plugin from Disk…` → pick the zip from
`build/distributions/`, then restart. Open the **Claude Code** tool window on the right.

## How it works

Each message spawns `claude -p "<prompt>" --output-format stream-json --verbose`
(plus `--resume <session_id>` after the first turn) in the project directory. The
plugin parses the streamed JSON events and renders assistant text, thinking, tool
calls, and tool results in the transcript.

## Notes / limitations (v1)

- Responses stream per message block, not token-by-token.
- Markdown rendering is intentionally lightweight (code blocks, inline code, bold).
- "Bypass all" permission mode lets Claude act without prompts — use deliberately.

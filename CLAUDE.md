# Claude Code GUI — project guide

An unofficial native chat GUI for Claude Code inside JetBrains IDEs. The plugin
adds a "Claude Code" tool window that drives the `claude` CLI and renders a rich
transcript.

## Build & run

- JDK 21, Gradle 9, IntelliJ Platform Gradle Plugin 2.x.
- `./gradlew compileKotlin` — fast compile check.
- `./gradlew runIde` — sandbox IDE with the plugin loaded.
- `./gradlew buildPlugin` — installable zip in `build/distributions/`.
- Bump `version` in `build.gradle.kts` before packaging.

## Architecture

- `ui/ClaudeChatPanel` — the tool window: composer (model/permission chips,
  send), transcript, session header, footer (context chip + usage).
- `ui/ChatWebView` / `ui/TranscriptView` — transcript renderers behind the
  `ChatView` interface (JCEF when available, editor-pane fallback).
- `cli/ClaudeCliClient` — drives one `claude -p --output-format stream-json` turn.
- `cli/ClaudeTui` — scrapes the `/model` list and usage limits from a headless PTY.
- `cli/SessionStore` — reads `~/.claude/projects/<slug>/*.jsonl` to resume past
  sessions.
- `cli/ProjectAssets` — discovers `CLAUDE.md`, `.claude/agents`, `.claude/skills`,
  `.claude/commands`.

## Conventions

- All UI mutations on the Swing EDT; CLI/disk work on pooled threads.
- Register disposables (JCEF browser) with a parent `Disposable`.
- Parse CLI/TUI output defensively — never crash the tool window on bad input.
- Target build `242+`; avoid APIs newer than IntelliJ 2024.2 unless guarded.

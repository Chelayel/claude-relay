# Claude Relay — marketing kit

Brand: **Claude Relay**, by **Chelayel**. Part of the **Relay** family of JetBrains IDE companions:

| Product | Wraps | Status |
| --- | --- | --- |
| **Claude Relay** | Claude Code CLI (Anthropic) | this repo |
| **Vertex Relay** | Gemini / Vertex AI (Google) | in progress |
| **Apigee Relay** | Apigee (Google) | in progress |

The shared **"Relay"** suffix + single publisher (**Chelayel**) signals one developer; the leading
vendor keyword (Claude / Vertex / Apigee) keeps each discoverable in Marketplace search.

---

## Taglines

- **Primary:** *Claude Code, native in your JetBrains IDE.*
- Your `claude` CLI, relayed into a polished side-panel chat.
- Stop juggling the terminal. Chat with Claude where you code.

## Short description (≤ 80 chars, for cards)

> Native Claude Code chat panel for JetBrains — editor-aware, with Agent & Ask modes.

## Long description (Marketplace / landing)

Claude Relay brings the Claude Code experience you get in VS Code into IntelliJ IDEA, PyCharm,
WebStorm, GoLand, Rider and the rest of the JetBrains family — a first-class tool window that drives
the real `claude` CLI in the context of your open project. Streaming responses, live tool activity,
editor-aware context, file & image attachments, project agents/skills/commands, session resume, and
**Agent vs Ask** modes.

## Feature bullets (lead with these)

1. **Streaming chat** with live tool activity (edits, commands, file reads).
2. **Agent & Ask modes** — full agentic edits, or strictly read-only Q&A.
3. **Editor-aware** — auto-attaches your current selection so "refactor this" just works.
4. **Attach files & paste images** straight into the prompt.
5. **Project assets** — `CLAUDE.md`, agents, skills and slash commands, auto-discovered.
6. **Session history & resume**, model & permission selectors, live usage.

---

## "Why I built this" — launch post draft

> **I built a native Claude Code panel for JetBrains, because I was tired of the terminal.**
>
> Claude Code is one of the best coding agents out there — but inside JetBrains it lived in a
> terminal tab. I was copy-pasting file paths, losing scrollback mid-task, and never quite seeing the
> conversation. The polished side-panel experience was VS Code-first.
>
> So I made **Claude Relay**: a tool window that drives the real `claude` CLI and renders a proper
> chat transcript — streaming text, tool calls, diffs, the works. It stays aware of the file and the
> exact lines I have selected, so "explain this" or "refactor this" just works. It picks up my
> project's `CLAUDE.md`, agents and skills automatically. And it has an **Ask** mode for when I just
> want answers without anything touching my files, and an **Agent** mode for when I want it to do the
> work.
>
> It's unofficial and independent (not affiliated with Anthropic) — just a tool I wanted for my own
> workflow, now cleaned up to share. It's the first of a small **Relay** family; *Vertex Relay* and
> *Apigee Relay* are next.
>
> Install from the JetBrains Marketplace, point it at your `claude` CLI, and you're going.

Post targets: r/JetBrains, r/ClaudeAI, LinkedIn, X/Twitter, dev.to.

---

## Screenshot shot-list (capture from a real, populated session)

Capture at ~1.5–2× scale on a clean theme (Dark by default; one Light variant for the banner).
Recommended: a wide, **detached** tool window so the composer breathes.

1. **Hero / transcript** — an in-progress chat with: a user message, streaming assistant text, a tool
   call (e.g. an Edit), and a collapsed tool result. *Caption: "A real transcript, not a terminal."*
2. **Add-context menu open** — the `+` popup showing Agents ▸ / Skills ▸ / Commands ▸ submenus and the
   Auto-attach toggle. *Caption: "Your project's agents, skills & commands — one click away."*
3. **Editor selection auto-attached** — code selected in the editor + the `✦ File.kt:40–55` chip in the
   composer. *Caption: "It knows what you're looking at."*
4. **Agent vs Ask** — the Mode chip dropdown open. *Caption: "Let it edit, or keep it read-only."*
5. **Image paste** — a pasted screenshot chip above the input. *Caption: "Paste a screenshot, ask about it."*
6. **Session history** — the history picker popup. *Caption: "Pick up any past conversation."*
7. **(Banner)** — a clean hero composite for the Marketplace banner / social card.

Marketplace assets needed: plugin icon (already `/icons/claude.svg`), 1+ screenshots, optional banner.

---

## Positioning notes

- Always label **unofficial / not affiliated with Anthropic** (trademark hygiene; the name uses
  "Claude" descriptively for an integration, which is standard for community plugins).
- Lead with the *editor-aware* + *Agent/Ask* differentiators — those are what the terminal can't do.
- Cross-link the Relay family in each plugin's description once Vertex/Apigee ship.

---
name: changelog
description: Maintain CHANGELOG.md in Keep a Changelog format — add entries under Unreleased and cut versioned sections.
---

# Changelog skill

Use this when adding or updating `CHANGELOG.md`.

## Format

Follow [Keep a Changelog](https://keepachangelog.com/):

- Newest version first.
- Group entries under `Added`, `Changed`, `Fixed`, `Removed`.
- Keep an `## [Unreleased]` section at the top for in-progress work.

## Cutting a release

1. Rename `## [Unreleased]` to `## [x.y.z] - YYYY-MM-DD`.
2. Add a fresh empty `## [Unreleased]` above it.
3. Match the version to `version` in `build.gradle.kts`.

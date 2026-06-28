---
name: gradle-build
description: Build, run, and package this IntelliJ plugin with the Gradle IntelliJ Platform plugin.
---

# Gradle build skill

Common tasks for this plugin (JDK 21, Gradle 9, IntelliJ Platform Gradle Plugin 2.x):

- `./gradlew compileKotlin` — fast compile check.
- `./gradlew runIde` — launch a sandbox IDE with the plugin loaded.
- `./gradlew buildPlugin` — produce the installable zip under `build/distributions/`.

## Notes

- Bump `version` in `build.gradle.kts` before packaging a release.
- `instrumentCode = false` — no Java UI forms here.
- Target build is `242+`; avoid APIs newer than IntelliJ 2024.2 unless guarded.

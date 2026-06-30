package com.chelayel.claudecode.cli

import java.io.File

/**
 * Locates the `claude` executable. GUI-launched IDEs on macOS often have a
 * minimal PATH, so we probe the common install locations directly before
 * falling back to a bare `claude` (resolved against PATH).
 */
object ClaudeCli {

    fun detectExecutable(): String {
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/.local/bin/claude",
            "$home/.claude/local/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            "/usr/bin/claude",
        )
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
            ?: "claude"
    }

    /** Extra bin directories to prepend to PATH so nested tools (node, git…) resolve. */
    fun extraPathEntries(): List<String> {
        val home = System.getProperty("user.home")
        return listOf(
            "$home/.local/bin",
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
        )
    }
}

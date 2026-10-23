package com.chelayel.claudecode.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ui.EditorNotificationPanel

/**
 * Claude Relay is deprecated: AI Relay carries the Relay work forward, driving the same
 * claude CLI from its own chat panel. Shown above the chat so nobody misses it, while the
 * panel keeps working for anyone not ready to move.
 */
object DeprecationBanner {
    const val AI_RELAY_URL = "https://plugins.jetbrains.com/plugin/34342-ai-relay"

    fun create(): EditorNotificationPanel =
        EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
            text = "Claude Relay is deprecated and will get no further updates. " +
                "AI Relay drives the same claude CLI, and adds Gemini and GitHub Copilot."
            createActionLabel("Get AI Relay") { BrowserUtil.browse(AI_RELAY_URL) }
        }
}

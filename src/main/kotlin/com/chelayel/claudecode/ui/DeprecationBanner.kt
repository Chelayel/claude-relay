package com.chelayel.claudecode.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ui.EditorNotificationPanel

/**
 * Claude Relay is deprecated: JetBrains Air runs Claude Agent natively, and AI Relay
 * carries the Relay work forward. Shown above the chat so nobody misses it, while the
 * panel keeps working for anyone not ready to move.
 */
object DeprecationBanner {
    const val AIR_URL = "https://www.jetbrains.com/help/air/quick-start-with-air.html"
    const val AI_RELAY_URL = "https://plugins.jetbrains.com/plugin/34342-ai-relay"

    fun create(): EditorNotificationPanel =
        EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
            text = "Claude Relay is deprecated and will get no further updates. " +
                "JetBrains Air runs Claude natively."
            createActionLabel("Try JetBrains Air") { BrowserUtil.browse(AIR_URL) }
            createActionLabel("AI Relay") { BrowserUtil.browse(AI_RELAY_URL) }
        }
}

package com.chelayel.claudecode.ui

import javax.swing.JComponent

/** Abstraction over the transcript renderer so the panel can use a rich JCEF
 *  webview when available and fall back to a plain editor pane otherwise. */
interface ChatView {
    val component: JComponent
    fun clear()
    fun addUser(text: String)
    fun assistantChunk(text: String)
    fun endAssistant()
    fun addThinking(text: String)
    fun addToolUse(name: String, summary: String)
    fun addToolResult(text: String, isError: Boolean)
    fun addSystem(text: String)
    fun addError(text: String)
    fun setBusy(busy: Boolean)

    /** Render an HTML snippet of project skills/agents on the empty "home" screen. */
    fun setHomeContext(html: String?) {}
}

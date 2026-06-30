package com.chelayel.claudecode.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.text.html.HTMLEditorKit

/**
 * Theme-aware HTML transcript. Messages are appended as styled blocks; a
 * single [JEditorPane] handles wrapping, scrolling and text selection.
 */
class TranscriptView : ChatView {

    private val pane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        editorKit = HTMLEditorKit()
        border = JBUI.Borders.empty(4, 8)
        background = UIUtil.getTextFieldBackground()
    }

    val scrollPane = JBScrollPane(pane).apply {
        border = JBUI.Borders.empty()
    }

    override val component: JComponent get() = scrollPane

    private val body = StringBuilder()

    override fun clear() {
        body.setLength(0)
        render()
    }

    override fun addUser(text: String) = block("user", "You", MdLite.render(text))

    fun addAssistant(text: String) = block("assistant", "Claude", MdLite.render(text))

    override fun assistantChunk(text: String) = addAssistant(text)

    override fun endAssistant() {}

    override fun setBusy(busy: Boolean) {}

    override fun addThinking(text: String) =
        block("thinking", "Thinking", MdLite.render(text))

    override fun addToolUse(name: String, summary: String) {
        val detail = if (summary.isBlank()) "" else " <span class='dim'>" + MdLite.escape(summary) + "</span>"
        block("tool", "&#128295; $name", detail, withRole = false)
    }

    override fun addToolResult(text: String, isError: Boolean) {
        val cls = if (isError) "toolresult error" else "toolresult"
        val shown = text.take(2000).let { if (text.length > 2000) "$it\n… (truncated)" else it }
        block(cls, "", "<pre class='code'>" + MdLite.escape(shown) + "</pre>", withRole = false)
    }

    override fun addSystem(text: String) = block("system", "", "<span class='dim'>" + MdLite.escape(text) + "</span>", withRole = false)

    override fun addError(text: String) = block("error", "Error", MdLite.escape(text))

    private fun block(cls: String, role: String, html: String, withRole: Boolean = true) {
        body.append("<div class='msg $cls'>")
        if (withRole && role.isNotEmpty()) body.append("<div class='role'>$role</div>")
        body.append("<div class='content'>").append(html).append("</div></div>")
        render()
    }

    private fun render() {
        pane.text = document()
        // Defer caret move until the new content is laid out.
        pane.caretPosition = pane.document.length
    }

    private fun document(): String {
        val link = ColorUtil.toHex(UIUtil.getLabelForeground())
        val dim = ColorUtil.toHex(muted())
        val codeBg = ColorUtil.toHex(codeBackground())
        val accent = ColorUtil.toHex(Color(0xD9, 0x77, 0x57))
        val userBg = ColorUtil.toHex(blend(UIUtil.getTextFieldBackground(), UIUtil.getLabelForeground(), 0.06f))
        val editorFont = EditorColorsManager.getInstance().globalScheme.editorFontName
        return """
            <html><head><style>
              body { font-family: '${UIUtil.getLabelFont().family}'; font-size: ${UIUtil.getLabelFont().size}px;
                     color: $link; margin: 0; }
              .msg { margin: 0 0 10px 0; padding: 2px 0; }
              .role { font-weight: bold; margin-bottom: 2px; }
              .msg.user .role { color: $accent; }
              .msg.user .content { background: #$userBg; padding: 4px 6px; }
              .msg.assistant .role { color: $link; }
              .msg.thinking { color: $dim; font-style: italic; }
              .msg.tool .content { color: $dim; font-family: '$editorFont'; }
              .msg.error .role, .msg.toolresult.error { color: #C75450; }
              .dim { color: $dim; }
              pre.code { background: #$codeBg; padding: 6px 8px; margin: 4px 0;
                         font-family: '$editorFont'; white-space: pre-wrap; word-wrap: break-word; }
              code { background: #$codeBg; font-family: '$editorFont'; padding: 0 2px; }
              .msg.toolresult .content { color: $dim; }
            </style></head><body>$body</body></html>
        """.trimIndent()
    }

    private fun muted(): Color = blend(UIUtil.getLabelForeground(), UIUtil.getTextFieldBackground(), 0.4f)

    private fun codeBackground(): Color =
        blend(UIUtil.getTextFieldBackground(), UIUtil.getLabelForeground(), if (isDark()) 0.10f else 0.06f)

    private fun isDark(): Boolean = ColorUtil.isDark(UIUtil.getPanelBackground())

    private fun blend(a: Color, b: Color, ratio: Float): Color {
        val r = ratio.coerceIn(0f, 1f)
        return Color(
            (a.red * (1 - r) + b.red * r).toInt(),
            (a.green * (1 - r) + b.green * r).toInt(),
            (a.blue * (1 - r) + b.blue * r).toInt(),
        )
    }
}

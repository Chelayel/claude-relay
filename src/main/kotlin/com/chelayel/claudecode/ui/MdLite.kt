package com.chelayel.claudecode.ui

/**
 * Minimal Markdown → HTML rendering good enough for chat: fenced code blocks,
 * inline code, bold, and line breaks. Everything is HTML-escaped first.
 */
object MdLite {

    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun render(src: String): String {
        val sb = StringBuilder()
        val parts = src.split("```")
        for ((i, part) in parts.withIndex()) {
            if (i % 2 == 1) {
                sb.append("<pre class='code'>").append(escape(stripLanguageHint(part))).append("</pre>")
            } else {
                sb.append(inline(part))
            }
        }
        return sb.toString()
    }

    private fun stripLanguageHint(code: String): String {
        val nl = code.indexOf('\n')
        if (nl < 0) return code
        val first = code.substring(0, nl).trim()
        // A short single-token first line is a language tag (```kotlin).
        return if (first.isNotEmpty() && !first.contains(' ') && first.length < 20) code.substring(nl + 1) else code
    }

    private fun inline(text: String): String {
        var html = escape(text)
        html = INLINE_CODE.replace(html) { "<code>${it.groupValues[1]}</code>" }
        html = BOLD.replace(html) { "<b>${it.groupValues[1]}</b>" }
        return html.replace("\n", "<br/>")
    }

    private val INLINE_CODE = Regex("`([^`]+)`")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
}

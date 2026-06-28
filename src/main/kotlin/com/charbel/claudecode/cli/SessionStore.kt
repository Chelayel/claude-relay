package com.charbel.claudecode.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.File

/**
 * Reads Claude Code's own on-disk session history so the GUI can "continue where
 * you left off" the way the official editor integrations do.
 *
 * Claude persists every session as a JSONL transcript under
 * `~/.claude/projects/<slug>/<session-id>.jsonl`, where `<slug>` is the project
 * path with every non-alphanumeric character replaced by `-`. Each line is one
 * event (user / assistant / tool result / bookkeeping). We list those files to
 * offer a history picker, and replay a chosen file to rebuild the transcript.
 *
 * Nothing here mutates Claude's files — it is read-only.
 */
object SessionStore {

    private val log = logger<SessionStore>()

    /** One past session, summarised for a history list. */
    data class SessionInfo(
        val id: String,
        val file: File,
        val lastModified: Long,
        val title: String,
        val messageCount: Int,
    )

    /** A replayable transcript element — mirrors what [ClaudeCliClient.Listener] emits live. */
    sealed interface Event {
        data class User(val text: String) : Event
        data class Assistant(val text: String) : Event
        data class Thinking(val text: String) : Event
        data class Tool(val name: String, val summary: String) : Event
        data class ToolResult(val text: String, val isError: Boolean) : Event
    }

    // ---- session discovery ---------------------------------------------------

    /** The `~/.claude/projects/<slug>/` directory for [workingDir], or null. */
    fun projectDir(workingDir: String): File? {
        val base = File(System.getProperty("user.home"), ".claude/projects")
        if (!base.isDirectory) return null
        // Primary: the documented slug (non-alphanumerics → '-').
        val slug = workingDir.replace(Regex("[^A-Za-z0-9]"), "-")
        File(base, slug).takeIf { it.isDirectory }?.let { return it }
        // Fallback: a folder whose newest transcript records this exact cwd.
        return base.listFiles { f -> f.isDirectory }
            ?.firstOrNull { dir -> firstCwd(dir) == workingDir }
    }

    /** Past sessions for the project, newest first, excluding empty ones. */
    fun listSessions(workingDir: String): List<SessionInfo> {
        val dir = projectDir(workingDir) ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()
        return files.mapNotNull { summarize(it) }
            .filter { it.messageCount > 0 }
            .sortedByDescending { it.lastModified }
    }

    /** The most recent non-empty session for the project. */
    fun latestSession(workingDir: String): SessionInfo? = listSessions(workingDir).firstOrNull()

    private fun summarize(file: File): SessionInfo? = runCatching {
        var title: String? = null
        var count = 0
        forEachEvent(file) { obj ->
            val role = obj.str("type")
            if (role == "user" && isRealUserText(obj)) {
                count++
                if (title == null) title = userText(obj)?.let { firstLine(it) }
            } else if (role == "assistant") {
                count++
            }
        }
        SessionInfo(
            id = file.nameWithoutExtension,
            file = file,
            lastModified = file.lastModified(),
            title = title?.takeIf { it.isNotBlank() } ?: "Untitled session",
            messageCount = count,
        )
    }.onFailure { log.info("Could not summarize ${file.name}: ${it.message}") }.getOrNull()

    // ---- transcript replay ---------------------------------------------------

    /**
     * Parse a transcript into ordered [Event]s for replay. Bookkeeping rows,
     * sub-agent sidechains, meta messages, and slash-command plumbing are
     * skipped so the result matches what the user actually saw. Capped at
     * [maxEvents] (most recent kept) to keep replay snappy on huge sessions.
     */
    fun readTranscript(file: File, maxEvents: Int = 600): List<Event> {
        val events = ArrayList<Event>()
        runCatching {
            forEachEvent(file) { obj ->
                when (obj.str("type")) {
                    "user" -> parseUser(obj, events)
                    "assistant" -> parseAssistant(obj, events)
                }
            }
        }.onFailure { log.info("Could not read transcript ${file.name}: ${it.message}") }
        return if (events.size > maxEvents) events.subList(events.size - maxEvents, events.size) else events
    }

    private fun parseUser(obj: JsonObject, out: MutableList<Event>) {
        if (obj.isMetaOrSidechain()) return
        val content = obj.getAsJsonObject("message")?.get("content") ?: return
        when {
            content.isJsonPrimitive -> {
                val text = content.asString
                if (isRenderableUserText(text)) out.add(Event.User(firstTrim(text)))
            }
            content.isJsonArray -> for (el in content.asJsonArray) {
                val block = el.asJsonObject
                when (block.str("type")) {
                    "text" -> block.str("text")?.takeIf { isRenderableUserText(it) }
                        ?.let { out.add(Event.User(it.trim())) }
                    "tool_result" -> {
                        val isError = block.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                        val text = toolResultText(block)
                        if (text.isNotBlank()) out.add(Event.ToolResult(text, isError))
                    }
                }
            }
        }
    }

    private fun parseAssistant(obj: JsonObject, out: MutableList<Event>) {
        if (obj.isMetaOrSidechain()) return
        val content = obj.getAsJsonObject("message")?.getAsJsonArray("content") ?: return
        for (el in content) {
            val block = el.asJsonObject
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf { it.isNotBlank() }?.let { out.add(Event.Assistant(it)) }
                "thinking" -> block.str("thinking")?.takeIf { it.isNotBlank() }?.let { out.add(Event.Thinking(it)) }
                "tool_use" -> {
                    val name = block.str("name") ?: "tool"
                    out.add(Event.Tool(name, summarizeInput(block.getAsJsonObject("input"))))
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private inline fun forEachEvent(file: File, body: (JsonObject) -> Unit) {
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
                val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull() ?: continue
                body(obj)
            }
        }
    }

    private fun firstCwd(dir: File): String? {
        val file = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.maxByOrNull { it.lastModified() } ?: return null
        var cwd: String? = null
        runCatching { forEachEvent(file) { if (cwd == null) cwd = it.str("cwd") } }
        return cwd
    }

    private fun JsonObject.isMetaOrSidechain(): Boolean {
        if (get("isMeta")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return true
        if (get("isSidechain")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return true
        return false
    }

    private fun isRealUserText(obj: JsonObject): Boolean {
        if (obj.isMetaOrSidechain()) return false
        val text = userText(obj) ?: return false
        return isRenderableUserText(text)
    }

    private fun userText(obj: JsonObject): String? {
        val content = obj.getAsJsonObject("message")?.get("content") ?: return null
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) {
            return content.asJsonArray.mapNotNull { it.asJsonObject.takeIf { b -> b.str("type") == "text" }?.str("text") }
                .joinToString("\n").ifBlank { null }
        }
        return null
    }

    /** True for genuine user prose — not tool plumbing or slash-command internals. */
    private fun isRenderableUserText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val noise = listOf(
            "<local-command-caveat>", "<command-name>", "<command-message>",
            "<command-args>", "<command-stdout>", "<command-stderr>",
            "[Request interrupted", "Caveat: The messages below",
        )
        if (noise.any { t.startsWith(it) || t.contains(it) }) return false
        return true
    }

    private fun toolResultText(block: JsonObject): String {
        val content = block.get("content") ?: return ""
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.mapNotNull { it.asJsonObject.str("text") }.joinToString("\n")
            else -> ""
        }.trim()
    }

    private fun summarizeInput(input: JsonObject?): String {
        if (input == null) return ""
        for (key in listOf("file_path", "command", "path", "pattern", "url", "query", "prompt", "description")) {
            input.str(key)?.let { return it.lineSequence().first().take(160) }
        }
        return input.toString().take(160)
    }

    private fun firstLine(s: String) = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: s.trim().take(80)
    private fun firstTrim(s: String) = s.trim()

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}

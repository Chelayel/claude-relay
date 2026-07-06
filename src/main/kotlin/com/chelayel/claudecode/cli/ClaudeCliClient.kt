package com.chelayel.claudecode.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Drives Claude Code through a single, long-lived CLI process using the
 * realtime streaming protocol (`--input-format stream-json --output-format
 * stream-json`). Each [send] writes one user message to the process's stdin and
 * the shared reader thread streams the reply back through [Listener].
 *
 * Keeping one process alive for the whole conversation is what makes this cheap:
 * a fresh `claude -p --resume` per turn re-sends the entire transcript and, once
 * the ~5-minute prompt cache goes cold, re-bills it as new input every turn. A
 * persistent process behaves like the interactive CLI — the prompt cache stays
 * warm, so each turn costs roughly its new tokens plus cheap cache reads.
 *
 * The process is only (re)started when it isn't running, when a launch-time
 * setting changes (model / permission mode / agent / disallowed tools), or when
 * the target session id changes (New Chat, resume-from-history). A cancel kills
 * the process; the next [send] resumes the session via `--resume`.
 *
 * All [Listener] callbacks are delivered on the Swing EDT.
 */
class ClaudeCliClient(
    private val workingDir: String,
    private val executable: String = ClaudeCli.detectExecutable(),
) {
    private val log = logger<ClaudeCliClient>()

    interface Listener {
        fun onSystemInit(sessionId: String) {}
        fun onAssistantText(text: String) {}
        fun onThinking(text: String) {}
        fun onToolUse(name: String, inputSummary: String) {}
        fun onToolResult(text: String, isError: Boolean) {}
        fun onResult(sessionId: String?, costUsd: Double?, isError: Boolean, errorText: String?) {}
        fun onStats(model: String?, contextUsed: Long, contextWindow: Long) {}
        fun onError(message: String) {}
        fun onComplete() {}
    }

    /** Launch-time settings; a change forces the process to restart. */
    private data class Config(
        val permissionMode: String,
        val model: String?,
        val agent: String?,
        val disallowed: List<String>,
    )

    /** Guards process lifecycle (start / restart / stop) and turn bookkeeping. */
    private val lock = Any()

    @Volatile
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    /** Settings the live process was launched with. */
    private var activeConfig: Config? = null

    /** Session id the live process is currently running (from `init` / `result`). */
    @Volatile
    private var liveSessionId: String? = null

    /** Listener that owns the in-flight turn. */
    @Volatile
    private var currentListener: Listener? = null

    @Volatile
    private var turnActive = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var closed = false

    /** Interrupt the current turn. Kills the process; the next [send] resumes. */
    fun cancel() {
        synchronized(lock) {
            cancelled = true
            stopProcess()
        }
    }

    /** Shut down for good (call from the owner's dispose). */
    fun close() {
        synchronized(lock) {
            closed = true
            stopProcess()
        }
    }

    fun send(
        prompt: String,
        sessionId: String?,
        permissionMode: String,
        model: String?,
        agent: String? = null,
        disallowedTools: List<String> = emptyList(),
        listener: Listener,
    ) {
        val cfg = Config(permissionMode, model, agent, disallowedTools)
        // Launching and writing block briefly; keep the EDT free (per project convention).
        ApplicationManager.getApplication().executeOnPooledThread {
            synchronized(lock) {
                if (closed) return@executeOnPooledThread
                cancelled = false
                currentListener = listener
                turnActive = true

                // Reuse the running process only when nothing launch-critical changed
                // and it's still driving the same session — otherwise the CLI would
                // ignore the new model/permission/session until relaunched.
                val reusable = process?.isAlive == true &&
                    activeConfig == cfg &&
                    liveSessionId == sessionId
                if (!reusable) {
                    try {
                        startProcess(cfg, resumeId = sessionId)
                    } catch (e: Exception) {
                        log.warn("Failed to launch Claude", e)
                        turnActive = false
                        edt { listener.onError(e.message ?: "Failed to launch Claude. Is the CLI installed?") }
                        edt { listener.onComplete() }
                        return@executeOnPooledThread
                    }
                }

                try {
                    writeUserMessage(prompt)
                } catch (e: Exception) {
                    log.warn("Failed to send prompt to Claude", e)
                    turnActive = false
                    stopProcess()
                    edt { listener.onError(e.message ?: "Lost connection to Claude.") }
                    edt { listener.onComplete() }
                }
            }
        }
    }

    // ---- process lifecycle (all under `lock`) --------------------------------

    private fun startProcess(cfg: Config, resumeId: String?) {
        stopProcess()

        val cmd = GeneralCommandLine(executable).apply {
            withWorkDirectory(workingDir)
            charset = StandardCharsets.UTF_8
            addParameter("--print")
            addParameters("--input-format", "stream-json")
            addParameters("--output-format", "stream-json")
            addParameter("--verbose")
            if (!resumeId.isNullOrBlank()) addParameters("--resume", resumeId)
            if (cfg.permissionMode.isNotBlank()) addParameters("--permission-mode", cfg.permissionMode)
            if (!cfg.model.isNullOrBlank()) addParameters("--model", cfg.model)
            if (!cfg.agent.isNullOrBlank()) addParameters("--agent", cfg.agent)
            if (cfg.disallowed.isNotEmpty()) {
                addParameter("--disallowedTools")
                addParameters(cfg.disallowed)
            }
        }

        // Make sure the spawned process can find its own runtime deps.
        val env = HashMap(System.getenv())
        val existingPath = env["PATH"].orEmpty()
        env["PATH"] = (ClaudeCli.extraPathEntries() + existingPath).filter { it.isNotBlank() }.joinToString(":")
        cmd.withEnvironment(env)

        val p = cmd.createProcess()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        activeConfig = cfg
        liveSessionId = resumeId

        // Written by the stderr drain thread, read by readLoop — use a thread-safe buffer.
        val stderr = StringBuffer()
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).forEachLine {
                    stderr.appendLine(it)
                }
            }
        }.apply { isDaemon = true; name = "claude-stderr"; start() }

        Thread { readLoop(p, stderr) }.apply { isDaemon = true; name = "claude-reader"; start() }
    }

    private fun stopProcess() {
        writer?.let { runCatching { it.close() } }
        writer = null
        process?.let { runCatching { it.destroy() } }
        process = null
        activeConfig = null
    }

    private fun writeUserMessage(prompt: String) {
        val msg = JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", prompt)
            })
        }
        val w = writer ?: throw IllegalStateException("Claude process is not running.")
        w.write(msg.toString())
        w.write("\n")
        w.flush()
    }

    // ---- output stream --------------------------------------------------------

    private fun readLoop(p: Process, stderr: StringBuffer) {
        runCatching {
            BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (process !== p) break // superseded by a newer process
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    runCatching { handleLine(trimmed) }
                        .onFailure { log.warn("Failed to parse Claude output line: $trimmed", it) }
                }
            }
        }.onFailure { log.warn("Claude reader failed", it) }

        val code = runCatching { p.waitFor() }.getOrDefault(-1)

        // The stream ended: the process exited (normal turn completion keeps it
        // alive, so reaching here mid-turn means it died). Report only if this is
        // still the current process and a turn was in flight.
        var listener: Listener? = null
        var errorMsg: String? = null
        synchronized(lock) {
            // A newer process was launched (restart for a settings/session change):
            // it owns the state and the current turn, so this one stays silent.
            // `process == null` means cancel()/close() killed us — still report so
            // the UI leaves its running state.
            if (process != null && process !== p) return
            if (process === p) {
                process = null
                writer = null
                activeConfig = null
            }
            if (turnActive) {
                turnActive = false
                if (!closed) {
                    listener = currentListener
                    errorMsg = when {
                        cancelled -> "Stopped."
                        code != 0 -> stderr.toString().trim().ifEmpty { "Claude exited with code $code." }
                        else -> "Claude ended the session unexpectedly."
                    }
                }
            }
        }
        listener?.let { l ->
            errorMsg?.let { m -> edt { l.onError(m) } }
            edt { l.onComplete() }
        }
    }

    private fun handleLine(line: String) {
        if (closed) return // owner disposed; don't touch a torn-down panel
        val listener = currentListener ?: return
        val obj = JsonParser.parseString(line).asJsonObject
        when (obj.str("type")) {
            "system" -> {
                if (obj.str("subtype") == "init") {
                    obj.str("session_id")?.let { id ->
                        liveSessionId = id
                        edt { listener.onSystemInit(id) }
                    }
                }
            }

            "assistant" -> {
                val content = obj.getAsJsonObject("message")?.getAsJsonArray("content") ?: return
                for (el in content) {
                    val block = el.asJsonObject
                    when (block.str("type")) {
                        "text" -> block.str("text")?.takeIf { it.isNotBlank() }
                            ?.let { t -> edt { listener.onAssistantText(t) } }

                        "thinking" -> block.str("thinking")?.takeIf { it.isNotBlank() }
                            ?.let { t -> edt { listener.onThinking(t) } }

                        "tool_use" -> {
                            val name = block.str("name") ?: "tool"
                            val summary = summarizeToolInput(block.getAsJsonObject("input"))
                            edt { listener.onToolUse(name, summary) }
                        }
                    }
                }
            }

            "user" -> {
                // Tool results are echoed back as user messages.
                val content = obj.getAsJsonObject("message")?.getAsJsonArray("content") ?: return
                for (el in content) {
                    val block = el.asJsonObject
                    if (block.str("type") == "tool_result") {
                        val isError = block.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                        val text = extractToolResultText(block)
                        if (text.isNotBlank()) edt { listener.onToolResult(text, isError) }
                    }
                }
            }

            "result" -> {
                val sid = obj.str("session_id")
                sid?.let { liveSessionId = it }
                val cost = obj.get("total_cost_usd")?.takeIf { it.isJsonPrimitive }?.asDouble
                val isError = obj.str("subtype") != "success"
                val errText = if (isError) obj.str("result") ?: "Run did not complete successfully." else null
                edt { listener.onResult(sid, cost, isError, errText) }

                val usage = obj.getAsJsonObject("usage")
                val contextUsed = if (usage != null) {
                    usage.long("input_tokens") + usage.long("cache_read_input_tokens") + usage.long("cache_creation_input_tokens")
                } else 0L
                val modelUsage = obj.getAsJsonObject("modelUsage")
                val model = modelUsage?.keySet()?.firstOrNull()
                val window = model?.let { modelUsage.getAsJsonObject(it).long("contextWindow") } ?: 0L
                edt { listener.onStats(model, contextUsed, window) }

                // Turn done, but the process stays alive for the next message.
                turnActive = false
                edt { listener.onComplete() }
            }
        }
    }

    private fun summarizeToolInput(input: JsonObject?): String {
        if (input == null) return ""
        // Show the most useful identifier per common tool.
        for (key in listOf("file_path", "command", "path", "pattern", "url", "query", "prompt", "description")) {
            input.str(key)?.let { return it.lineSequence().first().take(160) }
        }
        return input.toString().take(160)
    }

    private fun extractToolResultText(block: JsonObject): String {
        val content = block.get("content") ?: return ""
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .mapNotNull { it.asJsonObject.str("text") }
                .joinToString("\n")
            else -> ""
        }.trim()
    }

    private fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
}

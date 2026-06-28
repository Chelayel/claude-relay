package com.charbel.claudecode.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Drives a single Claude Code turn through the CLI using the `stream-json`
 * output format. One [send] call spawns one `claude -p` process; multi-turn
 * memory is preserved by passing the previous `session_id` via `--resume`.
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

    @Volatile
    private var process: Process? = null

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        process?.destroy()
    }

    fun send(
        prompt: String,
        sessionId: String?,
        permissionMode: String,
        model: String?,
        disallowedTools: List<String> = emptyList(),
        listener: Listener,
    ) {
        val cmd = GeneralCommandLine(executable).apply {
            withWorkDirectory(workingDir)
            charset = StandardCharsets.UTF_8
            addParameters("-p", prompt)
            addParameters("--output-format", "stream-json")
            addParameter("--verbose")
            if (!sessionId.isNullOrBlank()) addParameters("--resume", sessionId)
            if (permissionMode.isNotBlank()) addParameters("--permission-mode", permissionMode)
            if (!model.isNullOrBlank()) addParameters("--model", model)
            if (disallowedTools.isNotEmpty()) {
                addParameter("--disallowedTools")
                addParameters(disallowedTools)
            }
        }

        // Make sure the spawned process can find its own runtime deps.
        val env = HashMap(System.getenv())
        val existingPath = env["PATH"].orEmpty()
        env["PATH"] = (ClaudeCli.extraPathEntries() + existingPath).filter { it.isNotBlank() }.joinToString(":")
        cmd.withEnvironment(env)

        ApplicationManager.getApplication().executeOnPooledThread {
            runProcess(cmd, listener)
        }
    }

    private fun runProcess(cmd: GeneralCommandLine, listener: Listener) {
        val stderr = StringBuilder()
        try {
            val p = cmd.createProcess()
            process = p

            val errThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).forEachLine {
                        stderr.appendLine(it)
                    }
                }
            }.apply { isDaemon = true; start() }

            BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (cancelled) break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    runCatching { handleLine(trimmed, listener) }
                        .onFailure { log.warn("Failed to parse Claude output line: $trimmed", it) }
                }
            }

            val code = p.waitFor()
            errThread.join(500)

            if (cancelled) {
                edt { listener.onError("Stopped.") }
            } else if (code != 0) {
                val msg = stderr.toString().trim().ifEmpty { "Claude exited with code $code." }
                edt { listener.onError(msg) }
            }
        } catch (e: Exception) {
            log.warn("Claude process failed", e)
            edt { listener.onError(e.message ?: "Failed to launch Claude. Is the CLI installed?") }
        } finally {
            process = null
            edt { listener.onComplete() }
        }
    }

    private fun handleLine(line: String, listener: Listener) {
        val obj = JsonParser.parseString(line).asJsonObject
        when (obj.str("type")) {
            "system" -> {
                if (obj.str("subtype") == "init") {
                    obj.str("session_id")?.let { id -> edt { listener.onSystemInit(id) } }
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

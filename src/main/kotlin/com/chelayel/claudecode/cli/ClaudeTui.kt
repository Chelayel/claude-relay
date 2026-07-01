package com.chelayel.claudecode.cli

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives `claude` in a headless pseudo-terminal to scrape two things that are
 * only available in the interactive TUI:
 *
 *  - the live `/model` picker (so the model list reflects whatever the account
 *    can actually select, including newly added tiers); and
 *  - the footer status line (Session / Reset / Weekly limits).
 *
 * No prompt is ever submitted, so this costs no tokens. The menu is opened and
 * then dismissed with ESC, so nothing is changed.
 */
object ClaudeTui {

    private val log = logger<ClaudeTui>()

    private val ESC = Char(27).toString()
    private val CTRL_C = Char(3).toString()
    private val NBSP = Char(0xA0)
    private val ARROW = Char(0x276F)       // ❯ selection cursor
    private val VBAR = Char(0x2502)        // │ box border
    private val CHECK = Char(0x2714)       // ✔ default marker

    /** [name] is the `--model` alias (e.g. "Opus"); [version] is the concrete
     *  model it maps to (e.g. "Opus 4.8"), scraped from the menu description. */
    data class Model(val name: String, val version: String?, val enabled: Boolean, val isDefault: Boolean)
    data class Limits(
        val sessionPct: Double?,
        val resetText: String?,
        val weeklyPct: Double?,
        val weeklyResetText: String?,
        val model: String?,
    )
    data class Snapshot(val models: List<Model>, val limits: Limits)

    fun fetch(executable: String, workingDir: String, onResult: (Snapshot) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val snap = runCatching { scrape(executable, workingDir) }.getOrElse {
                log.warn("TUI scrape failed: ${it.message}", it)
                null
            } ?: run {
                log.warn("TUI scrape returned no snapshot — model list stays on the fallback.")
                return@executeOnPooledThread
            }
            log.warn(
                "TUI scrape OK — models=" +
                    snap.models.joinToString { "${it.name}[v=${it.version},enabled=${it.enabled}]" },
            )
            ApplicationManager.getApplication().invokeLater { onResult(snap) }
        }
    }

    private fun scrape(executable: String, workingDir: String): Snapshot? {
        val env = HashMap(System.getenv())
        env["PATH"] = (ClaudeCli.extraPathEntries() + env["PATH"].orEmpty()).filter { it.isNotBlank() }.joinToString(":")
        env["TERM"] = "xterm-256color"

        val process: PtyProcess = PtyProcessBuilder(arrayOf(executable))
            .setDirectory(workingDir)
            .setEnvironment(env)
            .setInitialColumns(220)
            .setInitialRows(50)
            .setRedirectErrorStream(true)
            .start()

        val buffer = StringBuilder()
        val stop = AtomicBoolean(false)
        val reader = Thread {
            val bytes = ByteArray(8192)
            val ins = process.inputStream
            try {
                while (!stop.get()) {
                    val n = ins.read(bytes)
                    if (n < 0) break
                    synchronized(buffer) { buffer.append(String(bytes, 0, n, StandardCharsets.UTF_8)) }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        fun snapshot() = synchronized(buffer) { buffer.toString() }
        fun send(s: String) = runCatching {
            process.outputStream.write(s.toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
        }

        try {
            // Wait for the TUI to load; answer a trust prompt if one appears.
            waitUntil(9000) { val s = snapshot(); s.contains("shortcuts") || s.contains("Welcome") || s.contains("Bypassing") }
            if (snapshot().contains("trust", ignoreCase = true)) { send("\r"); Thread.sleep(1200) }

            // Open the model picker and wait for it to render. On a slow start
            // (e.g. MCP init) the CLI may swallow the first `/model`, so retry a
            // couple of times, dismissing any half-open state before each attempt.
            var opened = false
            for (attempt in 1..3) {
                send("/model\r")
                opened = waitUntilTrue(6000) { snapshot().contains("Select model") }
                if (opened) break
                log.warn("TUI /model did not open (attempt $attempt); retrying")
                send(ESC); Thread.sleep(600)
            }
            if (!opened) log.warn("TUI /model never rendered a 'Select model' menu; parsing whatever was captured")
            Thread.sleep(900)
            val captured = snapshot()
            send(ESC)
            Thread.sleep(200)
            return parse(captured)
        } finally {
            stop.set(true)
            send(CTRL_C)
            Thread.sleep(150)
            runCatching { process.destroy() }
            runCatching { reader.join(500) }
        }
    }

    /** Like [waitUntil] but reports whether the condition was met before timing out. */
    private inline fun waitUntilTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(120)
        }
        return false
    }

    private inline fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(120)
        }
    }

    // ---- parsing -------------------------------------------------------------

    fun parse(raw: String): Snapshot {
        val text = clean(raw)
        return Snapshot(parseModels(text), parseLimits(text))
    }

    private fun parseModels(text: String): List<Model> {
        val models = LinkedHashMap<String, Model>()
        for (lineRaw in text.lines()) {
            val line = lineRaw.trim().trimStart(VBAR, '|', ' ', ARROW, '>')
            val m = Regex("^(\\d+)\\.\\s*([A-Za-z][A-Za-z0-9]*)").find(line) ?: continue
            val name = m.groupValues[2]
            if (name.length < 2 || name.equals("Learn", true)) continue
            val disabled = line.contains("(disabled)", true) || line.contains("unavailable", true)
            val isDefault = line.contains(CHECK) || line.contains("recommended", true)
            // The description after the alias opens with the concrete model version,
            // e.g. "Opus 4.8 …", "Sonnet 4.6 …" — pull the first such token out.
            val rest = line.substring(m.range.last + 1)
            val version = Regex("([A-Z][a-zA-Z]+\\s+[0-9]+(?:\\.[0-9]+)?)").find(rest)?.groupValues?.get(1)
            models.putIfAbsent(name, Model(name, version, !disabled, isDefault))
        }
        return models.values.toList()
    }

    private fun parseLimits(text: String): Limits {
        val session = Regex("Session:\\s*([0-9.]+)\\s*%").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val weekly = Regex("Weekly:\\s*([0-9.]+)\\s*%").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        // Two "Reset:" values can appear — one per limit. Scope each to its label
        // so the weekly reset isn't mistaken for the session reset.
        val weeklyIdx = text.indexOf("Weekly", ignoreCase = true)
        val sessionScope = if (weeklyIdx >= 0) text.substring(0, weeklyIdx) else text
        val weeklyScope = if (weeklyIdx >= 0) text.substring(weeklyIdx) else ""
        val reset = firstReset(sessionScope) ?: firstReset(text)
        val weeklyReset = firstReset(weeklyScope)
        val model = Regex("Model:\\s*(.+?)\\s*(?:\\u00B7|\\||Ctx|Session)").find(text)?.groupValues?.get(1)?.trim()
        return Limits(session, reset, weekly, weeklyReset, model)
    }

    private fun firstReset(scope: String): String? =
        Regex("Reset:\\s*(.+?)\\s*(?:\\u00B7|\\||Weekly|Session|$)").find(scope)?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

    /** Strip ANSI: drop SGR (color) and OSC, convert other CSI (cursor moves used
     *  for column layout) to a space so adjacent columns don't run together. */
    private fun clean(s: String): String {
        var t = s.replace(Regex("\\x1B\\[[0-9;]*m"), "")
        t = t.replace(Regex("\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)"), "")
        t = t.replace(Regex("\\x1B\\[[0-9;?]*[ -/]*[@-~]"), " ")
        t = t.replace(Regex("\\x1B[=>NOP]"), "")
        t = t.replace(NBSP, ' ')
        t = t.replace(Regex("[\\x{E0B0}-\\x{E0D4}]"), " ")
        return t
    }
}

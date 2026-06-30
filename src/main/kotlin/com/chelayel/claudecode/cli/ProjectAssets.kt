package com.chelayel.claudecode.cli

import java.io.File

/**
 * Discovers the project-local Claude assets that the `claude` CLI picks up
 * automatically when run in the project directory: the `CLAUDE.md` memory file,
 * sub-agents, skills, and custom slash commands.
 *
 * The CLI already loads these — this scan exists so the GUI can *surface* them
 * (show what's active, open the files, offer slash-command completion) the way
 * editor integrations do. Both the canonical `.claude/<kind>/` layout and a
 * bare top-level `<kind>/` directory are recognised.
 */
object ProjectAssets {

    enum class Kind(val label: String) { AGENT("agent"), SKILL("skill"), COMMAND("command") }

    /** A single agent / skill / command file the user can open or invoke. */
    data class Asset(
        val kind: Kind,
        val name: String,
        val file: File,
        val description: String?,
    )

    data class Snapshot(
        val claudeMd: File?,
        val agents: List<Asset>,
        val skills: List<Asset>,
        val commands: List<Asset>,
    ) {
        val isEmpty: Boolean get() = claudeMd == null && agents.isEmpty() && skills.isEmpty() && commands.isEmpty()
    }

    fun scan(workingDir: String): Snapshot {
        val root = File(workingDir)
        return Snapshot(
            claudeMd = findClaudeMd(root),
            agents = scanFlat(root, Kind.AGENT, "agents"),
            skills = scanSkills(root),
            commands = scanFlat(root, Kind.COMMAND, "commands"),
        )
    }

    private fun findClaudeMd(root: File): File? = listOf(
        File(root, "CLAUDE.md"),
        File(root, ".claude/CLAUDE.md"),
    ).firstOrNull { it.isFile }

    /** Agents and commands are flat `*.md` files under `.claude/<kind>/` or `<kind>/`. */
    private fun scanFlat(root: File, kind: Kind, dirName: String): List<Asset> {
        val dirs = listOf(File(root, ".claude/$dirName"), File(root, dirName))
        val files = dirs.firstOrNull { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.extension == "md" }
            ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { file ->
            val fm = frontmatter(file)
            Asset(kind, fm["name"] ?: file.nameWithoutExtension, file, fm["description"])
        }
    }

    /** Skills are directories each holding a `SKILL.md`. */
    private fun scanSkills(root: File): List<Asset> {
        val dir = listOf(File(root, ".claude/skills"), File(root, "skills"))
            .firstOrNull { it.isDirectory } ?: return emptyList()
        val skillDirs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return skillDirs.sortedBy { it.name.lowercase() }.mapNotNull { sub ->
            val md = File(sub, "SKILL.md").takeIf { it.isFile } ?: return@mapNotNull null
            val fm = frontmatter(md)
            Asset(Kind.SKILL, fm["name"] ?: sub.name, md, fm["description"])
        }
    }

    /** Parse simple `key: value` pairs from a leading `---` YAML frontmatter block. */
    private fun frontmatter(file: File): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                val it = lines.iterator()
                if (!it.hasNext() || it.next().trim() != "---") return emptyMap()
                while (it.hasNext()) {
                    val line = it.next()
                    if (line.trim() == "---") break
                    val sep = line.indexOf(':')
                    if (sep > 0) {
                        val key = line.substring(0, sep).trim().lowercase()
                        val value = line.substring(sep + 1).trim().trim('"', '\'')
                        if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
                    }
                }
            }
        }
        return out
    }
}

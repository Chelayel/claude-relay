package com.charbel.claudecode.ui

import com.charbel.claudecode.cli.ProjectAssets
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * An always-visible strip under the session header that surfaces the project's
 * Claude assets — CLAUDE.md, agents, skills, and slash commands — as clickable
 * chips. Unlike the empty-state "home" screen, this stays visible during a
 * conversation, so users can always see (and open) what's loaded.
 *
 * Clicking an agent / skill / CLAUDE.md chip opens its file; clicking a command
 * inserts it into the prompt. The strip collapses to a single summary line.
 */
class ContextBar(
    private val onOpenFile: (File) -> Unit,
    private val onUseAsset: (ProjectAssets.Asset) -> Unit,
) : JPanel(BorderLayout()) {

    private val header = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor.GRAY
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(5, 10, 5, 10)
        toolTipText = "Auto-loaded by Claude — click to collapse / expand"
    }
    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(0, 8, 6, 8)
    }

    private var snapshot: ProjectAssets.Snapshot? = null
    private var expanded = false

    init {
        isOpaque = false
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        add(header, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded = !expanded
                rebuild()
            }
        })
        isVisible = false
    }

    fun update(snap: ProjectAssets.Snapshot) {
        snapshot = snap
        isVisible = !snap.isEmpty
        rebuild()
    }

    private fun rebuild() {
        val snap = snapshot ?: return
        val total = snap.agents.size + snap.skills.size + snap.commands.size + (if (snap.claudeMd != null) 1 else 0)
        header.text = "${if (expanded) "▾" else "▸"}  Project context  ·  $total"

        content.removeAll()
        content.isVisible = expanded
        if (expanded) {
            snap.claudeMd?.let { md ->
                group("Memory", listOf(chip("CLAUDE.md", "Open project memory") { onOpenFile(md) }))
            }
            group("Agents", snap.agents.map { a -> chip(a.name, a.description) { onUseAsset(a) } })
            group("Skills", snap.skills.map { s -> chip(s.name, s.description) { onUseAsset(s) } })
            group("Commands", snap.commands.map { c -> chip("/${c.name}", c.description ?: "slash command") { onUseAsset(c) } })
        }
        revalidate()
        repaint()
    }

    private fun group(title: String, chips: List<JComponent>) {
        if (chips.isEmpty()) return
        val label = JBLabel(title.uppercase()).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(5, 2, 1, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        val row = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(5), JBUI.scale(4))).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        chips.forEach { row.add(it) }
        content.add(label)
        content.add(row)
    }

    private fun chip(text: String, tip: String?, onClick: () -> Unit): JComponent = object : JButton(text) {
        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = JBUI.Fonts.smallFont()
            margin = JBUI.insets(2, 9)
            foreground = UIUtil.getLabelForeground()
            toolTipText = tip?.takeIf { it.isNotBlank() }
            addActionListener { onClick() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fg = UIUtil.getLabelForeground()
            g2.color = if (model.isRollover) Color(fg.red, fg.green, fg.blue, 26) else Color(fg.red, fg.green, fg.blue, 14)
            val arc = JBUI.scale(12)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }
}

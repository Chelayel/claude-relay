package com.chelayel.claudecode.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClaudeChatPanel(project)
        val root = JPanel(BorderLayout()).apply {
            add(DeprecationBanner.create(), BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(root, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(panel.titleActions())
    }
}

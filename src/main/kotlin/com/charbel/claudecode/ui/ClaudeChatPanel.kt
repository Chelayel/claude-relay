package com.charbel.claudecode.ui

import com.charbel.claudecode.cli.ClaudeCli
import com.charbel.claudecode.cli.ClaudeCliClient
import com.charbel.claudecode.cli.ClaudeTui
import com.charbel.claudecode.cli.ProjectAssets
import com.charbel.claudecode.cli.SessionStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The Claude Code chat tool window: a rich transcript, an input box, and a
 * compact control strip (permission mode, dynamic model picker, usage). Each
 * send drives one [ClaudeCliClient] turn and carries the session id forward.
 */
class ClaudeChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val chat: ChatView =
        if (JBCefApp.isSupported()) ChatWebView(this) else TranscriptView()

    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(7, 9)
        emptyText.text = "Ask Claude…"
        toolTipText = "Enter to send · Shift+Enter for a newline"
    }
    private val sendButton = glyphButton("↑", ACCENT, "Send  ·  Enter")
    private val stopButton = glyphButton("■", STOP_BG, "Stop").apply { isVisible = false }
    private val permissionChip = ChipSelector(PermissionMode.values().toList(), PermissionMode.ACCEPT_EDITS) { it.label }
    private val modeChip = ChipSelector(Mode.values().toList(), Mode.AGENT) { it.label }
    private val modelChip = ChipSelector(MODEL_CHOICES, MODEL_CHOICES.first()) { it }
    private val statusLabel = JBLabel("").apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }
    private val usageLabel = JBLabel("").apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }
    private val busyIcon = AsyncProcessIcon("claude-busy").apply { isVisible = false }
    private val sessionTitleLabel = JBLabel("New chat").apply {
        font = JBUI.Fonts.label().asBold()
        foreground = JBColor.GRAY
    }
    private var currentTitle: String? = null
    private val contextBar = ContextBar(onOpenFile = ::openFile, onUseAsset = ::useAsset)
    private var assetSnapshot: ProjectAssets.Snapshot? = null

    // ---- prompt context: images, files, current editor selection -------------
    private val contextChips = mutableListOf<ContextChip>()
    private val contextPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(7, 10, 1, 10)
        isVisible = false
    }
    private val contextButton = JButton(AllIcons.General.Add).apply {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Add context — current file / selection, files, images, skills"
        margin = JBUI.emptyInsets()
        addActionListener { showContextMenu() }
    }
    /** Per-window scratch dir for pasted images; wiped on dispose. */
    private val tempDir by lazy {
        File(System.getProperty("java.io.tmpdir"), "claude-code-gui-${System.currentTimeMillis()}").apply { mkdirs() }
    }
    private var tmpCounter = 0

    // Auto-attach the active editor selection (Cursor-style), shown as a live chip.
    private var autoAttachSelection = true
    private var autoChip: ContextChip? = null

    // The single agent (persona) Claude runs as for this session, via --agent.
    private var activeAgent: ProjectAssets.Asset? = null

    /** One piece of attached context: image, file, or an editor selection. */
    private class ContextChip(
        val label: String,
        val icon: Icon?,
        val promptText: String,
        val displayMark: String,
        val tooltip: String?,
    )

    private val executable = ClaudeCli.detectExecutable()
    private val workingDir = project.basePath ?: System.getProperty("user.dir")
    private var sessionId: String? = null
    private var running = false
    private var activeClient: ClaudeCliClient? = null

    // ---- usage / model state -------------------------------------------------
    private var lastCost: Double? = null
    private var ctxUsed = 0L
    private var ctxWindow = 0L
    private var sessionPct: Double? = null
    private var weeklyPct: Double? = null
    private var resetText: String? = null
    private var weeklyResetText: String? = null
    private var resolvedModel: String? = null
    private var modelChoices = MODEL_CHOICES
    private var lastTuiAt = 0L

    init {
        val north = JPanel(BorderLayout()).apply {
            add(buildHeader(), BorderLayout.NORTH)
            add(contextBar, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)
        add(chat.component, BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)

        if (executable == "claude") {
            chat.addSystem("Claude CLI not found on common paths — relying on PATH. Install: npm i -g @anthropic-ai/claude-code")
        }

        sendButton.addActionListener { send() }
        stopButton.addActionListener { stop() }
        modeChip.toolTipText = "Agent: reads, edits & runs commands  ·  Ask: read-only answers"
        modeChip.onChange = { permissionChip.isVisible = modeChip.selected == Mode.AGENT }
        installEnterToSend()
        installSlashCompletion()
        installImagePaste()
        installSelectionTracking()
        updateControls()

        refreshAssets()
        restoreLastSession()
        refreshTui(force = true)
    }

    /** Track the active editor selection so it can be auto-attached as context. */
    private fun installSelectionTracking() {
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = refreshAutoContext()
        }, this)
        input.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                refreshAutoContext()
                refreshAssets()
            }
        })
        refreshAutoContext()
    }

    private fun refreshAutoContext() {
        autoChip = if (autoAttachSelection) selectionChip() else null
        rebuildContext()
    }

    /** Slim bar above the transcript showing the active conversation's title. */
    private fun buildHeader(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 10),
        )
        add(sessionTitleLabel, BorderLayout.CENTER)
    }

    /** A null/blank title shows the dimmed "New chat" placeholder. */
    private fun setSessionTitle(title: String?) {
        currentTitle = title?.takeIf { it.isNotBlank() }
        val shown = currentTitle?.let { truncate(it.replace(Regex("\\s+"), " ").trim(), 70) }
        sessionTitleLabel.text = shown ?: "New chat"
        sessionTitleLabel.foreground = if (shown == null) JBColor.GRAY else JBColor.foreground()
        sessionTitleLabel.toolTipText = currentTitle
    }

    private fun buildSouth(): JPanel {
        val composerWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 8, 4, 8)
            add(buildComposer(), BorderLayout.CENTER)
        }

        // Two stacked rows so the (transient) status line and the usage line
        // never fight for the same horizontal space on a narrow tool window.
        val infoRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(busyIcon)
            add(statusLabel)
        }
        val usageRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(usageLabel, BorderLayout.WEST)
        }
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 10, 6, 10)
            add(infoRow, BorderLayout.NORTH)
            add(usageRow, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
            add(composerWrap, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    /**
     * A single rounded "composer" surface: the prompt fills the top, and an
     * inline toolbar along the bottom holds the model / permission pickers on
     * the left and the Send (or Stop) button on the right.
     */
    private fun buildComposer(): JComponent {
        val arc = JBUI.scale(14)
        val fill = UIUtil.getTextFieldBackground()

        val composer = object : JPanel(BorderLayout()) {
            init { isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = fill
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = JBColor.border()
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
                super.paintComponent(g)
            }
        }

        input.isOpaque = false
        input.border = JBUI.Borders.empty(10, 12, 2, 12)
        val inputScroll = JBScrollPane(input).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, JBUI.scale(64))
        }

        // Controls wrap onto a second row on a narrow panel instead of colliding
        // with the Send button; Send stays pinned to the right.
        val pickers = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3))).apply {
            isOpaque = false
            add(contextButton)
            add(modeChip)
            add(modelChip)
            add(permissionChip)
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = revalidate()
            })
        }
        // Send and Stop share the trailing slot; updateControls toggles visibility.
        val action = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(stopButton)
            add(sendButton)
        }
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 8, 7, 6)
            add(pickers, BorderLayout.CENTER)
            add(action, BorderLayout.EAST)
        }

        composer.add(contextPanel, BorderLayout.NORTH)
        composer.add(inputScroll, BorderLayout.CENTER)
        composer.add(toolbar, BorderLayout.SOUTH)
        return composer
    }

    /** Actions placed in the tool-window title bar — always visible, unaffected by panel width. */
    fun titleActions(): List<AnAction> = listOf(
        object : DumbAwareAction("New Chat", "Start a new session", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) = newSession()
        },
        object : DumbAwareAction("Session History", "Resume a previous conversation in this project", AllIcons.Vcs.History) {
            override fun actionPerformed(e: AnActionEvent) = showHistory()
        },
        object : DumbAwareAction("Refresh", "Re-read models, usage limits, and project agents/skills", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshTui(force = true)
                refreshAssets()
            }
        },
    )

    private fun installEnterToSend() {
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
            }
        })
    }

    // ---- session history & resume --------------------------------------------

    /** On open, pick up the project's most recent conversation and replay it. */
    private fun restoreLastSession() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val latest = SessionStore.latestSession(workingDir) ?: return@executeOnPooledThread
            val events = SessionStore.readTranscript(latest.file)
            if (events.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                // Don't clobber a chat the user already started while we were loading.
                if (running || sessionId != null) return@invokeLater
                sessionId = latest.id
                setSessionTitle(latest.title)
                replay(events)
                chat.addSystem("Resumed your last session · ${latest.messageCount} messages. Start fresh anytime with New Chat (＋).")
            }
        }
    }

    private fun showHistory() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = SessionStore.listSessions(workingDir)
            ApplicationManager.getApplication().invokeLater {
                if (sessions.isEmpty()) {
                    chat.addSystem("No saved sessions for this project yet.")
                    return@invokeLater
                }
                showChooser("Resume a session", null, sessions, text = { s ->
                    "<html><b>${escape(s.title)}</b>&nbsp; <font color='#888'>${relativeTime(s.lastModified)} · ${s.messageCount} msgs</font></html>"
                }, onPick = { loadSession(it) })
            }
        }
    }

    private fun loadSession(info: SessionStore.SessionInfo) {
        if (running) stop()
        ApplicationManager.getApplication().executeOnPooledThread {
            val events = SessionStore.readTranscript(info.file)
            ApplicationManager.getApplication().invokeLater {
                chat.clear()
                sessionId = info.id
                ctxUsed = 0L
                setSessionTitle(info.title)
                replay(events)
                chat.addSystem("Resumed “${info.title}” · ${info.messageCount} messages. Replies continue this conversation.")
                statusLabel.text = ""
            }
        }
    }

    /** Re-emit stored transcript events through the same sinks the live turn uses. */
    private fun replay(events: List<SessionStore.Event>) {
        for (e in events) when (e) {
            is SessionStore.Event.User -> chat.addUser(e.text)
            is SessionStore.Event.Assistant -> chat.assistantChunk(e.text)
            is SessionStore.Event.Thinking -> chat.addThinking(e.text)
            is SessionStore.Event.Tool -> { chat.endAssistant(); chat.addToolUse(e.name, e.summary) }
            is SessionStore.Event.ToolResult -> chat.addToolResult(e.text, e.isError)
        }
        chat.endAssistant()
    }

    // ---- project context (CLAUDE.md / agents / skills / commands) -------------

    /** Re-scan project assets off the EDT and refresh the UI. */
    private fun refreshAssets() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val snap = ProjectAssets.scan(workingDir)
            ApplicationManager.getApplication().invokeLater { applyAssets(snap) }
        }
    }

    private fun applyAssets(snap: ProjectAssets.Snapshot) {
        assetSnapshot = snap
        contextBar.update(snap)
        // Make the (otherwise hidden) slash-command feature discoverable.
        if (snap.commands.isNotEmpty()) {
            input.emptyText.text = "Ask Claude…      type  /  for commands"
            input.toolTipText = "Enter to send · Shift+Enter for a newline · type / for commands"
        }
    }

    // ---- slash-command completion --------------------------------------------

    private var slashOpen = false

    private fun installSlashCompletion() {
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onInputChanged()
            override fun removeUpdate(e: DocumentEvent) = onInputChanged()
            override fun changedUpdate(e: DocumentEvent) = onInputChanged()
        })
    }

    private fun onInputChanged() {
        if (input.text == "/") { if (!slashOpen) showSlashCompletions() } else slashOpen = false
    }

    private fun showSlashCompletions() {
        val snap = assetSnapshot ?: ProjectAssets.scan(workingDir).also { assetSnapshot = it }
        if (snap.commands.isEmpty()) return
        slashOpen = true
        showChooser("Slash commands", input, snap.commands, text = { c ->
            val sub = c.description?.let { " &nbsp; <font color='#888'>${escape(truncate(it, 64))}</font>" } ?: ""
            "<html><b>/${escape(c.name)}</b>$sub</html>"
        }, onPick = { c -> insertIntoInput("/${c.name} "); slashOpen = false })
    }

    // ---- small helpers -------------------------------------------------------

    private fun insertIntoInput(text: String) {
        input.text = text
        input.requestFocusInWindow()
        input.caretPosition = input.text.length
    }

    private fun openFile(file: File) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /** An agent is a single persona Claude runs as (the `--agent` flag); a skill
     *  attaches as a removable context chip; a command drops in at the caret. */
    private fun useAsset(asset: ProjectAssets.Asset) {
        when (asset.kind) {
            ProjectAssets.Kind.AGENT -> setActiveAgent(if (activeAgent?.name == asset.name) null else asset)
            ProjectAssets.Kind.COMMAND -> insertAtCaret("/${asset.name} ")
            ProjectAssets.Kind.SKILL -> addChip(
                ContextChip(
                    label = "skill: ${asset.name}",
                    icon = null,
                    promptText = "Use the \"${asset.name}\" skill for this request.",
                    displayMark = "↳ ${asset.name} skill",
                    tooltip = asset.description ?: "skill: ${asset.name}",
                ),
            )
        }
    }

    private fun setActiveAgent(asset: ProjectAssets.Asset?) {
        activeAgent = asset
        rebuildContext()
    }

    private fun insertAtCaret(text: String) {
        val pos = input.caretPosition.coerceIn(0, input.text.length)
        input.insert(text, pos)
        input.requestFocusInWindow()
    }

    // ---- prompt context (images / files / selection) -------------------------

    /** Claude has no image/file flag in `-p` mode; we hand it absolute paths and
     *  selection snippets, and let its Read tool open the rest. */
    private fun buildPrompt(text: String, context: List<ContextChip>): String {
        if (context.isEmpty()) return text
        val refs = context.joinToString("\n\n") { it.promptText }
        val intro = text.ifBlank { "Please use the attached context." }
        return "$intro\n\n$refs"
    }

    private fun buildDisplay(text: String, context: List<ContextChip>): String {
        if (context.isEmpty()) return text
        val marks = context.joinToString("\n") { it.displayMark }
        return if (text.isBlank()) marks else "$text\n\n$marks"
    }

    /** The "+" menu: a compact action popup with Agents / Skills / Commands as
     *  searchable submenus, so it stays tidy no matter how many assets exist. */
    private fun showContextMenu() {
        // Re-scan so newly added agents/skills/commands appear without a restart.
        val snap = ProjectAssets.scan(workingDir).also { applyAssets(it) }
        val group = DefaultActionGroup()
        group.add(object : ToggleAction("Auto-attach editor selection") {
            override fun isSelected(e: AnActionEvent) = autoAttachSelection
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                autoAttachSelection = state
                refreshAutoContext()
            }
        })
        group.addSeparator()
        currentEditorFile()?.let { vf ->
            group.add(action("Current file · ${vf.name}", AllIcons.FileTypes.Any_type) { addChip(fileChip(File(vf.path))) })
        }
        group.add(action("Add file…", AllIcons.FileTypes.Any_type) { chooseFiles(IMAGE_EXTS, imagesOnly = false) })
        group.add(action("Add image…", AllIcons.FileTypes.Image) { chooseFiles(IMAGE_EXTS, imagesOnly = true) })

        if (!snap.isEmpty) {
            group.addSeparator()
            agentSubMenu(group, snap.agents)
            subMenu(group, "Skills", snap.skills, { it.name }, { it.description }) { useAsset(it) }
            subMenu(group, "Commands", snap.commands, { "/${it.name}" }, { it.description }) { useAsset(it) }
        }

        JBPopupFactory.getInstance().createActionGroupPopup(
            "Add context", group, DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        ).showUnderneathOf(contextButton)
    }

    private fun action(text: String, icon: javax.swing.Icon? = null, description: String? = null, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun <T> subMenu(
        parent: DefaultActionGroup,
        title: String,
        items: List<T>,
        label: (T) -> String,
        description: (T) -> String?,
        run: (T) -> Unit,
    ) {
        if (items.isEmpty()) return
        val sub = DefaultActionGroup("$title (${items.size})", true)
        items.forEach { item -> sub.add(action(label(item), description = description(item)) { run(item) }) }
        parent.add(sub)
    }

    /** Agents are single-select: a checkmark marks the active one; re-picking clears it. */
    private fun agentSubMenu(parent: DefaultActionGroup, agents: List<ProjectAssets.Asset>) {
        if (agents.isEmpty()) return
        val sub = DefaultActionGroup("Run as agent (${agents.size})", true)
        agents.forEach { agent ->
            sub.add(object : ToggleAction(agent.name, agent.description, null) {
                override fun isSelected(e: AnActionEvent) = activeAgent?.name == agent.name
                override fun setSelected(e: AnActionEvent, state: Boolean) =
                    setActiveAgent(if (state) agent else null)
            })
        }
        parent.add(sub)
    }

    private fun chooseFiles(imageExts: Set<String>, imagesOnly: Boolean) {
        var descriptor = FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle(if (imagesOnly) "Select Image" else "Select File")
        if (imagesOnly) descriptor = descriptor.withFileFilter { it.extension?.lowercase() in imageExts }
        FileChooser.chooseFiles(descriptor, project, null).forEach { vf ->
            val file = File(vf.path)
            addChip(if (vf.extension?.lowercase() in imageExts) imageChip(file) else fileChip(file))
        }
    }

    // ---- chip factories ----

    private fun imageChip(file: File) = ContextChip(
        label = file.name,
        icon = thumbnail(file),
        promptText = "Attached image: ${file.absolutePath}",
        displayMark = "📎 ${file.name}",
        tooltip = file.absolutePath,
    )

    private fun fileChip(file: File) = ContextChip(
        label = file.name,
        icon = AllIcons.FileTypes.Any_type,
        promptText = "Attached file: ${file.absolutePath}",
        displayMark = "📄 ${file.name}",
        tooltip = file.absolutePath,
    )

    /** Snapshot the active editor's selection, or null if there's none. */
    private fun selectionChip(): ContextChip? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val sel = editor.selectionModel
        val snippet = sel.selectedText?.takeIf { it.isNotBlank() } ?: return null
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        val name = vf?.name ?: "selection"
        val path = vf?.path ?: name
        val start = editor.document.getLineNumber(sel.selectionStart) + 1
        val end = editor.document.getLineNumber(sel.selectionEnd) + 1
        val loc = if (start == end) "$name:$start" else "$name:$start-$end"
        return ContextChip(
            label = loc,
            icon = AllIcons.Actions.MenuPaste,
            promptText = "From $path (lines $start-$end):\n```\n$snippet\n```",
            displayMark = "📄 $loc",
            tooltip = "Selected lines $start–$end of $name",
        )
    }

    private fun currentEditorFile() = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

    // ---- clipboard paste of images ----

    private fun installImagePaste() {
        val fallback = input.actionMap.get("paste-from-clipboard")
        input.actionMap.put("paste-from-clipboard", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (!pasteImageFromClipboard()) fallback?.actionPerformed(e)
            }
        })
    }

    private fun pasteImageFromClipboard(): Boolean {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return false
        val image = runCatching { clipboard.getData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return false
        val file = saveImageToTemp(image) ?: return false
        addChip(imageChip(file))
        return true
    }

    private fun saveImageToTemp(image: Image): File? = runCatching {
        val buffered = toBufferedImage(image)
        val file = File(tempDir, "paste-${System.currentTimeMillis()}-${tmpCounter++}.png")
        ImageIO.write(buffered, "png", file)
        file
    }.getOrNull()

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val w = image.getWidth(null).coerceAtLeast(1)
        val h = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return buffered
    }

    // ---- chip list management ----

    private fun addChip(chip: ContextChip) {
        contextChips.add(chip)
        rebuildContext()
    }

    private fun clearContext() {
        contextChips.clear()
        rebuildContext()
    }

    private fun rebuildContext() {
        contextPanel.removeAll()
        // One active agent (the persona Claude runs as), shown as an accent pill.
        activeAgent?.let { a ->
            val pill = ContextChip("▸ ${a.name}", null, "", "", "Running as the \"${a.name}\" agent")
            contextPanel.add(contextChipComponent(pill, accent = true) { setActiveAgent(null) })
        }
        contextChips.forEach { chip ->
            contextPanel.add(contextChipComponent(chip, accent = false) { contextChips.remove(chip); rebuildContext() })
        }
        val auto = autoChip.takeIf { autoAttachSelection }
        auto?.let {
            contextPanel.add(contextChipComponent(it, accent = true) { autoAttachSelection = false; refreshAutoContext() })
        }
        contextPanel.isVisible = activeAgent != null || contextChips.isNotEmpty() || auto != null
        contextPanel.revalidate()
        contextPanel.repaint()
        revalidate()
        repaint()
    }

    private fun contextChipComponent(chip: ContextChip, accent: Boolean, onRemove: () -> Unit): JComponent {
        val borderColor: Color = if (accent) ACCENT else JBColor.border()
        val comp = object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))) {
            init { isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val fg = JBColor.foreground()
                g2.color = Color(fg.red, fg.green, fg.blue, 16)
                val arc = JBUI.scale(10)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
                super.paintComponent(g)
            }
        }
        comp.toolTipText = chip.tooltip
        comp.add(JBLabel(truncate(chip.label, 26)).apply {
            icon = chip.icon
            font = JBUI.Fonts.smallFont()
        })
        comp.add(JButton(AllIcons.Actions.Close).apply {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            margin = JBUI.emptyInsets()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Remove"
            addActionListener { onRemove() }
        })
        return comp
    }

    private fun thumbnail(file: File): Icon? = runCatching {
        val img = ImageIO.read(file) ?: return null
        val h = JBUI.scale(26)
        val w = (img.width.toDouble() / img.height * h).toInt().coerceIn(JBUI.scale(10), JBUI.scale(72))
        ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH))
    }.getOrNull()

    private fun <T> showChooser(
        title: String?,
        anchor: java.awt.Component?,
        items: List<T>,
        text: (T) -> String,
        onPick: (T) -> Unit,
    ) {
        if (items.isEmpty()) return
        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setRenderer(SimpleListCellRenderer.create<T>("") { text(it) })
            .setItemChosenCallback { onPick(it) }
        if (title != null) builder.setTitle(title)
        val popup = builder.createPopup()
        if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showInFocusCenter()
    }

    /** A flat, borderless "chip" that opens a chooser popup — replaces a combo box. */
    private inner class ChipSelector<T>(
        items: List<T>,
        initial: T,
        private val render: (T) -> String,
    ) : JButton() {
        private var items: List<T> = items
        var onChange: (() -> Unit)? = null
        var selected: T = initial
            set(value) { field = value; updateText() }

        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(2, 7)
            foreground = JBColor.foreground()
            addActionListener {
                showChooser(null, this@ChipSelector, this@ChipSelector.items, text = { render(it) }) { sel ->
                    selected = sel
                    onChange?.invoke()
                }
            }
            updateText()
        }

        fun setItems(list: List<T>) { items = list }
        private fun updateText() { text = "${render(selected)}  ▾" }

        override fun paintComponent(g: Graphics) {
            if (model.isRollover || model.isPressed) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val fg = UIUtil.getLabelForeground()
                g2.color = Color(fg.red, fg.green, fg.blue, 28)
                val arc = JBUI.scale(8)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /** A solid, accent-filled icon button drawn from a single glyph. */
    private fun glyphButton(glyph: String, bg: Color, tip: String): JButton = object : JButton(glyph) {
        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            foreground = Color.WHITE
            font = font.deriveFont(Font.BOLD, (font.size + JBUI.scale(3)).toFloat())
            preferredSize = Dimension(JBUI.scale(38), JBUI.scale(28))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tip
            margin = JBUI.emptyInsets()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (model.isRollover && isEnabled) bg.brighter() else bg
            val arc = JBUI.scale(9)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private fun truncate(s: String, n: Int) = if (s.length > n) s.take(n - 1) + "…" else s
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun relativeTime(ts: Long): String {
        val min = (System.currentTimeMillis() - ts) / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 1440 -> "${min / 60}h ago"
            else -> "${min / 1440}d ago"
        }
    }


    // ---- model picker & usage (scraped from the interactive TUI) -------------

    /** Maps the selected label to a `--model` value (null = account default). */
    private fun modelCliValue(): String? = modelChip.selected
        .takeUnless { it == "Default" }
        ?.lowercase()

    /**
     * Re-reads the live `/model` list and the Session/Weekly/Reset footer by
     * driving `claude` in a PTY. Throttled, since each scrape spins a short-lived
     * headless session (~8s) — [force] (manual refresh / startup) bypasses it.
     */
    private fun refreshTui(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTuiAt < 45_000) return
        lastTuiAt = now
        ClaudeTui.fetch(executable, workingDir) { snap ->
            applyModels(snap.models)
            snap.limits.let {
                sessionPct = it.sessionPct ?: sessionPct
                weeklyPct = it.weeklyPct ?: weeklyPct
                resetText = it.resetText ?: resetText
                weeklyResetText = it.weeklyResetText ?: weeklyResetText
                it.model?.let { m -> resolvedModel = m }
            }
            updateUsageLabel()
        }
    }

    private fun applyModels(models: List<ClaudeTui.Model>) {
        val names = buildList {
            add("Default")
            models.filter { it.enabled && !it.name.equals("Default", true) }.forEach { add(it.name) }
        }
        if (names.size <= 1 || names == modelChoices) return
        modelChoices = names
        modelChip.setItems(names)
        if (modelChip.selected !in names) modelChip.selected = "Default"
    }

    // ---- send loop -----------------------------------------------------------

    private fun send() {
        if (running) return
        val text = input.text.trim()
        val context = contextChips + listOfNotNull(autoChip.takeIf { autoAttachSelection })
        if (text.isEmpty() && context.isEmpty()) return

        input.text = ""
        clearContext()
        val agent = activeAgent?.name
        val prompt = buildPrompt(text, context)
        if (currentTitle == null) setSessionTitle(text.ifBlank { context.firstOrNull()?.label ?: "Context" })
        val display = buildDisplay(text, context).let { if (agent != null) "$it\n\n▸ running as \"$agent\"" else it }
        chat.addUser(display)
        chat.setBusy(true)
        running = true
        statusLabel.text = "Working…"
        busyIcon.isVisible = true
        busyIcon.resume()
        updateControls()

        val askMode = modeChip.selected == Mode.ASK
        // Ask mode answers with read tools only; denying the mutators is the guard,
        // and acceptEdits keeps the remaining (safe) tools from stalling on prompts.
        val permission = if (askMode) "acceptEdits" else permissionChip.selected.cliValue
        val disallowed = if (askMode) ASK_DISALLOWED_TOOLS else emptyList()
        val model = modelCliValue()

        val client = ClaudeCliClient(workingDir, executable)
        activeClient = client
        client.send(prompt, sessionId, permission, model, agent, disallowed, object : ClaudeCliClient.Listener {
            override fun onSystemInit(sessionId: String) { this@ClaudeChatPanel.sessionId = sessionId }
            override fun onAssistantText(text: String) = chat.assistantChunk(text)
            override fun onThinking(text: String) = chat.addThinking(text)
            override fun onToolUse(name: String, inputSummary: String) {
                chat.endAssistant()
                chat.addToolUse(name, inputSummary)
            }
            override fun onToolResult(text: String, isError: Boolean) = chat.addToolResult(text, isError)
            override fun onResult(sessionId: String?, costUsd: Double?, isError: Boolean, errorText: String?) {
                sessionId?.let { this@ClaudeChatPanel.sessionId = it }
                costUsd?.let { lastCost = it }
                if (isError && errorText != null) chat.addError(errorText)
            }
            override fun onStats(model: String?, contextUsed: Long, contextWindow: Long) {
                model?.let { resolvedModel = it }
                if (contextWindow > 0) { ctxUsed = contextUsed; ctxWindow = contextWindow }
                updateUsageLabel()
            }
            override fun onError(message: String) = chat.addError(message)
            override fun onComplete() = finishTurn()
        })
    }

    private fun stop() {
        activeClient?.cancel()
    }

    private fun newSession() {
        if (running) stop()
        sessionId = null
        ctxUsed = 0L
        setSessionTitle(null)
        chat.clear()
        chat.addSystem("New session started.")
        statusLabel.text = ""
        updateUsageLabel()
    }

    private fun finishTurn() {
        running = false
        activeClient = null
        chat.endAssistant()
        chat.setBusy(false)
        busyIcon.isVisible = false
        busyIcon.suspend()
        statusLabel.text = ""
        updateControls()
        input.requestFocusInWindow()
        // Usage changed this turn — refresh limits (throttled).
        refreshTui(force = false)
    }

    private fun updateUsageLabel() {
        val parts = mutableListOf<String>()
        sessionPct?.let { p -> parts.add("Session %.0f%%".format(p) + (resetText?.let { " (resets $it)" } ?: "")) }
        weeklyPct?.let { p -> parts.add("Weekly %.0f%%".format(p) + (weeklyResetText?.let { " (resets $it)" } ?: "")) }
        if (parts.isEmpty()) {
            // Limits not scraped yet — fall back to per-turn context/cost.
            if (ctxWindow > 0 && ctxUsed > 0) parts.add("Context %.0f%%".format(ctxUsed * 100.0 / ctxWindow))
            lastCost?.let { parts.add("$%.3f".format(it)) }
        }
        usageLabel.text = parts.joinToString("   ·   ")
        usageLabel.toolTipText = buildString {
            append("<html>")
            append("Model: ").append(resolvedModel ?: "—")
            append("<br>Session limit: ").append(sessionPct?.let { "%.1f%%".format(it) } ?: "—")
            resetText?.let { append(" (resets in $it)") }
            append("<br>Weekly limit: ").append(weeklyPct?.let { "%.1f%%".format(it) } ?: "—")
            weeklyResetText?.let { append(" (resets in $it)") }
            append("<br>Context window: ")
            append(if (ctxWindow > 0) "%,d / %,d tokens (%.1f%%)".format(ctxUsed, ctxWindow, ctxUsed * 100.0 / ctxWindow) else "—")
            append("<br>Session cost: ").append(lastCost?.let { "$%.4f".format(it) } ?: "—")
            append("</html>")
        }
    }

    companion object {
        /** Curated choices matching Claude Code's `/model` picker. */
        private val MODEL_CHOICES = listOf("Default", "Opus", "Sonnet", "Haiku")
        private val ACCENT = Color(0xD9, 0x77, 0x57)
        private val STOP_BG = Color(0x8A, 0x46, 0x42)
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        /** Tools denied in Ask mode so Claude reads & answers but never modifies. */
        private val ASK_DISALLOWED_TOOLS = listOf("Edit", "Write", "MultiEdit", "NotebookEdit", "Bash")
    }

    private fun updateControls() {
        sendButton.isVisible = !running
        sendButton.isEnabled = !running
        stopButton.isVisible = running
        input.isEnabled = !running
    }

    override fun dispose() {
        activeClient?.cancel()
        runCatching { tempDir.deleteRecursively() }
    }

    private enum class PermissionMode(val label: String, val cliValue: String) {
        DEFAULT("Default (ask)", "default"),
        ACCEPT_EDITS("Accept edits", "acceptEdits"),
        PLAN("Plan only", "plan"),
        BYPASS("Bypass all", "bypassPermissions");

        override fun toString() = label
    }

    /** Interaction mode: Agent does the work; Ask is read-only Q&A. */
    private enum class Mode(val label: String) {
        AGENT("Agent"),
        ASK("Ask");

        override fun toString() = label
    }
}

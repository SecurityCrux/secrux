package com.securitycrux.secrux.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.securitycrux.secrux.intellij.callgraph.CallGraphListener
import com.securitycrux.secrux.intellij.callgraph.CallGraphService
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.i18n.SecruxI18nListener
import com.securitycrux.secrux.intellij.i18n.SecruxI18nSettings
import com.securitycrux.secrux.intellij.i18n.SecruxLanguageMode
import com.securitycrux.secrux.intellij.memo.AuditMemoListener
import com.securitycrux.secrux.intellij.memo.AuditMemoService
import com.securitycrux.secrux.intellij.memo.AuditMemoItemState
import com.securitycrux.secrux.intellij.services.SinkScanListener
import com.securitycrux.secrux.intellij.services.SinkScanService
import com.securitycrux.secrux.intellij.settings.SecruxConfigDialog
import com.securitycrux.secrux.intellij.settings.SecruxTokenStore
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class SecruxToolWindowPanel(
    private val project: Project
) : SimpleToolWindowPanel(/* vertical = */ true, /* borderless = */ true) {

    private val headerTitleLabel = JBLabel()

    private val scanButton = JButton()
    private val clearHighlightsButton = JButton()

    private val buildCallGraphButton = JButton()
    private val reloadCallGraphButton = JButton()
    private val clearCallGraphCacheButton = JButton()

    private val openConfigButton = JButton()
    private val languageButton = JButton()

    private val addMemoButton = JButton()
    private val removeMemoButton = JButton()
    private val clearMemoButton = JButton()

    private val sinksStatusLabel = JBLabel()
    private val callGraphStatusLabel = JBLabel()
    private val tokenStatusLabel = JBLabel()
    private val memoTitleLabel = JBLabel()

    private val sectionQuickActionsLabel = sectionLabel()
    private val sectionCallGraphLabel = sectionLabel()
    private val sectionStatusLabel = sectionLabel()
    private val sectionMemoLabel = sectionLabel()

    private val memoModel = DefaultListModel<AuditMemoItemState>()
    private val memoList =
        JBList(memoModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer =
                object : ColoredListCellRenderer<AuditMemoItemState>() {
                    override fun customizeCellRenderer(
                        list: javax.swing.JList<out AuditMemoItemState>,
                        value: AuditMemoItemState?,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean
                    ) {
                        if (value == null) return
                        append(value.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val suffix =
                            buildString {
                                if (value.filePath.isNotBlank()) append("  ").append(value.filePath)
                                if (value.line > 0) append(":").append(value.line)
                            }
                        if (suffix.isNotBlank()) append(suffix, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount != 2) return
                        openMemoItem(selectedValue ?: return)
                    }
                }
            )
        }

    init {
        val sinkScanService = SinkScanService.getInstance(project)
        val callGraphService = CallGraphService.getInstance(project)
        val memoService = AuditMemoService.getInstance(project)

        scanButton.addActionListener { sinkScanService.scanSinks() }
        clearHighlightsButton.addActionListener { sinkScanService.clearHighlights() }

        buildCallGraphButton.addActionListener { callGraphService.buildCallGraph() }
        reloadCallGraphButton.addActionListener { callGraphService.reloadCallGraph() }
        clearCallGraphCacheButton.addActionListener { callGraphService.clearCallGraphCache() }

        openConfigButton.addActionListener {
            SecruxConfigDialog(project).show()
            refreshTokenStatus()
        }

        languageButton.addActionListener {
            val settings = SecruxI18nSettings.getInstance()
            val next =
                when (settings.state.languageMode) {
                    SecruxLanguageMode.IDE -> SecruxLanguageMode.ZH_CN
                    SecruxLanguageMode.EN -> SecruxLanguageMode.IDE
                    SecruxLanguageMode.ZH_CN -> SecruxLanguageMode.EN
                }
            settings.setLanguageMode(next)
        }

        addMemoButton.addActionListener { memoService.addFromCurrentEditor() }
        removeMemoButton.addActionListener {
            val item = memoList.selectedValue ?: return@addActionListener
            memoService.removeById(item.id)
        }
        clearMemoButton.addActionListener { memoService.clear() }

        val root =
            JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(8))).apply {
                border = JBUI.Borders.empty(8)
            }

        root.add(
            JPanel(BorderLayout()).apply {
                add(headerTitleLabel, BorderLayout.WEST)
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                        add(openConfigButton)
                        add(languageButton)
                    },
                    BorderLayout.EAST
                )
            }
        )

        root.add(sectionQuickActionsLabel)
        root.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(scanButton)
                add(clearHighlightsButton)
            }
        )

        root.add(sectionCallGraphLabel)
        root.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(buildCallGraphButton)
                add(reloadCallGraphButton)
                add(clearCallGraphCacheButton)
            }
        )
        root.add(callGraphStatusLabel)

        root.add(sectionStatusLabel)
        root.add(sinksStatusLabel)
        root.add(tokenStatusLabel)

        root.add(sectionMemoLabel)
        root.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(addMemoButton)
                add(removeMemoButton)
                add(clearMemoButton)
            }
        )
        root.add(memoTitleLabel)
        root.add(JBScrollPane(memoList).apply { preferredSize = JBUI.size(0, 180) })

        setContent(JBScrollPane(root))

        setMemoItems(memoService.getItems())
        refreshTexts()
    }

    fun bind(disposable: Disposable) {
        project.messageBus
            .connect(disposable)
            .subscribe(
                SinkScanListener.TOPIC,
                SinkScanListener { matches ->
                    sinksStatusLabel.text = SecruxBundle.message("label.sinksStatus", matches.size)
                }
            )

        project.messageBus
            .connect(disposable)
            .subscribe(
                CallGraphListener.TOPIC,
                CallGraphListener { graph ->
                    callGraphStatusLabel.text = formatCallGraphStatus(graph)
                }
            )

        project.messageBus
            .connect(disposable)
            .subscribe(
                AuditMemoListener.TOPIC,
                AuditMemoListener { items ->
                    setMemoItems(items)
                }
            )

        ApplicationManager.getApplication()
            .messageBus
            .connect(disposable)
            .subscribe(
                SecruxI18nListener.TOPIC,
                SecruxI18nListener {
                    refreshTexts()
                }
            )
    }

    private fun refreshTexts() {
        headerTitleLabel.text = SecruxBundle.message("toolwindow.secrux.stripeTitle")

        sectionQuickActionsLabel.text = SecruxBundle.message("section.quickActions")
        sectionCallGraphLabel.text = SecruxBundle.message("section.callGraph")
        sectionStatusLabel.text = SecruxBundle.message("section.status")
        sectionMemoLabel.text = SecruxBundle.message("section.memo")

        scanButton.text = SecruxBundle.message("action.scanSinks")
        clearHighlightsButton.text = SecruxBundle.message("action.clearHighlights")

        buildCallGraphButton.text = SecruxBundle.message("action.buildCallGraph")
        buildCallGraphButton.toolTipText = SecruxBundle.message("tooltip.buildCallGraph.planned")
        reloadCallGraphButton.text = SecruxBundle.message("action.reloadCallGraph")
        reloadCallGraphButton.toolTipText = SecruxBundle.message("tooltip.reloadCallGraph")
        clearCallGraphCacheButton.text = SecruxBundle.message("action.clearCallGraphCache")
        clearCallGraphCacheButton.toolTipText = SecruxBundle.message("tooltip.clearCallGraphCache")

        openConfigButton.text = SecruxBundle.message("action.openConfig")

        val mode = SecruxI18nSettings.getInstance().state.languageMode
        languageButton.text = SecruxBundle.message("action.language", SecruxBundle.message("languageMode.$mode"))

        addMemoButton.text = SecruxBundle.message("action.addToAuditMemo")
        addMemoButton.toolTipText = SecruxBundle.message("tooltip.addToAuditMemo")
        removeMemoButton.text = SecruxBundle.message("action.removeMemoItem")
        removeMemoButton.toolTipText = SecruxBundle.message("tooltip.removeMemoItem")
        clearMemoButton.text = SecruxBundle.message("action.clearMemo")
        clearMemoButton.toolTipText = SecruxBundle.message("tooltip.clearMemo")

        memoList.emptyText.text = SecruxBundle.message("message.memo.empty")

        sinksStatusLabel.text = SecruxBundle.message("label.sinksStatus", SinkScanService.getInstance(project).getLastMatches().size)
        refreshCallGraphStatus()
        refreshTokenStatus()

        tuneButtonSize(scanButton)
        tuneButtonSize(clearHighlightsButton)
        tuneButtonSize(buildCallGraphButton)
        tuneButtonSize(reloadCallGraphButton)
        tuneButtonSize(clearCallGraphCacheButton)
        tuneButtonSize(openConfigButton)
        tuneButtonSize(languageButton)
        tuneButtonSize(addMemoButton)
        tuneButtonSize(removeMemoButton)
        tuneButtonSize(clearMemoButton)
    }

    private fun refreshTokenStatus() {
        SecruxTokenStore.getTokenAsync(project) { token ->
            tokenStatusLabel.text =
                if (token != null) {
                    SecruxBundle.message("label.tokenStatus.stored")
                } else {
                    SecruxBundle.message("label.tokenStatus.notSet")
                }
        }
    }

    private fun refreshCallGraphStatus() {
        val graph = CallGraphService.getInstance(project).getLastGraph()
        callGraphStatusLabel.text = formatCallGraphStatus(graph)
    }

    private fun formatCallGraphStatus(graph: com.securitycrux.secrux.intellij.callgraph.CallGraph?): String {
        if (graph == null) return SecruxBundle.message("label.callGraphStatusNotBuilt")
        val service = CallGraphService.getInstance(project)
        val base =
            SecruxBundle.message(
                "label.callGraphStatusBuilt",
                graph.stats.methodsIndexed,
                graph.stats.callEdges,
                graph.entryPoints.size
            )
        val typeStats = service.getLastTypeHierarchy()?.stats
        val methodSummaryStats = service.getLastMethodSummaries()?.stats
        val frameworkStats = service.getLastFrameworkModel()?.stats
        if (typeStats == null && methodSummaryStats == null && frameworkStats == null) return base

        return buildString {
            append(base)
            if (typeStats != null) {
                append("  ")
                append(
                    SecruxBundle.message(
                        "label.callGraphStatus.typeHierarchy",
                        typeStats.typesIndexed,
                        typeStats.edges,
                        typeStats.implEdges,
                        typeStats.exteEdges
                    )
                )
            }
            if (methodSummaryStats != null) {
                append("  ")
                append(
                    SecruxBundle.message(
                        "label.callGraphStatus.methodSummaries",
                        methodSummaryStats.methodsWithFieldAccess,
                        methodSummaryStats.distinctFields,
                        methodSummaryStats.fieldReads,
                        methodSummaryStats.fieldWrites
                    )
                )
            }
            if (frameworkStats != null) {
                append("  ")
                append(
                    SecruxBundle.message(
                        "label.callGraphStatus.frameworkModel",
                        frameworkStats.beans,
                        frameworkStats.injections,
                        frameworkStats.classesWithBeans,
                        frameworkStats.classesWithInjections
                    )
                )
            }
        }
    }

    private fun setMemoItems(items: List<AuditMemoItemState>) {
        memoModel.clear()
        for (item in items.sortedByDescending { it.createdAtEpochMs }) {
            memoModel.addElement(item)
        }
        memoTitleLabel.text = SecruxBundle.message("label.auditMemoWithCount", items.size)
    }

    private fun openMemoItem(item: AuditMemoItemState) {
        val basePath = project.basePath ?: return
        val file = LocalFileSystem.getInstance().findFileByPath("$basePath/${item.filePath}") ?: return
        val descriptor =
            when {
                item.line > 0 -> OpenFileDescriptor(project, file, (item.line - 1).coerceAtLeast(0), (item.column - 1).coerceAtLeast(0))
                else -> OpenFileDescriptor(project, file, item.offset.coerceAtLeast(0))
            }
        descriptor.navigate(true)
    }

    private fun sectionLabel(): JBLabel =
        JBLabel().apply {
            font = font.deriveFont(font.style or Font.BOLD)
            border = JBUI.Borders.emptyTop(8)
        }

    private fun tuneButtonSize(button: JButton) {
        button.margin = JBUI.insets(2, 10)
        val h = JBUI.scale(26)
        val pref = button.preferredSize
        button.preferredSize = java.awt.Dimension(pref.width, h)
    }
}

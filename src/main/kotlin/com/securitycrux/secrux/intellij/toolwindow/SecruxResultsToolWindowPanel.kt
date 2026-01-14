package com.securitycrux.secrux.intellij.toolwindow

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.Disposable
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.securitycrux.secrux.intellij.callgraph.CallChainsDialog
import com.securitycrux.secrux.intellij.callgraph.CallGraphService
import com.securitycrux.secrux.intellij.callgraph.MethodRef
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.i18n.SecruxI18nListener
import com.securitycrux.secrux.intellij.services.SinkScanListener
import com.securitycrux.secrux.intellij.services.SinkScanService
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettings
import com.securitycrux.secrux.intellij.settings.SecruxTokenStore
import com.securitycrux.secrux.intellij.secrux.ReportToSecruxDialog
import com.securitycrux.secrux.intellij.secrux.SecruxFindingReporter
import com.securitycrux.secrux.intellij.sinks.SinkMatch
import com.securitycrux.secrux.intellij.valueflow.ValueFlowTrace
import com.securitycrux.secrux.intellij.valueflow.ValueFlowTracer
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.abs
import javax.swing.JComboBox
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.TableRowSorter

class SecruxResultsToolWindowPanel(
    private val project: Project
) : SimpleToolWindowPanel(/* vertical = */ true, /* borderless = */ true) {

    private val tableModel = SinkMatchTableModel(project)
    private val sorter = TableRowSorter(tableModel)
    private val filterField = JBTextField()
    private val typeFilterCombo = JComboBox<TypeFilterItem>()
    private val groupByLocationCheckbox = JBCheckBox(SecruxBundle.message("label.groupByLocation"), true)
    private val mustReachEntryPointCheckbox = JBCheckBox(SecruxBundle.message("label.mustReachEntryPoint"), false)
    private val filterLabel = javax.swing.JLabel()
    private val filterTypeLabel = javax.swing.JLabel()
    private val showCallChainsButton = JButton()
    private val reportButton = JButton()

    private var rawMatches: List<SinkMatch> = emptyList()
    private val table = JBTable(tableModel).apply {
        rowSorter = sorter
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2 || selectedRow < 0) return
                    val modelRow = convertRowIndexToModel(selectedRow)
                    val match = tableModel.getAt(modelRow)?.primary ?: return
                    OpenFileDescriptor(project, match.file, match.startOffset).navigate(true)
                }
            }
        )
    }

    init {
        val settings = SecruxProjectSettings.getInstance(project)

        filterField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    applyFilters()
                }
            }
        )
        typeFilterCombo.addActionListener { applyFilters() }
        updateTypeFilterOptions(emptyList())

        groupByLocationCheckbox.addActionListener { renderAndRefresh() }

        showCallChainsButton.addActionListener {
            val selectedRows = getSelectedRows()
            if (selectedRows.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    SecruxBundle.message("message.selectRowToShowCallChains"),
                    SecruxBundle.message("dialog.title")
                )
                return@addActionListener
            }

            val callGraphService = CallGraphService.getInstance(project)
            val graph = callGraphService.getLastGraph()
            if (graph == null) {
                Messages.showInfoMessage(
                    project,
                    SecruxBundle.message("message.callGraphNotBuilt"),
                    SecruxBundle.message("dialog.title")
                )
                return@addActionListener
            }

            val maxDepth = 8
            val maxChains = 20
            val entryPointOnly = mustReachEntryPointCheckbox.isSelected
            val entryPoints = graph.entryPoints

            val methodSummaries = callGraphService.getLastMethodSummaries()
            val typeHierarchy = callGraphService.getLastTypeHierarchy()
            val frameworkModel = callGraphService.getLastFrameworkModel()
            val valueFlowTracer =
                if (methodSummaries != null) {
                    ValueFlowTracer(
                        graph = graph,
                        summaries = methodSummaries,
                        typeHierarchy = typeHierarchy,
                        frameworkModel = frameworkModel,
                        pointsToIndex = callGraphService.getLastPointsToIndex(),
                    )
                } else {
                    null
                }

            val sections = mutableListOf<CallChainsDialog.Section>()
            for (row in selectedRows) {
                val match = row.primary
                val header =
                    SecruxBundle.message(
                        "dialog.callChains.sectionHeader",
                        row.typesDisplay,
                        relativePath(match.file.path),
                        match.line,
                        match.column
                    )
                val sinkMethodRef = match.enclosingMethodId?.let { MethodRef.fromIdOrNull(it) }
                val target =
                    sinkMethodRef
                        ?: MethodRef(
                            classFqn = match.targetClassFqn,
                            name = match.targetMember,
                            paramCount = match.targetParamCount,
                        )
                val sinkCalleeRef =
                    MethodRef(
                        classFqn = match.targetClassFqn,
                        name = match.targetMember,
                        paramCount = match.targetParamCount,
                    )

                val sinkCallsite =
                    if (sinkMethodRef != null) {
                        val summary = methodSummaries?.summaries?.get(sinkMethodRef)
                        summary?.calls
                            ?.filter { it.calleeId == sinkCalleeRef.id }
                            ?.minByOrNull { cs ->
                                val off = cs.callOffset ?: Int.MAX_VALUE
                                abs(off - match.startOffset)
                            }
                    } else {
                        null
                    }

                val sinkValueFlows =
                    if (sinkMethodRef != null && sinkCallsite != null && valueFlowTracer != null) {
                        buildList {
                            sinkCallsite.receiver
                                ?.takeIf { it.isNotBlank() && it != "UNKNOWN" }
                                ?.let { token ->
                                    val traces =
                                        valueFlowTracer.traceToRoots(
                                            startMethod = sinkMethodRef,
                                            startToken = token,
                                            maxDepth = 10,
                                            maxStates = 1500,
                                            maxTraces = 3,
                                        )
                                    if (traces.isEmpty()) {
                                        add(CallChainsDialog.ValueFlowTraceItem(label = "receiver=$token", trace = null))
                                    } else {
                                        for ((idx, trace) in traces.withIndex()) {
                                            val suffix = if (traces.size > 1) " #${idx + 1}" else ""
                                            add(CallChainsDialog.ValueFlowTraceItem(label = "receiver=$token$suffix", trace = trace))
                                        }
                                    }
                                }

                            val maxArgsToTrace = minOf(sinkCallsite.args.size, 3)
                            for (idx in 0 until maxArgsToTrace) {
                                val token = sinkCallsite.args[idx]
                                if (token.isBlank() || token == "UNKNOWN") continue
                                val traces =
                                    valueFlowTracer.traceToRoots(
                                        startMethod = sinkMethodRef,
                                        startToken = token,
                                        maxDepth = 10,
                                        maxStates = 1500,
                                        maxTraces = 3,
                                    )
                                if (traces.isEmpty()) {
                                    add(CallChainsDialog.ValueFlowTraceItem(label = "arg$idx=$token", trace = null))
                                } else {
                                    for ((tIdx, trace) in traces.withIndex()) {
                                        val suffix = if (traces.size > 1) " #${tIdx + 1}" else ""
                                        add(CallChainsDialog.ValueFlowTraceItem(label = "arg$idx=$token$suffix", trace = trace))
                                    }
                                }
                            }
                        }
                    } else {
                        emptyList()
                    }
                val infoLines =
                    buildList {
                        add(SecruxBundle.message("dialog.callChains.target", target.id))
                        if (sinkMethodRef != null) {
                            add("sinkCall: ${match.targetClassFqn}#${match.targetMember}/${match.targetParamCount}")
                            if (sinkCallsite != null) {
                                val recv = sinkCallsite.receiver ?: "UNKNOWN"
                                val args = sinkCallsite.args.joinToString(",")
                                val ret = sinkCallsite.result ?: "UNKNOWN"
                                val loc = sinkCallsite.callOffset?.let { " @${it}" }.orEmpty()
                                add("sinkCallsite: recv=$recv args=[$args] ret=$ret$loc")
                            }
                        }
                    }

                if (entryPointOnly && entryPoints.isEmpty()) {
                    sections.add(
                        CallChainsDialog.Section(
                            header = header,
                            infoLines = infoLines,
                            chains = emptyList(),
                            emptyMessage = SecruxBundle.message("message.entryPointsNoneDetected")
                        )
                    )
                    continue
                }

                val chains = callGraphService.findCallerChains(target, maxDepth = maxDepth, maxChains = maxChains)
                val filteredChains =
                    if (entryPointOnly) {
                        chains
                            .mapNotNull { chain ->
                                val idx = chain.indexOfFirst { it in entryPoints }
                                if (idx < 0) null else chain.subList(idx, chain.size)
                            }
                            .distinctBy { chain -> chain.joinToString("->") { it.id } }
                    } else {
                        chains
                    }

                if (filteredChains.isEmpty()) {
                    sections.add(
                        CallChainsDialog.Section(
                            header = header,
                            infoLines = infoLines,
                            chains = emptyList(),
                            emptyMessage = SecruxBundle.message(
                                if (entryPointOnly) "message.callChainsNoEntryPoints" else "message.callChainsNone"
                            )
                        )
                    )
                    continue
                }

                val sinkLabel = "sink: ${match.targetClassFqn}#${match.targetMember}/${match.targetParamCount}"
                val stepChains =
                    filteredChains.map { chain ->
                        val trimmed =
                            if (chain.isNotEmpty() && graph.methods[chain.last()] == null) {
                                chain.dropLast(1)
                            } else {
                                chain
                            }
                        buildList<CallChainsDialog.Step> {
                            for (ref in trimmed) add(CallChainsDialog.MethodStep(ref))
                            add(
                                CallChainsDialog.SinkStep(
                                    label = sinkLabel,
                                    file = match.file,
                                    startOffset = match.startOffset,
                                    line = match.line,
                                    column = match.column,
                                    valueFlows = sinkValueFlows,
                                ),
                            )
                        }
                    }

                sections.add(
                    CallChainsDialog.Section(
                        header = header,
                        infoLines = infoLines,
                        chains = stepChains
                    )
                )
            }

            CallChainsDialog(
                project = project,
                title = SecruxBundle.message("dialog.callChains.title"),
                graph = graph,
                sections = sections
            ).show()
        }

        reportButton.addActionListener {
            val selected = getSelectedMatches()
            if (selected.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    SecruxBundle.message("message.selectRowToReport"),
                    SecruxBundle.message("dialog.title")
                )
                return@addActionListener
            }

            val dialog =
                ReportToSecruxDialog(
                    project = project,
                    initialBaseUrl = settings.state.baseUrl,
                    initialTaskId = settings.state.taskId,
                    initialIncludeSnippets = settings.state.includeSnippetsOnReport,
                    initialIncludeEnrichment = settings.state.includeEnrichmentOnReport,
                    initialTriggerAiReview = settings.state.triggerAiReviewOnReport,
                    initialWaitAiReview = settings.state.waitAiReviewOnReport
                )
            if (!dialog.showAndGet()) return@addActionListener

            val baseUrl = dialog.baseUrl
            val taskId = dialog.taskId
            val includeSnippets = dialog.includeSnippets
            val includeEnrichment = dialog.includeEnrichment
            val severity = dialog.severity
            val triggerAiReview = dialog.triggerAiReview
            val waitAiReview = dialog.waitAiReview

            if (baseUrl.isBlank() || taskId.isBlank()) {
                Messages.showErrorDialog(
                    project,
                    SecruxBundle.message("error.baseUrlTaskIdRequired"),
                    SecruxBundle.message("dialog.title")
                )
                return@addActionListener
            }

            settings.state.baseUrl = baseUrl
            settings.state.taskId = taskId
            settings.state.includeSnippetsOnReport = includeSnippets
            settings.state.includeEnrichmentOnReport = includeEnrichment
            settings.state.triggerAiReviewOnReport = triggerAiReview
            settings.state.waitAiReviewOnReport = waitAiReview

            val tokenEntered = dialog.tokenEntered
            val tokenToStore = dialog.tokenToStore

            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, SecruxBundle.message("task.reportFindings"), true) {
                    override fun run(indicator: ProgressIndicator) {
                        tokenToStore?.let { token ->
                            SecruxTokenStore.setToken(project, token)
                        }

                        val token = tokenEntered ?: SecruxTokenStore.getToken(project)
                        if (token.isNullOrBlank()) {
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    SecruxBundle.message("error.tokenNotSet"),
                                    SecruxBundle.message("dialog.title")
                                )
                            }
                            return
                        }

                        val reporter = SecruxFindingReporter(project)
                        reporter.report(
                            indicator = indicator,
                            baseUrl = baseUrl,
                            token = token,
                            taskId = taskId,
                            matches = selected,
                            severity = severity,
                            includeSnippets = includeSnippets,
                            includeEnrichment = includeEnrichment,
                            triggerAiReview = triggerAiReview,
                            waitAiReview = waitAiReview,
                            includeCallChains = true,
                            requireEntryPoint = mustReachEntryPointCheckbox.isSelected
                        )
                    }
                }
            )
        }

        val toolbar = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val filters = JPanel().apply {
                add(filterLabel)
                add(filterField)
                add(filterTypeLabel)
                add(typeFilterCombo)
                add(groupByLocationCheckbox)
                add(mustReachEntryPointCheckbox)
            }
            add(filters, BorderLayout.CENTER)

            val buttons = JPanel().apply {
                add(showCallChainsButton)
                add(reportButton)
            }
            add(buttons, BorderLayout.WEST)
        }

        val root = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }
        setContent(root)

        rawMatches = SinkScanService.getInstance(project).getLastMatches()
        updateTypeFilterOptions(rawMatches)
        renderAndRefresh()

        refreshTexts()
    }

    fun bind(disposable: Disposable) {
        project.messageBus
            .connect(disposable)
            .subscribe(
                SinkScanListener.TOPIC,
                SinkScanListener { matches ->
                    rawMatches = matches
                    updateTypeFilterOptions(rawMatches)
                    renderAndRefresh()
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
        filterLabel.text = SecruxBundle.message("label.filter")
        filterTypeLabel.text = SecruxBundle.message("label.filterType")

        filterField.toolTipText = SecruxBundle.message("tooltip.filterText")
        groupByLocationCheckbox.text = SecruxBundle.message("label.groupByLocation")
        groupByLocationCheckbox.toolTipText = SecruxBundle.message("tooltip.groupByLocation")
        mustReachEntryPointCheckbox.text = SecruxBundle.message("label.mustReachEntryPoint")
        mustReachEntryPointCheckbox.toolTipText = SecruxBundle.message("tooltip.mustReachEntryPoint")

        showCallChainsButton.text = SecruxBundle.message("action.showCallChains")
        reportButton.text = SecruxBundle.message("action.reportSelected")

        tableModel.refreshColumns()
    }

    private fun getSelectedRows(): List<SinkMatchRow> {
        val rows = table.selectedRows.toList()
        if (rows.isEmpty()) return emptyList()
        return rows.mapNotNull { viewRow ->
            val modelRow = table.convertRowIndexToModel(viewRow)
            tableModel.getAt(modelRow)
        }
    }

    private fun getSelectedMatches(): List<SinkMatch> =
        getSelectedRows()
            .flatMap { it.matches }
            .distinctBy { match -> dedupKey(match) }

    private fun renderAndRefresh() {
        tableModel.setRows(renderRows(rawMatches))
        applyFilters()
    }

    private fun renderRows(matches: List<SinkMatch>): List<SinkMatchRow> {
        val uniqueMatches = matches.distinctBy { match -> dedupKey(match) }
        if (!groupByLocationCheckbox.isSelected) {
            return uniqueMatches.map { match -> SinkMatchRow(primary = match, matches = listOf(match)) }
        }

        val rows =
            uniqueMatches
                .groupBy { match -> LocationKey(match.file.path, match.startOffset, match.endOffset) }
                .values
                .map { group ->
                    val sorted =
                        group.sortedWith(
                            compareBy(
                                { match -> match.type.name },
                                { match -> match.targetClassFqn },
                                { match -> match.targetMember },
                                { match -> match.targetParamCount }
                            )
                        )
                    SinkMatchRow(primary = sorted.first(), matches = sorted)
                }
        return rows.sortedWith(compareBy({ row -> row.primary.file.path }, { row -> row.primary.startOffset }))
    }

    private data class LocationKey(
        val filePath: String,
        val startOffset: Int,
        val endOffset: Int
    )

    private fun dedupKey(match: SinkMatch): String =
        buildString {
            append(match.file.path)
            append("|")
            append(match.startOffset)
            append("|")
            append(match.endOffset)
            append("|")
            append(match.type.name)
            append("|")
            append(match.targetClassFqn)
            append("|")
            append(match.targetMember)
            append("|")
            append(match.targetParamCount)
        }

    private fun relativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
    }

    private fun formatValueFlowTrace(trace: ValueFlowTrace, maxEdges: Int): List<String> {
        fun shortMethod(ref: MethodRef): String {
            val clazz = ref.classFqn.substringAfterLast('.')
            return "$clazz#${ref.name}/${ref.paramCount}"
        }

        val edges = trace.edges
        if (edges.isEmpty()) {
            return listOf("root: ${shortMethod(trace.end.method)} ${trace.end.token}")
        }

        val out = mutableListOf<String>()
        val limit = maxEdges.coerceIn(1, 50)
        for ((idx, edge) in edges.take(limit).withIndex()) {
            val loc = edge.offset?.let { " @${it}" }.orEmpty()
            out.add(
                "${idx + 1}) [${edge.kind}] " +
                    "${shortMethod(edge.from.method)} ${edge.from.token} -> " +
                    "${shortMethod(edge.to.method)} ${edge.to.token}$loc"
            )
        }
        if (edges.size > limit) {
            out.add("... +${edges.size - limit}")
        }
        out.add("root: ${shortMethod(trace.end.method)} ${trace.end.token}")
        return out
    }

    private fun applyFilters() {
        val text = filterField.text.trim()
        val selectedType = (typeFilterCombo.selectedItem as? TypeFilterItem)?.typeName

        if (text.isBlank() && selectedType == null) {
            sorter.rowFilter = null
            return
        }

        sorter.rowFilter =
            object : RowFilter<SinkMatchTableModel, Int>() {
                override fun include(entry: Entry<out SinkMatchTableModel, out Int>): Boolean {
                    if (selectedType != null) {
                        val row = tableModel.getAt(entry.identifier) ?: return false
                        if (row.matches.none { match -> match.type.name == selectedType }) return false
                    }
                    if (text.isBlank()) return true

                    val needle = text.lowercase()
                    for (column in 0 until entry.valueCount) {
                        if (entry.getStringValue(column).lowercase().contains(needle)) return true
                    }
                    return false
                }
            }
    }

    private fun updateTypeFilterOptions(matches: List<SinkMatch>) {
        val selected = (typeFilterCombo.selectedItem as? TypeFilterItem)?.typeName
        val types = matches.map { it.type.name }.distinct().sorted()
        typeFilterCombo.removeAllItems()
        typeFilterCombo.addItem(TypeFilterItem(null))
        for (type in types) {
            typeFilterCombo.addItem(TypeFilterItem(type))
        }
        if (selected != null) {
            for (i in 0 until typeFilterCombo.itemCount) {
                if (typeFilterCombo.getItemAt(i).typeName == selected) {
                    typeFilterCombo.selectedIndex = i
                    break
                }
            }
        }
    }

    private data class TypeFilterItem(
        val typeName: String?
    ) {
        override fun toString(): String =
            typeName ?: SecruxBundle.message("filter.type.all")
    }
}

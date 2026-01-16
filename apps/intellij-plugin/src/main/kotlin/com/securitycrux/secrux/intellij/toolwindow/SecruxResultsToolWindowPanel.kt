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
import com.intellij.util.ui.JBUI
import com.securitycrux.secrux.intellij.callgraph.CallChainsDialog
import com.securitycrux.secrux.intellij.callgraph.CallGraphService
import com.securitycrux.secrux.intellij.callgraph.MethodRef
import com.securitycrux.secrux.intellij.callgraph.buildCallChainsTree
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.i18n.SecruxI18nListener
import com.securitycrux.secrux.intellij.services.CustomValueFlowListener
import com.securitycrux.secrux.intellij.services.CustomValueFlowRequest
import com.securitycrux.secrux.intellij.services.CustomValueFlowService
import com.securitycrux.secrux.intellij.services.SinkScanListener
import com.securitycrux.secrux.intellij.services.SinkScanService
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettings
import com.securitycrux.secrux.intellij.settings.SecruxTokenStore
import com.securitycrux.secrux.intellij.secrux.ReportToSecruxDialog
import com.securitycrux.secrux.intellij.secrux.SecruxFindingReporter
import com.securitycrux.secrux.intellij.sinks.SinkMatch
import com.securitycrux.secrux.intellij.valueflow.ValueFlowNode
import com.securitycrux.secrux.intellij.valueflow.ValueFlowTrace
import com.securitycrux.secrux.intellij.valueflow.ValueFlowTracer
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.ListSelectionEvent
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
    private val reportButton = JButton()
    private val callChainsPlaceholder = javax.swing.JLabel()
    private val callChainsScrollPane = JBScrollPane(callChainsPlaceholder)

    private val callChainsRequestSeq = AtomicInteger(0)

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

        callChainsPlaceholder.border = JBUI.Borders.empty(6)

        filterField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    applyFilters()
                }
            }
        )
        typeFilterCombo.addActionListener { applyFilters() }
        updateTypeFilterOptions(emptyList())

        groupByLocationCheckbox.addActionListener {
            renderAndRefresh()
            showCallChainsPlaceholder()
        }
        mustReachEntryPointCheckbox.addActionListener { refreshCallChainsAsync() }

        table.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            refreshCallChainsAsync()
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
                add(reportButton)
            }
            add(buttons, BorderLayout.WEST)
        }

        val root =
            JPanel(BorderLayout()).apply {
                add(toolbar, BorderLayout.NORTH)
                add(
                    javax.swing.JSplitPane(
                        javax.swing.JSplitPane.HORIZONTAL_SPLIT,
                        JBScrollPane(table),
                        callChainsScrollPane,
                    ).apply {
                        resizeWeight = 0.35
                        isOneTouchExpandable = true
                    },
                    BorderLayout.CENTER,
                )
            }
        setContent(root)

        rawMatches = SinkScanService.getInstance(project).getLastMatches()
        updateTypeFilterOptions(rawMatches)
        renderAndRefresh()

        refreshTexts()
        showCallChainsPlaceholder()

        CustomValueFlowService.getInstance(project).consumePendingRequest()?.let { request ->
            showCustomValueFlowAsync(request)
        }
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
                    showCallChainsPlaceholder()
                }
            )

        project.messageBus
            .connect(disposable)
            .subscribe(
                CustomValueFlowListener.TOPIC,
                CustomValueFlowListener { request ->
                    showCustomValueFlowAsync(request)
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

        reportButton.text = SecruxBundle.message("action.reportSelected")

        tableModel.refreshColumns()
        showCallChainsPlaceholder()
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

    private fun shortClass(classFqn: String): String = classFqn.substringAfterLast('.')

    private fun shortMethod(ref: MethodRef): String = "${shortClass(ref.classFqn)}.${ref.name}"

    private fun shortEnclosingMethodFqn(enclosingMethodFqn: String): String {
        val raw = enclosingMethodFqn.trim()
        val noArgs = raw.substringBefore('(').trim()
        val separatorIndex = maxOf(noArgs.lastIndexOf('#'), noArgs.lastIndexOf('.'))
        if (separatorIndex < 0) return noArgs
        val classPart = noArgs.substring(0, separatorIndex).trim()
        val methodPart = noArgs.substring(separatorIndex + 1).trim()
        if (classPart.isBlank()) return methodPart
        return "${shortClass(classPart)}.$methodPart"
    }

    private fun shortSinkTarget(match: SinkMatch): String =
        "${shortClass(match.targetClassFqn)}.${match.targetMember}"

    private fun formatValueFlowTrace(trace: ValueFlowTrace, maxEdges: Int): List<String> {
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

    private fun forwardReadableChain(trace: ValueFlowTrace, maxNodes: Int = 14): String {
        val nodes =
            buildList<ValueFlowNode> {
                add(trace.end)
                for (edge in trace.edges.asReversed()) {
                    add(edge.from)
                }
            }
        val rendered =
            nodes.map { n ->
                "${shortMethod(n.method)}(${prettyValueFlowToken(n.token)})"
            }
        val safeMax = maxNodes.coerceIn(4, 40)
        if (rendered.size <= safeMax) return rendered.joinToString(" -> ")
        val head = safeMax / 2
        val tail = safeMax - head
        return rendered.take(head).joinToString(" -> ") + " -> ... -> " + rendered.takeLast(tail).joinToString(" -> ")
    }

    private fun prettyValueFlowToken(token: String, depth: Int = 0): String {
        val raw = token.trim()
        if (raw.isBlank()) return raw
        if (depth > 3) return raw

        fun shortFqn(fqn: String): String = shortClass(fqn.trim())

        fun parseFieldSuffix(suffix: String): Pair<String?, String?> {
            val trimmed = suffix.trim()
            if (trimmed.isBlank()) return null to null
            val owner = trimmed.substringBefore('#', missingDelimiterValue = "").trim().takeIf { it.isNotBlank() }
            val field = trimmed.substringAfter('#', missingDelimiterValue = "").trim().takeIf { it.isNotBlank() }
            return owner to field
        }

        return when {
            raw == "THIS" -> "this"
            raw == "RET" -> "ret"
            raw.startsWith("PARAM:") -> {
                val idx = raw.substringAfter("PARAM:", missingDelimiterValue = "").toIntOrNull()
                if (idx != null && idx >= 0) "arg${idx + 1}" else raw
            }
            raw.startsWith("CALLRET@") -> raw.lowercase()
            raw.startsWith("BEAN:") -> "bean(${shortFqn(raw.removePrefix("BEAN:"))})"
            raw.startsWith("INJECT:") -> "inject(${shortFqn(raw.removePrefix("INJECT:"))})"
            raw.startsWith("ALLOC:") -> {
                val suffix = raw.removePrefix("ALLOC:")
                val type = suffix.substringBefore('@', missingDelimiterValue = suffix).trim()
                val at = suffix.substringAfter('@', missingDelimiterValue = "").trim()
                val atSuffix = at.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                "alloc(${shortFqn(type)}$atSuffix)"
            }
            raw == "UNKNOWN" -> "unknown"
            raw.startsWith("UNKNOWN:") -> "unknown(${raw.removePrefix("UNKNOWN:")})"
            raw.startsWith("THIS:") -> {
                val suffix = raw.removePrefix("THIS:")
                val (owner, field) = parseFieldSuffix(suffix)
                when {
                    field != null -> "this.$field"
                    owner != null -> "this.${shortFqn(owner)}"
                    else -> raw
                }
            }
            raw.startsWith("STATIC:") -> {
                val suffix = raw.removePrefix("STATIC:")
                val (owner, field) = parseFieldSuffix(suffix)
                when {
                    owner != null && field != null -> "${shortFqn(owner)}.$field"
                    owner != null -> shortFqn(owner)
                    field != null -> field
                    else -> raw
                }
            }
            raw.startsWith("HEAP(") -> {
                val closeIdx = raw.indexOf(')')
                val base = if (closeIdx > 5) raw.substring(5, closeIdx) else null
                val suffix = raw.substringAfter("):", missingDelimiterValue = "").trim().takeIf { it.isNotBlank() }
                val (_, field) = suffix?.let(::parseFieldSuffix) ?: (null to null)
                val basePretty = base?.let { prettyValueFlowToken(it, depth = depth + 1) } ?: "heap"
                when {
                    field != null -> "heap($basePretty).$field"
                    suffix != null -> "heap($basePretty).$suffix"
                    else -> raw
                }
            }
            else -> raw
        }
    }

    private fun taintChainSteps(item: CallChainsDialog.ValueFlowTraceItem): List<CallChainsDialog.Step> {
        val trace = item.trace
        if (trace == null) {
            return listOf(CallChainsDialog.ValueFlowGroupStep("${item.label}\n<no path>"))
        }

        val chain = forwardReadableChain(trace)
        return buildList {
            add(CallChainsDialog.ValueFlowGroupStep("${item.label}\n${SecruxBundle.message("dialog.callChains.path")}: $chain"))
            add(CallChainsDialog.ValueFlowRootStep(trace.end))
            for (edge in trace.edges.asReversed()) {
                add(CallChainsDialog.ValueFlowEdgeStep(edge))
            }
            add(CallChainsDialog.ValueFlowSinkStep(trace.start))
        }
    }

    private fun showCallChainsPlaceholder() {
        callChainsPlaceholder.text = SecruxBundle.message("message.selectRowToShowCallChains")
        callChainsScrollPane.setViewportView(callChainsPlaceholder)
    }

    private fun findCallerChains(
        graph: com.securitycrux.secrux.intellij.callgraph.CallGraph,
        target: MethodRef,
        maxDepth: Int,
        maxChains: Int,
    ): List<List<MethodRef>> {
        if (maxDepth <= 0 || maxChains <= 0) return emptyList()

        val incoming = graph.incoming
        val results = mutableListOf<List<MethodRef>>()

        fun dfs(current: MethodRef, path: MutableList<MethodRef>, depth: Int) {
            if (results.size >= maxChains) return
            if (depth >= maxDepth) {
                results.add(path.reversed())
                return
            }

            val callers = incoming[current].orEmpty()
            if (callers.isEmpty()) {
                results.add(path.reversed())
                return
            }

            for (caller in callers) {
                if (caller in path) continue
                path.add(caller)
                dfs(caller, path, depth + 1)
                path.removeAt(path.lastIndex)
                if (results.size >= maxChains) return
            }
        }

        dfs(target, mutableListOf(target), depth = 0)
        return results
    }

    private fun showCustomValueFlowAsync(request: CustomValueFlowRequest) {
        val callGraphService = CallGraphService.getInstance(project)
        val graph = callGraphService.getLastGraph()
        if (graph == null) {
            callChainsPlaceholder.text = SecruxBundle.message("message.callGraphNotBuilt")
            callChainsScrollPane.setViewportView(callChainsPlaceholder)
            return
        }

        val requestId = callChainsRequestSeq.incrementAndGet()
        val entryPointOnly = mustReachEntryPointCheckbox.isSelected

        val maxDepth = 8
        val maxChains = 20

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.buildCallChains"), true) {
                override fun run(indicator: ProgressIndicator) {
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

                    val traces =
                        valueFlowTracer
                            ?.traceToRootsRoundTrip(
                                startMethod = request.method,
                                startToken = request.token,
                                startOffset = request.startOffset,
                                maxDepth = 10,
                                maxStates = 1500,
                                maxTraces = 3,
                            )
                            .orEmpty()

                    val valueFlowItems =
                        buildList {
                            if (traces.isEmpty()) {
                                add(CallChainsDialog.ValueFlowTraceItem(label = "token=${request.token}", trace = null))
                            } else {
                                for ((idx, trace) in traces.withIndex()) {
                                    val suffix = if (traces.size > 1) " #${idx + 1}" else ""
                                    add(CallChainsDialog.ValueFlowTraceItem(label = "token=${request.token}$suffix", trace = trace))
                                }
                            }
                        }

                    val target = request.method
                    val headerDetail = "${shortMethod(target)} = ${request.token}"
                    val header =
                        SecruxBundle.message(
                            "dialog.callChains.sectionHeader",
                            "CUSTOM",
                            headerDetail,
                        )

                    val infoLines =
                        buildList {
                            add(SecruxBundle.message("dialog.callChains.target", shortMethod(target)))
                            if (methodSummaries == null) {
                                add(SecruxBundle.message("dialog.callChains.valueFlowSummaryNotBuilt"))
                            }
                        }

                    val sinkLabel = "point: ${shortMethod(target)}"
                    val callChains =
                        run {
                            if (entryPointOnly && entryPoints.isEmpty()) return@run emptyList()
                            val chains = findCallerChains(graph, target, maxDepth = maxDepth, maxChains = maxChains)
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
                            filteredChains.map { chain ->
                                buildList<CallChainsDialog.Step> {
                                    for (ref in chain) add(CallChainsDialog.MethodStep(ref))
                                    add(
                                        CallChainsDialog.SinkStep(
                                            label = sinkLabel,
                                            file = request.file,
                                            startOffset = request.startOffset,
                                            line = null,
                                            column = null,
                                            valueFlows = emptyList(),
                                        ),
                                    )
                                }
                            }
                        }

                    val callChainsEmptyMessage =
                        when {
                            entryPointOnly && entryPoints.isEmpty() -> SecruxBundle.message("message.entryPointsNoneDetected")
                            entryPointOnly && callChains.isEmpty() -> SecruxBundle.message("message.callChainsNoEntryPoints")
                            callChains.isEmpty() -> SecruxBundle.message("message.callChainsNone")
                            else -> null
                        }

                    val taintChains =
                        valueFlowItems.map(::taintChainSteps)

                    val sections =
                        listOf(
                            CallChainsDialog.Section(
                                header = header,
                                infoLines = infoLines,
                                groups =
                                    listOf(
                                        CallChainsDialog.Group(
                                            kind = CallChainsDialog.GroupKind.TAINT,
                                            chains = taintChains,
                                            emptyMessage = SecruxBundle.message("message.taintChainsNone"),
                                        ),
                                        CallChainsDialog.Group(
                                            kind = CallChainsDialog.GroupKind.CALL,
                                            chains = callChains,
                                            emptyMessage = callChainsEmptyMessage,
                                        ),
                                    ),
                            )
                        )

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        if (callChainsRequestSeq.get() != requestId) return@invokeLater
                        val tree = buildCallChainsTree(project = project, graph = graph, sections = sections)
                        callChainsScrollPane.setViewportView(tree)
                    }
                }
            }
        )
    }

    private fun refreshCallChainsAsync() {
        val selectedRows = getSelectedRows()
        if (selectedRows.isEmpty()) {
            showCallChainsPlaceholder()
            return
        }

        val callGraphService = CallGraphService.getInstance(project)
        val graph = callGraphService.getLastGraph()
        if (graph == null) {
            callChainsPlaceholder.text = SecruxBundle.message("message.callGraphNotBuilt")
            callChainsScrollPane.setViewportView(callChainsPlaceholder)
            return
        }

        val requestId = callChainsRequestSeq.incrementAndGet()
        val entryPointOnly = mustReachEntryPointCheckbox.isSelected

        val maxDepth = 8
        val maxChains = 20
        val selection = selectedRows.take(5)

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.buildCallChains"), true) {
                override fun run(indicator: ProgressIndicator) {
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
	                    for (row in selection) {
	                        indicator.checkCanceled()
	                        if (callChainsRequestSeq.get() != requestId) return
	                        val match = row.primary
	                        val sinkMethodRef = match.enclosingMethodId?.let(MethodRef::fromIdOrNull)
	                        val calleeDisplay = shortSinkTarget(match)
	                        val callerDisplay =
	                            sinkMethodRef?.let(::shortMethod)
	                                ?: match.enclosingMethodFqn?.let(::shortEnclosingMethodFqn)
	                        val headerDetail =
	                            callerDisplay
	                                ?.takeIf { it.isNotBlank() }
	                                ?.let { "$it -> $calleeDisplay" }
	                                ?: calleeDisplay
	                        val header =
	                            SecruxBundle.message(
	                                "dialog.callChains.sectionHeader",
	                                row.typesDisplay,
	                                headerDetail
	                            )
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

	                        val infoLines =
	                            buildList {
	                                add(SecruxBundle.message("dialog.callChains.target", shortMethod(target)))
	                                if (sinkMethodRef != null) {
	                                    add("sinkCall: $calleeDisplay")
	                                    if (sinkCallsite != null) {
	                                        val recv = sinkCallsite.receiver ?: "UNKNOWN"
	                                        val args = sinkCallsite.args.joinToString(",")
	                                        val ret = sinkCallsite.result ?: "UNKNOWN"
                                        val loc = sinkCallsite.callOffset?.let { " @${it}" }.orEmpty()
                                        add("sinkCallsite: recv=$recv args=[$args] ret=$ret$loc")
                                    }
                                }
                            }

                        val sinkLabel = "sink: $calleeDisplay"

                        val sinkTokensToVerify =
                            if (sinkMethodRef != null && sinkCallsite != null) {
                                buildList<Pair<String, String>> {
                                    sinkCallsite.receiver
                                        ?.takeIf { it.isNotBlank() && it != "UNKNOWN" }
                                        ?.let { add("receiver" to it) }

                                    val maxArgsToTrace = minOf(sinkCallsite.args.size, 3)
                                    for (idx in 0 until maxArgsToTrace) {
                                        val token = sinkCallsite.args[idx]
                                        if (token.isBlank() || token == "UNKNOWN") continue
                                        val humanIdx = idx + 1
                                        add("arg$humanIdx" to token)
                                    }
                                }
                            } else {
                                emptyList()
                            }

                        val taintItems = mutableListOf<CallChainsDialog.ValueFlowTraceItem>()

                        val (callChains, callChainsEmptyMessage) =
                            if (entryPointOnly && entryPoints.isEmpty()) {
                                emptyList<List<CallChainsDialog.Step>>() to
                                    SecruxBundle.message("message.entryPointsNoneDetected")
                            } else {
                                val chains = findCallerChains(graph, target, maxDepth = maxDepth, maxChains = maxChains)
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

                                val stepChains =
                                    filteredChains.mapIndexed { chainIdx, chain ->
                                        val trimmed =
                                            if (chain.isNotEmpty() && graph.methods[chain.last()] == null) {
                                                chain.dropLast(1)
                                            } else {
                                                chain
                                            }

                                        val chainValueFlows =
                                            if (valueFlowTracer != null && sinkTokensToVerify.isNotEmpty() && trimmed.isNotEmpty() && trimmed.lastOrNull() == sinkMethodRef) {
                                                buildList {
                                                    for ((slot, token) in sinkTokensToVerify) {
                                                        val traces =
                                                            valueFlowTracer.traceToChainRoot(
                                                                chainMethodsOrdered = trimmed,
                                                                startToken = token,
                                                                startOffset = sinkCallsite?.callOffset,
                                                                maxDepth = 12,
                                                                maxStates = 1_200,
                                                                maxTraces = 1,
                                                            )

                                                        for ((tIdx, trace) in traces.withIndex()) {
                                                            val suffix = if (traces.size > 1) " #${tIdx + 1}" else ""
                                                            val label = "$slot=$token$suffix"
                                                            add(CallChainsDialog.ValueFlowTraceItem(label = label, trace = trace))
                                                            taintItems.add(
                                                                CallChainsDialog.ValueFlowTraceItem(
                                                                    label = "callChain${chainIdx + 1} $label",
                                                                    trace = trace,
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                emptyList()
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
                                                    valueFlows = chainValueFlows,
                                                ),
                                            )
                                        }
                                    }

                                val emptyMessage =
                                    when {
                                        entryPointOnly && stepChains.isEmpty() -> SecruxBundle.message("message.callChainsNoEntryPoints")
                                        stepChains.isEmpty() -> SecruxBundle.message("message.callChainsNone")
                                        else -> null
                                    }
                                stepChains to emptyMessage
                            }

                        val taintChains =
                            taintItems.map(::taintChainSteps)

                        sections.add(
                            CallChainsDialog.Section(
                                header = header,
                                infoLines = infoLines,
                                groups =
                                    listOf(
                                        CallChainsDialog.Group(
                                            kind = CallChainsDialog.GroupKind.TAINT,
                                            chains = taintChains,
                                            emptyMessage = SecruxBundle.message("message.taintChainsNone"),
                                        ),
                                        CallChainsDialog.Group(
                                            kind = CallChainsDialog.GroupKind.CALL,
                                            chains = callChains,
                                            emptyMessage = callChainsEmptyMessage,
                                        ),
                                    ),
                            )
                        )
                    }

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        if (callChainsRequestSeq.get() != requestId) return@invokeLater
                        val tree = buildCallChainsTree(project = project, graph = graph, sections = sections)
                        callChainsScrollPane.setViewportView(tree)
                    }
                }
            }
        )
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

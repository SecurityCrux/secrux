package com.securitycrux.secrux.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.i18n.SecruxI18nListener
import com.securitycrux.secrux.intellij.secrux.AiReviewDialog
import com.securitycrux.secrux.intellij.secrux.SelectSecruxTaskDialog
import com.securitycrux.secrux.intellij.secrux.SecruxApiClient
import com.securitycrux.secrux.intellij.secrux.findingdetail.FindingDetailDialog
import com.securitycrux.secrux.intellij.secrux.findingdetail.FindingDetailParser
import com.securitycrux.secrux.intellij.settings.SecruxProjectSettings
import com.securitycrux.secrux.intellij.settings.SecruxTokenStore
import com.securitycrux.secrux.intellij.util.SecruxNotifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

class SecruxFindingsToolWindowPanel(
    private val project: Project,
) : SimpleToolWindowPanel(/* vertical = */ true, /* borderless = */ true) {

    private val settings = SecruxProjectSettings.getInstance(project)

    private val taskIdField = JBTextField(settings.state.taskId)
    private val browseTaskButton = JButton()

    private val searchField = JBTextField()
    private val statusCombo = JComboBox(FindingStatusFilter.entries.toTypedArray())
    private val severityCombo = JComboBox(SeverityFilter.entries.toTypedArray())
    private val pageLabel = JBLabel()

    private val refreshButton = JButton()
    private val prevButton = JButton()
    private val nextButton = JButton()

    private val viewDetailButton = JButton()
    private val aiReviewButton = JButton()
    private val aiReviewTaskButton = JButton()

    private val statusUpdateCombo = JComboBox(FindingStatusFilter.entries.toTypedArray())
    private val statusReasonField = JBTextField()
    private val applyStatusButton = JButton()

    private val statusLabel = JBLabel()

    private val tableModel = FindingTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        autoCreateRowSorter = true
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val row = selectedFindingRows().firstOrNull() ?: return
                    loadFindingDetail(row.findingId)
                }
            },
        )
    }

    private var limit = 50
    private var offset = 0
    private var total = 0L

    init {
        taskIdField.columns = 40
        searchField.columns = 18
        searchField.toolTipText = SecruxBundle.message("tooltip.search")
        searchField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    // Do not auto reload on each keystroke; only reset paging.
                    offset = 0
                }
            },
        )

        refreshButton.addActionListener {
            offset = 0
            reload()
        }
        prevButton.addActionListener {
            offset = (offset - limit).coerceAtLeast(0)
            reload()
        }
        nextButton.addActionListener {
            offset = (offset + limit).coerceAtMost(maxOffset())
            reload()
        }

        statusCombo.addActionListener {
            offset = 0
            reload()
        }
        severityCombo.addActionListener {
            offset = 0
            reload()
        }

        browseTaskButton.addActionListener { browseTask() }
        viewDetailButton.addActionListener {
            val selected = selectedFindingRows()
            if (selected.size != 1) {
                Messages.showInfoMessage(project, SecruxBundle.message("message.selectOneFinding"), SecruxBundle.message("dialog.title"))
                return@addActionListener
            }
            loadFindingDetail(selected.first().findingId)
        }
        aiReviewButton.addActionListener {
            val selected = selectedFindingRows()
            if (selected.size != 1) {
                Messages.showInfoMessage(project, SecruxBundle.message("message.selectOneFinding"), SecruxBundle.message("dialog.title"))
                return@addActionListener
            }
            triggerFindingAiReview(selected.first().findingId)
        }
        aiReviewTaskButton.addActionListener { triggerTaskAiReview() }

        applyStatusButton.addActionListener { applyStatusUpdate() }

        val header =
            JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(6))).apply {
                border = JBUI.Borders.empty(0, 0, 6, 0)
            }

        header.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel(SecruxBundle.message("label.taskId")))
                add(taskIdField)
                add(browseTaskButton)
            },
        )

        header.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel(SecruxBundle.message("label.search")))
                add(searchField)
                add(JBLabel(SecruxBundle.message("label.status")))
                add(statusCombo)
                add(JBLabel(SecruxBundle.message("label.severity")))
                add(severityCombo)
                add(refreshButton)
            },
        )

        header.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(viewDetailButton)
                add(aiReviewButton)
                add(aiReviewTaskButton)
                add(JBLabel(SecruxBundle.message("label.setStatus")))
                add(statusUpdateCombo)
                statusReasonField.columns = 18
                add(statusReasonField)
                add(applyStatusButton)
            },
        )

        val footer =
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                        add(statusLabel)
                    },
                    BorderLayout.WEST,
                )
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                        add(prevButton)
                        add(nextButton)
                        add(pageLabel)
                    },
                    BorderLayout.EAST,
                )
            }

        val root =
            JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(JBScrollPane(table), BorderLayout.CENTER)
                add(footer, BorderLayout.SOUTH)
                minimumSize = Dimension(760, 260)
            }

        setContent(root)

        refreshTexts()
        reload()
    }

    fun bind(disposable: Disposable) {
        ApplicationManager.getApplication()
            .messageBus
            .connect(disposable)
            .subscribe(
                SecruxI18nListener.TOPIC,
                SecruxI18nListener {
                    refreshTexts()
                },
            )
    }

    private fun refreshTexts() {
        browseTaskButton.text = SecruxBundle.message("action.browseTasks")
        refreshButton.text = SecruxBundle.message("action.refresh")
        prevButton.text = SecruxBundle.message("action.prevPage")
        nextButton.text = SecruxBundle.message("action.nextPage")

        viewDetailButton.text = SecruxBundle.message("action.viewFindingDetail")
        aiReviewButton.text = SecruxBundle.message("action.aiReviewFinding")
        aiReviewTaskButton.text = SecruxBundle.message("action.aiReviewTask")
        applyStatusButton.text = SecruxBundle.message("action.applyStatus")

        tableModel.refreshColumns()
    }

    private fun selectedFindingRows(): List<FindingRow> =
        table.selectedRows
            .asSequence()
            .map { viewRow -> table.convertRowIndexToModel(viewRow) }
            .mapNotNull { modelRow -> tableModel.getAt(modelRow) }
            .toList()

    private fun browseTask() {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            Messages.showErrorDialog(project, SecruxBundle.message("error.baseUrlRequired"), SecruxBundle.message("dialog.title"))
            return
        }
        val token = SecruxTokenStore.getToken(project)
        if (token.isNullOrBlank()) {
            Messages.showErrorDialog(project, SecruxBundle.message("error.tokenNotSet"), SecruxBundle.message("dialog.title"))
            return
        }

        val selector = SelectSecruxTaskDialog(project = project, baseUrl = baseUrl, token = token)
        if (!selector.showAndGet()) return
        val selected = selector.selectedTaskId ?: return
        taskIdField.text = selected
        settings.state.taskId = selected
        offset = 0
        reload()
    }

    private fun reload() {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            statusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }
        val taskId = taskIdField.text.trim()
        if (taskId.isBlank()) {
            statusLabel.text = SecruxBundle.message("error.taskIdRequired")
            return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.loadingFindings"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }

                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    val response =
                        client.listFindings(
                            taskId = taskId,
                            limit = limit,
                            offset = offset,
                            search = searchField.text.trim().ifBlank { null },
                            status = (statusCombo.selectedItem as? FindingStatusFilter)?.status,
                            severity = (severityCombo.selectedItem as? SeverityFilter)?.severity,
                        )

                    val page = parseFindingPage(response)
                    ApplicationManager.getApplication().invokeLater {
                        total = page.total
                        limit = page.limit
                        offset = page.offset
                        tableModel.setFindings(page.items)
                        pageLabel.text = SecruxBundle.message("label.pageInfo", offset, limit, total)
                        statusLabel.text = ""
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = SecruxBundle.message("error.loadingFindings", message)
                    }
                }
            },
        )
    }

    private fun loadFindingDetail(findingId: String) {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            statusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.loadingFindingDetail"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }

                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    val response = client.getFindingDetail(findingId)
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            val model = FindingDetailParser.parse(response)
                            FindingDetailDialog(project = project, model = model).show()
                        } catch (e: Exception) {
                            val message = e.message ?: e.javaClass.simpleName
                            AiReviewDialog(
                                project = project,
                                title = SecruxBundle.message("dialog.findingDetail.title"),
                                content = "Failed to parse finding detail: $message\n\n$response",
                            ).show()
                        }
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = SecruxBundle.message("error.loadingFindingDetail", message)
                    }
                }
            },
        )
    }

    private fun triggerFindingAiReview(findingId: String) {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            statusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.aiReviewFinding"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }

                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    val response = client.triggerFindingAiReview(findingId)
                    val jobId = parseJobId(response)
                    ApplicationManager.getApplication().invokeLater {
                        if (jobId != null) {
                            SecruxNotifications.info(project, SecruxBundle.message("message.aiReviewTriggered", jobId))
                        } else {
                            SecruxNotifications.info(project, SecruxBundle.message("message.aiReviewTriggeredNoJob"))
                        }
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = SecruxBundle.message("error.aiReviewFailed", message)
                    }
                }
            },
        )
    }

    private fun triggerTaskAiReview() {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            statusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }
        val taskId = taskIdField.text.trim()
        if (taskId.isBlank()) {
            statusLabel.text = SecruxBundle.message("error.taskIdRequired")
            return
        }

        val request = buildJsonObject { put("mode", "simple") }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.aiReviewTask"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }
                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    val response = client.runIdeTaskAiReview(taskId, request.toString())
                    ApplicationManager.getApplication().invokeLater {
                        AiReviewDialog(
                            project = project,
                            title = SecruxBundle.message("dialog.taskAiReview.title"),
                            content = response,
                        ).show()
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = SecruxBundle.message("error.aiReviewTaskFailed", message)
                    }
                }
            },
        )
    }

    private fun applyStatusUpdate() {
        val baseUrl = settings.state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            statusLabel.text = SecruxBundle.message("error.baseUrlRequired")
            return
        }

        val selected = selectedFindingRows()
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project, SecruxBundle.message("message.selectFindingToUpdate"), SecruxBundle.message("dialog.title"))
            return
        }
        val targetStatus = (statusUpdateCombo.selectedItem as? FindingStatusFilter)?.status
        if (targetStatus.isNullOrBlank()) {
            Messages.showInfoMessage(project, SecruxBundle.message("message.selectStatusToApply"), SecruxBundle.message("dialog.title"))
            return
        }
        val reason = statusReasonField.text.trim().ifBlank { null }
        val body =
            buildJsonObject {
                put("status", targetStatus)
                if (reason != null) put("reason", reason)
            }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, SecruxBundle.message("task.updatingFindingStatus"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val token = SecruxTokenStore.getToken(project)
                    if (token.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = SecruxBundle.message("error.tokenNotSet")
                        }
                        return
                    }

                    val client = SecruxApiClient(baseUrl = baseUrl, token = token)
                    for ((index, finding) in selected.withIndex()) {
                        indicator.checkCanceled()
                        indicator.fraction = index.toDouble() / selected.size.coerceAtLeast(1).toDouble()
                        client.updateFindingStatus(finding.findingId, body.toString())
                    }
                    ApplicationManager.getApplication().invokeLater {
                        SecruxNotifications.info(project, SecruxBundle.message("message.statusUpdated", selected.size))
                        reload()
                    }
                }

                override fun onThrowable(error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = SecruxBundle.message("error.updateFindingStatusFailed", message)
                    }
                }
            },
        )
    }

    private fun maxOffset(): Int {
        val remaining = (total - limit).coerceAtLeast(0)
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private data class FindingPage(
        val items: List<FindingRow>,
        val total: Long,
        val limit: Int,
        val offset: Int,
    ) {
        companion object {
            fun empty(): FindingPage = FindingPage(items = emptyList(), total = 0, limit = 50, offset = 0)
        }
    }

    private data class FindingRow(
        val findingId: String,
        val ruleId: String?,
        val sourceEngine: String?,
        val severity: String?,
        val status: String?,
        val path: String?,
        val line: Int?,
        val reviewVerdict: String?,
        val introducedBy: String?,
    )

    private class FindingTableModel : AbstractTableModel() {
        private val findings = mutableListOf<FindingRow>()
        private var columns = buildColumns()

        fun refreshColumns() {
            columns = buildColumns()
            fireTableStructureChanged()
        }

        fun setFindings(items: List<FindingRow>) {
            findings.clear()
            findings.addAll(items)
            fireTableDataChanged()
        }

        fun getAt(row: Int): FindingRow? = findings.getOrNull(row)

        override fun getRowCount(): Int = findings.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns.getOrElse(column) { "" }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = findings.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> row.ruleId ?: ""
                1 -> row.severity ?: ""
                2 -> row.status ?: ""
                3 -> row.path ?: ""
                4 -> row.line?.toString() ?: ""
                5 -> row.sourceEngine ?: ""
                6 -> row.reviewVerdict ?: ""
                7 -> row.introducedBy ?: ""
                8 -> row.findingId
                else -> ""
            }
        }

        private fun buildColumns(): List<String> =
            listOf(
                SecruxBundle.message("table.finding.column.ruleId"),
                SecruxBundle.message("table.finding.column.severity"),
                SecruxBundle.message("table.finding.column.status"),
                SecruxBundle.message("table.finding.column.path"),
                SecruxBundle.message("table.finding.column.line"),
                SecruxBundle.message("table.finding.column.engine"),
                SecruxBundle.message("table.finding.column.review"),
                SecruxBundle.message("table.finding.column.introducedBy"),
                SecruxBundle.message("table.finding.column.findingId"),
            )
    }

    private fun parseFindingPage(body: String): FindingPage {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(body).jsonObject

        val success = root.booleanOrNull("success") ?: true
        if (!success) {
            val message = root.stringOrNull("message") ?: "unknown error"
            throw IllegalStateException(message)
        }

        val data = root["data"]?.jsonObject ?: return FindingPage.empty()
        val items = data["items"]?.jsonArray ?: JsonArray(emptyList())

        val findings =
            items.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val findingId = obj.stringOrNull("findingId") ?: return@mapNotNull null
                val location = obj["location"] as? JsonObject
                val review = obj["review"] as? JsonObject
                FindingRow(
                    findingId = findingId,
                    ruleId = obj.stringOrNull("ruleId"),
                    sourceEngine = obj.stringOrNull("sourceEngine"),
                    severity = obj.stringOrNull("severity"),
                    status = obj.stringOrNull("status"),
                    path = location?.stringOrNull("path"),
                    line = location?.intOrNull("line"),
                    reviewVerdict = review?.stringOrNull("verdict"),
                    introducedBy = obj.stringOrNull("introducedBy"),
                )
            }

        val total = data.longOrNull("total") ?: findings.size.toLong()
        val limit = data.intOrNull("limit") ?: this.limit
        val offset = data.intOrNull("offset") ?: this.offset

        return FindingPage(items = findings, total = total, limit = limit, offset = offset)
    }

    private fun parseJobId(body: String): String? {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(body).jsonObject
        val success = root.booleanOrNull("success") ?: true
        if (!success) return null
        val data = root["data"] as? JsonObject ?: return null
        return data.stringOrNull("jobId")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toLongOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toBooleanStrictOrNull()

    private enum class FindingStatusFilter(
        val status: String?,
    ) {
        ALL(null),
        OPEN("OPEN"),
        CONFIRMED("CONFIRMED"),
        FALSE_POSITIVE("FALSE_POSITIVE"),
        RESOLVED("RESOLVED"),
        WONT_FIX("WONT_FIX");

        override fun toString(): String =
            status ?: SecruxBundle.message("filter.status.all")
    }

    private enum class SeverityFilter(
        val severity: String?,
    ) {
        ALL(null),
        CRITICAL("CRITICAL"),
        HIGH("HIGH"),
        MEDIUM("MEDIUM"),
        LOW("LOW"),
        INFO("INFO");

        override fun toString(): String =
            severity ?: SecruxBundle.message("filter.severity.all")
    }
}

package com.securitycrux.secrux.intellij.secrux

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class SelectSecruxTaskDialog(
    private val project: Project,
    private val baseUrl: String,
    private val token: String
) : DialogWrapper(project) {

    private val client = SecruxApiClient(baseUrl = baseUrl, token = token)

    private val searchField = JBTextField()
    private val statusCombo = JComboBox(StatusFilterItem.entries.toTypedArray())
    private val pageLabel = JBLabel()

    private val tableModel = TaskTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = true
    }

    private var limit = 50
    private var offset = 0
    private var total = 0L

    var selectedTaskId: String? = null
        private set

    init {
        title = SecruxBundle.message("dialog.selectTask.title")

        searchField.columns = 24
        searchField.toolTipText = SecruxBundle.message("tooltip.search")
        searchField.addActionListener {
            offset = 0
            reload()
        }

        statusCombo.addActionListener {
            offset = 0
            reload()
        }

        init()
        reload()
    }

    override fun createCenterPanel(): JComponent {
        val refreshButton = JButton(SecruxBundle.message("action.refresh")).apply {
            addActionListener { reload() }
        }
        val prevButton = JButton(SecruxBundle.message("action.prevPage")).apply {
            addActionListener {
                offset = (offset - limit).coerceAtLeast(0)
                reload()
            }
        }
        val nextButton = JButton(SecruxBundle.message("action.nextPage")).apply {
            addActionListener {
                offset = (offset + limit).coerceAtMost(maxOffset())
                reload()
            }
        }

        val controls = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val left = JPanel().apply {
                add(JBLabel(SecruxBundle.message("label.search")))
                add(searchField)
                add(JBLabel(SecruxBundle.message("label.status")))
                add(statusCombo)
                add(refreshButton)
            }
            add(left, BorderLayout.WEST)

            val right = JPanel().apply {
                add(prevButton)
                add(nextButton)
                add(pageLabel)
            }
            add(right, BorderLayout.EAST)
        }

        val root = JPanel(BorderLayout(0, 8)).apply {
            add(controls, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            preferredSize = Dimension(980, 520)
        }

        return root
    }

    override fun doOKAction() {
        val viewRow = table.selectedRow
        val modelRow = if (viewRow >= 0) table.convertRowIndexToModel(viewRow) else -1
        val task = tableModel.getAt(modelRow)
        if (task == null) {
            Messages.showInfoMessage(project, SecruxBundle.message("message.selectTask.noneSelected"), SecruxBundle.message("dialog.title"))
            return
        }
        selectedTaskId = task.taskId
        super.doOKAction()
    }

    private fun reload() {
        ProgressManager.getInstance().run(
            object : Task.Modal(project, SecruxBundle.message("task.loadingTasks"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val response =
                        client.listTasks(
                            limit = limit,
                            offset = offset,
                            search = searchField.text.trim().ifBlank { null },
                            status = (statusCombo.selectedItem as? StatusFilterItem)?.status,
                            projectId = null
                        )

                    val page = parseTaskPage(response)

                    ApplicationManager.getApplication().invokeLater {
                        total = page.total
                        limit = page.limit
                        offset = page.offset
                        tableModel.setTasks(page.items)
                        pageLabel.text = SecruxBundle.message("label.pageInfo", offset, limit, total)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    Messages.showErrorDialog(
                        project,
                        SecruxBundle.message("error.loadingTasks", error.message ?: error.javaClass.simpleName),
                        SecruxBundle.message("dialog.title")
                    )
                }
            }
        )
    }

    private fun maxOffset(): Int {
        val remaining = (total - limit).coerceAtLeast(0)
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun parseTaskPage(body: String): TaskPage {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(body).jsonObject

        val success = root.booleanOrNull("success") ?: true
        if (!success) {
            val message = root.stringOrNull("message") ?: "unknown error"
            throw IllegalStateException(message)
        }

        val data = root["data"]?.jsonObject ?: return TaskPage.empty()
        val items = data["items"]?.jsonArray ?: JsonArray(emptyList())

        val tasks =
            items.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val taskId = obj.stringOrNull("taskId") ?: return@mapNotNull null
                TaskRow(
                    taskId = taskId,
                    name = obj.stringOrNull("name"),
                    status = obj.stringOrNull("status"),
                    type = obj.stringOrNull("type"),
                    createdAt = obj.stringOrNull("createdAt")
                )
            }

        val total = data.longOrNull("total") ?: tasks.size.toLong()
        val limit = data.intOrNull("limit") ?: this.limit
        val offset = data.intOrNull("offset") ?: this.offset

        return TaskPage(items = tasks, total = total, limit = limit, offset = offset)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toLongOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toBooleanStrictOrNull()

    private data class TaskPage(
        val items: List<TaskRow>,
        val total: Long,
        val limit: Int,
        val offset: Int
    ) {
        companion object {
            fun empty(): TaskPage = TaskPage(items = emptyList(), total = 0, limit = 50, offset = 0)
        }
    }

    private data class TaskRow(
        val taskId: String,
        val name: String?,
        val status: String?,
        val type: String?,
        val createdAt: String?
    )

    private class TaskTableModel : AbstractTableModel() {

        private val tasks = mutableListOf<TaskRow>()

        private val columns =
            listOf(
                SecruxBundle.message("table.task.column.name"),
                SecruxBundle.message("table.task.column.status"),
                SecruxBundle.message("table.task.column.type"),
                SecruxBundle.message("table.task.column.createdAt"),
                SecruxBundle.message("table.task.column.taskId")
            )

        fun setTasks(newTasks: List<TaskRow>) {
            tasks.clear()
            tasks.addAll(newTasks)
            fireTableDataChanged()
        }

        fun getAt(row: Int): TaskRow? = tasks.getOrNull(row)

        override fun getRowCount(): Int = tasks.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns.getOrElse(column) { "" }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = tasks.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> row.name ?: ""
                1 -> row.status ?: ""
                2 -> row.type ?: ""
                3 -> row.createdAt ?: ""
                4 -> row.taskId
                else -> ""
            }
        }
    }

    private enum class StatusFilterItem(
        val status: String?
    ) {
        ALL(null),
        PENDING("PENDING"),
        RUNNING("RUNNING"),
        SUCCEEDED("SUCCEEDED"),
        FAILED("FAILED"),
        CANCELED("CANCELED");

        override fun toString(): String =
            status ?: SecruxBundle.message("filter.status.all")
    }
}

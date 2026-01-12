package com.securitycrux.secrux.intellij.toolwindow

import com.intellij.openapi.project.Project
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.sinks.SinkMatch
import java.util.concurrent.atomic.AtomicReference
import javax.swing.table.AbstractTableModel

data class SinkMatchRow(
    val primary: SinkMatch,
    val matches: List<SinkMatch>
) {
    val count: Int
        get() = matches.size

    val typesDisplay: String
        get() = matches.map { it.type.name }.distinct().sorted().joinToString(", ")
}

class SinkMatchTableModel(
    private val project: Project
) : AbstractTableModel() {

    private val rowsRef = AtomicReference<List<SinkMatchRow>>(emptyList())

    private var columns = buildColumns()

    fun refreshColumns() {
        columns = buildColumns()
        fireTableStructureChanged()
    }

    fun setRows(rows: List<SinkMatchRow>) {
        rowsRef.set(rows)
        fireTableDataChanged()
    }

    fun getAt(row: Int): SinkMatchRow? = rowsRef.get().getOrNull(row)

    override fun getRowCount(): Int = rowsRef.get().size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns.getOrElse(column) { "" }

    override fun getColumnClass(columnIndex: Int): Class<*> =
        when (columnIndex) {
            1 -> Int::class.java
            else -> String::class.java
        }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rowsRef.get().getOrNull(rowIndex) ?: return ""
        val match = row.primary
        return when (columnIndex) {
            0 -> row.typesDisplay
            1 -> row.count
            2 -> "${match.targetClassFqn}#${match.targetMember}"
            3 -> relativePath(match.file.path)
            4 -> "${match.line}:${match.column}"
            5 -> match.enclosingMethodFqn ?: ""
            else -> ""
        }
    }

    private fun relativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
    }

    private fun buildColumns(): List<String> =
        listOf(
            SecruxBundle.message("table.column.type"),
            SecruxBundle.message("table.column.count"),
            SecruxBundle.message("table.column.target"),
            SecruxBundle.message("table.column.file"),
            SecruxBundle.message("table.column.line"),
            SecruxBundle.message("table.column.enclosing")
        )
}

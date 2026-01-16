package com.securitycrux.secrux.intellij.toolwindow

import com.intellij.openapi.project.Project
import com.securitycrux.secrux.intellij.callgraph.MethodRef
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

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rowsRef.get().getOrNull(rowIndex) ?: return ""
        val match = row.primary
        return when (columnIndex) {
            0 -> row.typesDisplay
            1 -> "${shortClass(match.targetClassFqn)}#${match.targetMember}"
            2 ->
                match.enclosingMethodId
                    ?.let(MethodRef::fromIdOrNull)
                    ?.let(::shortMethod)
                    ?: match.enclosingMethodFqn?.let(::shortEnclosingMethodFqn).orEmpty()
            else -> ""
        }
    }

    private fun shortClass(classFqn: String): String = classFqn.substringAfterLast('.')

    private fun shortMethod(ref: MethodRef): String = "${shortClass(ref.classFqn)}#${ref.name}"

    private fun shortEnclosingMethodFqn(enclosingMethodFqn: String): String {
        val raw = enclosingMethodFqn.trim()
        val noArgs = raw.substringBefore('(').trim()
        val separatorIndex = maxOf(noArgs.lastIndexOf('#'), noArgs.lastIndexOf('.'))
        if (separatorIndex < 0) return noArgs
        val classPart = noArgs.substring(0, separatorIndex).trim()
        val methodPart = noArgs.substring(separatorIndex + 1).trim()
        if (classPart.isBlank()) return methodPart
        return "${shortClass(classPart)}#$methodPart"
    }

    private fun buildColumns(): List<String> =
        listOf(
            SecruxBundle.message("table.column.type"),
            SecruxBundle.message("table.column.target"),
            SecruxBundle.message("table.column.enclosing")
        )
}

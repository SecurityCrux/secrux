package com.securitycrux.secrux.intellij.callgraph

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.securitycrux.secrux.intellij.valueflow.ValueFlowEdge
import com.securitycrux.secrux.intellij.valueflow.ValueFlowNode
import com.securitycrux.secrux.intellij.valueflow.ValueFlowTrace
import java.awt.Dimension
import javax.swing.JComponent

class CallChainsDialog(
    private val project: Project,
    title: String,
    private val graph: CallGraph,
    private val sections: List<Section>
) : DialogWrapper(project) {

    sealed interface Step

    data class MethodStep(val ref: MethodRef) : Step

    enum class GroupKind {
        TAINT,
        CALL,
    }

    data class Group(
        val kind: GroupKind,
        val chains: List<List<Step>>,
        val emptyMessage: String? = null,
    )

    data class ValueFlowTraceItem(
        val label: String,
        val trace: ValueFlowTrace?,
    )

    data class ValueFlowGroupStep(
        val label: String,
    ) : Step

    data class ValueFlowEdgeStep(
        val edge: ValueFlowEdge,
    ) : Step

    data class ValueFlowRootStep(
        val node: ValueFlowNode,
    ) : Step

    data class ValueFlowSinkStep(
        val node: ValueFlowNode,
    ) : Step

    data class SinkStep(
        val label: String,
        val file: VirtualFile,
        val startOffset: Int,
        val line: Int?,
        val column: Int?,
        val valueFlows: List<ValueFlowTraceItem> = emptyList(),
    ) : Step

    data class Section(
        val header: String,
        val infoLines: List<String> = emptyList(),
        val groups: List<Group>,
        val emptyMessage: String? = null
    )

    private val tree = buildCallChainsTree(project = project, graph = graph, sections = sections)

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent =
        JBScrollPane(
            tree
        ).apply {
            preferredSize = Dimension(900, 520)
        }
}

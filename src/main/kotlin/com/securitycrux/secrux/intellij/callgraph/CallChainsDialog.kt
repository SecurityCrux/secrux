package com.securitycrux.secrux.intellij.callgraph

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.securitycrux.secrux.intellij.valueflow.MethodSummaryIndex
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import javax.swing.ToolTipManager

class CallChainsDialog(
    private val project: Project,
    title: String,
    private val graph: CallGraph,
    private val sections: List<Section>
) : DialogWrapper(project) {

    sealed interface Step

    data class MethodStep(val ref: MethodRef) : Step

    data class SinkStep(
        val label: String,
        val file: VirtualFile,
        val startOffset: Int,
        val line: Int?,
        val column: Int?,
    ) : Step

    data class Section(
        val header: String,
        val infoLines: List<String> = emptyList(),
        val chains: List<List<Step>>,
        val emptyMessage: String? = null
    )

    private data class SectionNode(val header: String)
    private data class ChainNode(val index: Int, val stepsCount: Int)
    private data class InfoNode(val text: String)

    private val tree = createTree()

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

    private fun createTree(): JTree {
        val root = DefaultMutableTreeNode()

        for (section in sections) {
            val sectionNode = DefaultMutableTreeNode(SectionNode(section.header))
            root.add(sectionNode)

            for (line in section.infoLines) {
                sectionNode.add(DefaultMutableTreeNode(InfoNode(line)))
            }

            if (section.chains.isEmpty()) {
                val message = section.emptyMessage ?: SecruxBundle.message("message.callChainsNone")
                sectionNode.add(DefaultMutableTreeNode(InfoNode(message)))
                continue
            }

            for ((index, chain) in section.chains.withIndex()) {
                val chainNode = DefaultMutableTreeNode(ChainNode(index = index + 1, stepsCount = chain.size))
                sectionNode.add(chainNode)
                for (step in chain) {
                    chainNode.add(DefaultMutableTreeNode(step))
                }
            }
        }

        val model = DefaultTreeModel(root)
        val methodSummaries: MethodSummaryIndex? = CallGraphService.getInstance(project).getLastMethodSummaries()
        val tree =
            object : JTree(model) {
                override fun getToolTipText(event: MouseEvent): String? {
                    val path = getPathForLocation(event.x, event.y) ?: return null
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
                    val step = node.userObject as? Step ?: return null
                    if (step !is MethodStep) return null
                    val summary = methodSummaries?.summaries?.get(step.ref) ?: return null

                    val chainNode = node.parent as? DefaultMutableTreeNode
                    val chainSteps =
                        chainNode
                            ?.children()
                            ?.toList()
                            ?.mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? Step }
                            .orEmpty()
                    val idx = chainSteps.indexOf(step)
                    val next =
                        if (idx >= 0 && idx < chainSteps.lastIndex) {
                            chainSteps[idx + 1]
                        } else {
                            null
                        }
                    val nextRef = (next as? MethodStep)?.ref
                    val callsToNext =
                        if (nextRef != null) {
                            summary.calls.filter { it.calleeId == nextRef.id }
                        } else {
                            emptyList()
                        }

                    fun renderToken(t: String?): String = t?.takeIf { it.isNotBlank() } ?: "UNKNOWN"

                    val header =
                        buildString {
                            append("<b>").append(step.ref.id).append("</b><br/>")
                            append("reads=").append(summary.fieldsRead.size)
                            append(" writes=").append(summary.fieldsWritten.size)
                            append(" stores=").append(summary.stores.size)
                            append(" loads=").append(summary.loads.size)
                            append(" calls=").append(summary.calls.size)
                            if (nextRef != null) {
                                append(" (to next=").append(callsToNext.size).append(")")
                            }
                        }

                    val stores =
                        summary.stores.take(6).joinToString("<br/>") { s ->
                            val loc = s.offset?.let { " @${it}" }.orEmpty()
                            "${s.targetField} &lt;= ${renderToken(s.value)}$loc"
                        }
                    val loads =
                        summary.loads.take(6).joinToString("<br/>") { l ->
                            val loc = l.offset?.let { " @${it}" }.orEmpty()
                            "${renderToken(l.target)} &lt;= ${l.sourceField}$loc"
                        }
                    val calls =
                        callsToNext.take(6).joinToString("<br/>") { c ->
                            val loc = c.callOffset?.let { " @${it}" }.orEmpty()
                            val recv = c.receiver?.let(::renderToken)?.let { "recv=$it " }.orEmpty()
                            val args = c.args.joinToString(",") { renderToken(it) }
                            val res = c.result?.let(::renderToken)?.let { "ret=$it" }.orEmpty()
                            "${recv}${args} ${if (res.isNotBlank()) res else ""}$loc".trim()
                        }

                    return buildString {
                        append("<html>")
                        append(header)
                        if (callsToNext.isNotEmpty()) {
                            append("<br/><b>calls(to next)</b><br/>").append(calls)
                        }
                        if (summary.stores.isNotEmpty()) {
                            append("<br/><b>stores</b><br/>").append(stores)
                        }
                        if (summary.loads.isNotEmpty()) {
                            append("<br/><b>loads</b><br/>").append(loads)
                        }
                        append("</html>")
                    }
                }
            }.apply {
                isRootVisible = false
                showsRootHandles = true
                selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
                ToolTipManager.sharedInstance().registerComponent(this)
                cellRenderer = CallChainsTreeCellRenderer(project = project, graph = graph, methodSummaries = methodSummaries)
                expandRow(0)
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (e.clickCount != 2) return
                            val path = getPathForLocation(e.x, e.y) ?: return
                            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                            val step = node.userObject as? Step ?: return
                            val chainNode = node.parent as? DefaultMutableTreeNode
                            val chainSteps =
                                chainNode
                                    ?.children()
                                    ?.toList()
                                    ?.mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? Step }
                                    .orEmpty()

                            val idx = chainSteps.indexOf(step)
                            val callSite =
                                when {
                                    idx < 0 || idx >= chainSteps.lastIndex -> null
                                    else -> {
                                        val next = chainSteps[idx + 1]
                                        when (step) {
                                            is MethodStep ->
                                                when (next) {
                                                    is MethodStep -> graph.callSites[CallEdge(caller = step.ref, callee = next.ref)]
                                                    is SinkStep -> CallSiteLocation(file = next.file, startOffset = next.startOffset)
                                                }

                                            is SinkStep -> null
                                        }
                                    }
                                }

                            val file =
                                callSite?.file
                                    ?: when (step) {
                                        is MethodStep -> graph.methods[step.ref]?.file
                                        is SinkStep -> step.file
                                    }
                            val offset =
                                callSite?.startOffset
                                    ?: when (step) {
                                        is MethodStep -> graph.methods[step.ref]?.startOffset
                                        is SinkStep -> step.startOffset
                                    }
                            if (file == null || offset == null) {
                                Messages.showInfoMessage(
                                    project,
                                    SecruxBundle.message("message.callChainsNoSource"),
                                    SecruxBundle.message("dialog.title")
                                )
                                return
                            }
                            OpenFileDescriptor(project, file, offset).navigate(true)
                        }
                    }
                )
            }

        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }

        return tree
    }

    private class CallChainsTreeCellRenderer(
        private val project: Project,
        private val graph: CallGraph,
        private val methodSummaries: MethodSummaryIndex?,
    ) : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is SectionNode -> append(obj.header, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                is ChainNode -> append(
                    SecruxBundle.message("dialog.callChains.chain", obj.index, obj.stepsCount),
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                )
                is InfoNode -> append(obj.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                is MethodStep -> {
                    append(obj.ref.id, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val chainNode = node.parent as? DefaultMutableTreeNode
                    val chainSteps =
                        chainNode
                            ?.children()
                            ?.toList()
                            ?.mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? Step }
                            .orEmpty()
                    val idx = chainSteps.indexOf(obj)
                    val next =
                        if (idx >= 0 && idx < chainSteps.lastIndex) {
                            chainSteps[idx + 1]
                        } else {
                            null
                        }
                    val edgeKind =
                        when (next) {
                            is MethodStep -> graph.edgeKinds[CallEdge(caller = obj.ref, callee = next.ref)]
                            else -> null
                        }
                    if (edgeKind == CallEdgeKind.IMPL || edgeKind == CallEdgeKind.EXTE) {
                        append("  [${edgeKind.name}]", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    }

                    val summary = methodSummaries?.summaries?.get(obj.ref)
                    if (summary != null) {
                        append(
                            "  " +
                                SecruxBundle.message(
                                    "dialog.callChains.valueFlowStats",
                                    summary.stores.size,
                                    summary.loads.size,
                                    summary.calls.size,
                                ),
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        )
                    }
                    val callSite =
                        when {
                            idx < 0 || idx >= chainSteps.lastIndex -> null
                            else -> {
                                when (next) {
                                    is MethodStep -> graph.callSites[CallEdge(caller = obj.ref, callee = next.ref)]
                                    is SinkStep -> CallSiteLocation(file = next.file, startOffset = next.startOffset)
                                    null -> null
                                }
                            }
                        }

                    val filePath =
                        callSite?.file?.path
                            ?: graph.methods[obj.ref]?.file?.path
                    if (filePath == null) {
                        append("  " + SecruxBundle.message("message.callChains.external"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    } else {
                        val relPath = relativePath(project.basePath, filePath)
                        append("  $relPath", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                is SinkStep -> {
                    append(obj.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val relPath = relativePath(project.basePath, obj.file.path)
                    val loc =
                        buildString {
                            append(relPath)
                            if (obj.line != null) {
                                append(":").append(obj.line)
                                if (obj.column != null) append(":").append(obj.column)
                            }
                        }
                    append("  $loc", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                else -> append(obj?.toString().orEmpty(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        private fun relativePath(
            basePath: String?,
            absolutePath: String
        ): String {
            if (basePath.isNullOrBlank()) return absolutePath
            return absolutePath.removePrefix(basePath).removePrefix("/")
        }
    }
}

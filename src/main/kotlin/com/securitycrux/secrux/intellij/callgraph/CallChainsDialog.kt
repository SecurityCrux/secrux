package com.securitycrux.secrux.intellij.callgraph

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class CallChainsDialog(
    private val project: Project,
    title: String,
    private val graph: CallGraph,
    private val sections: List<Section>
) : DialogWrapper(project) {

    data class Section(
        val header: String,
        val infoLines: List<String> = emptyList(),
        val chains: List<List<MethodRef>>,
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
        val tree =
            JTree(model).apply {
                isRootVisible = false
                showsRootHandles = true
                selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
                cellRenderer = CallChainsTreeCellRenderer(project = project, graph = graph)
                expandRow(0)
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (e.clickCount != 2) return
                            val path = getPathForLocation(e.x, e.y) ?: return
                            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                            val ref = node.userObject as? MethodRef ?: return
                            val loc = graph.methods[ref]
                            if (loc == null) {
                                Messages.showInfoMessage(
                                    project,
                                    SecruxBundle.message("message.callChainsNoSource"),
                                    SecruxBundle.message("dialog.title")
                                )
                                return
                            }
                            OpenFileDescriptor(project, loc.file, loc.startOffset).navigate(true)
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
        private val graph: CallGraph
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
                is MethodRef -> {
                    append(obj.id, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val loc = graph.methods[obj]
                    if (loc == null) {
                        append("  " + SecruxBundle.message("message.callChains.external"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    } else {
                        val relPath = relativePath(project.basePath, loc.file.path)
                        append("  $relPath", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
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

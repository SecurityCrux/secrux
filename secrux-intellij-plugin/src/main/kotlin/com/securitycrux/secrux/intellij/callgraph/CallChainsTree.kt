package com.securitycrux.secrux.intellij.callgraph

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.valueflow.MethodSummaryIndex
import com.securitycrux.secrux.intellij.valueflow.ValueFlowEdge
import com.securitycrux.secrux.intellij.valueflow.ValueFlowNode
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreeSelectionModel

private data class SectionNode(val header: String)
private data class GroupNode(val kind: CallChainsDialog.GroupKind, val chainsCount: Int)
private data class ChainNode(
    val kind: CallChainsDialog.GroupKind,
    val index: Int,
    val stepsCount: Int,
    val taintVerified: Boolean = false,
)
private data class InfoNode(val text: String)

fun buildCallChainsTree(
    project: Project,
    graph: CallGraph,
    sections: List<CallChainsDialog.Section>,
): JTree {
    val root = DefaultMutableTreeNode()

    fun shortMethod(ref: MethodRef): String {
        val clazz = ref.classFqn.substringAfterLast('.')
        return "$clazz.${ref.name}"
    }

    fun resolveVirtualFile(path: String): VirtualFile? {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isBlank()) return null

        val isAbsolute =
            trimmed.startsWith("/") ||
                trimmed.matches(Regex("^[A-Za-z]:/.*"))
        val absolute =
            if (isAbsolute) {
                trimmed
            } else {
                val base = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return null
                "$base/$trimmed"
            }
        return LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    fun resolveValueFlowEdgeLocation(edge: ValueFlowEdge): Pair<VirtualFile?, Int?> {
        edge.filePath?.takeIf { it.isNotBlank() }?.let { relOrAbs ->
            val vf = resolveVirtualFile(relOrAbs)
            if (vf != null) {
                return vf to (edge.offset ?: 0)
            }
        }

        val methodToOpen =
            when (edge.kind) {
                com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.HEAP_STORE -> edge.to.method
                com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.HEAP_LOAD -> edge.from.method
                com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_ARG,
                com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_RECEIVER,
                com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_RETURN,
                -> edge.to.method
                com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_RESULT -> edge.from.method
                else -> edge.from.method
            }
        val loc = graph.methods[methodToOpen] ?: return null to null
        val offset = edge.offset ?: loc.startOffset
        return loc.file to offset
    }

    fun attachValueFlowsPerStep(
        methodNodesInChain: Map<MethodRef, DefaultMutableTreeNode>,
        chainMethodsOrdered: List<MethodRef>,
        sinkMethodNode: DefaultMutableTreeNode,
        sinkStep: CallChainsDialog.SinkStep,
    ) {
        if (sinkStep.valueFlows.isEmpty()) return

        fun parseLabelParts(label: String): Triple<String, String, String> {
            val raw = label.trim()
            val prefix = raw.substringBefore('=', missingDelimiterValue = raw).trim()
            val hashIdx = raw.lastIndexOf(" #")
            val suffix = if (hashIdx >= 0) raw.substring(hashIdx).trim() else ""
            val tokenPart =
                if (hashIdx >= 0) {
                    raw.substringBeforeLast(" #").substringAfter('=', missingDelimiterValue = "").trim()
                } else {
                    raw.substringAfter('=', missingDelimiterValue = "").trim()
                }
            return Triple(prefix.ifBlank { "value" }, tokenPart, suffix)
        }

        for (item in sinkStep.valueFlows) {
            val (paramName, _, traceSuffix) = parseLabelParts(item.label)
            val trace = item.trace
            val traceTag = "sink.$paramName${if (traceSuffix.isBlank()) "" else " $traceSuffix"}".trim()

            if (trace == null) {
                val groupNode = DefaultMutableTreeNode(CallChainsDialog.ValueFlowGroupStep("valueFlow: $traceTag"))
                groupNode.add(DefaultMutableTreeNode(InfoNode("<no path>")))
                sinkMethodNode.add(groupNode)
                continue
            }

            fun forwardReadableChain(trace: com.securitycrux.secrux.intellij.valueflow.ValueFlowTrace, maxNodes: Int = 14): String {
                val nodes =
                    buildList<com.securitycrux.secrux.intellij.valueflow.ValueFlowNode> {
                        add(trace.end)
                        for (edge in trace.edges.asReversed()) {
                            add(edge.from)
                        }
                    }
                val rendered = nodes.map { n -> "${shortMethod(n.method)} ${n.token}" }
                val safeMax = maxNodes.coerceIn(4, 40)
                if (rendered.size <= safeMax) return rendered.joinToString(" -> ")
                val head = safeMax / 2
                val tail = safeMax - head
                return rendered.take(head).joinToString(" -> ") + " -> ... -> " + rendered.takeLast(tail).joinToString(" -> ")
            }

            val tokenByMethod = linkedMapOf<MethodRef, String>()
            tokenByMethod[trace.start.method] = trace.start.token
            for (edge in trace.edges) {
                tokenByMethod[edge.to.method] = edge.to.token
            }
            tokenByMethod[trace.end.method] = trace.end.token

            val edgesByFromMethod =
                trace.edges.groupByTo(linkedMapOf()) { it.from.method }

            val chainMethodSet = methodNodesInChain.keys

            val startGroupNode =
                run {
                    val startMethod = trace.start.method
                    val node = methodNodesInChain[startMethod] ?: sinkMethodNode
                    val chain = forwardReadableChain(trace)
                    val groupNode = DefaultMutableTreeNode(CallChainsDialog.ValueFlowGroupStep("valueFlow: $traceTag · $chain"))
                    val edges = edgesByFromMethod[startMethod].orEmpty()
                    for (edge in edges) {
                        groupNode.add(DefaultMutableTreeNode(CallChainsDialog.ValueFlowEdgeStep(edge)))
                    }
                    if (trace.end.method == startMethod) {
                        groupNode.add(DefaultMutableTreeNode(CallChainsDialog.ValueFlowRootStep(trace.end)))
                    }
                    node.add(groupNode)
                    groupNode
                }

            for (method in chainMethodsOrdered) {
                if (method == trace.start.method) continue
                val token = tokenByMethod[method] ?: continue
                val edges = edgesByFromMethod[method].orEmpty()

                if (edges.isEmpty() && method != trace.end.method) continue

                val node = methodNodesInChain[method] ?: continue
                val groupNode = DefaultMutableTreeNode(CallChainsDialog.ValueFlowGroupStep("valueFlow: $traceTag = $token"))
                for (edge in edges) {
                    groupNode.add(DefaultMutableTreeNode(CallChainsDialog.ValueFlowEdgeStep(edge)))
                }
                if (method == trace.end.method) {
                    groupNode.add(DefaultMutableTreeNode(CallChainsDialog.ValueFlowRootStep(trace.end)))
                }
                node.add(groupNode)
            }

            val nonChainMethods =
                edgesByFromMethod.keys
                    .filter { it !in chainMethodSet }
            for (method in nonChainMethods) {
                val edges = edgesByFromMethod[method].orEmpty()
                if (edges.isEmpty() && method != trace.end.method) continue
                val token = tokenByMethod[method] ?: edges.firstOrNull()?.from?.token ?: "UNKNOWN"
                val viaNode =
                    DefaultMutableTreeNode(
                        CallChainsDialog.ValueFlowGroupStep("via: ${shortMethod(method)} = $token")
                    )
                for (edge in edges) {
                    viaNode.add(DefaultMutableTreeNode(CallChainsDialog.ValueFlowEdgeStep(edge)))
                }
                if (method == trace.end.method) {
                    viaNode.add(DefaultMutableTreeNode(CallChainsDialog.ValueFlowRootStep(trace.end)))
                }
                startGroupNode.add(viaNode)
            }

            if (trace.end.method !in chainMethodSet && trace.end.method !in nonChainMethods) {
                startGroupNode.add(DefaultMutableTreeNode(CallChainsDialog.ValueFlowRootStep(trace.end)))
            }
        }
    }

    for (section in sections) {
        val sectionNode = DefaultMutableTreeNode(SectionNode(section.header))
        root.add(sectionNode)

        for (line in section.infoLines) {
            sectionNode.add(DefaultMutableTreeNode(InfoNode(line)))
        }

        if (section.groups.isEmpty()) {
            val message = section.emptyMessage ?: SecruxBundle.message("message.callChainsNone")
            sectionNode.add(DefaultMutableTreeNode(InfoNode(message)))
            continue
        }

        for (group in section.groups) {
            val groupNode = DefaultMutableTreeNode(GroupNode(kind = group.kind, chainsCount = group.chains.size))
            sectionNode.add(groupNode)

            if (group.chains.isEmpty()) {
                val message =
                    group.emptyMessage
                        ?: section.emptyMessage
                        ?: SecruxBundle.message("message.callChainsNone")
                groupNode.add(DefaultMutableTreeNode(InfoNode(message)))
                continue
            }

            for ((index, chain) in group.chains.withIndex()) {
                val taintVerified =
                    chain.any { step ->
                        (step as? CallChainsDialog.SinkStep)?.valueFlows?.any { it.trace != null } == true
                    }
                val chainNode =
                    DefaultMutableTreeNode(
                        ChainNode(
                            kind = group.kind,
                            index = index + 1,
                            stepsCount = chain.size,
                            taintVerified = taintVerified,
                        )
                    )
                groupNode.add(chainNode)
                val methodNodesInChain = linkedMapOf<MethodRef, DefaultMutableTreeNode>()
                val chainMethodsOrdered = mutableListOf<MethodRef>()
                var sinkMethodNode: DefaultMutableTreeNode? = null
                var sinkStep: CallChainsDialog.SinkStep? = null
                for (step in chain) {
                    when (step) {
                        is CallChainsDialog.MethodStep -> {
                            val node = DefaultMutableTreeNode(step)
                            chainNode.add(node)
                            methodNodesInChain[step.ref] = node
                            chainMethodsOrdered.add(step.ref)
                            sinkMethodNode = node
                        }
                        is CallChainsDialog.SinkStep -> {
                            val node = DefaultMutableTreeNode(step)
                            chainNode.add(node)
                            sinkStep = step
                        }
                        else -> chainNode.add(DefaultMutableTreeNode(step))
                    }
                }
                val sink = sinkStep
                val sinkOwnerMethodNode = sinkMethodNode
                if (sink != null && sinkOwnerMethodNode != null) {
                    attachValueFlowsPerStep(
                        methodNodesInChain = methodNodesInChain,
                        chainMethodsOrdered = chainMethodsOrdered,
                        sinkMethodNode = sinkOwnerMethodNode,
                        sinkStep = sink,
                    )
                }
            }
        }
    }

    val model = DefaultTreeModel(root)
    val methodSummaries: MethodSummaryIndex? = CallGraphService.getInstance(project).getLastMethodSummaries()

    fun resolveValueFlowNodeLocation(node: ValueFlowNode, preferLatest: Boolean): Pair<VirtualFile?, Int?> {
        val loc = graph.methods[node.method] ?: return null to null
        val file = loc.file
        val token = node.token.trim()

        fun embeddedOffset(t: String): Int? {
            if (t.startsWith("CALLRET@")) {
                return t.substringAfter("CALLRET@", missingDelimiterValue = "").toIntOrNull()
            }
            if (t.startsWith("ALLOC:")) {
                val at = t.substringAfterLast('@', missingDelimiterValue = "")
                return at.toIntOrNull()
            }
            return null
        }

        embeddedOffset(token)?.let { return file to it }

        if (!preferLatest && (token == "THIS" || token.startsWith("PARAM:"))) {
            return file to loc.startOffset
        }

        val summary = methodSummaries?.summaries?.get(node.method)
        if (summary != null) {
            val offsets = mutableListOf<Int>()

            summary.aliases.forEach { a ->
                if (a.left == token || a.right == token) a.offset?.let(offsets::add)
            }
            summary.stores.forEach { s ->
                if (s.targetField == token || s.value == token) s.offset?.let(offsets::add)
            }
            summary.loads.forEach { l ->
                if (l.target == token || l.sourceField == token) l.offset?.let(offsets::add)
            }
            summary.calls.forEach { c ->
                val callOffset = c.callOffset ?: return@forEach
                if (c.receiver?.trim() == token) offsets.add(callOffset)
                if (c.result?.trim() == token) offsets.add(callOffset)
                if (c.args.any { it.trim() == token }) offsets.add(callOffset)
            }

            if (offsets.isNotEmpty()) {
                val chosen = if (preferLatest) offsets.maxOrNull() else offsets.minOrNull()
                if (chosen != null) return file to chosen
            }
        }

        return file to loc.startOffset
    }

    val tree =
        object : JTree(model) {
            override fun getToolTipText(event: MouseEvent): String? {
                val path = getPathForLocation(event.x, event.y) ?: return null
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
                val step = node.userObject as? CallChainsDialog.Step ?: return null
                if (step !is CallChainsDialog.MethodStep) return null
                val summary = methodSummaries?.summaries?.get(step.ref) ?: return null

                val chainNode = node.parent as? DefaultMutableTreeNode
                val chainSteps =
                    chainNode
                        ?.children()
                        ?.toList()
                        ?.mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? CallChainsDialog.Step }
                        .orEmpty()
                val idx = chainSteps.indexOf(step)
                val next =
                    if (idx >= 0 && idx < chainSteps.lastIndex) {
                        chainSteps[idx + 1]
                    } else {
                        null
                    }
                val nextRef = (next as? CallChainsDialog.MethodStep)?.ref
                val callsToNext =
                    if (nextRef != null) {
                        summary.calls.filter { it.calleeId == nextRef.id }
                    } else {
                        emptyList()
                    }

                fun renderToken(t: String?): String = t?.takeIf { it.isNotBlank() } ?: "UNKNOWN"

	                val header =
	                    buildString {
	                        append("<b>").append(shortMethod(step.ref)).append("</b><br/>")
	                        append("reads=").append(summary.fieldsRead.size)
	                        append(" writes=").append(summary.fieldsWritten.size)
	                        append(" aliases=").append(summary.aliases.size)
                        append(" stores=").append(summary.stores.size)
                        append(" loads=").append(summary.loads.size)
                        append(" calls=").append(summary.calls.size)
                        if (nextRef != null) {
                            append(" (to next=").append(callsToNext.size).append(")")
                        }
                    }

                val aliases =
                    summary.aliases.take(6).joinToString("<br/>") { a ->
                        val loc = a.offset?.let { " @${it}" }.orEmpty()
                        "${renderToken(a.left)} &lt;-&gt; ${renderToken(a.right)}$loc"
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
                    if (summary.aliases.isNotEmpty()) {
                        append("<br/><b>aliases</b><br/>").append(aliases)
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
                rowHeight = 0
                selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
                ToolTipManager.sharedInstance().registerComponent(this)
                cellRenderer =
                    CallChainsWrappingTreeCellRenderer(
                        project = project,
                        graph = graph,
                        methodSummaries = methodSummaries,
                        resolveValueFlowEdgeLocation = ::resolveValueFlowEdgeLocation,
                    )
                expandRow(0)
                addMouseListener(
                    object : MouseAdapter() {
                        private fun shouldNavigate(e: MouseEvent): Boolean = e.isPopupTrigger

                        private fun navigate(e: MouseEvent) {
                            if (!shouldNavigate(e)) return

                            val path = getPathForLocation(e.x, e.y) ?: return
                            selectionPath = path

                            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                            val step = node.userObject as? CallChainsDialog.Step ?: return
                            val (file, offset) =
                                when (step) {
                                    is CallChainsDialog.ValueFlowGroupStep -> null to null
                                    is CallChainsDialog.ValueFlowEdgeStep -> resolveValueFlowEdgeLocation(step.edge)
                                    is CallChainsDialog.ValueFlowRootStep -> {
                                        resolveValueFlowNodeLocation(step.node, preferLatest = false)
                                    }
                                    is CallChainsDialog.ValueFlowSinkStep -> {
                                        resolveValueFlowNodeLocation(step.node, preferLatest = true)
                                    }

                                    else -> {
                                        val chainNode = node.parent as? DefaultMutableTreeNode
                                        val chainSteps =
                                            chainNode
                                                ?.children()
                                                ?.toList()
                                                ?.mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? CallChainsDialog.Step }
                                                .orEmpty()

                                        val idx = chainSteps.indexOf(step)
                                        val callSite =
                                            when {
                                                idx < 0 || idx >= chainSteps.lastIndex -> null
                                                else -> {
                                                    val next = chainSteps[idx + 1]
                                                    when (step) {
                                                        is CallChainsDialog.MethodStep ->
                                                            when (next) {
                                                                is CallChainsDialog.MethodStep -> graph.callSiteFor(CallEdge(caller = step.ref, callee = next.ref))
                                                                is CallChainsDialog.SinkStep -> CallSiteLocation(file = next.file, startOffset = next.startOffset)
                                                                else -> null
                                                            }

                                                        is CallChainsDialog.SinkStep -> null
                                                        else -> null
                                                    }
                                                }
                                            }

                                        val file =
                                            callSite?.file
                                                ?: when (step) {
                                                    is CallChainsDialog.MethodStep -> graph.methods[step.ref]?.file
                                                    is CallChainsDialog.SinkStep -> step.file
                                                    else -> null
                                                }
                                        val offset =
                                            callSite?.startOffset
                                                ?: when (step) {
                                                    is CallChainsDialog.MethodStep -> graph.methods[step.ref]?.startOffset
                                                    is CallChainsDialog.SinkStep -> step.startOffset
                                                    else -> null
                                                }
                                        file to offset
                                    }
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

                        override fun mousePressed(e: MouseEvent) = navigate(e)

                        override fun mouseReleased(e: MouseEvent) = navigate(e)
                    }
            )
        }

    val rootNode = model.root as? DefaultMutableTreeNode
    if (rootNode != null) {
        for (i in 0 until rootNode.childCount) {
            val sectionNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            tree.expandPath(javax.swing.tree.TreePath(sectionNode.path))
            for (j in 0 until sectionNode.childCount) {
                val child = sectionNode.getChildAt(j) as? DefaultMutableTreeNode ?: continue
                if (child.userObject !is GroupNode) continue
                tree.expandPath(javax.swing.tree.TreePath(child.path))
            }
        }
    }

    return tree
}

private class CallChainsWrappingTreeCellRenderer(
    private val project: Project,
    private val graph: CallGraph,
    private val methodSummaries: MethodSummaryIndex?,
    private val resolveValueFlowEdgeLocation: (ValueFlowEdge) -> Pair<VirtualFile?, Int?>,
) : TreeCellRenderer {

    private val label =
        JLabel().apply {
            isOpaque = true
            border = BorderFactory.createEmptyBorder(4, 2, 4, 2)
        }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): JComponent {
        val node = value as? DefaultMutableTreeNode
        val obj = node?.userObject

        val fg =
            if (selected) UIManager.getColor("Tree.selectionForeground") else UIManager.getColor("Tree.textForeground")
        val bg =
            if (selected) UIManager.getColor("Tree.selectionBackground") else UIManager.getColor("Tree.textBackground")
        label.foreground = fg
        label.background = bg
        label.font = tree.font

        val leftIndent = (UIManager.get("Tree.leftChildIndent") as? Int) ?: 7
        val rightIndent = (UIManager.get("Tree.rightChildIndent") as? Int) ?: 7
        val indentPerLevel = leftIndent + rightIndent
        val depth =
            ((node?.level ?: 0) - if (tree.isRootVisible) 0 else 1)
                .coerceAtLeast(0)
        val xOffset = depth * indentPerLevel
        val availableWidth =
            ((tree.visibleRect.width.takeIf { it > 0 } ?: tree.width) - xOffset - 24)
                .coerceAtLeast(240)

        val htmlBody =
            when (obj) {
                is SectionNode -> "<b>${escapeHtml(obj.header)}</b>"
                is GroupNode -> {
                    val title =
                        when (obj.kind) {
                            CallChainsDialog.GroupKind.TAINT -> SecruxBundle.message("dialog.callChains.group.taint")
                            CallChainsDialog.GroupKind.CALL -> SecruxBundle.message("dialog.callChains.group.call")
                        }
                    "<b>${escapeHtml(SecruxBundle.message("dialog.callChains.groupWithCount", title, obj.chainsCount))}</b>"
                }
                is ChainNode -> {
                    val key =
                        when (obj.kind) {
                            CallChainsDialog.GroupKind.TAINT -> "dialog.callChains.taintChain"
                            CallChainsDialog.GroupKind.CALL ->
                                if (obj.taintVerified) {
                                    "dialog.callChains.callChainWithTaint"
                                } else {
                                    "dialog.callChains.callChain"
                                }
                        }
                    "<b>${escapeHtml(SecruxBundle.message(key, obj.index, obj.stepsCount))}</b>"
                }
                is InfoNode -> "<span style='opacity:0.75;'>${escapeHtml(obj.text)}</span>"
                is CallChainsDialog.ValueFlowGroupStep -> {
                    val parts = obj.label.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                    when {
                        parts.isEmpty() -> ""
                        parts.size == 1 -> "<b>${escapeHtml(parts.single())}</b>"
                        else -> {
                            val title = "<b>${escapeHtml(parts.first())}</b>"
                            val body =
                                parts
                                    .drop(1)
                                    .joinToString("<br/>") { escapeHtml(it) }
                            "$title<br/><span style='opacity:0.75;'>$body</span>"
                        }
                    }
                }
                is CallChainsDialog.ValueFlowRootStep -> {
                    "<b>root</b>: <b>${escapeHtml(shortMethod(obj.node.method))}</b> <code>${escapeHtml(prettyValueFlowToken(obj.node.token))}</code>"
                }
                is CallChainsDialog.ValueFlowSinkStep -> {
                    "<b>sink</b>: <b>${escapeHtml(shortMethod(obj.node.method))}</b> <code>${escapeHtml(prettyValueFlowToken(obj.node.token))}</code>"
                }
                is CallChainsDialog.ValueFlowEdgeStep -> {
                    val edge = obj.edge
                    val source = edge.to
                    val target = edge.from
                    val loc = edge.offset?.let { "@$it" }
                    val details = edge.details?.trim().takeIf { !it.isNullOrBlank() }

                    fun methodHtml(m: MethodRef): String = "<b>${escapeHtml(shortMethod(m))}</b>"
                    fun tokenHtml(t: String): String = "<code>${escapeHtml(prettyValueFlowToken(t))}</code>"

                    fun paramIndex1Based(t: String): Int? {
                        if (!t.startsWith("PARAM:")) return null
                        val idx = t.substringAfter("PARAM:", missingDelimiterValue = "").toIntOrNull() ?: return null
                        return (idx + 1).takeIf { it >= 1 }
                    }

                    fun slotHtml(t: String): String {
                        val raw = t.trim()
                        if (raw.isBlank()) return tokenHtml(raw)
                        if (raw == "THIS") return SecruxBundle.message("dialog.callChains.vf.slot.receiver")
                        if (raw == "RET") return SecruxBundle.message("dialog.callChains.vf.slot.return")
                        val p = paramIndex1Based(raw)
                        if (p != null) return SecruxBundle.message("dialog.callChains.vf.slot.param", p)
                        return tokenHtml(raw)
                    }

                    val sourceMethod = methodHtml(source.method)
                    val targetMethod = methodHtml(target.method)
                    val sourceSlot = slotHtml(source.token)
                    val targetSlot = slotHtml(target.token)

                    val explainHtml =
                        when (edge.kind) {
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_ARG ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.callArg",
                                    sourceMethod,
                                    sourceSlot,
                                    targetMethod,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_RECEIVER ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.callReceiver",
                                    sourceMethod,
                                    sourceSlot,
                                    targetMethod,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_RETURN ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.callReturn",
                                    sourceMethod,
                                    sourceSlot,
                                    targetMethod,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_RESULT ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.callResult",
                                    sourceMethod,
                                    sourceSlot,
                                    targetMethod,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_APPROX ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.callApprox",
                                    sourceMethod,
                                    sourceSlot,
                                    targetMethod,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.COPY ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.copy",
                                    sourceMethod,
                                    sourceSlot,
                                    targetMethod,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.LOAD ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.load",
                                    sourceMethod,
                                    sourceSlot,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.STORE ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.store",
                                    sourceMethod,
                                    sourceSlot,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.HEAP_LOAD,
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.HEAP_STORE ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.heap",
                                    sourceMethod,
                                    sourceSlot,
                                    targetMethod,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.INJECT ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.inject",
                                    sourceMethod,
                                    sourceSlot,
                                    targetMethod,
                                    targetSlot,
                                )
                            com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.ALIAS ->
                                SecruxBundle.message(
                                    "dialog.callChains.vf.alias",
                                    sourceMethod,
                                    tokenHtml(source.token),
                                    tokenHtml(target.token),
                                )
                        }

                    val meta =
                        buildList {
                            loc?.let(::add)
                            details?.let(::add)
                        }.joinToString(" · ")
                    val lowSignal =
                        edge.kind == com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.ALIAS ||
                            edge.kind == com.securitycrux.secrux.intellij.valueflow.ValueFlowEdgeKind.CALL_APPROX ||
                            source.token.startsWith("UNKNOWN") ||
                            target.token.startsWith("UNKNOWN")

                    buildString {
                        if (lowSignal) append("<span style='opacity:0.75;'>")
                        append(explainHtml)
                        if (meta.isNotBlank()) append("<br/><span style='opacity:0.75;'>").append(escapeHtml(meta)).append("</span>")
                        if (lowSignal) append("</span>")
                    }
                }
                is CallChainsDialog.MethodStep -> {
                    val chainNode = node.parent as? DefaultMutableTreeNode
                    val chainSteps =
                        chainNode
                            ?.children()
                            ?.toList()
                            ?.mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? CallChainsDialog.Step }
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
                            is CallChainsDialog.MethodStep -> graph.edgeKinds[CallEdge(caller = obj.ref, callee = next.ref)]
                            else -> null
                        }
                    val edgeTag = edgeKind?.let { " [$it]" }.orEmpty()

                    val summary = methodSummaries?.summaries?.get(obj.ref)
                    val stats =
                        if (summary != null) {
                            SecruxBundle.message(
                                "dialog.callChains.valueFlowStats",
                                summary.aliases.size,
                                summary.stores.size,
                                summary.loads.size,
                                summary.calls.size,
                            )
                        } else {
                            ""
                        }

                    buildString {
                        append("<b>").append(escapeHtml(shortMethod(obj.ref))).append("</b>").append(escapeHtml(edgeTag))
                        if (stats.isNotBlank()) append("<br/><span style='opacity:0.75;'>").append(escapeHtml(stats)).append("</span>")
                    }
                }
                is CallChainsDialog.SinkStep -> {
                    "<b>${escapeHtml(obj.label)}</b>"
                }
                else -> escapeHtml(obj?.toString().orEmpty())
            }

        label.text = "<html><div style='width:${availableWidth}px; line-height:1.35;'>$htmlBody</div></html>"
        return label
    }

    private fun shortMethod(ref: MethodRef): String {
        val clazz = ref.classFqn.substringAfterLast('.')
        return "$clazz.${ref.name}"
    }

    private fun prettyValueFlowToken(token: String, depth: Int = 0): String {
        val raw = token.trim()
        if (raw.isBlank()) return raw
        if (depth > 3) return raw

        fun shortClassFqn(fqn: String): String = fqn.trim().substringAfterLast('.')

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
            raw.startsWith("BEAN:") -> "bean(${shortClassFqn(raw.removePrefix("BEAN:"))})"
            raw.startsWith("INJECT:") -> "inject(${shortClassFqn(raw.removePrefix("INJECT:"))})"
            raw.startsWith("ALLOC:") -> {
                val suffix = raw.removePrefix("ALLOC:")
                val type = suffix.substringBefore('@', missingDelimiterValue = suffix).trim()
                val at = suffix.substringAfter('@', missingDelimiterValue = "").trim()
                val atSuffix = at.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                "alloc(${shortClassFqn(type)}$atSuffix)"
            }
            raw == "UNKNOWN" -> "unknown"
            raw.startsWith("UNKNOWN:") -> "unknown(${raw.removePrefix("UNKNOWN:")})"
            raw.startsWith("THIS:") -> {
                val suffix = raw.removePrefix("THIS:")
                val (owner, field) = parseFieldSuffix(suffix)
                when {
                    field != null -> "this.$field"
                    owner != null -> "this.${shortClassFqn(owner)}"
                    else -> raw
                }
            }
            raw.startsWith("STATIC:") -> {
                val suffix = raw.removePrefix("STATIC:")
                val (owner, field) = parseFieldSuffix(suffix)
                when {
                    owner != null && field != null -> "${shortClassFqn(owner)}.$field"
                    owner != null -> shortClassFqn(owner)
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
}

private fun escapeHtml(s: String): String {
    if (s.isEmpty()) return s
    val out = StringBuilder(s.length + 16)
    for (c in s) {
        when (c) {
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '&' -> out.append("&amp;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(c)
        }
    }
    return out.toString()
}

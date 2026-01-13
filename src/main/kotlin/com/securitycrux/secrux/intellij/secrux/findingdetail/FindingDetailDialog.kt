package com.securitycrux.secrux.intellij.secrux.findingdetail

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.securitycrux.secrux.intellij.callgraph.CallGraphService
import com.securitycrux.secrux.intellij.callgraph.MethodRef
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.valueflow.MethodSummary
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.border.TitledBorder

class FindingDetailDialog(
    private val project: Project,
    private val model: FindingDetailModel,
) : DialogWrapper(project) {

    private val codeViewerFileType: FileType =
        FileTypeManager.getInstance().getFileTypeByExtension("txt")

    private class CodeViewerField(
        text: String,
        project: Project,
        fileType: FileType,
    ) : EditorTextField(EditorFactory.getInstance().createDocument(text), project, fileType) {
        override fun createEditor(): EditorEx {
            val editor = super.createEditor()
            editor.setEmbeddedIntoDialogWrapper(true)
            return editor
        }

        init {
            isOneLineMode = false
            isViewer = true
            setCaretPosition(0)
        }
    }

    init {
        title = SecruxBundle.message("dialog.findingDetail.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        tabs.addTab(SecruxBundle.message("dialog.findingDetail.tab.overview"), buildOverviewTab())
        tabs.addTab(SecruxBundle.message("dialog.findingDetail.tab.callChains"), buildCallChainsTab())
        tabs.addTab(SecruxBundle.message("dialog.findingDetail.tab.snippet"), buildSnippetTab())
        tabs.addTab(SecruxBundle.message("dialog.findingDetail.tab.enrichment"), buildEnrichmentTab())
        return tabs.apply { preferredSize = Dimension(980, 640) }
    }

    private fun buildOverviewTab(): JComponent {
        val panel =
            JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(6))).apply {
                border = JBUI.Borders.empty(10)
            }
        panel.add(JBLabel("findingId: ${model.findingId}"))
        model.ruleId?.let { panel.add(JBLabel("ruleId: $it")) }
        model.sourceEngine?.let { panel.add(JBLabel("sourceEngine: $it")) }
        model.severity?.let { panel.add(JBLabel("severity: $it")) }
        model.status?.let { panel.add(JBLabel("status: $it")) }
        panel.add(JBLabel("task: ${model.taskName.orEmpty()} (${model.taskId.orEmpty()})"))
        panel.add(JBLabel("project: ${model.projectName.orEmpty()} (${model.projectId.orEmpty()})"))
        panel.add(JBLabel("location: ${model.locationPath.orEmpty()}:${model.locationLine ?: ""}"))
        model.introducedBy?.let { panel.add(JBLabel("introducedBy: $it")) }
        model.createdAt?.let { panel.add(JBLabel("createdAt: $it")) }

        val review = model.review
        if (review != null) {
            panel.add(JBLabel("review: ${review.reviewType.orEmpty()}"))
            panel.add(JBLabel("reviewer: ${review.reviewer.orEmpty()}"))
            panel.add(JBLabel("verdict: ${review.verdict.orEmpty()}"))
            panel.add(JBLabel("confidence: ${review.confidence.orEmpty()}"))
            review.summaryZh?.takeIf { it.isNotBlank() }?.let { panel.add(JBLabel("summary: $it")) }
            review.fixHintZh?.takeIf { it.isNotBlank() }?.let { panel.add(JBLabel("fixHint: $it")) }
        }

        return JBScrollPane(panel)
    }

    private fun buildCallChainsTab(): JComponent {
        if (model.callChains.isEmpty()) {
            return JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(12)
                add(JBLabel(SecruxBundle.message("dialog.findingDetail.empty.noCallChains")), BorderLayout.NORTH)
            }
        }

        val chainsTabs = JBTabbedPane()
        for ((idx, chain) in model.callChains.withIndex()) {
            val container =
                JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(10))).apply {
                    border = JBUI.Borders.empty(10)
                }
            for ((stepIdx, step) in chain.steps.withIndex()) {
                val next = chain.steps.getOrNull(stepIdx + 1)
                container.add(buildCallChainStepPanel(stepIndex = stepIdx + 1, step = toDisplayStep(step), nextStep = next))
            }
            chainsTabs.addTab(
                SecruxBundle.message("dialog.callChains.chain", idx + 1, chain.steps.size),
                JBScrollPane(container),
            )
        }
        return chainsTabs.apply { preferredSize = Dimension(900, 520) }
    }

    private fun buildCallChainStepPanel(stepIndex: Int, step: DisplayStep, nextStep: CallChainStepModel?): JComponent {
        val role = step.raw.role?.takeIf { it.isNotBlank() }?.uppercase()
        val file = step.displayFile ?: step.raw.file
        val line = step.displayLine ?: step.raw.line
        val locationTitle =
            buildString {
                file?.takeIf { it.isNotBlank() }?.let { p ->
                    append(p)
                    line?.let { ln -> append(":").append(ln) }
                }
            }.ifBlank { step.raw.nodeId }

        val headerText =
            buildString {
                append(stepIndex).append(") ")
                role?.let { r -> append("[").append(r).append("] ") }
                append(step.raw.label)
            }

        val openButton =
            JButton(SecruxBundle.message("dialog.callChains.open")).apply {
                isFocusable = false
                addActionListener { openStepLocation(step.raw) }
            }

        val header =
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(JBLabel(headerText), BorderLayout.CENTER)
                add(openButton, BorderLayout.EAST)
            }

        val code =
            step.displaySnippet?.takeIf { it.isNotBlank() }
                ?: step.raw.snippet?.takeIf { it.isNotBlank() }
                ?: step.raw.label

        val editor = CodeViewerField(code, project, fileTypeForPath(file))

        val summaryPanel = buildValueFlowSummaryPanel(step = step.raw, nextStep = nextStep)

        val body =
            JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(6))).apply {
                add(editor)
                summaryPanel?.let { add(it) }
            }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(TitledBorder(locationTitle), JBUI.Borders.empty(6))
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    private fun buildValueFlowSummaryPanel(step: CallChainStepModel, nextStep: CallChainStepModel?): JComponent? {
        val methodRef = extractMethodRef(step) ?: return null
        val nextMethodRef = nextStep?.let { extractMethodRef(it) }

        val index = CallGraphService.getInstance(project).getLastMethodSummaries()
        if (index == null) {
            return JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.compound(TitledBorder(SecruxBundle.message("dialog.callChains.valueFlowSummary")), JBUI.Borders.empty(6))
                add(JBLabel(SecruxBundle.message("dialog.callChains.valueFlowSummaryNotBuilt")), BorderLayout.NORTH)
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))
            }
        }

        val summary = index.summaries[methodRef]
        if (summary == null) {
            return JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.compound(TitledBorder(SecruxBundle.message("dialog.callChains.valueFlowSummary")), JBUI.Borders.empty(6))
                add(JBLabel(SecruxBundle.message("dialog.callChains.valueFlowSummaryMissing", methodRef.id)), BorderLayout.NORTH)
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))
            }
        }

        val text = formatMethodSummaryText(methodRef = methodRef, summary = summary, nextMethodRef = nextMethodRef)
        val editor = CodeViewerField(text, project, codeViewerFileType)
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.compound(TitledBorder(SecruxBundle.message("dialog.callChains.valueFlowSummary")), JBUI.Borders.empty(6))
            add(editor, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(260))
        }
    }

    private fun extractMethodRef(step: CallChainStepModel): MethodRef? {
        val node = step.nodeId.trim().takeIf { it.isNotBlank() }
        if (node != null) {
            MethodRef.fromIdOrNull(node)?.let { return it }
        }
        val label = step.label.trim().takeIf { it.isNotBlank() }
        if (label != null) {
            MethodRef.fromIdOrNull(label)?.let { return it }
        }
        return null
    }

    private fun formatMethodSummaryText(methodRef: MethodRef, summary: MethodSummary, nextMethodRef: MethodRef?): String {
        fun renderToken(token: String?): String = token?.takeIf { it.isNotBlank() } ?: "UNKNOWN"

        fun <T> appendList(
            out: StringBuilder,
            title: String,
            items: List<T>,
            limit: Int = 40,
            render: (T) -> String,
        ) {
            out.append(title).append(" (").append(items.size).append(")").append('\n')
            if (items.isEmpty()) return
            val capped = items.take(limit)
            for (item in capped) {
                out.append("  - ").append(render(item)).append('\n')
            }
            if (items.size > limit) {
                out.append("  ... +").append(items.size - limit).append('\n')
            }
        }

        val sb = StringBuilder()
        sb.append("method: ").append(methodRef.id).append('\n')
        sb.append("reads=").append(summary.fieldsRead.size)
            .append(" writes=").append(summary.fieldsWritten.size)
            .append(" stores=").append(summary.stores.size)
            .append(" loads=").append(summary.loads.size)
            .append(" calls=").append(summary.calls.size)
            .append('\n')

        val reads = summary.fieldsRead.toList().sorted()
        val writes = summary.fieldsWritten.toList().sorted()
        appendList(sb, "reads", reads, limit = 30) { it }
        appendList(sb, "writes", writes, limit = 30) { it }

        appendList(sb, "stores", summary.stores) { s ->
            val loc = s.offset?.let { " @${it}" }.orEmpty()
            "${s.targetField} <= ${renderToken(s.value)}$loc"
        }
        appendList(sb, "loads", summary.loads) { l ->
            val loc = l.offset?.let { " @${it}" }.orEmpty()
            "${renderToken(l.target)} <= ${l.sourceField}$loc"
        }

        val callsToNext =
            if (nextMethodRef != null) {
                summary.calls.filter { it.calleeId == nextMethodRef.id }
            } else {
                emptyList()
            }
        if (nextMethodRef != null) {
            appendList(sb, "calls(to next: ${nextMethodRef.id})", callsToNext, limit = 20) { c ->
                val loc = c.callOffset?.let { " @${it}" }.orEmpty()
                val recv = c.receiver?.let(::renderToken)?.let { "recv=$it " }.orEmpty()
                val args = c.args.joinToString(",") { renderToken(it) }
                val res = c.result?.let(::renderToken)?.let { "ret=$it" }.orEmpty()
                "$recv$args ${if (res.isNotBlank()) res else ""}${loc}"
                    .trim()
            }
        }

        appendList(sb, "calls", summary.calls, limit = 30) { c ->
            val callee = c.calleeId ?: "<unresolved>"
            val loc = c.callOffset?.let { " @${it}" }.orEmpty()
            val recv = c.receiver?.let(::renderToken)?.let { "recv=$it " }.orEmpty()
            val args = c.args.joinToString(",") { renderToken(it) }
            val res = c.result?.let(::renderToken)?.let { "ret=$it " }.orEmpty()
            "$callee: $recv$args ${res.trim()}$loc".trim()
        }

        return sb.toString().trimEnd()
    }

    private fun openStepLocation(step: CallChainStepModel) {
        val path = step.file?.trim().takeIf { !it.isNullOrBlank() } ?: return
        val base = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath("$base/$path")
        if (vf == null) {
            Messages.showInfoMessage(project, SecruxBundle.message("message.callChainsNoSource"), SecruxBundle.message("dialog.title"))
            return
        }
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        val rawLine = (step.line ?: 1).coerceAtLeast(1)
        val rawCol = (step.startColumn ?: 1).coerceAtLeast(1)
        val preferred = if (doc != null) resolvePreferredLine(doc, rawLine, maxLookahead = 40) ?: rawLine else rawLine
        val (line, column) = if (preferred != rawLine) preferred to 1 else preferred to rawCol
        OpenFileDescriptor(project, vf, line - 1, (column - 1).coerceAtLeast(0)).navigate(true)
    }

    private fun buildSnippetTab(): JComponent {
        val snippet = model.snippet
        if (snippet == null || snippet.code.isBlank()) {
            return JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(12)
                add(JBLabel(SecruxBundle.message("dialog.findingDetail.empty.noSnippet")), BorderLayout.NORTH)
            }
        }
        val title =
            buildString {
                append(snippet.path)
                append(" (").append(snippet.startLine).append("-").append(snippet.endLine).append(")")
                snippet.highlightedLine?.let { hl -> append(" highlight=").append(hl) }
            }
        return buildCodePanel(title = title, code = snippet.code, filePath = snippet.path)
    }

    private fun buildEnrichmentTab(): JComponent {
        val enrichment = model.enrichment
        if (enrichment == null) {
            return JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(12)
                add(JBLabel(SecruxBundle.message("dialog.findingDetail.empty.noEnrichment")), BorderLayout.NORTH)
            }
        }

        val container =
            JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(10))).apply {
                border = JBUI.Borders.empty(10)
            }

        container.add(JBLabel("engine: ${enrichment.engine}"))
        enrichment.generatedAt?.takeIf { it.isNotBlank() }?.let { container.add(JBLabel("generatedAt: $it")) }

        val isZh = Locale.getDefault().language.lowercase().startsWith("zh")
        if (enrichment.blocks.isNotEmpty()) {
            for (block in enrichment.blocks) {
                container.add(buildEnrichmentBlockPanel(block = block, isZh = isZh))
            }
            if (enrichment.externalSymbols.isNotEmpty()) {
                container.add(buildStringListPanel(title = "externalSymbols", items = enrichment.externalSymbols))
            }
            if (enrichment.fieldDefinitions.isNotEmpty()) {
                container.add(buildStringListPanel(title = "fieldDefinitions", items = enrichment.fieldDefinitions))
            }
            return JBScrollPane(container)
        }

        enrichment.primary?.let { primary ->
            val method = primary.method
            val title =
                buildString {
                    append(SecruxBundle.message("dialog.findingDetail.enrichment.primary"))
                    primary.path?.let { p ->
                        append(" - ").append(p)
                        primary.line?.let { ln -> append(":").append(ln) }
                    }
                    method?.signature?.takeIf { it.isNotBlank() }?.let { sig -> append(" - ").append(sig) }
                    if (method?.startLine != null && method.endLine != null) {
                        append(" (").append(method.startLine).append("-").append(method.endLine).append(")")
                    }
                }

            val code = method?.text.orEmpty()
            container.add(buildCodePanel(title = title, code = code, filePath = primary.path))
            container.add(buildLineListPanel(title = "conditions", items = primary.conditions))
            container.add(buildLineListPanel(title = "invocations", items = primary.invocations))
        }

        for (node in enrichment.nodeMethods) {
            val method = node.method
            val title =
                buildString {
                    append(SecruxBundle.message("dialog.findingDetail.enrichment.nodeMethod"))
                    if (node.chainIndex != null && node.stepIndex != null) {
                        append(" - Chain ").append(node.chainIndex).append(" Step ").append(node.stepIndex)
                    }
                    append(" - ").append(node.label)
                    node.path?.let { p ->
                        append(" @ ").append(p)
                        node.line?.let { ln -> append(":").append(ln) }
                    }
                    method?.signature?.takeIf { it.isNotBlank() }?.let { sig -> append(" - ").append(sig) }
                }
            val code = method?.text.orEmpty()
            container.add(buildCodePanel(title = title, code = code, filePath = node.path))
            container.add(buildLineListPanel(title = "conditions", items = node.conditions))
            container.add(buildLineListPanel(title = "invocations", items = node.invocations))
        }

        if (enrichment.externalSymbols.isNotEmpty()) {
            container.add(buildStringListPanel(title = "externalSymbols", items = enrichment.externalSymbols))
        }
        if (enrichment.fieldDefinitions.isNotEmpty()) {
            container.add(buildStringListPanel(title = "fieldDefinitions", items = enrichment.fieldDefinitions))
        }

        return JBScrollPane(container)
    }

    private fun buildEnrichmentBlockPanel(block: EnrichmentBlockModel, isZh: Boolean): JComponent {
        val reasonTitle =
            if (isZh) block.reason?.titleZh?.takeIf { it.isNotBlank() } else block.reason?.titleEn?.takeIf { it.isNotBlank() }
        val reasonDetails =
            if (isZh) block.reason?.detailsZh?.takeIf { it.isNotBlank() } else block.reason?.detailsEn?.takeIf { it.isNotBlank() }

        val outerTitle =
            buildString {
                append(reasonTitle ?: block.reason?.code ?: block.kind)
                val rel = block.related
                if (rel?.chainIndex != null && rel.stepIndex != null) {
                    append(" - Chain ").append(rel.chainIndex).append(" Step ").append(rel.stepIndex)
                }
                rel?.role?.takeIf { it.isNotBlank() }?.let { r -> append(" [").append(r.uppercase()).append("]") }
                rel?.label?.takeIf { it.isNotBlank() }?.let { lbl -> append(" - ").append(lbl) }
            }

        val panel =
            JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(8))).apply {
                border = JBUI.Borders.compound(TitledBorder(outerTitle), JBUI.Borders.empty(8))
            }

        reasonDetails?.let { panel.add(JBLabel(it)) }

        val method = block.method
        val code = method?.text.orEmpty()
        if (code.isNotBlank()) {
            val methodTitle =
                buildString {
                    block.filePath?.takeIf { it.isNotBlank() }?.let { p ->
                        append(p)
                        if (block.startLine != null && block.endLine != null) {
                            append(" (").append(block.startLine).append("-").append(block.endLine).append(")")
                        }
                    }
                    method?.signature?.takeIf { it.isNotBlank() }?.let { sig ->
                        if (isNotEmpty()) append(" - ")
                        append(sig)
                    }
                }.ifBlank { outerTitle }
            panel.add(buildCodePanel(title = methodTitle, code = code, filePath = block.filePath))
        }

        if (block.conditions.isNotEmpty()) {
            panel.add(buildLineListPanel(title = "conditions", items = block.conditions))
        }
        if (block.invocations.isNotEmpty()) {
            panel.add(buildLineListPanel(title = "invocations", items = block.invocations))
        }

        return panel
    }

    private fun buildCodePanel(
        title: String,
        code: String,
        filePath: String?,
    ): JComponent {
        val editor = CodeViewerField(code, project, codeViewerFileType)
        val wrapper =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.compound(TitledBorder(title), JBUI.Borders.empty(6))
                add(editor, BorderLayout.CENTER)
            }
        return wrapper
    }

    private fun buildLineListPanel(title: String, items: List<EnrichedLineText>): JComponent {
        if (items.isEmpty()) return JPanel()
        val content =
            items.joinToString("\n") { item ->
                val prefix = item.line?.let { "$it: " } ?: ""
                prefix + item.text.trim()
            }
        val editor = CodeViewerField(content, project, codeViewerFileType)
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(TitledBorder(title), JBUI.Borders.empty(6))
            add(editor, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(160))
        }
    }

    private fun buildStringListPanel(title: String, items: List<String>): JComponent {
        val content = items.joinToString("\n") { it.trim() }.trim()
        if (content.isBlank()) return JPanel()
        val editor = CodeViewerField(content, project, codeViewerFileType)
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(TitledBorder(title), JBUI.Borders.empty(6))
            add(editor, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))
        }
    }

    private fun fileTypeForPath(path: String?): FileType {
        return codeViewerFileType
    }

    private data class DisplayStep(
        val raw: CallChainStepModel,
        val displayFile: String?,
        val displayLine: Int?,
        val displaySnippet: String?,
    )

    private fun toDisplayStep(step: CallChainStepModel): DisplayStep {
        val snippet = step.snippet?.trim()
        val looksLikeDocComment =
            snippet != null && (snippet.startsWith("/**") || snippet.startsWith("/*") || snippet.startsWith("*"))
        if (!looksLikeDocComment) {
            return DisplayStep(raw = step, displayFile = step.file, displayLine = step.line, displaySnippet = step.snippet)
        }

        val filePath = step.file?.trim().takeIf { !it.isNullOrBlank() } ?: return DisplayStep(raw = step, displayFile = step.file, displayLine = step.line, displaySnippet = step.snippet)
        val base = project.basePath ?: return DisplayStep(raw = step, displayFile = step.file, displayLine = step.line, displaySnippet = step.snippet)
        val vf = LocalFileSystem.getInstance().findFileByPath("$base/$filePath") ?: return DisplayStep(raw = step, displayFile = step.file, displayLine = step.line, displaySnippet = step.snippet)

        val document = FileDocumentManager.getInstance().getDocument(vf) ?: return DisplayStep(raw = step, displayFile = step.file, displayLine = step.line, displaySnippet = step.snippet)
        val rawLine = step.line ?: return DisplayStep(raw = step, displayFile = step.file, displayLine = step.line, displaySnippet = step.snippet)
        val preferredLine = resolvePreferredLine(document, rawLine, maxLookahead = 40) ?: rawLine
        val text = getLineText(document, preferredLine)
        return DisplayStep(raw = step, displayFile = step.file, displayLine = preferredLine, displaySnippet = text)
    }

    private fun resolvePreferredLine(
        document: com.intellij.openapi.editor.Document,
        startLine: Int,
        maxLookahead: Int,
    ): Int? {
        if (startLine <= 0 || startLine > document.lineCount) return null
        val safeLookahead = maxLookahead.coerceIn(0, 200)
        val startText = getLineText(document, startLine)
        if (!isCommentLine(startText)) return startLine

        var firstNonComment: Int? = null
        val end = kotlin.math.min(document.lineCount, startLine + safeLookahead)
        for (line in startLine..end) {
            val text = getLineText(document, line)
            val trimmed = text.trim()
            if (trimmed.isBlank()) continue
            if (isCommentLine(trimmed)) continue
            if (firstNonComment == null) firstNonComment = line
            if (!trimmed.startsWith("@")) return line
        }
        return firstNonComment
    }

    private fun isCommentLine(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isBlank()) return false
        return t.startsWith("/**") ||
            t.startsWith("/*") ||
            t.startsWith("*") ||
            t.startsWith("*/") ||
            t.startsWith("//") ||
            t.startsWith("#") ||
            t.startsWith("<!--")
    }

    private fun getLineText(document: com.intellij.openapi.editor.Document, line: Int): String {
        if (line <= 0 || line > document.lineCount) return ""
        val idx = line - 1
        val start = document.getLineStartOffset(idx)
        val end = document.getLineEndOffset(idx)
        return document.charsSequence.subSequence(start, end).toString().take(400)
    }
}

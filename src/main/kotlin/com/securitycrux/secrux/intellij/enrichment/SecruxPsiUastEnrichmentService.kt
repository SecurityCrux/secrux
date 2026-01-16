package com.securitycrux.secrux.intellij.enrichment

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UForExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UWhileExpression
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

class SecruxPsiUastEnrichmentService(
    private val project: Project
) {
    private companion object {
        const val MAX_NODE_METHODS = 80
        const val MAX_METHOD_EXCERPT_LINES = 160
        const val MAX_METHOD_EXCERPT_CHARS = 40_000
        const val MAX_METHOD_LINE_CHARS = 800
        const val MAX_INVOCATIONS = 200
        const val MAX_CONDITIONS = 200
        const val MAX_CALL_ARG_IDENTIFIERS = 6
        const val MAX_CALL_TEXT_CHARS = 800
        const val MAX_CONDITION_TEXT_CHARS = 600
    }

    private val psiManager = PsiManager.getInstance(project)
    private val psiDocumentManager = PsiDocumentManager.getInstance(project)
    private val fileDocumentManager = FileDocumentManager.getInstance()

    fun buildEnrichment(
        primaryFile: VirtualFile,
        primaryOffset: Int,
        primaryPath: String,
        primaryLine: Int,
        dataflow: JsonObject,
    ): JsonObject? =
        ReadAction.compute<JsonObject?, RuntimeException> {
            val primary = buildPrimary(primaryFile, primaryOffset, primaryPath, primaryLine)
            val nodeMethodsResult = buildDataflowNodeMethods(dataflow)
            val nodeMethods = nodeMethodsResult.methods
            val blocks = buildBlocks(primary = primary, nodeMethods = nodeMethods)

            buildJsonObject {
                put("engine", "intellij-psi-uast-enricher")
                put("version", 2)
                put("generatedAt", Instant.now().toString())
                put("blocks", blocks)
                if (primary != null) put("primary", primary)
                putJsonObject("dataflow") {
                    val nodes = dataflow["nodes"] ?: JsonNull
                    val edges = dataflow["edges"] ?: JsonNull
                    put("nodes", nodes)
                    put("edges", edges)
                    put("nodeMethods", nodeMethods)
                    if (nodeMethodsResult.truncated) {
                        put("nodeMethodsTruncated", true)
                        put("nodeMethodsLimit", MAX_NODE_METHODS)
                    }
                }
            }
        }

    private fun buildBlocks(
        primary: JsonObject?,
        nodeMethods: JsonArray,
    ): JsonArray {
        return buildJsonArray {
            primary?.let { add(buildPrimaryBlock(it)) }
            for (entry in nodeMethods) {
                val obj = entry as? JsonObject ?: continue
                buildNodeMethodBlock(obj)?.let { add(it) }
            }
        }
    }

    private fun buildPrimaryBlock(primary: JsonObject): JsonObject {
        val path = primary.stringOrNull("path")
        val line = primary.intOrNull("line")
        val method = primary["method"] as? JsonObject
        val conditions = primary["conditions"] as? JsonArray ?: JsonArray(emptyList())
        val invocations = primary["invocations"] as? JsonArray ?: JsonArray(emptyList())
        val startLine = method?.intOrNull("startLine")
        val endLine = method?.intOrNull("endLine")
        val language = method?.stringOrNull("language")

        return buildJsonObject {
            put("id", "primary.method")
            put("kind", "METHOD")
            put(
                "reason",
                buildReason(
                    code = "PRIMARY_METHOD",
                    titleZh = "主方法上下文",
                    titleEn = "Primary method context",
                    detailsZh = "漏洞命中点所在方法的完整上下文（用于人类/AI 理解漏洞发生位置与周边逻辑）。",
                    detailsEn = "Full method context containing the finding location (for humans/AI to understand surrounding logic).",
                ),
            )
            if (!path.isNullOrBlank()) {
                putJsonObject("file") {
                    put("path", path)
                    if (!language.isNullOrBlank()) put("language", language)
                }
            }
            if (startLine != null || endLine != null || line != null) {
                putJsonObject("range") {
                    if (startLine != null) put("startLine", startLine)
                    if (endLine != null) put("endLine", endLine)
                    if (line != null) {
                        putJsonArray("highlightLines") {
                            add(JsonPrimitive(line))
                        }
                    }
                }
            }
            putJsonObject("related") {
                if (line != null) put("findingLine", line)
            }
            if (method != null) put("method", method)
            if (!conditions.isEmpty()) put("conditions", conditions)
            if (!invocations.isEmpty()) put("invocations", invocations)
        }
    }

    private fun buildNodeMethodBlock(nodeMethod: JsonObject): JsonObject? {
        val nodeId = nodeMethod.stringOrNull("nodeId") ?: return null
        val label = nodeMethod.stringOrNull("label") ?: nodeId
        val role = nodeMethod.stringOrNull("role")
        val path = nodeMethod.stringOrNull("path")
        val line = nodeMethod.intOrNull("line")
        val method = nodeMethod["method"] as? JsonObject
        val conditions = nodeMethod["conditions"] as? JsonArray ?: JsonArray(emptyList())
        val invocations = nodeMethod["invocations"] as? JsonArray ?: JsonArray(emptyList())
        val startLine = method?.intOrNull("startLine")
        val endLine = method?.intOrNull("endLine")
        val language = method?.stringOrNull("language")

        return buildJsonObject {
            put("id", "node.$nodeId.method")
            put("kind", "METHOD")
            put(
                "reason",
                buildReason(
                    code = "DATAFLOW_NODE_METHOD",
                    titleZh = "链路节点方法上下文",
                    titleEn = "Chain-node method context",
                    detailsZh = "数据流节点（$label）所在方法的上下文，用于解释该节点的周边逻辑与潜在约束条件。",
                    detailsEn = "Context of the method containing the dataflow node ($label), to explain nearby logic and possible constraints.",
                ),
            )
            putJsonObject("related") {
                put("nodeId", nodeId)
                put("label", label)
                if (!role.isNullOrBlank()) put("role", role)
            }
            if (!path.isNullOrBlank()) {
                putJsonObject("file") {
                    put("path", path)
                    if (!language.isNullOrBlank()) put("language", language)
                }
            }
            if (startLine != null || endLine != null || line != null) {
                putJsonObject("range") {
                    if (startLine != null) put("startLine", startLine)
                    if (endLine != null) put("endLine", endLine)
                    if (line != null) {
                        putJsonArray("highlightLines") {
                            add(JsonPrimitive(line))
                        }
                    }
                }
            }
            if (method != null) put("method", method)
            if (!conditions.isEmpty()) put("conditions", conditions)
            if (!invocations.isEmpty()) put("invocations", invocations)
        }
    }

    private fun buildReason(
        code: String,
        titleZh: String,
        titleEn: String,
        detailsZh: String,
        detailsEn: String,
    ): JsonObject =
        buildJsonObject {
            put("code", code)
            putJsonObject("titleI18n") {
                put("zh", titleZh)
                put("en", titleEn)
            }
            putJsonObject("detailsI18n") {
                put("zh", detailsZh)
                put("en", detailsEn)
            }
        }

    private fun buildPrimary(
        file: VirtualFile,
        offset: Int,
        path: String,
        line: Int
    ): JsonObject? {
        val psiFile = psiManager.findFile(file) ?: return null
        val document = psiDocumentManager.getDocument(psiFile) ?: fileDocumentManager.getDocument(file) ?: return null
        val method = findEnclosingMethod(psiFile, offset) ?: return null
        val methodSummary = buildMethodSummary(method, document, highlightLine = line) ?: return null
        val invocations = collectInvocations(method, document)
        val conditions = collectConditions(method, document)

        return buildJsonObject {
            put("path", path)
            put("line", line)
            put("method", methodSummary)
            if (invocations.isNotEmpty()) put("invocations", invocations)
            if (conditions.isNotEmpty()) put("conditions", conditions)
        }
    }

    private fun buildDataflowNodeMethods(dataflow: JsonObject): NodeMethodsResult {
        val nodes = (dataflow["nodes"] as? JsonArray) ?: return NodeMethodsResult(JsonArray(emptyList()), truncated = false)
        val seen = HashSet<String>()
        var truncated = false
        var added = 0
        val nodeMethods =
            buildJsonArray {
                for (node in nodes) {
                    if (added >= MAX_NODE_METHODS) {
                        truncated = true
                        break
                    }
                    val obj = node as? JsonObject ?: continue
                    val nodeId = obj.stringOrNull("id") ?: continue
                    val label = obj.stringOrNull("label") ?: nodeId
                    val role = obj.stringOrNull("role")
                    val filePath = obj.stringOrNull("file") ?: continue
                    val line = obj.intOrNull("line") ?: continue
                    val startColumn = obj.intOrNull("startColumn") ?: 1
                    val uniqueKey = "$filePath:$line:$startColumn"
                    if (!seen.add(uniqueKey)) continue

                    val enriched = buildNodeMethod(nodeId, label, role, filePath, line, startColumn) ?: continue
                    add(enriched)
                    added += 1
                }
            }
        return NodeMethodsResult(methods = nodeMethods, truncated = truncated)
    }

    private data class NodeMethodsResult(
        val methods: JsonArray,
        val truncated: Boolean,
    )

    private fun buildNodeMethod(
        nodeId: String,
        label: String,
        role: String?,
        filePath: String,
        line: Int,
        startColumn: Int
    ): JsonObject? {
        val vFile = resolveVirtualFile(filePath) ?: return null
        val psiFile = psiManager.findFile(vFile) ?: return null
        val document = psiDocumentManager.getDocument(psiFile) ?: fileDocumentManager.getDocument(vFile) ?: return null

        val offset = offsetForLineColumn(document, line = line, column = startColumn)
        val method = findEnclosingMethod(psiFile, offset) ?: return null
        val methodSummary = buildMethodSummary(method, document, highlightLine = line) ?: return null
        val invocations = collectInvocations(method, document)
        val conditions = collectConditions(method, document)

        return buildJsonObject {
            put("nodeId", nodeId)
            put("label", label)
            if (!role.isNullOrBlank()) put("role", role)
            put("path", filePath)
            put("line", line)
            put("method", methodSummary)
            if (invocations.isNotEmpty()) put("invocations", invocations)
            if (conditions.isNotEmpty()) put("conditions", conditions)
        }
    }

    private fun resolveVirtualFile(path: String): VirtualFile? {
        val trimmed = path.trim().takeIf { it.isNotEmpty() } ?: return null
        val absolute =
            if (trimmed.startsWith("/") || trimmed.matches(Regex("^[A-Za-z]:[\\\\/].*"))) {
                trimmed
            } else {
                val base = project.basePath?.trimEnd('/', '\\') ?: return null
                "$base/$trimmed"
            }
        return LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    private fun offsetForLineColumn(
        document: com.intellij.openapi.editor.Document,
        line: Int,
        column: Int
    ): Int {
        if (line <= 0 || document.lineCount <= 0) return 0
        val lineIdx = (line - 1).coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(lineIdx)
        val lineEnd = document.getLineEndOffset(lineIdx)
        val colIdx = (column - 1).coerceAtLeast(0)
        return (lineStart + colIdx).coerceIn(lineStart, lineEnd.coerceAtLeast(lineStart))
    }

    private fun findEnclosingMethod(
        psiFile: PsiFile,
        offset: Int
    ): UMethod? {
        val fileLen = psiFile.textLength
        if (fileLen <= 0) return null
        val safeOffset = offset.coerceIn(0, fileLen - 1)
        val anchor = psiFile.findElementAt(safeOffset)

        if (anchor != null) {
            PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java, false)
                ?.toUElementOfType<UMethod>()
                ?.let { return it }
            PsiTreeUtil.getParentOfType(anchor, KtNamedFunction::class.java, false)
                ?.toUElementOfType<UMethod>()
                ?.let { return it }
            PsiTreeUtil.getParentOfType(anchor, KtPropertyAccessor::class.java, false)
                ?.toUElementOfType<UMethod>()
                ?.let { return it }
        }

        val uFile = psiFile.toUElementOfType<UFile>() ?: return null
        var best: UMethod? = null
        var bestLen = Int.MAX_VALUE

        uFile.accept(
            object : AbstractUastVisitor() {
                override fun visitMethod(node: UMethod): Boolean {
                    val range = node.sourcePsi?.textRange ?: return false
                    if (offset in range.startOffset until range.endOffset) {
                        val len = range.length
                        if (len < bestLen) {
                            best = node
                            bestLen = len
                        }
                    }
                    return super.visitMethod(node)
                }
            }
        )
        return best
    }

    private fun buildMethodSummary(
        method: UMethod,
        document: Document,
        highlightLine: Int?,
    ): JsonObject? {
        val sourcePsi = method.sourcePsi ?: return null
        val range = sourcePsi.textRange ?: return null
        val fullStartLine = document.getLineNumber(range.startOffset) + 1
        val fullEndLine = document.getLineNumber((range.endOffset - 1).coerceAtLeast(range.startOffset)) + 1
        val classFqn =
            method.javaPsi.containingClass?.qualifiedName
                ?: containingUClass(method)?.qualifiedName
        val params = method.uastParameters.joinToString(",") { it.type.presentableText }
        val signature =
            if (!classFqn.isNullOrBlank()) {
                "$classFqn#${method.name}($params)"
            } else {
                "${method.name}($params)"
            }
        val language = sourcePsi.language.id

        val safeHighlight = highlightLine?.coerceIn(fullStartLine, fullEndLine)
        val (excerptStartLine, excerptEndLine) = computeExcerptLines(fullStartLine, fullEndLine, safeHighlight)
        val excerpt = buildTextExcerpt(document, excerptStartLine, excerptEndLine)
        val truncated =
            excerpt.truncated || excerptStartLine != fullStartLine || excerptEndLine != fullEndLine

        return buildJsonObject {
            put("name", method.name)
            put("signature", signature)
            put("startLine", excerptStartLine)
            put("endLine", excerptEndLine)
            put("fullStartLine", fullStartLine)
            put("fullEndLine", fullEndLine)
            put("language", language)
            put("text", excerpt.text)
            put("truncated", truncated)
        }
    }

    private fun collectInvocations(
        method: UMethod,
        document: Document
    ): JsonArray {
        val calls = mutableListOf<JsonObject>()
        method.accept(
            object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    if (calls.size >= MAX_INVOCATIONS) return true
                    val sourcePsi = node.sourcePsi ?: return false
                    val range = sourcePsi.textRange ?: return false
                    val line = document.getLineNumber(range.startOffset) + 1
                    val text = sourcePsi.text.trim().take(MAX_CALL_TEXT_CHARS)
                    val argIds =
                        node.valueArguments
                            .asSequence()
                            .take(MAX_CALL_ARG_IDENTIFIERS)
                            .mapNotNull { arg ->
                                val psi = arg.sourcePsi
                                psi?.text
                                    ?.trim()
                                    ?.take(MAX_METHOD_LINE_CHARS)
                                    ?.takeIf { it.isNotEmpty() }
                            }
                            .toList()
                    calls.add(
                        buildJsonObject {
                            put("line", line)
                            put("text", text)
                            if (argIds.isNotEmpty()) {
                                putJsonArray("argIdentifiers") {
                                    for (id in argIds) add(JsonPrimitive(id))
                                }
                            }
                        }
                    )
                    return super.visitCallExpression(node)
                }
            }
        )
        return JsonArray(calls)
    }

    private fun collectConditions(
        method: UMethod,
        document: Document
    ): JsonArray {
        val conditions = mutableListOf<JsonObject>()
        method.accept(
            object : AbstractUastVisitor() {
                override fun visitIfExpression(node: UIfExpression): Boolean {
                    val cond = node.condition ?: return super.visitIfExpression(node)
                    addCondition(cond.sourcePsi?.text, cond.sourcePsi?.textRange?.startOffset)
                    return super.visitIfExpression(node)
                }

                override fun visitWhileExpression(node: UWhileExpression): Boolean {
                    val cond = node.condition ?: return super.visitWhileExpression(node)
                    addCondition(cond.sourcePsi?.text, cond.sourcePsi?.textRange?.startOffset)
                    return super.visitWhileExpression(node)
                }

                override fun visitForExpression(node: UForExpression): Boolean {
                    val cond = node.condition ?: return super.visitForExpression(node)
                    addCondition(cond.sourcePsi?.text, cond.sourcePsi?.textRange?.startOffset)
                    return super.visitForExpression(node)
                }

                private fun addCondition(text: String?, startOffset: Int?) {
                    if (conditions.size >= MAX_CONDITIONS) return
                    val safeText = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
                    val offset = startOffset ?: return
                    val line = document.getLineNumber(offset) + 1
                    conditions.add(
                        buildJsonObject {
                            put("line", line)
                            put("text", safeText.take(MAX_CONDITION_TEXT_CHARS))
                        }
                    )
                }
            }
        )
        return JsonArray(conditions)
    }

    private fun computeExcerptLines(
        fullStartLine: Int,
        fullEndLine: Int,
        highlightLine: Int?,
    ): Pair<Int, Int> {
        val total = (fullEndLine - fullStartLine + 1).coerceAtLeast(1)
        if (total <= MAX_METHOD_EXCERPT_LINES) return fullStartLine to fullEndLine

        val half = MAX_METHOD_EXCERPT_LINES / 2
        val center = highlightLine ?: (fullStartLine + half)
        var start = center - half
        var end = start + MAX_METHOD_EXCERPT_LINES - 1

        if (start < fullStartLine) {
            start = fullStartLine
            end = start + MAX_METHOD_EXCERPT_LINES - 1
        }
        if (end > fullEndLine) {
            end = fullEndLine
            start = (end - MAX_METHOD_EXCERPT_LINES + 1).coerceAtLeast(fullStartLine)
        }
        return start to end
    }

    private data class TextExcerpt(
        val text: String,
        val truncated: Boolean,
    )

    private fun buildTextExcerpt(
        document: Document,
        startLine: Int,
        endLine: Int,
    ): TextExcerpt {
        if (document.lineCount <= 0) return TextExcerpt(text = "", truncated = false)
        val safeStart = startLine.coerceAtLeast(1)
        val safeEnd = endLine.coerceAtLeast(safeStart)

        val builder = StringBuilder()
        var truncated = false
        for (line in safeStart..safeEnd) {
            val lineText = getLineText(document, line)
            val clippedLine = lineText.take(MAX_METHOD_LINE_CHARS)
            if (builder.length + clippedLine.length + 1 > MAX_METHOD_EXCERPT_CHARS) {
                truncated = true
                break
            }
            builder.append(clippedLine).append('\n')
        }

        val text =
            builder
                .toString()
                .trimEnd()
                .ifBlank { "" }
        return TextExcerpt(text = text, truncated = truncated)
    }

    private fun getLineText(document: Document, line: Int): String {
        if (line <= 0 || line > document.lineCount) return ""
        val idx = line - 1
        val start = document.getLineStartOffset(idx)
        val end = document.getLineEndOffset(idx)
        return document.charsSequence.subSequence(start, end).toString()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()

    private fun containingUClass(method: UMethod): UClass? {
        var parent = method.uastParent
        while (parent != null && parent !is UClass) {
            parent = parent.uastParent
        }
        return parent as? UClass
    }
}

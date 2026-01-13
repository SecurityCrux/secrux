package com.securitycrux.secrux.intellij.enrichment

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
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
            val nodeMethods = buildDataflowNodeMethods(dataflow)
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
        val methodSummary = buildMethodSummary(method, document) ?: return null
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

    private fun buildDataflowNodeMethods(dataflow: JsonObject): JsonArray {
        val nodes = (dataflow["nodes"] as? JsonArray) ?: return JsonArray(emptyList())
        val seen = HashSet<String>()
        return buildJsonArray {
            for (node in nodes) {
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
            }
        }
    }

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
        val methodSummary = buildMethodSummary(method, document) ?: return null
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
        psiFile: com.intellij.psi.PsiFile,
        offset: Int
    ): UMethod? {
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
        document: com.intellij.openapi.editor.Document
    ): JsonObject? {
        val sourcePsi = method.sourcePsi ?: return null
        val range = sourcePsi.textRange ?: return null
        val startLine = document.getLineNumber(range.startOffset) + 1
        val endLine = document.getLineNumber((range.endOffset - 1).coerceAtLeast(range.startOffset)) + 1
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

        return buildJsonObject {
            put("name", method.name)
            put("signature", signature)
            put("startLine", startLine)
            put("endLine", endLine)
            put("language", language)
            put("text", sourcePsi.text)
            put("truncated", false)
        }
    }

    private fun collectInvocations(
        method: UMethod,
        document: com.intellij.openapi.editor.Document
    ): JsonArray {
        val calls = mutableListOf<JsonObject>()
        method.accept(
            object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    val sourcePsi = node.sourcePsi ?: return false
                    val range = sourcePsi.textRange ?: return false
                    val line = document.getLineNumber(range.startOffset) + 1
                    val text = sourcePsi.text.trim()
                    val argIds =
                        node.valueArguments
                            .asSequence()
                            .mapNotNull { arg ->
                                val psi = arg.sourcePsi
                                psi?.text?.trim()?.takeIf { it.isNotEmpty() }
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
        document: com.intellij.openapi.editor.Document
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
                    val safeText = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
                    val offset = startOffset ?: return
                    val line = document.getLineNumber(offset) + 1
                    conditions.add(
                        buildJsonObject {
                            put("line", line)
                            put("text", safeText)
                        }
                    )
                }
            }
        )
        return JsonArray(conditions)
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

package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.dto.CallChainDto
import com.secrux.dto.CallChainStepDto
import com.secrux.dto.CodeLineDto
import com.secrux.dto.CodeSnippetDto
import com.secrux.dto.DataFlowEdgeDto
import com.secrux.dto.DataFlowNodeDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

@Component
class FindingEvidenceService(
    @Value("\${secrux.workspace.root:build/workspaces}") private val workspaceRootDir: String
) {

    fun normalizeWorkspaceNodeFile(taskId: UUID, raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = value.replace('\\', '/')
        val rootAbsolute = Path.of(workspaceRootDir).toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
        val rootRelative = workspaceRootDir.replace('\\', '/').trimEnd('/')
        val id = taskId.toString()
        val absolutePrefix = "$rootAbsolute/$id/"
        val relativePrefix = "$rootRelative/$id/"
        return when {
            normalized.startsWith(absolutePrefix) -> normalized.removePrefix(absolutePrefix)
            normalized.startsWith(relativePrefix) -> normalized.removePrefix(relativePrefix)
            else -> value
        }
    }

    fun loadSnippet(location: Map<String, Any?>, context: Int = 5): CodeSnippetDto? {
        val pathValue = location["path"] as? String ?: return null
        val lineValue = (location["line"] as? Number)?.toInt() ?: (location["startLine"] as? Number)?.toInt()
        val line = lineValue ?: return null
        val filePath = Path.of(pathValue)
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return null
        }
        val lines = Files.readAllLines(filePath)
        val start = max(1, line - context)
        val end = min(lines.size, line + context)
        val snippetLines = mutableListOf<CodeLineDto>()
        for (idx in (start..end)) {
            val content = lines[idx - 1].take(400)
            snippetLines.add(CodeLineDto(lineNumber = idx, content = content, highlight = idx == line))
        }
        return CodeSnippetDto(
            path = filePath.toString(),
            startLine = start,
            endLine = end,
            lines = snippetLines
        )
    }

    fun extractSnippetFromEvidence(evidence: Map<String, Any?>?): CodeSnippetDto? {
        if (evidence.isNullOrEmpty()) return null
        val snippet = evidence["codeSnippet"] as? Map<*, *> ?: return null
        return parseSnippetMap(snippet)
    }

    fun extractEnrichmentFromEvidence(evidence: Map<String, Any?>?): Map<String, Any?>? {
        if (evidence.isNullOrEmpty()) return null
        val enrichment = evidence["enrichment"] as? Map<*, *> ?: return null
        return enrichment.entries.associate { (k, v) -> k.toString() to v }
    }

    fun extractSnippetFromDataflowNode(evidence: Map<String, Any?>?, path: String, line: Int): CodeSnippetDto? {
        if (evidence.isNullOrEmpty()) return null
        val df = evidence["dataflow"] ?: evidence["dataFlow"] ?: return null
        val map = df as? Map<*, *> ?: return null
        val nodes = map["nodes"] as? List<*> ?: return null

        val node =
            nodes.asSequence()
                .mapNotNull { it as? Map<*, *> }
                .firstOrNull { raw ->
                    val file = raw["file"]?.toString()
                    val nodeLine = (raw["line"] as? Number)?.toInt()
                    file == path && nodeLine == line
                } ?: return null

        val nodeSnippet = node["codeSnippet"] as? Map<*, *>
        if (nodeSnippet != null) {
            parseSnippetMap(nodeSnippet)?.let { return it }
        }

        val text = node["value"]?.toString()?.take(400)?.takeIf { it.isNotBlank() } ?: return null
        return CodeSnippetDto(
            path = path,
            startLine = line,
            endLine = line,
            lines = listOf(CodeLineDto(lineNumber = line, content = text, highlight = true))
        )
    }

    private fun parseSnippetMap(snippet: Map<*, *>): CodeSnippetDto? {
        val path = snippet["path"]?.toString()?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val startLine = (snippet["startLine"] as? Number)?.toInt() ?: return null
        val endLine = (snippet["endLine"] as? Number)?.toInt() ?: return null
        if (startLine <= 0 || endLine <= 0 || endLine < startLine) return null
        val linesRaw = snippet["lines"] as? List<*> ?: return null
        val lines =
            linesRaw.asSequence()
                .mapNotNull { it as? Map<*, *> }
                .mapNotNull { line ->
                    val lineNumber = (line["lineNumber"] as? Number)?.toInt() ?: return@mapNotNull null
                    val content = line["content"]?.toString()?.take(400) ?: return@mapNotNull null
                    val highlight = line["highlight"] as? Boolean ?: false
                    CodeLineDto(lineNumber = lineNumber, content = content, highlight = highlight)
                }.take(200)
                .toList()
        if (lines.isEmpty()) return null
        return CodeSnippetDto(path = path, startLine = startLine, endLine = endLine, lines = lines)
    }

    fun getSnippetForTaskWorkspace(taskId: UUID, path: String, line: Int, context: Int): CodeSnippetDto? {
        val safeContext = context.coerceIn(0, 50)
        if (line <= 0) return null
        val candidate = resolveTaskWorkspaceFilePath(taskId, path)
        val location = mapOf("path" to candidate.toString(), "line" to line)
        return loadSnippet(location, safeContext)
    }

    fun resolveTaskWorkspaceFilePath(taskId: UUID, path: String): Path {
        val workspaceRoot = Path.of(workspaceRootDir).toAbsolutePath().normalize()
        val taskWorkspace = workspaceRoot.resolve(taskId.toString()).normalize()
        val requested = Path.of(path)
        val candidates =
            buildList<Path> {
                if (requested.isAbsolute) {
                    add(requested)
                } else {
                    add(taskWorkspace.resolve(requested))
                    add(requested.toAbsolutePath())
                }
            }.map { it.toAbsolutePath().normalize() }
        return candidates.firstOrNull { it.startsWith(taskWorkspace) }
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Requested snippet path is outside task workspace")
    }

    fun parseDataflow(evidence: Map<String, Any?>?): Pair<List<DataFlowNodeDto>, List<DataFlowEdgeDto>> {
        val df = evidence?.get("dataflow") ?: evidence?.get("dataFlow") ?: return emptyList<DataFlowNodeDto>() to emptyList()
        val map = df as? Map<*, *> ?: return emptyList<DataFlowNodeDto>() to emptyList()
        val nodesRaw = map["nodes"] as? List<*> ?: emptyList<Any?>()
        val edgesRaw = map["edges"] as? List<*> ?: emptyList<Any?>()
        val nodes =
            nodesRaw.mapNotNull { raw ->
                val obj = raw as? Map<*, *> ?: return@mapNotNull null
                val id = obj["id"]?.toString() ?: return@mapNotNull null
                val label = obj["label"]?.toString() ?: id
                val inferred = inferSemgrepRoleAndValue(label)
                val role = obj["role"]?.toString() ?: inferred.first
                val value = obj["value"]?.toString() ?: inferred.second
                DataFlowNodeDto(
                    id = id,
                    label = label,
                    role = role,
                    value = value,
                    file = obj["file"]?.toString(),
                    line = (obj["line"] as? Number)?.toInt(),
                    startColumn = (obj["startColumn"] as? Number)?.toInt(),
                    endColumn = (obj["endColumn"] as? Number)?.toInt()
                )
            }
        val edges =
            edgesRaw.mapNotNull { raw ->
                val obj = raw as? Map<*, *> ?: return@mapNotNull null
                val source = obj["source"]?.toString() ?: return@mapNotNull null
                val target = obj["target"]?.toString() ?: return@mapNotNull null
                DataFlowEdgeDto(
                    source = source,
                    target = target,
                    label = obj["label"]?.toString()
                )
            }
        return nodes to edges
    }

    fun normalizeCallChainsForDisplay(taskId: UUID, callChains: List<CallChainDto>, maxLookahead: Int = 20): List<CallChainDto> {
        if (callChains.isEmpty()) return callChains

        val safeLookahead = maxLookahead.coerceIn(0, 200)
        val fileLinesCache = mutableMapOf<String, List<String>?>()

        fun readLines(path: String): List<String>? {
            return fileLinesCache.getOrPut(path) {
                runCatching {
                    val resolved = resolveTaskWorkspaceFilePath(taskId, path)
                    if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) return@getOrPut null
                    Files.readAllLines(resolved)
                }.getOrNull()
            }
        }

        fun isCommentLine(text: String?): Boolean {
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

        fun findPreferredLine(lines: List<String>, startLine: Int): Int? {
            if (startLine <= 0 || startLine > lines.size) return null
            val startText = lines[startLine - 1]
            if (!isCommentLine(startText)) return startLine

            var firstNonComment: Int? = null
            val end = min(lines.size, startLine + safeLookahead)
            for (line in startLine..end) {
                val raw = lines[line - 1]
                val t = raw.trim()
                if (t.isBlank()) continue
                if (isCommentLine(t)) continue
                if (firstNonComment == null) firstNonComment = line
                if (!t.startsWith("@")) return line
            }
            return firstNonComment
        }

        fun normalizeStep(step: CallChainStepDto): CallChainStepDto {
            val file = step.file?.trim()?.takeIf { it.isNotBlank() } ?: return step
            val line = step.line ?: return step
            val needsFix = isCommentLine(step.label) || isCommentLine(step.snippet)
            if (!needsFix) return step

            val lines = readLines(file) ?: return step
            val preferredLine = findPreferredLine(lines, line) ?: return step
            val preferredText = lines.getOrNull(preferredLine - 1)?.take(400) ?: return step

            val updatedLabel = if (isCommentLine(step.label)) preferredText else step.label
            val updatedSnippet =
                when {
                    step.snippet.isNullOrBlank() -> preferredText
                    isCommentLine(step.snippet) -> preferredText
                    else -> step.snippet
                }

            if (preferredLine == line && updatedLabel == step.label && updatedSnippet == step.snippet) return step
            return step.copy(
                label = updatedLabel,
                line = preferredLine,
                snippet = updatedSnippet
            )
        }

        return callChains.map { chain ->
            chain.copy(steps = chain.steps.map { step -> normalizeStep(step) })
        }
    }

    fun hasDataFlow(evidence: Map<String, Any?>?): Boolean {
        val (nodes, edges) = parseDataflow(evidence)
        return nodes.isNotEmpty() || edges.isNotEmpty()
    }

    private fun inferSemgrepRoleAndValue(label: String): Pair<String?, String?> {
        val trimmed = label.trim()
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return null to null
        val prefix = trimmed.substring(0, colon).trim()
        val role =
            when (prefix.lowercase()) {
                "source" -> "SOURCE"
                "propagator" -> "PROPAGATOR"
                "sink" -> "SINK"
                else -> null
            } ?: return null to null
        var rest = trimmed.substring(colon + 1).trim()
        val atIndex = rest.indexOf(" @")
        if (atIndex >= 0) {
            rest = rest.substring(0, atIndex).trim()
        }
        val value = rest.trim().trim('\'', '"').takeIf { it.isNotBlank() }
        return role to value
    }
}

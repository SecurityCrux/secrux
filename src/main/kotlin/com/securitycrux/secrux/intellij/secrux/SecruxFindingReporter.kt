package com.securitycrux.secrux.intellij.secrux

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.securitycrux.secrux.intellij.callgraph.CallGraph
import com.securitycrux.secrux.intellij.callgraph.CallGraphService
import com.securitycrux.secrux.intellij.callgraph.MethodLocation
import com.securitycrux.secrux.intellij.callgraph.MethodRef
import com.securitycrux.secrux.intellij.i18n.SecruxBundle
import com.securitycrux.secrux.intellij.sinks.SinkMatch
import com.securitycrux.secrux.intellij.util.SecruxNotifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class SecruxFindingReporter(
    private val project: Project
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun report(
        indicator: ProgressIndicator,
        baseUrl: String,
        token: String,
        taskId: String,
        matches: List<SinkMatch>,
        severity: SecruxSeverity,
        includeSnippets: Boolean,
        triggerAiReview: Boolean = false,
        waitAiReview: Boolean = false,
        includeCallChains: Boolean = true,
        requireEntryPoint: Boolean = false
    ) {
        indicator.checkCanceled()
        indicator.text = SecruxBundle.message("progress.preparingPayload")

        val fileDocumentManager = FileDocumentManager.getInstance()
        val callGraphService = CallGraphService.getInstance(project)
        val graphSnapshot = callGraphService.getLastGraph()
        val findings =
            buildJsonArray {
                for ((i, match) in matches.withIndex()) {
                    indicator.checkCanceled()
                    indicator.fraction = i.toDouble() / matches.size.coerceAtLeast(1).toDouble()
                    val document = fileDocumentManager.getDocument(match.file)
                    val relativePath = toProjectRelativePath(match.file.path)
                    val line = match.line
                    val startColumn = match.column
                    val endColumn = computeEndColumn(document, match.endOffset)
                    val fingerprint =
                        fingerprint(
                            match.type.name,
                            relativePath,
                            line.toString(),
                            startColumn.toString(),
                            match.targetClassFqn,
                            match.targetMember,
                            match.targetParamCount.toString()
                        )
                    val snippet = if (includeSnippets && document != null) buildSnippet(document, relativePath, line, context = 5) else null
                    val sinkLineText = if (document != null) getLineText(document, line).take(400) else null
                    val callChainMode =
                        CallChainMode(
                            include = includeCallChains,
                            requireEntryPoint = requireEntryPoint
                        )
                    val callChains =
                        if (callChainMode.include && graphSnapshot != null) {
                            val target =
                                MethodRef(
                                    classFqn = match.targetClassFqn,
                                    name = match.targetMember,
                                    paramCount = match.targetParamCount
                                )
                            buildCallChains(callGraphService, graphSnapshot, target, maxDepth = 8, maxChains = 20, mode = callChainMode)
                        } else {
                            emptyList()
                        }
                    val sarifCodeFlows =
                        if (callChains.isNotEmpty() && graphSnapshot != null) {
                            buildSarifCodeFlows(
                                graph = graphSnapshot,
                                chains = callChains,
                                match = match,
                                matchRelativePath = relativePath,
                                matchLine = line,
                                matchStartColumn = startColumn,
                                matchEndColumn = endColumn,
                                sinkLineText = sinkLineText,
                                fileDocumentManager = fileDocumentManager
                            )
                        } else {
                            null
                        }
                    val dataflow =
                        if (sarifCodeFlows != null && graphSnapshot != null) {
                            buildDataflowFromCallChains(
                                graph = graphSnapshot,
                                chains = callChains,
                                match = match,
                                matchRelativePath = relativePath,
                                matchLine = line,
                                matchStartColumn = startColumn,
                                matchEndColumn = endColumn,
                                sinkLineText = sinkLineText,
                                fileDocumentManager = fileDocumentManager
                            )
                        } else {
                            buildJsonObject {
                                put("source", "ide.sinkScan")
                                putJsonArray("nodes") {
                                    add(
                                        buildJsonObject {
                                            put("id", "n0")
                                            put("label", "sink: ${match.targetClassFqn}#${match.targetMember}")
                                            put("role", "SINK")
                                            put("file", relativePath)
                                            put("line", line)
                                            put("startColumn", startColumn)
                                            if (endColumn != null) put("endColumn", endColumn)
                                            if (sinkLineText != null) put("value", sinkLineText)
                                        }
                                    )
                                }
                                putJsonArray("edges") {}
                            }
                        }

                    add(
                        buildJsonObject {
                            put("sourceEngine", "secrux-intellij-plugin")
                            put("ruleId", match.type.name)
                            put("fingerprint", fingerprint)
                            put("severity", severity.name)
                            put("status", "OPEN")
                            putJsonObject("location") {
                                put("path", relativePath)
                                put("line", line)
                                put("startColumn", startColumn)
                                if (endColumn != null) put("endColumn", endColumn)
                            }
                            putJsonObject("evidence") {
                                put("message", "IDE sink match: ${match.type.name}")
                                putJsonObject("target") {
                                    put("classFqn", match.targetClassFqn)
                                    put("member", match.targetMember)
                                }
                                match.enclosingMethodFqn?.let { put("enclosingMethod", it) }
                                if (snippet != null) {
                                    put("codeSnippet", snippet)
                                }
                                put("dataflow", dataflow)
                                if (sarifCodeFlows != null) {
                                    putJsonObject("sarif") {
                                        put("version", "2.1.0")
                                        putJsonObject("result") {
                                            put("ruleId", match.type.name)
                                            putJsonObject("fingerprints") {
                                                put("primary", fingerprint)
                                            }
                                            putJsonObject("message") {
                                                put("text", "IDE sink match: ${match.type.name}")
                                            }
                                            putJsonArray("locations") {
                                                add(
                                                    buildJsonObject {
                                                        putJsonObject("physicalLocation") {
                                                            putJsonObject("artifactLocation") {
                                                                put("uri", relativePath)
                                                            }
                                                            putJsonObject("region") {
                                                                put("startLine", line)
                                                                put("startColumn", startColumn)
                                                                if (endColumn != null) put("endColumn", endColumn)
                                                                if (sinkLineText != null) {
                                                                    putJsonObject("snippet") {
                                                                        put("text", sinkLineText)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                            putJsonObject("properties") {
                                                put("severity", severity.name.lowercase())
                                                put("callChainMode", callChainMode.toSarifModeString())
                                            }
                                            put("codeFlows", sarifCodeFlows)
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }

        val request =
            buildJsonObject {
                put("format", "IDE")
                put("findings", findings)
        }

        indicator.checkCanceled()
        indicator.text = SecruxBundle.message("progress.uploadingFindings")

        try {
            val client = SecruxApiClient(baseUrl = baseUrl, token = token)
            val ingestResponse = client.ingestFindings(taskId, request.toString())
            val findingIds = parseFindingIds(ingestResponse)
            SecruxNotifications.info(project, SecruxBundle.message("notification.reportedFindings", matches.size))

            if (!triggerAiReview) return
            if (findingIds.isEmpty()) {
                SecruxNotifications.error(project, SecruxBundle.message("notification.aiReviewNoFindingIds"))
                return
            }

            indicator.checkCanceled()
            indicator.text = SecruxBundle.message("progress.triggeringAiReview")

            val jobs = mutableListOf<Pair<String, String>>() // findingId -> jobId
            for ((index, findingId) in findingIds.withIndex()) {
                indicator.checkCanceled()
                indicator.fraction = index.toDouble() / findingIds.size.coerceAtLeast(1).toDouble()
                val triggerResponse = client.triggerFindingAiReview(findingId)
                val jobId = parseJobId(triggerResponse)
                if (jobId != null) {
                    jobs.add(findingId to jobId)
                }
            }

            if (jobs.isEmpty()) {
                SecruxNotifications.error(project, SecruxBundle.message("notification.aiReviewFailed", "no jobId returned"))
                return
            }

            if (!waitAiReview) {
                SecruxNotifications.info(project, SecruxBundle.message("notification.aiReviewTriggered", jobs.size))
                return
            }

            val pollTimeoutMs = 2 * 60 * 1000L
            val pollIntervalMs = 1500L

            indicator.checkCanceled()
            indicator.text = SecruxBundle.message("progress.waitingAiReview")

            val completed = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val timedOut = mutableListOf<String>()

            for ((index, pair) in jobs.withIndex()) {
                indicator.checkCanceled()
                indicator.fraction = index.toDouble() / jobs.size.coerceAtLeast(1).toDouble()
                val (findingId, jobId) = pair
                val status = waitForJobCompletion(client, jobId, indicator, timeoutMs = pollTimeoutMs, intervalMs = pollIntervalMs)
                when (status) {
                    "COMPLETED" -> completed.add(findingId)
                    "FAILED" -> failed.add(findingId)
                    else -> timedOut.add(findingId)
                }
            }

            indicator.checkCanceled()
            indicator.text = SecruxBundle.message("progress.loadingAiReviewResults")

            val summary = buildAiReviewSummary(client, completed, failed, timedOut)
            ApplicationManager.getApplication().invokeLater {
                AiReviewDialog(project = project, title = SecruxBundle.message("dialog.aiReview.title"), content = summary).show()
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            SecruxNotifications.error(project, SecruxBundle.message("notification.reportFailed", reason))
        }
    }

    private fun parseFindingIds(responseJson: String): List<String> {
        val data = parseApiResponseData(responseJson) ?: return emptyList()
        val arr = runCatching { data.jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { element ->
            runCatching { element.jsonObject["findingId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun parseJobId(responseJson: String): String? {
        val data = parseApiResponseData(responseJson) ?: return null
        val obj = runCatching { data.jsonObject }.getOrNull() ?: return null
        return obj["jobId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseJobStatus(responseJson: String): String? {
        val data = parseApiResponseData(responseJson) ?: return null
        val obj = runCatching { data.jsonObject }.getOrNull() ?: return null
        return obj["status"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseApiResponseData(responseJson: String): JsonElement? {
        val root = runCatching { json.parseToJsonElement(responseJson).jsonObject }.getOrNull() ?: return null
        return root["data"]
    }

    private fun waitForJobCompletion(
        client: SecruxApiClient,
        jobId: String,
        indicator: ProgressIndicator,
        timeoutMs: Long,
        intervalMs: Long
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            indicator.checkCanceled()
            val jobResponse = client.getAiJob(jobId)
            val status = parseJobStatus(jobResponse)
            if (status == "COMPLETED" || status == "FAILED") return status
            Thread.sleep(intervalMs)
        }
        return "TIMEOUT"
    }

    private fun buildAiReviewSummary(
        client: SecruxApiClient,
        completedFindingIds: List<String>,
        failedFindingIds: List<String>,
        timedOutFindingIds: List<String>
    ): String {
        val preferZh = Locale.getDefault().language.lowercase().startsWith("zh")

        fun formatFindingDetail(findingId: String): String {
            val response = runCatching { client.getFindingDetail(findingId) }.getOrNull() ?: return "- $findingId (detail fetch failed)"
            val data = parseApiResponseData(response) ?: return "- $findingId (invalid response)"
            val obj = runCatching { data.jsonObject }.getOrNull() ?: return "- $findingId (invalid response)"

            val ruleId = obj["ruleId"]?.jsonPrimitive?.contentOrNull
            val location = obj["location"]?.jsonObject
            val path = location?.get("path")?.jsonPrimitive?.contentOrNull
            val line = location?.get("line")?.jsonPrimitive?.contentOrNull

            val review = obj["review"]?.jsonObject
            if (review == null) {
                return "- $findingId ${formatLocation(path, line)} ${formatRuleId(ruleId)} (no review yet)"
            }

            val verdict = review["verdict"]?.jsonPrimitive?.contentOrNull
            val confidence = review["confidence"]?.jsonPrimitive?.contentOrNull
            val opinionI18n = review["opinionI18n"]?.jsonObject
            val preferred = (if (preferZh) "zh" else "en")
            val fallback = (if (preferZh) "en" else "zh")
            val opinion = opinionI18n?.get(preferred)?.jsonObject ?: opinionI18n?.get(fallback)?.jsonObject
            val summary = opinion?.get("summary")?.jsonPrimitive?.contentOrNull
            val fixHint = opinion?.get("fixHint")?.jsonPrimitive?.contentOrNull

            val header =
                buildString {
                    append("- ")
                    append(findingId)
                    append(' ')
                    append(formatLocation(path, line))
                    append(' ')
                    append(formatRuleId(ruleId))
                    if (!verdict.isNullOrBlank()) append(" verdict=$verdict")
                    if (!confidence.isNullOrBlank()) append(" confidence=$confidence")
                }.trimEnd()

            return buildString {
                append(header)
                if (!summary.isNullOrBlank()) append("\n  summary: ").append(summary.trim())
                if (!fixHint.isNullOrBlank()) append("\n  fix: ").append(fixHint.trim())
            }
        }

        return buildString {
            append(SecruxBundle.message("message.aiReviewSummary.header")).append('\n')

            if (completedFindingIds.isNotEmpty()) {
                append('\n').append(SecruxBundle.message("message.aiReviewSummary.completed", completedFindingIds.size)).append('\n')
                for (id in completedFindingIds) {
                    append(formatFindingDetail(id)).append('\n')
                }
            }

            if (failedFindingIds.isNotEmpty()) {
                append('\n').append(SecruxBundle.message("message.aiReviewSummary.failed", failedFindingIds.size)).append('\n')
                for (id in failedFindingIds) {
                    append("- ").append(id).append('\n')
                }
            }

            if (timedOutFindingIds.isNotEmpty()) {
                append('\n').append(SecruxBundle.message("message.aiReviewSummary.timedOut", timedOutFindingIds.size)).append('\n')
                for (id in timedOutFindingIds) {
                    append("- ").append(id).append('\n')
                }
            }
        }.trim()
    }

    private fun formatLocation(path: String?, line: String?): String =
        when {
            !path.isNullOrBlank() && !line.isNullOrBlank() -> "($path:$line)"
            !path.isNullOrBlank() -> "($path)"
            else -> ""
        }

    private fun formatRuleId(ruleId: String?): String =
        if (ruleId.isNullOrBlank()) "" else "[$ruleId]"

    private fun toProjectRelativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
    }

    private fun computeEndColumn(document: Document?, endOffset: Int): Int? {
        if (document == null) return null
        val safeOffset = endOffset.coerceIn(0, document.textLength)
        val lineIndex = document.getLineNumber(safeOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        return (safeOffset - lineStart) + 1
    }

    private fun computeLineAndColumn(document: Document, offset: Int): Pair<Int, Int> {
        val safeOffset = offset.coerceIn(0, document.textLength)
        val lineIndex = document.getLineNumber(safeOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        return (lineIndex + 1) to ((safeOffset - lineStart) + 1)
    }

    private fun buildSnippet(
        document: Document,
        path: String,
        line: Int,
        context: Int
    ): JsonObject {
        val lineIndex = (line - 1).coerceIn(0, document.lineCount - 1)
        val startLineIndex = max(0, lineIndex - context)
        val endLineIndex = min(document.lineCount - 1, lineIndex + context)

        val lines =
            buildJsonArray {
                for (idx in startLineIndex..endLineIndex) {
                    val content = getLineText(document, idx + 1).take(400)
                    add(
                        buildJsonObject {
                            put("lineNumber", idx + 1)
                            put("content", content)
                            put("highlight", idx == lineIndex)
                        }
                    )
                }
            }

        return buildJsonObject {
            put("path", path)
            put("startLine", startLineIndex + 1)
            put("endLine", endLineIndex + 1)
            put("lines", lines)
        }
    }

    private fun getLineText(document: Document, line: Int): String {
        if (line <= 0 || line > document.lineCount) return ""
        val idx = line - 1
        val start = document.getLineStartOffset(idx)
        val end = document.getLineEndOffset(idx)
        return document.charsSequence.subSequence(start, end).toString()
    }

    private fun fingerprint(vararg parts: String): String {
        val joined = parts.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class CallChainMode(
        val include: Boolean,
        val requireEntryPoint: Boolean
    ) {
        fun toSarifModeString(): String =
            when {
                !include -> "none"
                requireEntryPoint -> "entrypointOnly"
                else -> "all"
            }
    }

    private fun buildCallChains(
        callGraphService: CallGraphService,
        graph: CallGraph,
        target: MethodRef,
        maxDepth: Int,
        maxChains: Int,
        mode: CallChainMode
    ): List<List<MethodRef>> {
        val chains = callGraphService.findCallerChains(target, maxDepth = maxDepth, maxChains = maxChains)
        if (!mode.requireEntryPoint) return chains
        if (graph.entryPoints.isEmpty()) return emptyList()
        return chains
            .mapNotNull { chain ->
                val idx = chain.indexOfFirst { it in graph.entryPoints }
                if (idx < 0) null else chain.subList(idx, chain.size)
            }.distinctBy { chain -> chain.joinToString("->") { it.id } }
    }

    private data class StepLocation(
        val path: String?,
        val line: Int?,
        val startColumn: Int?,
        val endColumn: Int?,
        val snippet: String?
    )

    private fun locationForMethod(
        location: MethodLocation?,
        fileDocumentManager: FileDocumentManager
    ): StepLocation {
        if (location == null) return StepLocation(path = null, line = null, startColumn = null, endColumn = null, snippet = null)
        val rel = toProjectRelativePath(location.file.path)
        val document = fileDocumentManager.getDocument(location.file) ?: return StepLocation(rel, null, null, null, null)
        val (line, col) = computeLineAndColumn(document, location.startOffset)
        val text = getLineText(document, line).take(400)
        return StepLocation(path = rel, line = line, startColumn = col, endColumn = null, snippet = text)
    }

    private fun buildDataflowFromCallChains(
        graph: CallGraph,
        chains: List<List<MethodRef>>,
        match: SinkMatch,
        matchRelativePath: String,
        matchLine: Int,
        matchStartColumn: Int,
        matchEndColumn: Int?,
        sinkLineText: String?,
        fileDocumentManager: FileDocumentManager
    ): JsonObject {
        var nodeCounter = 0
        val edges = mutableListOf<Pair<String, String>>()

        val nodes =
            buildJsonArray {
                for (chain in chains) {
                    val trimmedChain =
                        if (chain.isNotEmpty() && graph.methods[chain.last()] == null) {
                            chain.dropLast(1)
                        } else {
                            chain
                        }
                    val steps = trimmedChain.map { ref -> ref to locationForMethod(graph.methods[ref], fileDocumentManager) }
                    var prev: String? = null
                    for ((idx, step) in steps.withIndex()) {
                        val (ref, loc) = step
                        val id = "n${nodeCounter++}"
                        val role = when {
                            idx == 0 && ref in graph.entryPoints -> "SOURCE"
                            else -> "PROPAGATOR"
                        }
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("label", ref.id)
                                put("role", role)
                                loc.path?.let { put("file", it) }
                                loc.line?.let { put("line", it) }
                                loc.startColumn?.let { put("startColumn", it) }
                                loc.endColumn?.let { put("endColumn", it) }
                                loc.snippet?.let { put("value", it) }
                            }
                        )
                        prev?.let { edges.add(it to id) }
                        prev = id
                    }

                    val sinkId = "n${nodeCounter++}"
                    add(
                        buildJsonObject {
                            put("id", sinkId)
                            put("label", "sink: ${match.targetClassFqn}#${match.targetMember}")
                            put("role", "SINK")
                            put("file", matchRelativePath)
                            put("line", matchLine)
                            put("startColumn", matchStartColumn)
                            if (matchEndColumn != null) put("endColumn", matchEndColumn)
                            if (sinkLineText != null) put("value", sinkLineText)
                        }
                    )
                    prev?.let { edges.add(it to sinkId) }
                }
            }

        val edgesJson =
            buildJsonArray {
                for ((source, target) in edges) {
                    add(
                        buildJsonObject {
                            put("source", source)
                            put("target", target)
                            put("label", "callChain")
                        }
                    )
                }
            }

        return buildJsonObject {
            put("source", "ide.callChains")
            put("nodes", nodes)
            put("edges", edgesJson)
        }
    }

    private fun buildSarifCodeFlows(
        graph: CallGraph,
        chains: List<List<MethodRef>>,
        match: SinkMatch,
        matchRelativePath: String,
        matchLine: Int,
        matchStartColumn: Int,
        matchEndColumn: Int?,
        sinkLineText: String?,
        fileDocumentManager: FileDocumentManager
    ): JsonArray {
        return buildJsonArray {
            for (chain in chains) {
                val trimmedChain =
                    if (chain.isNotEmpty() && graph.methods[chain.last()] == null) {
                        chain.dropLast(1)
                    } else {
                        chain
                    }
                add(
                    buildJsonObject {
                        putJsonArray("threadFlows") {
                            add(
                                buildJsonObject {
                                    putJsonArray("locations") {
                                        val steps =
                                            trimmedChain.map { ref ->
                                                val loc = locationForMethod(graph.methods[ref], fileDocumentManager)
                                                ref to loc
                                            }

                                        for ((idx, step) in steps.withIndex()) {
                                            val (ref, loc) = step
                                            add(
                                                buildThreadFlowLocation(
                                                    message = buildStepMessage(idx, ref, graph),
                                                    location = loc
                                                )
                                            )
                                        }

                                        add(
                                            buildThreadFlowLocation(
                                                message = "sink: ${match.targetClassFqn}#${match.targetMember}",
                                                location =
                                                    StepLocation(
                                                        path = matchRelativePath,
                                                        line = matchLine,
                                                        startColumn = matchStartColumn,
                                                        endColumn = matchEndColumn,
                                                        snippet = sinkLineText
                                                    )
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    private fun buildStepMessage(index: Int, ref: MethodRef, graph: CallGraph): String {
        if (index == 0 && ref in graph.entryPoints) return "entrypoint: ${ref.id}"
        return "call: ${ref.id}"
    }

    private fun buildThreadFlowLocation(message: String, location: StepLocation): JsonObject =
        buildJsonObject {
            putJsonObject("location") {
                putJsonObject("message") {
                    put("text", message)
                }
                if (location.path != null || location.line != null || location.startColumn != null || location.endColumn != null || location.snippet != null) {
                    putJsonObject("physicalLocation") {
                        location.path?.let { path ->
                            putJsonObject("artifactLocation") {
                                put("uri", path)
                            }
                        }
                        putJsonObject("region") {
                            location.line?.let { put("startLine", it) }
                            location.startColumn?.let { put("startColumn", it) }
                            location.endColumn?.let { put("endColumn", it) }
                            location.snippet?.let { snippet ->
                                putJsonObject("snippet") {
                                    put("text", snippet)
                                }
                            }
                        }
                    }
                }
            }
        }
}

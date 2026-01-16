package com.securitycrux.secrux.intellij.secrux.findingdetail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object FindingDetailParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(responseBody: String): FindingDetailModel {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val success = root.booleanOrNull("success") ?: true
        if (!success) {
            val message = root.stringOrNull("message") ?: "unknown error"
            throw IllegalStateException(message)
        }

        val data = root["data"]?.jsonObject ?: throw IllegalStateException("missing data")

        val callChains = parseCallChains(data)
        val nodeIndex = buildNodeIndex(callChains)
        val enrichment = parseEnrichment(data["enrichment"] as? JsonObject, nodeIndex)

        return FindingDetailModel(
            findingId = data.stringOrNull("findingId") ?: "",
            ruleId = data.stringOrNull("ruleId"),
            sourceEngine = data.stringOrNull("sourceEngine"),
            severity = data.stringOrNull("severity"),
            status = data.stringOrNull("status"),
            taskName = data.stringOrNull("taskName"),
            taskId = data.stringOrNull("taskId"),
            projectName = data.stringOrNull("projectName"),
            projectId = data.stringOrNull("projectId"),
            locationPath = (data["location"] as? JsonObject)?.stringOrNull("path"),
            locationLine = (data["location"] as? JsonObject)?.intOrNull("line"),
            introducedBy = data.stringOrNull("introducedBy"),
            createdAt = data.stringOrNull("createdAt"),
            review = parseReview(data["review"] as? JsonObject),
            snippet = parseCodeSnippet(data["codeSnippet"] as? JsonObject),
            callChains = callChains,
            enrichment = enrichment,
        )
    }

    private fun parseReview(review: JsonObject?): FindingReviewModel? {
        if (review == null) return null
        val opinion = review["opinionI18n"] as? JsonObject
        val zh = opinion?.get("zh") as? JsonObject
        return FindingReviewModel(
            reviewType = review.stringOrNull("reviewType"),
            reviewer = review.stringOrNull("reviewer"),
            verdict = review.stringOrNull("verdict"),
            confidence = review["confidence"]?.let { (it as? JsonPrimitive)?.content },
            summaryZh = zh?.stringOrNull("summary"),
            fixHintZh = zh?.stringOrNull("fixHint"),
        )
    }

    private fun parseCodeSnippet(snippet: JsonObject?): CodeSnippetModel? {
        if (snippet == null) return null
        val path = snippet.stringOrNull("path") ?: return null
        val startLine = snippet.intOrNull("startLine") ?: return null
        val endLine = snippet.intOrNull("endLine") ?: return null
        val lines = snippet["lines"] as? JsonArray ?: return null
        var highlighted: Int? = null
        val code =
            buildString {
                for (entry in lines) {
                    val obj = entry as? JsonObject ?: continue
                    val content = obj.stringOrNull("content") ?: ""
                    val hl = obj.booleanOrNull("highlight") == true
                    if (hl && highlighted == null) {
                        highlighted = obj.intOrNull("lineNumber")
                    }
                    append(content)
                    append('\n')
                }
            }.trimEnd()
        return CodeSnippetModel(
            path = path,
            startLine = startLine,
            endLine = endLine,
            highlightedLine = highlighted,
            code = code,
        )
    }

    private fun parseCallChains(data: JsonObject): List<CallChainModel> {
        val raw = data["callChains"] as? JsonArray ?: return emptyList()
        return raw.mapIndexedNotNull { idx, el ->
            val chainObj = el as? JsonObject ?: return@mapIndexedNotNull null
            val chainId = chainObj.stringOrNull("chainId") ?: "chain${idx + 1}"
            val stepsRaw = chainObj["steps"] as? JsonArray ?: return@mapIndexedNotNull null
            val steps =
                stepsRaw.mapNotNull { stepEl ->
                    val step = stepEl as? JsonObject ?: return@mapNotNull null
                    val nodeId = step.stringOrNull("nodeId") ?: return@mapNotNull null
                    CallChainStepModel(
                        nodeId = nodeId,
                        role = step.stringOrNull("role"),
                        label = step.stringOrNull("label") ?: nodeId,
                        file = step.stringOrNull("file"),
                        line = step.intOrNull("line"),
                        startColumn = step.intOrNull("startColumn"),
                        endColumn = step.intOrNull("endColumn"),
                        snippet = step.stringOrNull("snippet"),
                    )
                }
            CallChainModel(chainId = chainId, steps = steps)
        }.filter { it.steps.isNotEmpty() }
    }

    private fun buildNodeIndex(callChains: List<CallChainModel>): Map<String, Pair<Int, Int>> {
        val index = LinkedHashMap<String, Pair<Int, Int>>()
        for ((chainIdx, chain) in callChains.withIndex()) {
            for ((stepIdx, step) in chain.steps.withIndex()) {
                index.putIfAbsent(step.nodeId, (chainIdx + 1) to (stepIdx + 1))
            }
        }
        return index
    }

    private fun parseEnrichment(enrichment: JsonObject?, nodeIndex: Map<String, Pair<Int, Int>>): EnrichmentModel? {
        if (enrichment == null) return null
        val engine = enrichment.stringOrNull("engine") ?: return null
        val generatedAt = enrichment.stringOrNull("generatedAt")
        val version = enrichment.intOrNull("version")
        val blocks = parseEnrichmentBlocks(enrichment["blocks"] as? JsonArray, nodeIndex)

        val primary = parseEnrichmentPrimary(enrichment["primary"] as? JsonObject)

        val dataflow = enrichment["dataflow"] as? JsonObject
        val nodeMethodsRaw = dataflow?.get("nodeMethods") as? JsonArray ?: JsonArray(emptyList())
        val nodeMethods =
            nodeMethodsRaw.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val nodeId = obj.stringOrNull("nodeId") ?: return@mapNotNull null
                val pos = nodeIndex[nodeId]
                EnrichedNodeMethodModel(
                    nodeId = nodeId,
                    label = obj.stringOrNull("label") ?: nodeId,
                    path = obj.stringOrNull("path"),
                    line = obj.intOrNull("line"),
                    chainIndex = pos?.first,
                    stepIndex = pos?.second,
                    method = parseMethod(obj["method"] as? JsonObject),
                    conditions = parseLineTextList(obj["conditions"] as? JsonArray),
                    invocations = parseLineTextList(obj["invocations"] as? JsonArray),
                )
            }

        val externalSymbols =
            (enrichment["externalSymbols"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            } ?: emptyList()

        val fieldDefinitions =
            (enrichment["fieldDefinitions"] as? JsonArray)?.mapNotNull { el ->
                val obj = el as? JsonObject
                obj?.stringOrNull("text") ?: (el as? JsonPrimitive)?.content
            } ?: emptyList()

        return EnrichmentModel(
            engine = engine,
            generatedAt = generatedAt,
            version = version,
            blocks = blocks,
            primary = primary,
            nodeMethods = nodeMethods,
            externalSymbols = externalSymbols,
            fieldDefinitions = fieldDefinitions,
        )
    }

    private fun parseEnrichmentBlocks(array: JsonArray?, nodeIndex: Map<String, Pair<Int, Int>>): List<EnrichmentBlockModel> {
        if (array == null || array.isEmpty()) return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val blockId = obj.stringOrNull("id") ?: obj.stringOrNull("blockId") ?: return@mapNotNull null
            val kind = obj.stringOrNull("kind") ?: "UNKNOWN"

            val reason = parseReason(obj["reason"] as? JsonObject)
            val fileObj = obj["file"] as? JsonObject
            val filePath = fileObj?.stringOrNull("path") ?: obj.stringOrNull("path")
            val language = fileObj?.stringOrNull("language")

            val range = obj["range"] as? JsonObject
            val startLine = range?.intOrNull("startLine")
            val endLine = range?.intOrNull("endLine")
            val highlightLines =
                (range?.get("highlightLines") as? JsonArray)
                    ?.mapNotNull { item -> (item as? JsonPrimitive)?.content?.toIntOrNull() }
                    ?: emptyList()

            val relatedObj = obj["related"] as? JsonObject
            val findingLine = relatedObj?.intOrNull("findingLine")
            val nodeId = relatedObj?.stringOrNull("nodeId")
            val label = relatedObj?.stringOrNull("label")
            val role = relatedObj?.stringOrNull("role")
            val pos = nodeId?.let { nodeIndex[it] }
            val related =
                if (relatedObj != null) {
                    EnrichmentRelatedModel(
                        findingLine = findingLine,
                        nodeId = nodeId,
                        label = label,
                        role = role,
                        chainIndex = pos?.first,
                        stepIndex = pos?.second,
                    )
                } else {
                    null
                }

            EnrichmentBlockModel(
                blockId = blockId,
                kind = kind,
                reason = reason,
                filePath = filePath,
                language = language,
                startLine = startLine,
                endLine = endLine,
                highlightLines = highlightLines,
                related = related,
                method = parseMethod(obj["method"] as? JsonObject),
                conditions = parseLineTextList(obj["conditions"] as? JsonArray),
                invocations = parseLineTextList(obj["invocations"] as? JsonArray),
            )
        }
    }

    private fun parseReason(reason: JsonObject?): EnrichmentReasonModel? {
        if (reason == null) return null
        val titleI18n = reason["titleI18n"] as? JsonObject
        val detailsI18n = reason["detailsI18n"] as? JsonObject
        return EnrichmentReasonModel(
            code = reason.stringOrNull("code"),
            titleZh = titleI18n?.stringOrNull("zh"),
            titleEn = titleI18n?.stringOrNull("en"),
            detailsZh = detailsI18n?.stringOrNull("zh"),
            detailsEn = detailsI18n?.stringOrNull("en"),
        )
    }

    private fun parseEnrichmentPrimary(primary: JsonObject?): EnrichmentPrimaryModel? {
        if (primary == null) return null
        return EnrichmentPrimaryModel(
            path = primary.stringOrNull("path"),
            line = primary.intOrNull("line"),
            method = parseMethod(primary["method"] as? JsonObject),
            conditions = parseLineTextList(primary["conditions"] as? JsonArray),
            invocations = parseLineTextList(primary["invocations"] as? JsonArray),
        )
    }

    private fun parseMethod(method: JsonObject?): EnrichedMethodModel? {
        if (method == null) return null
        return EnrichedMethodModel(
            signature = method.stringOrNull("signature"),
            name = method.stringOrNull("name"),
            startLine = method.intOrNull("startLine"),
            endLine = method.intOrNull("endLine"),
            language = method.stringOrNull("language"),
            text = method.stringOrNull("text"),
            truncated = method.booleanOrNull("truncated") == true,
        )
    }

    private fun parseLineTextList(array: JsonArray?): List<EnrichedLineText> {
        if (array == null || array.isEmpty()) return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val text = obj.stringOrNull("text") ?: return@mapNotNull null
            EnrichedLineText(
                line = obj.intOrNull("line"),
                text = text,
            )
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toBooleanStrictOrNull()
}

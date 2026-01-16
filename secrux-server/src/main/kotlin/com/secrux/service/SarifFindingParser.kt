package com.secrux.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.Finding
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.domain.Task
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Component
class SarifFindingParser(
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {

    fun parseSarif(
        tenantId: UUID,
        task: Task,
        sarifPath: String
    ): List<Finding> {
        val node = objectMapper.readTree(Path.of(sarifPath).toFile())
        val runs = node.get("runs") ?: return emptyList()
        val now = OffsetDateTime.now(clock)
        return runs.flatMap { run ->
            val ruleLevelById = buildRuleLevelIndex(run)
            val resultsNode = run.get("results") ?: return@flatMap emptyList<Finding>()
            resultsNode.map { result ->
                val ruleId = result.get("ruleId")?.asText() ?: "unknown"
                val resultLevel = result.get("level")?.asText()?.takeIf { it.isNotBlank() }
                val level = resultLevel ?: ruleLevelById[ruleId]
                val message = result.get("message")?.get("text")?.asText() ?: "finding"
                val location = result.get("locations")?.firstOrNull()
                val uri = location?.path("physicalLocation")?.path("artifactLocation")?.path("uri")?.asText() ?: "unknown"
                val line = location?.path("physicalLocation")?.path("region")?.path("startLine")?.asInt() ?: 0
                val fingerprint = result.path("fingerprints").path("primary").asText(UUID.randomUUID().toString())
                val propertiesSeverity = result.path("properties").path("severity").asText(null)
                val severity = mapSarifSeverity(level, propertiesSeverity)
                val evidence = buildEvidenceFromSarifResult(message, result)
                Finding(
                    findingId = UUID.randomUUID(),
                    tenantId = tenantId,
                    taskId = task.taskId,
                    projectId = task.projectId,
                    sourceEngine = "semgrep",
                    ruleId = ruleId,
                    location = mapOf(
                        "path" to uri,
                        "line" to line
                    ),
                    evidence = evidence,
                    severity = severity,
                    fingerprint = fingerprint,
                    status = FindingStatus.OPEN,
                    introducedBy = task.commitSha,
                    fixVersion = null,
                    exploitMaturity = null,
                    createdAt = now,
                    updatedAt = now
                )
            }
        }
    }

    private fun buildRuleLevelIndex(run: JsonNode): Map<String, String> {
        val rules = run.path("tool").path("driver").path("rules")
        if (!rules.isArray) return emptyMap()
        val result = HashMap<String, String>(rules.size())
        for (rule in rules) {
            val id = rule.get("id")?.asText()?.takeIf { it.isNotBlank() } ?: continue
            val level = rule.path("defaultConfiguration").path("level").asText("").takeIf { it.isNotBlank() } ?: continue
            result[id] = level
        }
        return result
    }

    private fun buildEvidenceFromSarifResult(message: String, result: JsonNode): Map<String, Any?> {
        val evidence = mutableMapOf<String, Any?>("message" to message)

        val properties = result.get("properties")
        if (properties != null && !properties.isNull) {
            runCatching {
                objectMapper.convertValue(properties, object : TypeReference<Map<String, Any?>>() {})
            }.getOrNull()?.let { evidence["properties"] = it }
        }

        val dataflow = extractDataflowFromSarif(result)
        if (dataflow != null) {
            evidence["dataflow"] = dataflow
        }

        return evidence
    }

    private fun extractDataflowFromSarif(result: JsonNode): Map<String, Any?>? {
        val codeFlows = result.get("codeFlows") ?: return null
        if (!codeFlows.isArray || codeFlows.isEmpty) return null

        val nodes = mutableListOf<Map<String, Any?>>()
        val edges = mutableListOf<Map<String, Any?>>()
        var nodeCounter = 0

        fun stripInlineLocation(text: String?): String? {
            val value = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val atIndex = value.indexOf(" @")
            return if (atIndex >= 0) value.substring(0, atIndex).trim() else value
        }

        fun inferSemgrepRole(rawLabel: String?): String? {
            val value = rawLabel?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val colonIndex = value.indexOf(':')
            if (colonIndex <= 0) return null
            return when (value.substring(0, colonIndex).trim().lowercase()) {
                "source" -> "SOURCE"
                "propagator" -> "PROPAGATOR"
                "sink" -> "SINK"
                else -> null
            }
        }

        fun inferSemgrepValue(rawLabel: String?): String? {
            val value = rawLabel?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val colonIndex = value.indexOf(':')
            if (colonIndex <= 0) return null
            var rest = value.substring(colonIndex + 1).trim()
            val atIndex = rest.indexOf(" @")
            if (atIndex >= 0) {
                rest = rest.substring(0, atIndex).trim()
            }
            return rest.trim().trim('\'', '"').takeIf { it.isNotBlank() }
        }

        fun addNode(
            file: String?,
            line: Int?,
            startColumn: Int?,
            endColumn: Int?,
            message: String?,
            snippet: String?
        ): String {
            val id = "n${nodeCounter++}"
            val role = inferSemgrepRole(message)
            val value = snippet?.trim()?.takeIf { it.isNotBlank() } ?: inferSemgrepValue(message)
            val label = value ?: stripInlineLocation(message)
            nodes.add(
                mapOf(
                    "id" to id,
                    "label" to (label ?: listOfNotNull(file, line?.toString()).joinToString(":").ifBlank { id }),
                    "role" to role,
                    "value" to value,
                    "file" to file,
                    "line" to line,
                    "startColumn" to startColumn,
                    "endColumn" to endColumn
                )
            )
            return id
        }

        for (codeFlow in codeFlows) {
            val threadFlows = codeFlow.get("threadFlows") ?: continue
            if (!threadFlows.isArray) continue

            for (threadFlow in threadFlows) {
                val locations = threadFlow.get("locations") ?: continue
                if (!locations.isArray) continue

                val locationNodes = locations.take(50)
                var prev: String? = null
                for (tfl in locationNodes) {
                    val loc = tfl.get("location") ?: tfl.get("physicalLocation") ?: continue
                    val phys = loc.get("physicalLocation") ?: loc
                    val file = phys.path("artifactLocation").path("uri").asText(null)
                    val region = phys.path("region")
                    val line = region.path("startLine").asInt(0).takeIf { it > 0 }
                    val startColumn = region.path("startColumn").asInt(0).takeIf { it > 0 }
                    val endColumn = region.path("endColumn").asInt(0).takeIf { it > 0 }
                    val snippet = region.path("snippet").path("text").asText(null)
                    val text = tfl.path("location").path("message").path("text").asText(null)
                        ?: tfl.path("message").path("text").asText(null)
                    val id = addNode(file, line, startColumn, endColumn, text, snippet)
                    prev?.let { p ->
                        edges.add(mapOf("source" to p, "target" to id, "label" to "dataflow"))
                    }
                    prev = id
                }
            }
        }

        if (nodes.isEmpty()) return null
        return mapOf(
            "nodes" to nodes,
            "edges" to edges,
            "source" to "sarif.codeFlows"
        )
    }
}

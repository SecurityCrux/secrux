package com.secrux.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@Component
class SemgrepSarifConverter(
    private val objectMapper: ObjectMapper
) {

    companion object {
        private const val SARIF_VERSION = "2.1.0"
        private const val SARIF_SCHEMA =
            "https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/schemas/sarif-schema-2.1.0.json"
    }

    fun supports(path: Path): Boolean {
        if (!Files.exists(path) || Files.isDirectory(path)) return false
        if (path.extension.lowercase() != "json") return false
        return runCatching {
            objectMapper.readTree(path.toFile()).path("results").isArray
        }.getOrElse { false }
    }

    fun convert(source: Path): Path {
        val payload =
            runCatching { objectMapper.readValue(source.toFile(), SemgrepJsonPayload::class.java) }
                .getOrElse { ex ->
                    throw SecruxException(
                        ErrorCode.VALIDATION_ERROR,
                        "Failed to parse Semgrep JSON ${source.absolutePathString()}: ${ex.message}"
                    )
                }
        val sarifNode = buildSarif(payload)
        val target = source.resolveSibling("${source.nameWithoutExtension}.sarif.json")
        target.parent?.createDirectories()
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), sarifNode)
        return target
    }

    private fun buildSarif(payload: SemgrepJsonPayload): ObjectNode {
        val root = objectMapper.createObjectNode()
        root.put("version", SARIF_VERSION)
        root.put("\$schema", SARIF_SCHEMA)
        val runs = root.putArray("runs")
        val run = runs.addObject()
        val tool = run.putObject("tool")
        val driver = tool.putObject("driver")
        driver.put("name", "Semgrep")
        driver.put("informationUri", "https://semgrep.dev")
        payload.version?.let { driver.put("semanticVersion", it) }
        val rulesArray = driver.putArray("rules")
        val ruleCache = mutableMapOf<String, ObjectNode>()
        val resultsArray = run.putArray("results")

        payload.results.forEach { match ->
            val ruleId = match.checkId ?: "semgrep.unknown"
            ruleCache.getOrPut(ruleId) {
                buildRule(ruleId, match).also { rulesArray.add(it) }
            }
            val resultNode = resultsArray.addObject()
            resultNode.put("ruleId", ruleId)
            resultNode.put("kind", "fail")
            resultNode.put("level", mapLevel(match.extra?.severity))
            resultNode.set<ObjectNode>(
                "message",
                objectMapper.createObjectNode().put("text", match.extra?.message ?: ruleId)
            )
            resultNode.set<ArrayNode>("locations", buildLocations(match))
            val fingerprints = resultNode.putObject("fingerprints")
            val fingerprint = match.extra?.fingerprint ?: buildDefaultFingerprint(match)
            fingerprints.put("primary", fingerprint)
            val properties = resultNode.putObject("properties")
            match.extra?.lines?.let { properties.put("context", it) }
            match.extra?.fix?.let { properties.put("fix", it) }
            match.extra?.metadata?.let {
                properties.set<ObjectNode>("metadata", objectMapper.valueToTree(it))
            }
            match.extra?.metavars?.let {
                properties.set<ObjectNode>("metavars", objectMapper.valueToTree(it))
            }
        }

        if (payload.errors.isNotEmpty()) {
            val invocation = run.putArray("invocations").addObject()
            invocation.put("executionSuccessful", payload.errors.isEmpty())
            val notifications = invocation.putArray("toolExecutionNotifications")
            payload.errors.forEach { error ->
                val notice = notifications.addObject()
                notice.put("level", "error")
                notice.set<ObjectNode>(
                    "message",
                    objectMapper.createObjectNode().put("text", error.message ?: "Semgrep error")
                )
            }
        }

        return root
    }

    private fun buildRule(ruleId: String, match: SemgrepMatch): ObjectNode {
        val node = objectMapper.createObjectNode()
        node.put("id", ruleId)
        node.putObject("shortDescription").put("text", match.extra?.message ?: ruleId)
        node.putObject("defaultConfiguration").put("level", mapLevel(match.extra?.severity))
        match.extra?.metadata?.let {
            node.putObject("properties").set<ObjectNode>("metadata", objectMapper.valueToTree(it))
        }
        return node
    }

    private fun buildLocations(match: SemgrepMatch): com.fasterxml.jackson.databind.node.ArrayNode {
        val locations = objectMapper.createArrayNode()
        val physicalLocation = objectMapper.createObjectNode()
        val artifactLocation = objectMapper.createObjectNode()
        artifactLocation.put("uri", match.path ?: "unknown")
        physicalLocation.set<ObjectNode>("artifactLocation", artifactLocation)
        val region = objectMapper.createObjectNode()
        region.put("startLine", match.start?.line ?: 1)
        region.put("startColumn", match.start?.col ?: 1)
        region.put("endLine", match.end?.line ?: region.get("startLine").asInt())
        region.put("endColumn", match.end?.col ?: region.get("startColumn").asInt())
        physicalLocation.set<ObjectNode>("region", region)
        locations.add(objectMapper.createObjectNode().set<ObjectNode>("physicalLocation", physicalLocation))
        return locations
    }

    private fun mapLevel(severity: String?): String =
        when (severity?.lowercase()) {
            "critical", "error", "high" -> "error"
            "warning", "medium" -> "warning"
            else -> "note"
        }

    private fun buildDefaultFingerprint(match: SemgrepMatch): String {
        val path = match.path ?: "unknown"
        val line = match.start?.line ?: 0
        val rule = match.checkId ?: "semgrep.unknown"
        return "$rule:$path:$line"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemgrepJsonPayload(
    val results: List<SemgrepMatch> = emptyList(),
    val errors: List<SemgrepError> = emptyList(),
    val version: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemgrepMatch(
    @JsonProperty("check_id") val checkId: String? = null,
    val path: String? = null,
    val start: SemgrepPosition? = null,
    val end: SemgrepPosition? = null,
    val extra: SemgrepExtra? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemgrepPosition(
    val line: Int? = null,
    val col: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemgrepExtra(
    val message: String? = null,
    val severity: String? = null,
    val fingerprint: String? = null,
    val fix: String? = null,
    val lines: String? = null,
    val metadata: Map<String, Any?>? = null,
    val metavars: Map<String, Any?>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemgrepError(
    val message: String? = null
)


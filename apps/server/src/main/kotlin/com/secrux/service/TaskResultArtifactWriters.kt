package com.secrux.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.secrux.domain.Task
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

internal data class ScanArtifactPaths(
    val resultPath: Path? = null,
    val sbomPath: Path? = null,
    val graphPath: Path? = null,
    val usageIndexPath: Path? = null,
    val stageArtifactEntries: List<String> = emptyList()
)

internal class TextFileWriter {
    fun write(path: Path, content: String): Path {
        Files.writeString(
            path,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        return path
    }
}

internal class SarifArtifactPersister(
    private val workspaceRootDir: String,
    private val objectMapper: ObjectMapper,
    private val fileWriter: TextFileWriter
) {

    private val uriRewriter = SarifUriRewriter(workspaceRootDir, objectMapper)

    fun persist(taskId: UUID, stageId: UUID, payload: ExecutorTaskResultPayload): ScanArtifactPaths {
        val sarifPayload = payload.result?.takeIf { it.isNotBlank() } ?: return ScanArtifactPaths()
        val dir = Path.of("build", "sarif", taskId.toString()).createDirectories()
        val sarifPath = dir.resolve("$stageId.sarif.json")
        val rewritten = uriRewriter.rewrite(taskId, sarifPayload)
        fileWriter.write(sarifPath, rewritten)
        return ScanArtifactPaths(
            resultPath = sarifPath,
            stageArtifactEntries = listOf("sarif:${sarifPath.absolutePathString()}")
        )
    }
}

internal class ScaArtifactPersister(
    private val dependencyGraphService: CycloneDxDependencyGraphService,
    private val fileWriter: TextFileWriter
) {

    fun persist(task: Task, stageId: UUID, payload: ExecutorTaskResultPayload): ScanArtifactPaths {
        val outputDir = Path.of("build", "sca", task.taskId.toString(), stageId.toString()).createDirectories()
        val vulnPath =
            payload.result?.takeIf { it.isNotBlank() }?.let {
                val path = outputDir.resolve("trivy-vulns.json")
                fileWriter.write(path, it)
            }

        val sbomPayload =
            payload.artifacts
                ?.entries
                ?.firstOrNull { (key, value) -> key.isSbomKey() && value.isNotBlank() }
                ?.value

        val sbomPath =
            sbomPayload?.let { content ->
                val path = outputDir.resolve("sbom.cdx.json")
                fileWriter.write(path, content)
            }

        val graphPayload =
            payload.artifacts
                ?.entries
                ?.firstOrNull { (key, value) -> key.isDependencyGraphKey() && value.isNotBlank() }
                ?.value

        val graphPath =
            when {
                graphPayload != null -> fileWriter.write(outputDir.resolve("dependency-graph.json"), graphPayload)
                sbomPath != null -> {
                    val path = outputDir.resolve("dependency-graph.json")
                    runCatching { dependencyGraphService.writeGraph(sbomPath, path) }.getOrNull()
                    path.takeIf { it.exists() && it.isRegularFile() }
                }
                else -> null
            }

        val usageIndexPayload =
            payload.artifacts
                ?.entries
                ?.firstOrNull { (key, value) -> key.isUsageIndexKey() && value.isNotBlank() }
                ?.value

        val usageIndexPath =
            usageIndexPayload?.let { content ->
                val path = outputDir.resolve("sca-usage-index.json")
                fileWriter.write(path, content)
            }

        val entries =
            buildList {
                vulnPath?.let { add("sca:vulns:${it.toAbsolutePath()}") }
                sbomPath?.let { add("sca:sbom:${it.toAbsolutePath()}") }
                graphPath?.let { add("sca:graph:${it.toAbsolutePath()}") }
                usageIndexPath?.let { add("sca:usage:${it.toAbsolutePath()}") }
            }
        return ScanArtifactPaths(
            resultPath = vulnPath,
            sbomPath = sbomPath,
            graphPath = graphPath,
            usageIndexPath = usageIndexPath,
            stageArtifactEntries = entries
        )
    }
}

private class SarifUriRewriter(
    private val workspaceRootDir: String,
    private val objectMapper: ObjectMapper
) {

    fun rewrite(taskId: UUID, sarifPayload: String): String {
        val workspace = Path.of(workspaceRootDir).toAbsolutePath().normalize().resolve(taskId.toString()).normalize()
        val prefix = workspace.toString().replace('\\', '/').trimEnd('/') + "/"

        fun rewriteUri(raw: String): String {
            val value = raw.trim().replace('\\', '/')
            if (value.isBlank()) return raw
            if (value == "/src" || value == "file:///src") return workspace.toString()
            if (value.startsWith("file:///src/")) return prefix + value.removePrefix("file:///src/")
            if (value.startsWith("/src/")) return prefix + value.removePrefix("/src/")
            val uri = runCatching { URI(value) }.getOrNull()
            if (uri != null && !uri.scheme.isNullOrBlank()) {
                if (uri.scheme == "file" && uri.path != null && uri.path.startsWith("/src/")) {
                    return prefix + uri.path.removePrefix("/src/")
                }
                return raw
            }
            val p = runCatching { Path.of(value) }.getOrNull()
            if (p != null && !p.isAbsolute) {
                return prefix + value
            }
            return raw
        }

        return runCatching {
            val root = objectMapper.readTree(sarifPayload)
            rewriteSarif(root, ::rewriteUri)
            objectMapper.writeValueAsString(root)
        }.getOrElse { sarifPayload }
    }

    private fun rewriteSarif(root: JsonNode, rewriteUri: (String) -> String) {
        val runs = root.path("runs")
        if (!runs.isArray) return
        for (run in runs) {
            val results = run.path("results")
            if (!results.isArray) continue
            for (result in results) {
                rewriteLocations(result.path("locations"), rewriteUri)
                rewriteCodeFlows(result.path("codeFlows"), rewriteUri)
            }
        }
    }

    private fun rewriteLocations(locations: JsonNode, rewriteUri: (String) -> String) {
        if (!locations.isArray) return
        for (loc in locations) {
            val artifactLocation = loc.path("physicalLocation").path("artifactLocation")
            rewriteArtifactLocation(artifactLocation, rewriteUri)
        }
    }

    private fun rewriteCodeFlows(codeFlows: JsonNode, rewriteUri: (String) -> String) {
        if (!codeFlows.isArray) return
        for (codeFlow in codeFlows) {
            val threadFlows = codeFlow.path("threadFlows")
            if (!threadFlows.isArray) continue
            for (threadFlow in threadFlows) {
                val threadLocations = threadFlow.path("locations")
                if (!threadLocations.isArray) continue
                for (tfl in threadLocations) {
                    val artifactLocation =
                        tfl.path("location")
                            .path("physicalLocation")
                            .path("artifactLocation")
                    rewriteArtifactLocation(artifactLocation, rewriteUri)
                }
            }
        }
    }

    private fun rewriteArtifactLocation(artifactLocation: JsonNode, rewriteUri: (String) -> String) {
        if (!artifactLocation.isObject) return
        val uriNode = artifactLocation.get("uri")?.takeIf { it.isTextual } ?: return
        (artifactLocation as ObjectNode).put("uri", rewriteUri(uriNode.asText()))
    }
}

private fun String.isSbomKey(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "sbom" ||
        normalized == "sca:sbom" ||
        normalized.endsWith("/sbom.cdx.json") ||
        normalized.endsWith("sbom.cdx.json")
}

private fun String.isDependencyGraphKey(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "graph" ||
        normalized == "sca:graph" ||
        normalized == "dependency-graph.json" ||
        normalized.endsWith("/dependency-graph.json") ||
        normalized == "dependencygraph"
}

private fun String.isUsageIndexKey(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "usage" ||
        normalized == "usage-index" ||
        normalized == "sca:usage" ||
        normalized == "sca:usage-index" ||
        normalized == "sca-usage-index.json" ||
        normalized.endsWith("/sca-usage-index.json") ||
        normalized.endsWith("sca-usage-index.json") ||
        normalized == "usage-index.json" ||
        normalized.endsWith("/usage-index.json") ||
        normalized.endsWith("usage-index.json")
}

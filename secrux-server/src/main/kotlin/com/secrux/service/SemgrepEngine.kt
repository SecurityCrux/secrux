package com.secrux.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.config.SemgrepProperties
import com.secrux.domain.SarifResult
import com.secrux.domain.Severity
import com.secrux.support.CommandRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class SemgrepExecutionResult(
    val sarifFile: Path,
    val sarif: JsonNode,
    val results: List<SarifResult>
)

data class SemgrepRunOptions(
    val usePro: Boolean = false,
    val appToken: String? = null,
    val enableDataflowTraces: Boolean = true
)

@Component
open class SemgrepEngine(
    private val commandRunner: CommandRunner,
    private val objectMapper: ObjectMapper,
    private val properties: SemgrepProperties
) {

    private val log = LoggerFactory.getLogger(SemgrepEngine::class.java)

    open fun run(paths: List<String>, options: SemgrepRunOptions = SemgrepRunOptions()): SemgrepExecutionResult {
        if (paths.isEmpty()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Semgrep requires at least one path")
        }
        val outputFile = Files.createTempFile("semgrep-", ".sarif.json")
        val command = buildCommand(outputFile, paths, options)
        val timeout = Duration.ofSeconds(properties.timeoutSeconds)
        val workingDir = properties.workingDirectory?.let { Path.of(it) }
        val environment =
            buildMap<String, String> {
                if (!options.appToken.isNullOrBlank()) {
                    put("SEMGREP_APP_TOKEN", options.appToken)
                }
            }
        log.info(
            "event=semgrep_exec_started paths={} pro={} dataflowTraces={}",
            paths,
            options.usePro,
            options.usePro && options.enableDataflowTraces
        )
        val result = commandRunner.run(command, workingDir, timeout, environment)
        if (result.exitCode !in setOf(0, 1)) {
            Files.deleteIfExists(outputFile)
            throw SecruxException(
                ErrorCode.SCAN_EXECUTION_FAILED,
                "Semgrep failed with exit code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}"
            )
        }
        val sarifNode = objectMapper.readTree(outputFile.toFile())
        val findings = parseFindings(sarifNode)
        log.info("event=semgrep_exec_completed findings={}", findings.size)
        return SemgrepExecutionResult(outputFile, sarifNode, findings)
    }

    private fun buildCommand(outputFile: Path, paths: List<String>, options: SemgrepRunOptions): List<String> {
        val command = mutableListOf(
            properties.executable,
            "--config", properties.config,
            "--sarif",
            "--output", outputFile.toAbsolutePath().toString(),
            "--disable-version-check"
        )
        if (options.usePro && !command.contains("--pro")) {
            command.add("--pro")
        }
        if (options.usePro && options.enableDataflowTraces && !command.contains("--dataflow-traces")) {
            command.add("--dataflow-traces")
        }
        if (properties.additionalArgs.isNotEmpty()) {
            command.addAll(properties.additionalArgs)
        }
        command.addAll(paths)
        return command
    }

    private fun parseFindings(root: JsonNode): List<SarifResult> {
        val runs = root.path("runs")
        if (!runs.isArray) return emptyList()
        val findings = mutableListOf<SarifResult>()
        runs.forEach { run ->
            val resultsNode = run.path("results")
            if (resultsNode.isArray) {
                resultsNode.forEach { node ->
                    toSarifResult(node)?.let { findings.add(it) }
                }
            }
        }
        return findings
    }

    private fun toSarifResult(node: JsonNode): SarifResult? {
        val ruleId = node.path("ruleId").asText(null) ?: return null
        val message = node.path("message").path("text").asText("")
        val level = node.path("level").asText("")
        val locationNode = firstLocation(node.path("locations"))
        val path = locationNode?.path("artifactLocation")?.path("uri")?.asText("unknown") ?: "unknown"
        val line = locationNode?.path("region")?.path("startLine")?.asInt(0) ?: 0
        val fingerprint = node.path("fingerprints").path("primary").asText(null)
            ?: "$ruleId:$path:$line"
        return SarifResult(
            ruleId = ruleId,
            severity = mapSarifSeverity(level),
            message = message,
            path = path,
            line = line,
            fingerprint = fingerprint
        )
    }

    private fun firstLocation(locations: JsonNode): JsonNode? =
        if (locations.isArray && locations.size() > 0) {
            locations[0].path("physicalLocation")
        } else {
            null
        }

}

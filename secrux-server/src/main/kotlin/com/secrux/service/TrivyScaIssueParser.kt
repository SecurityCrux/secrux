package com.secrux.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.FindingStatus
import com.secrux.domain.ScaIssue
import com.secrux.domain.Severity
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Component
class TrivyScaIssueParser(
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {

    fun parse(task: com.secrux.domain.Task, trivyJsonPath: Path): List<ScaIssue> {
        val root = objectMapper.readTree(trivyJsonPath.toFile())
        val results = root.path("Results")
        if (!results.isArray) return emptyList()
        val now = OffsetDateTime.now(clock)
        val introducedBy = resolveIntroducedBy(task)
        val issues = mutableListOf<ScaIssue>()
        results.forEach resultLoop@{ result ->
            val target = result.path("Target").asText(null)
            val klass = result.path("Class").asText(null)
            val type = result.path("Type").asText(null)
            val vulns = result.path("Vulnerabilities")
            if (!vulns.isArray) return@resultLoop
            vulns.forEach vulnLoop@{ vuln ->
                val vulnId = vuln.path("VulnerabilityID").asText(null)?.trim()
                if (vulnId.isNullOrBlank()) return@vulnLoop
                val pkgName = vuln.path("PkgName").asText(null)
                val installedVersion = vuln.path("InstalledVersion").asText(null)
                val fixedVersion = vuln.path("FixedVersion").asText(null)
                val severity = mapSeverity(vuln.path("Severity").asText(null))
                val primaryUrl = vuln.path("PrimaryURL").asText(null)
                val purl = resolvePurl(vuln)

                val location =
                    buildMap<String, Any?> {
                        put("target", target)
                        put("class", klass)
                        put("type", type)
                        put("package", pkgName)
                        put("installedVersion", installedVersion)
                        put("fixedVersion", fixedVersion)
                        put("primaryUrl", primaryUrl)
                        put("purl", purl)
                    }.filterValues { it != null }

                val evidence =
                    buildMap<String, Any?> {
                        put("title", vuln.path("Title").asText(null))
                        put("description", vuln.path("Description").asText(null))
                        put("severity", vuln.path("Severity").asText(null))
                        put("publishedDate", vuln.path("PublishedDate").asText(null))
                        put("lastModifiedDate", vuln.path("LastModifiedDate").asText(null))
                        put("references", toStringList(vuln.path("References")))
                        put("cvss", parseCvss(vuln.path("CVSS")))
                        put("pkgName", pkgName)
                        put("installedVersion", installedVersion)
                        put("fixedVersion", fixedVersion)
                        put("primaryUrl", primaryUrl)
                        put("target", target)
                        put("purl", purl)
                    }.filterValues { it != null }

                val issueKey = buildIssueKey(vulnId, pkgName, installedVersion, purl)

                issues.add(
                    ScaIssue(
                        issueId = UUID.randomUUID(),
                        tenantId = task.tenantId,
                        taskId = task.taskId,
                        projectId = task.projectId,
                        sourceEngine = "trivy",
                        vulnId = vulnId,
                        location = location,
                        evidence = evidence,
                        severity = severity,
                        status = FindingStatus.OPEN,
                        introducedBy = introducedBy,
                        packageName = pkgName,
                        installedVersion = installedVersion,
                        fixedVersion = fixedVersion,
                        primaryUrl = primaryUrl,
                        componentPurl = purl,
                        componentName = pkgName,
                        componentVersion = installedVersion,
                        issueKey = issueKey,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
        return issues
    }

    private fun resolveIntroducedBy(task: com.secrux.domain.Task): String? {
        task.commitSha?.takeIf { it.isNotBlank() }?.let { return it }
        val source = task.spec.source
        source.image?.ref?.takeIf { it.isNotBlank() }?.let { return it }
        source.filesystem?.path?.takeIf { it.isNotBlank() }?.let { return it }
        source.sbom?.uploadId?.takeIf { it.isNotBlank() }?.let { return "sbom:$it" }
        source.sbom?.url?.takeIf { it.isNotBlank() }?.let { return "sbom:$it" }
        return task.sourceRef?.takeIf { it.isNotBlank() }
    }

    private fun mapSeverity(raw: String?): Severity =
        when (raw?.trim()?.uppercase()) {
            "CRITICAL" -> Severity.CRITICAL
            "HIGH" -> Severity.HIGH
            "MEDIUM" -> Severity.MEDIUM
            "LOW" -> Severity.LOW
            else -> Severity.INFO
        }

    private fun buildIssueKey(
        vulnId: String,
        pkgName: String?,
        installedVersion: String?,
        purl: String?
    ): String {
        val raw =
            buildString {
                append(vulnId)
                append('|')
                append(pkgName ?: "")
                append('|')
                append(installedVersion ?: "")
                append('|')
                append(purl ?: "")
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun resolvePurl(vuln: JsonNode): String? {
        val identifier = vuln.path("PkgIdentifier")
        if (identifier.isObject) {
            identifier.path("PURL").asText(null)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            identifier.path("Purl").asText(null)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            identifier.path("purl").asText(null)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return vuln.path("PURL").asText(null)?.trim()?.takeIf { it.isNotBlank() }
            ?: vuln.path("Purl").asText(null)?.trim()?.takeIf { it.isNotBlank() }
            ?: vuln.path("purl").asText(null)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun toStringList(node: JsonNode): List<String>? {
        if (!node.isArray) return null
        val values = node.mapNotNull { it.asText(null)?.trim()?.takeIf { value -> value.isNotBlank() } }
        return values.takeIf { it.isNotEmpty() }
    }

    private fun parseCvss(node: JsonNode): Map<String, Any?>? {
        if (node.isMissingNode || node.isNull) return null
        if (!node.isObject) return null
        val map = mutableMapOf<String, Any?>()
        node.fields().forEach { (key, value) ->
            if (value.isObject) {
                map[key] = mapOf(
                    "v3Score" to value.path("V3Score").asDouble(),
                    "v3Vector" to value.path("V3Vector").asText(null)
                ).filterValues { it != null }
            }
        }
        return map.takeIf { it.isNotEmpty() }
    }
}

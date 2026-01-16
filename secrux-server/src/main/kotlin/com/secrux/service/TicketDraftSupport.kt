package com.secrux.service

import com.secrux.domain.Finding
import com.secrux.domain.ScaIssue
import java.security.MessageDigest
import java.util.UUID

internal object TicketDedupeKeys {
    fun compute(
        projectId: UUID,
        provider: String,
        findingIds: List<UUID>,
        scaIssueIds: List<UUID> = emptyList()
    ): String {
        val input =
            buildString {
                append(projectId)
                append('|')
                append(provider.trim().lowercase())
                append('|')
                append(findingIds.distinct().sorted().joinToString(","))
                if (scaIssueIds.isNotEmpty()) {
                    append('|')
                    append(scaIssueIds.distinct().sorted().joinToString(","))
                }
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal object TicketSeverities {
    fun maxSeverityName(findings: List<Finding>, issues: List<ScaIssue>): String =
        (findings.map { it.severity } + issues.map { it.severity })
            .maxBy { severityRank(it.name) }
            .name

    private fun severityRank(value: String): Int =
        when (value) {
            "CRITICAL" -> 5
            "HIGH" -> 4
            "MEDIUM" -> 3
            "LOW" -> 2
            else -> 1
        }
}

internal object TicketDraftText {
    fun resolveSummary(titleI18n: Map<String, Any?>?, projectId: UUID, itemCount: Int): String {
        val raw = (titleI18n?.get("en") as? String)?.takeIf { it.isNotBlank() }
            ?: (titleI18n?.get("zh") as? String)?.takeIf { it.isNotBlank() }
        if (raw != null) return raw
        return "Security issues ($itemCount) for project $projectId"
    }

    fun resolveDescription(descriptionI18n: Map<String, Any?>?, findings: List<Finding>, issues: List<ScaIssue>): String {
        val raw = (descriptionI18n?.get("en") as? String)?.takeIf { it.isNotBlank() }
            ?: (descriptionI18n?.get("zh") as? String)?.takeIf { it.isNotBlank() }
        if (raw != null) return raw
        return buildString {
            if (findings.isNotEmpty()) {
                appendLine("Code findings:")
                findings.forEach { finding ->
                    val locPath = finding.location["path"] ?: "unknown"
                    val locLine = finding.location["line"] ?: finding.location["startLine"] ?: ""
                    append("- ")
                    append(finding.ruleId ?: "N/A")
                    append(" | ")
                    append(finding.severity.name)
                    append(" | ")
                    append(locPath)
                    append(":")
                    append(locLine)
                    appendLine()
                }
                appendLine()
            }
            if (issues.isNotEmpty()) {
                appendLine("SCA issues:")
                issues.forEach { issue ->
                    append("- ")
                    append(issue.vulnId)
                    append(" | ")
                    append(issue.severity.name)
                    issue.packageName?.takeIf { it.isNotBlank() }?.let {
                        append(" | ")
                        append(it)
                    }
                    issue.installedVersion?.takeIf { it.isNotBlank() }?.let {
                        append("@")
                        append(it)
                    }
                    issue.fixedVersion?.takeIf { it.isNotBlank() }?.let {
                        append(" -> ")
                        append(it)
                    }
                    appendLine()
                }
            }
        }.trim()
    }
}


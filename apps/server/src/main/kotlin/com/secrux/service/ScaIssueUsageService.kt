package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.ScaIssue
import com.secrux.dto.PageResponse
import com.secrux.dto.ScaUsageEntryDto
import com.secrux.repo.ScaIssueRepository
import org.springframework.stereotype.Service
import java.util.UUID

data class ScaUsageEvidence(
    val generatedAt: String? = null,
    val scannedFiles: Int? = null,
    val entries: List<ScaUsageEntryDto> = emptyList()
)

@Service
class ScaIssueUsageService(
    private val scaIssueRepository: ScaIssueRepository,
    private val scaTaskArtifactService: ScaTaskArtifactService,
    private val usageIndexLoader: ScaUsageIndexLoader
) {

    fun listUsageEntries(
        tenantId: UUID,
        issueId: UUID,
        limit: Int,
        offset: Int
    ): PageResponse<ScaUsageEntryDto> {
        val issue = scaIssueRepository.findById(tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        return listUsageEntries(tenantId, issue, limit, offset)
    }

    fun listUsageEntries(
        tenantId: UUID,
        issue: ScaIssue,
        limit: Int,
        offset: Int
    ): PageResponse<ScaUsageEntryDto> {
        val evidence = loadEvidenceOrNull(tenantId, issue) ?: return PageResponse(emptyList(), 0L, limit, offset)
        val total = evidence.entries.size
        val start = offset.coerceAtLeast(0).coerceAtMost(total)
        val end = (start + limit.coerceAtLeast(0)).coerceAtMost(total)
        val items = if (start >= end) emptyList() else evidence.entries.subList(start, end)
        return PageResponse(items = items, total = total.toLong(), limit = limit, offset = offset)
    }

    fun loadEvidenceOrNull(
        tenantId: UUID,
        issue: ScaIssue,
        maxEntries: Int = 80
    ): ScaUsageEvidence? {
        val usagePath = scaTaskArtifactService.resolveLatestUsageIndexPathOrNull(tenantId, issue.taskId) ?: return null
        val usageIndex = usageIndexLoader.load(usagePath) ?: return null
        val matches = matchEntries(issue, usageIndex).take(maxEntries)
        return ScaUsageEvidence(
            generatedAt = usageIndex.generatedAt,
            scannedFiles = usageIndex.scannedFiles,
            entries = matches.map { it.toDto() }
        )
    }

    private fun matchEntries(issue: ScaIssue, index: ScaUsageIndex): List<ScaUsageIndexEntry> {
        val matchKeys = buildMatchKeys(issue)
        if (matchKeys.isEmpty()) return emptyList()
        return index.entries.filter { entry ->
            val key = entry.key?.trim()?.lowercase() ?: return@filter false
            matchKeys.contains(key)
        }
    }

    private fun buildMatchKeys(issue: ScaIssue): Set<String> {
        val keys = LinkedHashSet<String>()
        fun add(value: String?) {
            value?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        }
        add(issue.componentPurl)
        add(issue.packageName)
        add(issue.componentName)

        val purl = issue.componentPurl?.trim()?.lowercase()
        if (!purl.isNullOrBlank() && purl.startsWith("pkg:")) {
            val withoutQualifiers = purl.split('?', '#').firstOrNull()?.trim()
            if (!withoutQualifiers.isNullOrBlank()) {
                add(withoutQualifiers)
            }
            val mavenPrefix = "pkg:maven/"
            if (withoutQualifiers != null && withoutQualifiers.startsWith(mavenPrefix)) {
                val coords = withoutQualifiers.removePrefix(mavenPrefix)
                val coordParts = coords.split("@").firstOrNull()?.split('/')
                if (coordParts != null && coordParts.size >= 2) {
                    add("${coordParts[0]}:${coordParts[1]}")
                    add(coordParts[0])
                    add(coordParts[1])
                }
            }
            val npmPrefix = "pkg:npm/"
            if (withoutQualifiers != null && withoutQualifiers.startsWith(npmPrefix)) {
                val name = withoutQualifiers.removePrefix(npmPrefix).split("@").firstOrNull()
                add(name)
            }
            val golangPrefix = "pkg:golang/"
            if (withoutQualifiers != null && withoutQualifiers.startsWith(golangPrefix)) {
                val name = withoutQualifiers.removePrefix(golangPrefix).split("@").firstOrNull()
                add(name)
            }
        }
        return keys
    }
}

private fun ScaUsageIndexEntry.toDto(): ScaUsageEntryDto =
    ScaUsageEntryDto(
        ecosystem = ecosystem,
        key = key,
        file = file,
        line = line,
        kind = kind,
        snippet = snippet,
        language = language,
        symbol = symbol,
        receiver = receiver,
        callee = callee,
        startLine = startLine,
        startCol = startCol,
        endLine = endLine,
        endCol = endCol,
        confidence = confidence,
    )

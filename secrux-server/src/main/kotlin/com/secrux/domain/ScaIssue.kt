package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class ScaIssue(
    val issueId: UUID,
    val tenantId: UUID,
    val taskId: UUID,
    val projectId: UUID,
    val sourceEngine: String,
    val vulnId: String,
    val severity: Severity,
    val status: FindingStatus,
    val packageName: String? = null,
    val installedVersion: String? = null,
    val fixedVersion: String? = null,
    val primaryUrl: String? = null,
    val componentPurl: String? = null,
    val componentName: String? = null,
    val componentVersion: String? = null,
    val introducedBy: String? = null,
    val location: Map<String, Any?> = emptyMap(),
    val evidence: Map<String, Any?>? = null,
    val issueKey: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)


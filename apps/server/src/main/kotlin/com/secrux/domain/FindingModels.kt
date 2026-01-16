package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

enum class Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

enum class FindingStatus { OPEN, CONFIRMED, FALSE_POSITIVE, RESOLVED, WONT_FIX }

enum class Verdict { CONFIRM, FP, WONT_FIX, INFO }

data class Finding(
    val findingId: UUID,
    val tenantId: UUID,
    val taskId: UUID,
    val projectId: UUID,
    val sourceEngine: String,
    val ruleId: String?,
    val location: Map<String, Any?>,
    val evidence: Map<String, Any?>? = null,
    val severity: Severity,
    val fingerprint: String,
    val status: FindingStatus,
    val introducedBy: String? = null,
    val fixVersion: String? = null,
    val exploitMaturity: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class SarifResult(
    val ruleId: String,
    val severity: Severity,
    val message: String,
    val path: String,
    val line: Int,
    val fingerprint: String
)


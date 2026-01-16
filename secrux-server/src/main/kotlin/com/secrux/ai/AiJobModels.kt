package com.secrux.ai

import java.time.OffsetDateTime

enum class AiJobType {
    STAGE_REVIEW,
    FINDING_REVIEW,
    SCA_ISSUE_REVIEW
}

enum class AiJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}

data class AiJobTicket(
    val jobId: String,
    val status: AiJobStatus,
    val jobType: AiJobType,
    val tenantId: String,
    val targetId: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val result: Map<String, Any?>? = null,
    val error: String? = null
)

data class AiJobSubmissionRequest(
    val tenantId: String,
    val jobType: AiJobType = AiJobType.STAGE_REVIEW,
    val targetId: String,
    val payload: Map<String, Any?> = emptyMap(),
    val context: Map<String, Any?> = emptyMap(),
    val agent: String? = null
)

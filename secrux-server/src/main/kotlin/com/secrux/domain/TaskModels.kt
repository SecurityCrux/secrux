package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

enum class TaskType { CODE_CHECK, SECURITY_SCAN, SUPPLY_CHAIN, SCA_CHECK, IDE_AUDIT }

enum class TaskStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELED }

enum class SourceRefType { BRANCH, TAG, COMMIT, UPLOAD }

enum class RuleSelectorMode { EXPLICIT, AUTO, PROFILE }

enum class FailStrategy { FAIL_OPEN, FAIL_CLOSED }

data class Task(
    val taskId: UUID,
    val tenantId: UUID,
    val projectId: UUID,
    val repoId: UUID? = null,
    val executorId: UUID? = null,
    val type: TaskType,
    val spec: TaskSpec,
    val status: TaskStatus,
    val owner: UUID?,
    val name: String? = null,
    val correlationId: String,
    val sourceRefType: SourceRefType,
    val sourceRef: String?,
    val commitSha: String?,
    val engine: String? = null,
    val semgrepProEnabled: Boolean = false,
    val semgrepTokenCipher: String? = null,
    val semgrepTokenExpiresAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class TaskSpec(
    val source: SourceSpec,
    val ruleSelector: RuleSelectorSpec,
    val policy: PolicySpec? = null,
    val ticket: TicketPolicySpec? = null,
    val engineOptions: EngineOptionsSpec? = null,
    val aiReview: AiReviewSpec = AiReviewSpec(),
    val scaAiReview: ScaAiReviewSpec = ScaAiReviewSpec(),
)

data class AiReviewSpec(
    val enabled: Boolean = false,
    val mode: String = "simple",
    val dataFlowMode: String? = null
)

data class ScaAiReviewSpec(
    val enabled: Boolean = false,
    val critical: Boolean = true,
    val high: Boolean = true,
    val medium: Boolean = false,
    val low: Boolean = false,
    val info: Boolean = false,
)

data class SourceSpec(
    val git: GitSourceSpec? = null,
    val archive: ArchiveSourceSpec? = null,
    val filesystem: FilesystemSourceSpec? = null,
    val image: ImageSourceSpec? = null,
    val sbom: SbomSourceSpec? = null,
    val url: UrlSourceSpec? = null,
    val baselineFingerprints: List<String>? = null
)

data class GitSourceSpec(
    val repo: String,
    val ref: String,
    val refType: SourceRefType = SourceRefType.BRANCH,
    val auth: Map<String, String>? = null
)

data class ArchiveSourceSpec(
    val url: String? = null,
    val uploadId: String? = null
)

data class FilesystemSourceSpec(
    val path: String? = null,
    val uploadId: String? = null
)

data class ImageSourceSpec(
    val ref: String
)

data class SbomSourceSpec(
    val uploadId: String? = null,
    val url: String? = null
)

data class UrlSourceSpec(
    val url: String
)

data class RuleSelectorSpec(
    val mode: RuleSelectorMode,
    val profile: String? = null,
    val explicitRules: List<String>? = null
)

data class EngineOptionsSpec(
    val semgrep: SemgrepEngineOptionsSpec? = null
)

data class SemgrepEngineOptionsSpec(
    val usePro: Boolean = false
)

data class PolicySpec(
    val failOn: FailOnPolicy? = null,
    val scanPolicy: ScanPolicy? = null
)

data class FailOnPolicy(
    val severity: Severity = Severity.HIGH
)

data class ScanPolicy(
    val mode: String = "full",
    val parallelism: Int = 1,
    val failStrategy: FailStrategy = FailStrategy.FAIL_CLOSED
)

data class TicketPolicySpec(
    val project: String,
    val assigneeStrategy: String,
    val slaDays: Int? = null,
    val labels: List<String> = emptyList()
)

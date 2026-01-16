package com.secrux.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.secrux.domain.FailStrategy
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.Severity
import com.secrux.domain.SourceRefType
import com.secrux.domain.TaskType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "CreateTaskRequest")
data class CreateTaskRequest @JsonCreator constructor(
    @param:JsonProperty("projectId")
    @field:NotNull
    val projectId: UUID,
    @param:JsonProperty("repoId")
    val repoId: UUID? = null,
    @param:JsonProperty("executorId")
    val executorId: UUID? = null,
    @param:JsonProperty("type")
    @field:NotNull
    val type: TaskType = TaskType.CODE_CHECK,
    @param:JsonProperty("name")
    val name: String? = null,
    @param:JsonProperty("correlationId")
    val correlationId: String? = null,
    @param:JsonProperty("sourceRefType")
    @field:NotNull
    val sourceRefType: SourceRefType,
    @param:JsonProperty("sourceRef")
    val sourceRef: String? = null,
    @param:JsonProperty("commitSha")
    val commitSha: String? = null,
    @param:JsonProperty("engine")
    val engine: String? = null,
    @param:JsonProperty("spec")
    @field:NotNull
    val spec: TaskSpecRequest,
    @param:JsonProperty("owner")
    val owner: UUID? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "UpdateTaskRequest")
data class UpdateTaskRequest @JsonCreator constructor(
    @param:JsonProperty("projectId")
    @field:NotNull
    val projectId: UUID,
    @param:JsonProperty("repoId")
    val repoId: UUID? = null,
    @param:JsonProperty("executorId")
    val executorId: UUID? = null,
    @param:JsonProperty("type")
    @field:NotNull
    val type: TaskType = TaskType.CODE_CHECK,
    @param:JsonProperty("name")
    val name: String? = null,
    @param:JsonProperty("correlationId")
    val correlationId: String? = null,
    @param:JsonProperty("sourceRefType")
    @field:NotNull
    val sourceRefType: SourceRefType,
    @param:JsonProperty("sourceRef")
    val sourceRef: String? = null,
    @param:JsonProperty("commitSha")
    val commitSha: String? = null,
    @param:JsonProperty("engine")
    val engine: String? = null,
    @param:JsonProperty("spec")
    @field:NotNull
    val spec: TaskSpecRequest,
    @param:JsonProperty("owner")
    val owner: UUID? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TaskSpecRequest @JsonCreator constructor(
    @param:JsonProperty("source")
    @field:NotNull
    val source: SourceSpecRequest,
    @param:JsonProperty("ruleSelector")
    @field:NotNull
    val ruleSelector: RuleSelectorRequest,
    @param:JsonProperty("policy")
    val policy: PolicySpecRequest? = null,
    @param:JsonProperty("ticket")
    val ticket: TicketPolicyRequest? = null,
    @param:JsonProperty("engineOptions")
    val engineOptions: EngineOptionsRequest? = null,
    @param:JsonProperty("aiReview")
    val aiReview: AiReviewSpecRequest? = null,
    @param:JsonProperty("scaAiReview")
    val scaAiReview: ScaAiReviewSpecRequest? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AiReviewSpecRequest @JsonCreator constructor(
    @param:JsonProperty("enabled")
    val enabled: Boolean = false,
    @param:JsonProperty("mode")
    val mode: String? = null,
    @param:JsonProperty("dataFlowMode")
    val dataFlowMode: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScaAiReviewSpecRequest @JsonCreator constructor(
    @param:JsonProperty("enabled")
    val enabled: Boolean = false,
    @param:JsonProperty("critical")
    val critical: Boolean = true,
    @param:JsonProperty("high")
    val high: Boolean = true,
    @param:JsonProperty("medium")
    val medium: Boolean = false,
    @param:JsonProperty("low")
    val low: Boolean = false,
    @param:JsonProperty("info")
    val info: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceSpecRequest @JsonCreator constructor(
    @param:JsonProperty("git")
    val git: GitSourceRequest? = null,
    @param:JsonProperty("archive")
    val archive: ArchiveSourceRequest? = null,
    @param:JsonProperty("filesystem")
    val filesystem: FilesystemSourceRequest? = null,
    @param:JsonProperty("image")
    val image: ImageSourceRequest? = null,
    @param:JsonProperty("sbom")
    val sbom: SbomSourceRequest? = null,
    @param:JsonProperty("url")
    val url: UrlSourceRequest? = null,
    @param:JsonProperty("baselineFingerprints")
    val baselineFingerprints: List<String>? = null,
    @param:JsonProperty("gitRefType")
    val gitRefType: SourceRefType? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitSourceRequest @JsonCreator constructor(
    @param:JsonProperty("repo")
    @field:NotBlank val repo: String,
    @param:JsonProperty("ref")
    @field:NotBlank val ref: String,
    @param:JsonProperty("refType")
    val refType: SourceRefType? = null,
    @param:JsonProperty("auth")
    val auth: Map<String, String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArchiveSourceRequest @JsonCreator constructor(
    @param:JsonProperty("url")
    val url: String? = null,
    @param:JsonProperty("uploadId")
    val uploadId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FilesystemSourceRequest @JsonCreator constructor(
    @param:JsonProperty("path")
    val path: String? = null,
    @param:JsonProperty("uploadId")
    val uploadId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageSourceRequest @JsonCreator constructor(
    @param:JsonProperty("ref")
    @field:NotBlank
    val ref: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SbomSourceRequest @JsonCreator constructor(
    @param:JsonProperty("uploadId")
    val uploadId: String? = null,
    @param:JsonProperty("url")
    val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UrlSourceRequest @JsonCreator constructor(
    @param:JsonProperty("url")
    @field:NotBlank val url: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RuleSelectorRequest @JsonCreator constructor(
    @param:JsonProperty("mode")
    @field:NotNull val mode: RuleSelectorMode,
    @param:JsonProperty("profile")
    val profile: String? = null,
    @param:JsonProperty("explicitRules")
    val explicitRules: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EngineOptionsRequest @JsonCreator constructor(
    @param:JsonProperty("semgrep")
    val semgrep: SemgrepEngineOptionsRequest? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemgrepEngineOptionsRequest @JsonCreator constructor(
    @param:JsonProperty("usePro")
    val usePro: Boolean = false,
    @param:JsonProperty("token")
    val token: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PolicySpecRequest @JsonCreator constructor(
    @param:JsonProperty("failOn")
    val failOn: FailOnPolicyRequest? = null,
    @param:JsonProperty("scanPolicy")
    val scanPolicy: ScanPolicyRequest? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FailOnPolicyRequest @JsonCreator constructor(
    @param:JsonProperty("severity")
    val severity: Severity = Severity.HIGH
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScanPolicyRequest @JsonCreator constructor(
    @param:JsonProperty("mode")
    val mode: String = "full",
    @param:JsonProperty("parallelism")
    val parallelism: Int = 1,
    @param:JsonProperty("failStrategy")
    val failStrategy: FailStrategy = FailStrategy.FAIL_CLOSED
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketPolicyRequest @JsonCreator constructor(
    @param:JsonProperty("project")
    @field:NotBlank val project: String,
    @param:JsonProperty("assigneeStrategy")
    @field:NotBlank val assigneeStrategy: String,
    @param:JsonProperty("slaDays")
    val slaDays: Int? = null,
    @param:JsonProperty("labels")
    val labels: List<String> = emptyList()
)

@Schema(name = "TaskSpecPayload")
data class TaskSpecPayload(
    val source: SourceSpecPayload,
    val ruleSelector: RuleSelectorPayload,
    val policy: PolicySpecPayload? = null,
    val ticket: TicketPolicyPayload? = null,
    val engineOptions: EngineOptionsPayload? = null,
    val aiReview: AiReviewSpecPayload? = null,
    val scaAiReview: ScaAiReviewSpecPayload? = null
)

data class AiReviewSpecPayload(
    val enabled: Boolean = false,
    val mode: String = "simple",
    val dataFlowMode: String? = null
)

data class ScaAiReviewSpecPayload(
    val enabled: Boolean = false,
    val critical: Boolean = true,
    val high: Boolean = true,
    val medium: Boolean = false,
    val low: Boolean = false,
    val info: Boolean = false,
)

data class SourceSpecPayload(
    val git: GitSourcePayload? = null,
    val archive: ArchiveSourcePayload? = null,
    val filesystem: FilesystemSourcePayload? = null,
    val image: ImageSourcePayload? = null,
    val sbom: SbomSourcePayload? = null,
    val url: UrlSourcePayload? = null,
    val baselineFingerprints: List<String>? = null,
    val gitRefType: SourceRefType? = null
)

data class GitSourcePayload(
    val repo: String,
    val ref: String,
    val refType: SourceRefType? = null,
)

data class ArchiveSourcePayload(
    val url: String? = null,
    val uploadId: String? = null
)

data class FilesystemSourcePayload(
    val path: String? = null,
    val uploadId: String? = null
)

data class ImageSourcePayload(
    val ref: String
)

data class SbomSourcePayload(
    val uploadId: String? = null,
    val url: String? = null
)

data class UrlSourcePayload(
    val url: String
)

data class RuleSelectorPayload(
    val mode: RuleSelectorMode,
    val profile: String? = null,
    val explicitRules: List<String>? = null
)

data class EngineOptionsPayload(
    val semgrep: SemgrepEngineOptionsPayload? = null
)

data class SemgrepEngineOptionsPayload(
    val usePro: Boolean = false
)

data class PolicySpecPayload(
    val failOn: FailOnPolicyPayload? = null,
    val scanPolicy: ScanPolicyPayload? = null
)

data class FailOnPolicyPayload(
    val severity: Severity = Severity.HIGH
)

data class ScanPolicyPayload(
    val mode: String = "full",
    val parallelism: Int = 1,
    val failStrategy: FailStrategy = FailStrategy.FAIL_CLOSED
)

data class TicketPolicyPayload(
    val project: String,
    val assigneeStrategy: String,
    val slaDays: Int? = null,
    val labels: List<String> = emptyList()
)

@Schema(name = "TaskSummary")
data class TaskSummary(
    val taskId: UUID,
    val tenantId: UUID,
    val projectId: UUID,
    val repoId: UUID?,
    val executorId: UUID?,
    val status: String,
    val type: TaskType,
    val name: String?,
    val correlationId: String,
    val owner: UUID?,
    val sourceRefType: SourceRefType,
    val sourceRef: String?,
    val commitSha: String?,
    val engine: String?,
    val semgrepProEnabled: Boolean,
    val createdAt: String,
    val updatedAt: String?,
    val spec: TaskSpecPayload
)

data class AssignExecutorRequest(
    @field:NotNull val executorId: UUID
)

package com.secrux.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "CreateIntellijTaskRequest")
data class CreateIntellijTaskRequest @JsonCreator constructor(
    @param:JsonProperty("projectId")
    @field:NotNull
    val projectId: UUID,
    @param:JsonProperty("repoId")
    val repoId: UUID? = null,
    @param:JsonProperty("name")
    val name: String? = null,
    @param:JsonProperty("branch")
    val branch: String? = null,
    @param:JsonProperty("commitSha")
    val commitSha: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "IntellijTaskAiReviewRequest")
data class IntellijTaskAiReviewRequest @JsonCreator constructor(
    @param:JsonProperty("mode")
    @field:NotBlank
    val mode: String = "simple",
    @param:JsonProperty("dataFlowMode")
    val dataFlowMode: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "CreateIntellijTokenRequest")
data class CreateIntellijTokenRequest @JsonCreator constructor(
    @param:JsonProperty("name")
    val name: String? = null,
)

@Schema(name = "IntellijTokenCreatedResponse")
data class IntellijTokenCreatedResponse(
    val tokenId: UUID,
    val name: String?,
    val token: String,
    val tokenHint: String,
    val createdAt: OffsetDateTime,
)

@Schema(name = "IntellijTokenSummary")
data class IntellijTokenSummary(
    val tokenId: UUID,
    val name: String?,
    val tokenHint: String,
    val lastUsedAt: OffsetDateTime?,
    val revokedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

@Schema(name = "ProjectUpsertRequest")
data class ProjectUpsertRequest(
    @field:NotBlank val name: String = "",
    val codeOwners: List<String> = emptyList()
)

@Schema(name = "ProjectResponse")
data class ProjectResponse(
    val projectId: UUID,
    val tenantId: UUID,
    val name: String,
    val codeOwners: List<String>,
    val createdAt: String,
    val updatedAt: String?
)


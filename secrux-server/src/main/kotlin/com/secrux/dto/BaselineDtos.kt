package com.secrux.dto

import com.secrux.domain.BaselineKind
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(name = "BaselineUpsertRequest")
data class BaselineUpsertRequest(
    @field:NotNull val kind: BaselineKind,
    @field:NotEmpty val fingerprints: List<String>
)

@Schema(name = "BaselineResponse")
data class BaselineResponse(
    val baselineId: UUID,
    val tenantId: UUID,
    val projectId: UUID,
    val kind: BaselineKind,
    val fingerprints: List<String>,
    val generatedAt: String
)


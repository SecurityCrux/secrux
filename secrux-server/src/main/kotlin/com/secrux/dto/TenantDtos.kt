package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero

@Schema(name = "TenantUpsertRequest")
data class TenantUpsertRequest(
    @field:NotBlank val name: String,
    @field:Email val contactEmail: String,
    @field:PositiveOrZero val planLevel: Int = 0,
    val features: Map<String, Boolean> = emptyMap()
)


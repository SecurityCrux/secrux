package com.secrux.dto

import com.secrux.domain.ExecutorStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(name = "ExecutorRegisterRequest")
data class ExecutorRegisterRequest(
    @field:NotBlank val name: String = "",
    val labels: Map<String, String> = emptyMap(),
    @field:NotNull val cpuCapacity: Int = 1,
    @field:NotNull val memoryCapacityMb: Int = 256
)

@Schema(name = "ExecutorSummary")
data class ExecutorSummary(
    val executorId: UUID,
    val name: String,
    val status: ExecutorStatus,
    val labels: Map<String, String>,
    val cpuCapacity: Int,
    val memoryCapacityMb: Int,
    val cpuUsage: Float?,
    val memoryUsageMb: Int?,
    val lastHeartbeat: String?,
    val createdAt: String
)

data class ExecutorTokenResponse(
    val executorId: UUID,
    val token: String
)


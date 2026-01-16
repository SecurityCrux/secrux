package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

enum class ExecutorStatus { REGISTERED, READY, BUSY, DRAINING, OFFLINE }

data class Executor(
    val executorId: UUID,
    val tenantId: UUID,
    val name: String,
    val status: ExecutorStatus,
    val labels: Map<String, String>,
    val cpuCapacity: Int,
    val memoryCapacityMb: Int,
    val cpuUsage: Float?,
    val memoryUsageMb: Int?,
    val lastHeartbeat: OffsetDateTime?,
    val quicToken: String,
    val publicKey: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)


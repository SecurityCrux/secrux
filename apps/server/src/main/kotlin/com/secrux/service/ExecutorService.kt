package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Executor
import com.secrux.domain.ExecutorStatus
import com.secrux.dto.ExecutorRegisterRequest
import com.secrux.dto.ExecutorSummary
import com.secrux.dto.ExecutorTokenResponse
import com.secrux.repo.ExecutorRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ExecutorService(
    private val executorRepository: ExecutorRepository,
    private val clock: Clock,
    @Value("\${secrux.executor.heartbeat-timeout:PT2M}") private val heartbeatTimeout: Duration
) {

    private val log = LoggerFactory.getLogger(ExecutorService::class.java)
    private val random = SecureRandom()
    private val staleStatuses = setOf(ExecutorStatus.READY, ExecutorStatus.BUSY, ExecutorStatus.DRAINING)

    fun registerExecutor(tenantId: UUID, request: ExecutorRegisterRequest): ExecutorSummary {
        val executor = Executor(
            executorId = UUID.randomUUID(),
            tenantId = tenantId,
            name = request.name,
            status = ExecutorStatus.REGISTERED,
            labels = request.labels,
            cpuCapacity = request.cpuCapacity,
            memoryCapacityMb = request.memoryCapacityMb,
            cpuUsage = null,
            memoryUsageMb = null,
            lastHeartbeat = null,
            quicToken = generateToken(),
            publicKey = null,
            createdAt = OffsetDateTime.now(clock),
            updatedAt = null
        )
        executorRepository.insert(executor)
        return executor.toSummary()
    }

    fun listExecutors(tenantId: UUID, status: ExecutorStatus?, search: String?): List<ExecutorSummary> =
        executorRepository.list(tenantId, status, search).map { it.toSummary() }

    fun updateHeartbeat(
        token: String,
        cpuUsage: Float?,
        memoryUsage: Int?
    ): Executor? {
        val executor = executorRepository.findByToken(token) ?: return null
        val updated = executor.copy(
            status = if (executor.status == ExecutorStatus.REGISTERED || executor.status == ExecutorStatus.OFFLINE) {
                ExecutorStatus.READY
            } else executor.status,
            cpuUsage = cpuUsage,
            memoryUsageMb = memoryUsage,
            lastHeartbeat = OffsetDateTime.now(clock),
            updatedAt = OffsetDateTime.now(clock)
        )
        executorRepository.update(updated)
        return updated
    }

    fun updateStatus(tenantId: UUID, executorId: UUID, status: ExecutorStatus) {
        val existing = executorRepository.findById(executorId, tenantId) ?: return
        executorRepository.update(
            existing.copy(
                status = status,
                updatedAt = OffsetDateTime.now(clock)
            )
        )
    }

    fun getToken(tenantId: UUID, executorId: UUID): ExecutorTokenResponse {
        val executor =
            executorRepository.findById(executorId, tenantId)
                ?: throw SecruxException(ErrorCode.EXECUTOR_NOT_FOUND, "Executor not found")
        return ExecutorTokenResponse(executorId = executor.executorId, token = executor.quicToken)
    }

    @Scheduled(fixedDelayString = "\${secrux.executor.heartbeat-scan-ms:30000}")
    fun expireStaleExecutors() {
        val deadline = OffsetDateTime.now(clock).minus(heartbeatTimeout)
        val stale = executorRepository.findStaleExecutors(deadline, staleStatuses)
        if (stale.isEmpty()) {
            return
        }
        val now = OffsetDateTime.now(clock)
        stale.forEach { executor ->
            executorRepository.update(
                executor.copy(
                    status = ExecutorStatus.OFFLINE,
                    cpuUsage = null,
                    memoryUsageMb = null,
                    updatedAt = now
                )
            )
        }
        log.info("event=executor_heartbeat_expired count={}", stale.size)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun Executor.toSummary() =
        ExecutorSummary(
            executorId = executorId,
            name = name,
            status = status,
            labels = labels,
            cpuCapacity = cpuCapacity,
            memoryCapacityMb = memoryCapacityMb,
            cpuUsage = cpuUsage,
            memoryUsageMb = memoryUsageMb,
            lastHeartbeat = lastHeartbeat?.toString(),
            createdAt = createdAt.toString()
        )
}

package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.ExecutorStatus
import com.secrux.repo.ExecutorRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ExecutorAvailabilityService(
    private val executorRepository: ExecutorRepository
) {

    fun resolveOptional(tenantId: UUID, executorId: UUID?): UUID? {
        executorId ?: return null
        ensureAvailable(tenantId, executorId)
        return executorId
    }

    fun ensureAvailable(tenantId: UUID, executorId: UUID) {
        val executor =
            executorRepository.findById(executorId, tenantId)
                ?: throw SecruxException(ErrorCode.EXECUTOR_NOT_FOUND, "Executor not found")
        if (executor.status != ExecutorStatus.READY && executor.status != ExecutorStatus.REGISTERED) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Executor is not available")
        }
    }
}

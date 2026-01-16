package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Baseline
import com.secrux.domain.BaselineKind
import com.secrux.dto.BaselineResponse
import com.secrux.dto.BaselineUpsertRequest
import com.secrux.repo.BaselineRepository
import com.secrux.repo.ProjectRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class BaselineService(
    private val projectRepository: ProjectRepository,
    private val baselineRepository: BaselineRepository,
    private val clock: Clock
) {

    fun upsertBaseline(
        tenantId: UUID,
        projectId: UUID,
        request: BaselineUpsertRequest
    ): BaselineResponse {
        ensureProjectOwnedByTenant(tenantId, projectId)
        val baseline = Baseline(
            baselineId = UUID.randomUUID(),
            tenantId = tenantId,
            projectId = projectId,
            kind = request.kind,
            fingerprints = request.fingerprints.distinct(),
            generatedAt = OffsetDateTime.now(clock)
        )
        baselineRepository.upsert(baseline)
        return baseline.toResponse()
    }

    fun listBaselines(tenantId: UUID, projectId: UUID): List<BaselineResponse> {
        ensureProjectOwnedByTenant(tenantId, projectId)
        return baselineRepository.list(tenantId, projectId).map { it.toResponse() }
    }

    fun getBaseline(tenantId: UUID, projectId: UUID, kind: BaselineKind): BaselineResponse {
        ensureProjectOwnedByTenant(tenantId, projectId)
        val baseline = baselineRepository.find(tenantId, projectId, kind)
            ?: throw SecruxException(ErrorCode.BASELINE_NOT_FOUND, "Baseline not found for $kind")
        return baseline.toResponse()
    }

    private fun ensureProjectOwnedByTenant(tenantId: UUID, projectId: UUID) {
        if (!projectRepository.exists(tenantId, projectId)) {
            throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found for tenant")
        }
    }

    private fun Baseline.toResponse() = BaselineResponse(
        baselineId = baselineId,
        tenantId = tenantId,
        projectId = projectId,
        kind = kind,
        fingerprints = fingerprints,
        generatedAt = generatedAt.toString()
    )
}

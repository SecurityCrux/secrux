package com.secrux.service

import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.dto.AiJobTicketResponse
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.dto.CodeSnippetDto
import com.secrux.dto.FindingBatchRequest
import com.secrux.dto.FindingBatchStatusUpdateRequest
import com.secrux.dto.FindingBatchStatusUpdateResponse
import com.secrux.dto.FindingDetailResponse
import com.secrux.dto.FindingStatusUpdateRequest
import com.secrux.dto.FindingSummary
import com.secrux.dto.PageResponse
import com.secrux.security.SecruxPrincipal
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FindingService(
    private val findingQueryService: FindingQueryService,
    private val findingCommandService: FindingCommandService
) {

    fun ingestFindings(
        tenantId: UUID,
        taskId: UUID,
        request: FindingBatchRequest
    ): List<FindingSummary> = findingCommandService.ingestFindings(tenantId, taskId, request)

    fun listFindings(
        tenantId: UUID,
        taskId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int
    ): PageResponse<FindingSummary> =
        findingQueryService.listFindings(tenantId, taskId, status, severity, search, limit, offset)

    fun updateStatus(
        principal: SecruxPrincipal,
        findingId: UUID,
        request: FindingStatusUpdateRequest
    ): FindingSummary = findingCommandService.updateStatus(principal, findingId, request)

    fun updateStatusBatch(
        principal: SecruxPrincipal,
        request: FindingBatchStatusUpdateRequest
    ): FindingBatchStatusUpdateResponse = findingCommandService.updateStatusBatch(principal, request)

    fun deleteFinding(
        tenantId: UUID,
        findingId: UUID
    ) = findingCommandService.deleteFinding(tenantId, findingId)

    fun triggerAiReview(
        tenantId: UUID,
        findingId: UUID,
        request: AiReviewTriggerRequest? = null
    ): AiJobTicketResponse = findingCommandService.triggerAiReview(tenantId, findingId, request)

    fun getFindingDetail(tenantId: UUID, findingId: UUID): FindingDetailResponse =
        findingQueryService.getFindingDetail(tenantId, findingId)

    fun getFindingSnippet(
        tenantId: UUID,
        findingId: UUID,
        path: String,
        line: Int,
        context: Int
    ): CodeSnippetDto? = findingQueryService.getFindingSnippet(tenantId, findingId, path, line, context)

    fun listFindingsByProject(
        tenantId: UUID,
        projectId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int
    ): PageResponse<FindingSummary> =
        findingQueryService.listFindingsByProject(
            tenantId = tenantId,
            projectId = projectId,
            status = status,
            severity = severity,
            search = search,
            limit = limit,
            offset = offset
        )

    fun listFindingsByRepo(
        tenantId: UUID,
        projectId: UUID,
        repoId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int
    ): PageResponse<FindingSummary> =
        findingQueryService.listFindingsByRepo(
            tenantId = tenantId,
            projectId = projectId,
            repoId = repoId,
            status = status,
            severity = severity,
            search = search,
            limit = limit,
            offset = offset
        )

    fun listFindingIdsByTask(tenantId: UUID, taskId: UUID): List<UUID> =
        findingQueryService.listFindingIdsByTask(tenantId, taskId)

    fun listFindingIdsByProject(tenantId: UUID, projectId: UUID): List<UUID> =
        findingQueryService.listFindingIdsByProject(tenantId, projectId)

    fun listFindingIdsByRepo(tenantId: UUID, repoId: UUID): List<UUID> =
        findingQueryService.listFindingIdsByRepo(tenantId, repoId)
}

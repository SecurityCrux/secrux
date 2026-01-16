package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.dto.CodeSnippetDto
import com.secrux.dto.FindingDetailResponse
import com.secrux.dto.FindingLocationSummary
import com.secrux.dto.FindingSummary
import com.secrux.dto.PageResponse
import com.secrux.repo.FindingRepository
import com.secrux.repo.ProjectRepository
import com.secrux.repo.RepositoryRepository
import com.secrux.repo.TaskRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FindingQueryService(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val repositoryRepository: RepositoryRepository,
    private val findingRepository: FindingRepository,
    private val findingReviewService: FindingReviewService,
    private val evidenceService: FindingEvidenceService,
    private val dataflowCallChainService: DataflowCallChainService,
    private val findingSummaryMapper: FindingSummaryMapper
) {

    fun listFindings(
        tenantId: UUID,
        taskId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int
    ): PageResponse<FindingSummary> {
        taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        val (items, total) =
            findingRepository.listByTaskPaged(
                tenantId = tenantId,
                taskId = taskId,
                status = status,
                severity = severity,
                search = search,
                limit = limit,
                offset = offset
            )
        val reviews = findingReviewService.latestReviews(tenantId, items.map { it.findingId })
        return PageResponse(
            items = findingSummaryMapper.toSummaries(items, reviews),
            total = total,
            limit = limit,
            offset = offset
        )
    }

    fun getFindingDetail(tenantId: UUID, findingId: UUID): FindingDetailResponse {
        val finding = findingRepository.findById(findingId, tenantId)
            ?: throw SecruxException(ErrorCode.FINDING_NOT_FOUND, "Finding not found")
        val snippet = evidenceService.loadSnippet(finding.location)
            ?: evidenceService.extractSnippetFromEvidence(finding.evidence)
        val enrichment = evidenceService.extractEnrichmentFromEvidence(finding.evidence)
        val (nodesRaw, edges) = evidenceService.parseDataflow(finding.evidence)
        val nodes = nodesRaw.map { node -> node.copy(file = evidenceService.normalizeWorkspaceNodeFile(finding.taskId, node.file)) }
        val callChainsRaw = dataflowCallChainService.buildCallChains(nodes = nodes, edges = edges)
        val callChains = evidenceService.normalizeCallChainsForDisplay(taskId = finding.taskId, callChains = callChainsRaw)
        val task = taskRepository.findById(finding.taskId, tenantId)
        val project = projectRepository.findById(finding.projectId, tenantId)
        val repo = task?.repoId?.let { repoId -> repositoryRepository.findById(repoId, tenantId) }
        val review = findingReviewService.latestReview(tenantId, findingId)
        return FindingDetailResponse(
            findingId = finding.findingId,
            taskId = finding.taskId,
            taskName = task?.name,
            projectId = finding.projectId,
            projectName = project?.name,
            repoId = task?.repoId,
            repoRemoteUrl = repo?.remoteUrl,
            ruleId = finding.ruleId,
            sourceEngine = finding.sourceEngine,
            severity = finding.severity,
            status = finding.status,
            location = toLocationSummary(finding.taskId, finding.location),
            introducedBy = finding.introducedBy,
            createdAt = finding.createdAt.toString(),
            updatedAt = finding.updatedAt?.toString(),
            codeSnippet = snippet,
            dataFlowNodes = nodes,
            dataFlowEdges = edges,
            callChains = callChains,
            enrichment = enrichment,
            review = review
        )
    }

    fun getFindingSnippet(
        tenantId: UUID,
        findingId: UUID,
        path: String,
        line: Int,
        context: Int
    ): CodeSnippetDto? {
        val finding = findingRepository.findById(findingId, tenantId)
            ?: throw SecruxException(ErrorCode.FINDING_NOT_FOUND, "Finding not found")
        val fromWorkspace =
            evidenceService.getSnippetForTaskWorkspace(
                taskId = finding.taskId,
                path = path,
                line = line,
                context = context
            )
        if (fromWorkspace != null) return fromWorkspace

        val fromEvidence = evidenceService.extractSnippetFromEvidence(finding.evidence)
        if (fromEvidence != null && fromEvidence.path == path && line in fromEvidence.startLine..fromEvidence.endLine) {
            return fromEvidence
        }

        return evidenceService.extractSnippetFromDataflowNode(finding.evidence, path, line)
    }

    fun listFindingsByProject(
        tenantId: UUID,
        projectId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int
    ): PageResponse<FindingSummary> {
        val (items, total) =
            findingRepository.listByProjectPaged(
                tenantId = tenantId,
                projectId = projectId,
                status = status,
                severity = severity,
                search = search,
                limit = limit,
                offset = offset
            )
        val reviews = findingReviewService.latestReviews(tenantId, items.map { it.findingId })
        return PageResponse(
            items = findingSummaryMapper.toSummaries(items, reviews),
            total = total,
            limit = limit,
            offset = offset
        )
    }

    fun listFindingsByRepo(
        tenantId: UUID,
        projectId: UUID,
        repoId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int
    ): PageResponse<FindingSummary> {
        val repo = repositoryRepository.findById(repoId, tenantId)
            ?: throw SecruxException(ErrorCode.REPOSITORY_NOT_FOUND, "Repository not found")
        if (repo.projectId != projectId) {
            throw SecruxException(ErrorCode.REPOSITORY_NOT_FOUND, "Repository not found")
        }
        val (items, total) =
            findingRepository.listByRepoPaged(
                tenantId = tenantId,
                repoId = repoId,
                status = status,
                severity = severity,
                search = search,
                limit = limit,
                offset = offset
            )
        val reviews = findingReviewService.latestReviews(tenantId, items.map { it.findingId })
        return PageResponse(
            items = findingSummaryMapper.toSummaries(items, reviews),
            total = total,
            limit = limit,
            offset = offset
        )
    }

    fun listFindingIdsByTask(tenantId: UUID, taskId: UUID): List<UUID> =
        findingRepository.listIdsByTask(tenantId, taskId)

    fun listFindingIdsByProject(tenantId: UUID, projectId: UUID): List<UUID> =
        findingRepository.listIdsByProject(tenantId, projectId)

    fun listFindingIdsByRepo(tenantId: UUID, repoId: UUID): List<UUID> =
        findingRepository.listIdsByRepo(tenantId, repoId)

    private fun toLocationSummary(taskId: UUID, location: Map<String, Any?>): FindingLocationSummary {
        val pathRaw = location["path"]?.toString()
        val path = evidenceService.normalizeWorkspaceNodeFile(taskId, pathRaw)
        val line = (location["line"] as? Number)?.toInt()
        val startLine = (location["startLine"] as? Number)?.toInt()
        val startColumn = (location["startColumn"] as? Number)?.toInt()
        val endColumn = (location["endColumn"] as? Number)?.toInt()
        return FindingLocationSummary(
            path = path,
            line = line,
            startLine = startLine,
            startColumn = startColumn,
            endColumn = endColumn
        )
    }
}

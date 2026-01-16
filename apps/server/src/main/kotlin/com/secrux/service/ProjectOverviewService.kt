package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.dto.ProjectOverviewResponse
import com.secrux.repo.ProjectRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectOverviewService(
    private val projectRepository: ProjectRepository,
    private val catalogQueryService: ProjectOverviewCatalogQueryService,
    private val taskQueryService: ProjectOverviewTaskQueryService,
    private val findingQueryService: ProjectOverviewFindingQueryService,
    private val scaIssueQueryService: ProjectOverviewScaIssueQueryService
) {

    fun getOverview(tenantId: UUID, projectId: UUID): ProjectOverviewResponse {
        val project = projectRepository.findById(projectId, tenantId)
            ?: throw SecruxException(ErrorCode.PROJECT_NOT_FOUND, "Project not found")
        return ProjectOverviewResponse(
            project = project.toResponse(),
            repositories = catalogQueryService.getRepositoryOverview(tenantId, projectId),
            tasks = taskQueryService.getTaskOverview(tenantId, projectId),
            findings = findingQueryService.getFindingOverview(tenantId, projectId),
            sca = scaIssueQueryService.getScaIssueOverview(tenantId, projectId)
        )
    }
}


package com.secrux.dto

import com.secrux.domain.FindingStatus
import com.secrux.domain.RepositorySourceMode
import com.secrux.domain.Severity
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import io.swagger.v3.oas.annotations.media.Schema

@Schema(name = "ProjectOverviewResponse")
data class ProjectOverviewResponse(
    val project: ProjectResponse,
    val repositories: ProjectRepositoryOverview,
    val tasks: ProjectTaskOverview,
    val findings: ProjectFindingOverview,
    val sca: ProjectScaIssueOverview
)

data class ProjectRepositoryOverview(
    val total: Int,
    val bySourceMode: Map<RepositorySourceMode, Int>
)

data class ProjectTaskOverview(
    val total: Int,
    val running: Int,
    val byStatus: Map<TaskStatus, Int>,
    val byType: Map<TaskType, Int>
)

data class ProjectFindingOverview(
    val open: Int,
    val confirmed: Int,
    val bySeverityUnresolved: Map<Severity, Int>
)

data class ProjectScaIssueOverview(
    val open: Int,
    val confirmed: Int,
    val bySeverityUnresolved: Map<Severity, Int>
)


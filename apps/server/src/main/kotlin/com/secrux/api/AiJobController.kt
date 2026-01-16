package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.ai.AiClient
import com.secrux.ai.AiJobType
import com.secrux.common.ApiResponse
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.dto.AiJobTicketResponse
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.findingResource
import com.secrux.security.taskResource
import com.secrux.service.FindingReviewService
import com.secrux.service.ScaIssueReviewService
import com.secrux.service.toResponse
import com.secrux.repo.ScaIssueRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/ai/jobs")
@Tag(name = "AI Job APIs", description = "AI job status query")
class AiJobController(
    private val aiClient: AiClient,
    private val authorizationService: AuthorizationService,
    private val findingReviewService: FindingReviewService,
    private val scaIssueRepository: ScaIssueRepository,
    private val scaIssueReviewService: ScaIssueReviewService
) {

    @GetMapping("/{jobId}")
    @Operation(summary = "Get AI job status", description = "Fetch job ticket from AI service")
    @ApiOperationSupport(order = 0)
    fun getJob(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable jobId: String
    ): ApiResponse<AiJobTicketResponse> {
        val ticket = aiClient.fetch(jobId)
        if (ticket.tenantId != principal.tenantId.toString()) {
            throw SecruxException(ErrorCode.FORBIDDEN, "Job not accessible")
        }
        if (ticket.jobType == AiJobType.FINDING_REVIEW) {
            val findingId = runCatching { java.util.UUID.fromString(ticket.targetId) }.getOrNull()
            if (findingId != null) {
                authorizationService.require(
                    principal = principal,
                    action = AuthorizationAction.FINDING_READ,
                    resource = principal.findingResource(findingId = findingId)
                )
                // Apply AI suggestion only if the caller can manage findings.
                runCatching {
                    authorizationService.require(
                        principal = principal,
                        action = AuthorizationAction.FINDING_MANAGE,
                        resource = principal.findingResource(findingId = findingId)
                    )
                    findingReviewService.applyAiReviewIfReady(principal.tenantId, ticket)
                }
            }
        }
        if (ticket.jobType == AiJobType.SCA_ISSUE_REVIEW) {
            val issueId = runCatching { java.util.UUID.fromString(ticket.targetId) }.getOrNull()
            if (issueId != null) {
                val issue = scaIssueRepository.findById(principal.tenantId, issueId)
                if (issue != null) {
                    authorizationService.require(
                        principal = principal,
                        action = AuthorizationAction.TASK_READ,
                        resource = principal.taskResource(taskId = issue.taskId)
                    )
                    // Apply AI suggestion only if the caller can update tasks.
                    runCatching {
                        authorizationService.require(
                            principal = principal,
                            action = AuthorizationAction.TASK_UPDATE,
                            resource = principal.taskResource(taskId = issue.taskId)
                        )
                        scaIssueReviewService.applyAiReviewIfReady(principal.tenantId, ticket)
                    }
                }
            }
        }
        return ApiResponse(data = ticket.toResponse())
    }
}

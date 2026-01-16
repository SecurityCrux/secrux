package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.taskResource
import com.secrux.service.ScaIssueAiReviewCommandService
import com.secrux.service.ScaIssueSnippetService
import com.secrux.service.ScaIssueUsageService
import com.secrux.repo.ScaIssueRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/sca/issues")
@Tag(name = "SCA Issue APIs", description = "SCA issue review and AI hooks")
class ScaIssueReviewController(
    private val scaIssueRepository: ScaIssueRepository,
    private val scaIssueUsageService: ScaIssueUsageService,
    private val scaIssueSnippetService: ScaIssueSnippetService,
    private val scaIssueAiReviewCommandService: ScaIssueAiReviewCommandService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping("/{issueId}/usage")
    @Operation(summary = "List SCA issue usage evidence", description = "Paginated usage evidence extracted during scan")
    @ApiOperationSupport(order = 10)
    fun listUsage(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable issueId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<*> {
        val issue = scaIssueRepository.findById(principal.tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = issue.taskId)
        )
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return ApiResponse(data = scaIssueUsageService.listUsageEntries(principal.tenantId, issue, safeLimit, safeOffset))
    }

    @GetMapping("/{issueId}/snippet")
    @Operation(summary = "Get SCA issue code snippet", description = "Return code snippet around a file:line within task workspace")
    @ApiOperationSupport(order = 11)
    fun getSnippet(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable issueId: UUID,
        @RequestParam path: String,
        @RequestParam line: Int,
        @RequestParam(defaultValue = "8") context: Int,
        @RequestParam(required = false) receiver: String?,
        @RequestParam(required = false) symbol: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) language: String?
    ): ApiResponse<*> {
        val issue = scaIssueRepository.findById(principal.tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = issue.taskId)
        )
        return ApiResponse(
            data =
                scaIssueSnippetService.getUsageSnippet(
                    taskId = issue.taskId,
                    path = path,
                    line = line,
                    context = context,
                    receiver = receiver,
                    symbol = symbol,
                    kind = kind,
                    language = language
                )
        )
    }

    @PostMapping("/{issueId}/ai-review")
    @Operation(summary = "Trigger SCA issue AI review", description = "Invoke AI assistance for an SCA issue")
    @ApiOperationSupport(order = 12)
    fun triggerAiReview(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable issueId: UUID,
        @Valid @RequestBody(required = false) request: AiReviewTriggerRequest?
    ): ApiResponse<*> {
        val issue = scaIssueRepository.findById(principal.tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_UPDATE,
            resource = principal.taskResource(taskId = issue.taskId)
        )
        return ApiResponse(data = scaIssueAiReviewCommandService.triggerAiReview(principal.tenantId, issue, request))
    }
}

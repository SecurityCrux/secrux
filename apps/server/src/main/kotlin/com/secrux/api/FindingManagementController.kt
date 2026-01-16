package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.dto.FindingBatchStatusUpdateRequest
import com.secrux.dto.FindingBatchStatusUpdateResponse
import com.secrux.dto.FindingDetailResponse
import com.secrux.dto.FindingStatusUpdateRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.findingResource
import com.secrux.service.FindingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/findings")
@Tag(name = "Finding Management APIs", description = "Finding review and AI hooks")
class FindingManagementController(
    private val findingService: FindingService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping("/{findingId}")
    @Operation(summary = "Get finding detail", description = "Return snippet and dataflow for a finding")
    @ApiOperationSupport(order = 1)
    fun getFinding(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable findingId: UUID
    ): ApiResponse<FindingDetailResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_READ,
            resource = principal.findingResource(findingId = findingId)
        )
        return ApiResponse(data = findingService.getFindingDetail(principal.tenantId, findingId))
    }

    @GetMapping("/{findingId}/snippet")
    @Operation(summary = "Get code snippet", description = "Return code snippet around a file:line within task workspace")
    @ApiOperationSupport(order = 2)
    fun getSnippet(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable findingId: UUID,
        @RequestParam path: String,
        @RequestParam line: Int,
        @RequestParam(defaultValue = "5") context: Int
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_READ,
            resource = principal.findingResource(findingId = findingId)
        )
        return ApiResponse(data = findingService.getFindingSnippet(principal.tenantId, findingId, path, line, context))
    }

    @PatchMapping("/{findingId}")
    @Operation(summary = "Update finding status", description = "Change finding workflow state")
    @ApiOperationSupport(order = 3)
    fun updateStatus(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable findingId: UUID,
        @Valid @RequestBody request: FindingStatusUpdateRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_MANAGE,
            resource = principal.findingResource(findingId = findingId),
            context = mapOf("status" to request.status.name)
        )
        return ApiResponse(data = findingService.updateStatus(principal, findingId, request))
    }

    @PatchMapping("/batch/status")
    @Operation(summary = "Batch update finding status", description = "Bulk apply manual review status changes")
    @ApiOperationSupport(order = 4)
    fun updateStatusBatch(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: FindingBatchStatusUpdateRequest
    ): ApiResponse<FindingBatchStatusUpdateResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_MANAGE,
            resource = principal.findingResource(),
            context = mapOf("status" to request.status.name, "count" to request.findingIds.size)
        )
        return ApiResponse(data = findingService.updateStatusBatch(principal, request))
    }

    @PostMapping("/{findingId}/ai-review")
    @Operation(summary = "Trigger finding AI review", description = "Invoke AI assistance for a finding")
    @ApiOperationSupport(order = 5)
    fun aiReview(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable findingId: UUID,
        @RequestBody(required = false) request: AiReviewTriggerRequest?
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_MANAGE,
            resource = principal.findingResource(findingId = findingId)
        )
        return ApiResponse(data = findingService.triggerAiReview(principal.tenantId, findingId, request))
    }

    @DeleteMapping("/{findingId}")
    @Operation(summary = "Delete finding", description = "Soft delete a finding")
    @ApiOperationSupport(order = 6)
    fun deleteFinding(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable findingId: UUID,
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_MANAGE,
            resource = principal.findingResource(findingId = findingId)
        )
        findingService.deleteFinding(principal.tenantId, findingId)
        return ApiResponse(data = mapOf("findingId" to findingId))
    }
}


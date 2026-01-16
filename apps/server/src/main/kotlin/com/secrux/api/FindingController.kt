package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.dto.FindingBatchRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.findingResource
import com.secrux.service.FindingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tasks/{taskId}/findings")
@Tag(name = "Finding APIs", description = "Finding ingestion and review")
class FindingController(
    private val findingService: FindingService,
    private val authorizationService: AuthorizationService
) {

    @PostMapping
    @Operation(summary = "Ingest findings", description = "Import findings for a task")
    @ApiOperationSupport(order = 1)
    fun ingest(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: FindingBatchRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_INGEST,
            resource = principal.findingResource(
                taskId = taskId
            ),
            context = mapOf(
                "format" to request.format,
                "count" to request.findings.size
            )
        )
        return ApiResponse(data = findingService.ingestFindings(principal.tenantId, taskId, request))
    }

    @GetMapping
    @Operation(summary = "List findings", description = "List normalized findings for a task")
    @ApiOperationSupport(order = 2)
    fun list(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @RequestParam(required = false) status: FindingStatus?,
        @RequestParam(required = false) severity: Severity?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_READ,
            resource = principal.findingResource(taskId = taskId)
        )
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return ApiResponse(
            data = findingService.listFindings(
                tenantId = principal.tenantId,
                taskId = taskId,
                status = status,
                severity = severity,
                search = search,
                limit = safeLimit,
                offset = safeOffset
            )
        )
    }
}

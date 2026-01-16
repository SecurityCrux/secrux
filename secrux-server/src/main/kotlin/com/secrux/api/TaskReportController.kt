package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.dto.ReportExportRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.findingResource
import com.secrux.service.ReportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tasks/{taskId}/reports")
@Tag(name = "Task Report APIs", description = "Export reports for a task")
class TaskReportController(
    private val reportService: ReportService,
    private val authorizationService: AuthorizationService
) {

    @PostMapping("/export")
    @Operation(summary = "Export report for a task", description = "Export all findings under a task, or a subset specified in findingIds")
    @ApiOperationSupport(order = 1)
    fun exportForTask(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: ReportExportRequest
    ): ResponseEntity<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_READ,
            resource = principal.findingResource(taskId = taskId)
        )
        return reportService.exportForTask(principal, taskId, request)
    }
}

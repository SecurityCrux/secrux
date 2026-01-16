package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.dto.ReportExportRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.projectResource
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
@RequestMapping("/projects/{projectId}/reports")
@Tag(name = "Project Report APIs", description = "Export reports for a project")
class ProjectReportController(
    private val reportService: ReportService,
    private val authorizationService: AuthorizationService
) {

    @PostMapping("/export")
    @Operation(summary = "Export report for a project", description = "Export findings under a project (or specified findingIds)")
    @ApiOperationSupport(order = 1)
    fun exportForProject(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @Valid @RequestBody request: ReportExportRequest
    ): ResponseEntity<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_READ,
            resource = principal.projectResource(projectId = projectId)
        )
        return reportService.exportForProject(principal, projectId, request)
    }
}

package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.ReportExportRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.findingResource
import com.secrux.service.ReportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/reports")
@Tag(name = "Report APIs", description = "Export findings into reports")
class ReportController(
    private val reportService: ReportService,
    private val authorizationService: AuthorizationService
) {

    @PostMapping("/export")
    @Operation(summary = "Export findings report", description = "Export selected findings as PDF/HTML/DOCX")
    @ApiOperationSupport(order = 1)
    fun export(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: ReportExportRequest
    ): ResponseEntity<*> {
        request.findingIds.forEach { findingId ->
            authorizationService.require(
                principal = principal,
                action = AuthorizationAction.FINDING_READ,
                resource = principal.findingResource(findingId = findingId)
            )
        }
        return reportService.export(principal, request)
    }
}

package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.taskResource
import com.secrux.service.ScaTaskArtifactService
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.util.UUID

@RestController
@RequestMapping("/sca/tasks")
@Tag(name = "SCA Task APIs", description = "SCA task artifacts such as SBOM and dependency graph")
class ScaTaskArtifactsController(
    private val service: ScaTaskArtifactService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping("/{taskId}/sbom")
    @Operation(summary = "Get SBOM info", description = "Fetch SBOM metadata for the latest SCA scan stage")
    @ApiOperationSupport(order = 0)
    fun getSbom(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        return ApiResponse(data = service.loadLatestSbomInfo(principal.tenantId, taskId))
    }

    @GetMapping("/{taskId}/sbom/download")
    @Operation(summary = "Download SBOM", description = "Download the SBOM as a file")
    @ApiOperationSupport(order = 1)
    fun downloadSbom(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID
    ): ResponseEntity<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        val sbomPath = service.resolveLatestSbomPath(principal.tenantId, taskId)
        val fileName = sbomPath.fileName?.toString()?.takeIf { it.isNotBlank() } ?: "sbom.cdx.json"
        val resource = FileSystemResource(sbomPath)
        val size = runCatching { Files.size(sbomPath) }.getOrNull()
        val builder =
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
        return if (size != null) {
            builder.contentLength(size).body(resource)
        } else {
            builder.body(resource)
        }
    }

    @GetMapping("/{taskId}/dependency-graph")
    @Operation(summary = "Get dependency graph", description = "Fetch the dependency graph parsed from CycloneDX SBOM")
    @ApiOperationSupport(order = 2)
    fun getDependencyGraph(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        return ApiResponse(data = service.loadLatestDependencyGraph(principal.tenantId, taskId))
    }
}

package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.UploadResponse
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.taskResource
import com.secrux.service.UploadStorageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/projects/{projectId}/sca/uploads")
@Tag(name = "SCA Upload APIs", description = "Upload artifacts for SCA tasks (archives, SBOM files)")
class ScaUploadController(
    private val uploadStorageService: UploadStorageService,
    private val authorizationService: AuthorizationService
) {

    @PostMapping("/archive", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Upload source archive for SCA", description = "Upload a zip archive to be scanned as filesystem")
    @ApiOperationSupport(order = 0)
    fun uploadArchive(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @RequestPart("file") file: MultipartFile
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_CREATE,
            resource = principal.taskResource(projectId = projectId)
        )
        val handle = uploadStorageService.save(principal.tenantId, file)
        return ApiResponse(
            data =
                UploadResponse(
                    uploadId = handle.uploadId,
                    fileName = handle.fileName,
                    sizeBytes = handle.sizeBytes,
                    sha256 = handle.sha256
                )
        )
    }

    @PostMapping("/sbom", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Upload SBOM for SCA", description = "Upload an SBOM file to scan with trivy sbom")
    @ApiOperationSupport(order = 1)
    fun uploadSbom(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @RequestPart("file") file: MultipartFile
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_CREATE,
            resource = principal.taskResource(projectId = projectId)
        )
        val handle = uploadStorageService.save(principal.tenantId, file)
        return ApiResponse(
            data =
                UploadResponse(
                    uploadId = handle.uploadId,
                    fileName = handle.fileName,
                    sizeBytes = handle.sizeBytes,
                    sha256 = handle.sha256
                )
        )
    }
}


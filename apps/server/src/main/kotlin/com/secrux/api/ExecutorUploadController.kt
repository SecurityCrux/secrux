package com.secrux.api

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.repo.ExecutorRepository
import com.secrux.service.UploadStorageService
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/executor/uploads")
class ExecutorUploadController(
    private val executorRepository: ExecutorRepository,
    private val uploadStorageService: UploadStorageService
) {

    @GetMapping("/{uploadId}")
    fun downloadUpload(
        @RequestHeader("X-Executor-Token", required = false) token: String?,
        @PathVariable uploadId: String
    ): ResponseEntity<FileSystemResource> {
        val safeToken = token?.trim()?.takeIf { it.isNotBlank() }
            ?: throw SecruxException(ErrorCode.UNAUTHORIZED, "Missing executor token")
        val executor = executorRepository.findByToken(safeToken)
            ?: throw SecruxException(ErrorCode.UNAUTHORIZED, "Invalid executor token")
        val path = uploadStorageService.resolve(executor.tenantId, uploadId)
        val resource = FileSystemResource(path)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${path.fileName}\"")
            .contentLength(resource.contentLength())
            .body(resource)
    }
}


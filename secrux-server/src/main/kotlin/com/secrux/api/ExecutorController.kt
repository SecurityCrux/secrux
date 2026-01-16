package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.ExecutorStatus
import com.secrux.dto.ExecutorRegisterRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.authResource
import com.secrux.service.ExecutorService
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
@RequestMapping("/executors")
@Tag(name = "Executor APIs", description = "Executor fleet management")
class ExecutorController(
    private val executorService: ExecutorService,
    private val authorizationService: AuthorizationService
) {

    @PostMapping("/register")
    @Operation(summary = "Register executor", description = "Provision a new executor and issue token")
    @ApiOperationSupport(order = 1)
    fun registerExecutor(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: ExecutorRegisterRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.EXECUTOR_REGISTER,
            resource = principal.authResource("executor")
        )
        return ApiResponse(data = executorService.registerExecutor(principal.tenantId, request))
    }

    @GetMapping
    @Operation(summary = "List executors")
    @ApiOperationSupport(order = 2)
    fun listExecutors(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(required = false) status: ExecutorStatus?,
        @RequestParam(required = false) search: String?
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.EXECUTOR_READ,
            resource = principal.authResource("executor")
        )
        return ApiResponse(data = executorService.listExecutors(principal.tenantId, status, search))
    }

    @PostMapping("/{executorId}/status")
    @Operation(summary = "Update executor status")
    @ApiOperationSupport(order = 3)
    fun updateStatus(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable executorId: UUID,
        @RequestParam status: ExecutorStatus
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.EXECUTOR_MANAGE,
            resource = principal.authResource("executor")
        )
        executorService.updateStatus(principal.tenantId, executorId, status)
        return ApiResponse<Unit>()
    }

    @GetMapping("/{executorId}/token")
    @Operation(summary = "View executor token")
    @ApiOperationSupport(order = 4)
    fun getToken(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable executorId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.EXECUTOR_MANAGE,
            resource = principal.authResource("executor")
        )
        return ApiResponse(data = executorService.getToken(principal.tenantId, executorId))
    }
}


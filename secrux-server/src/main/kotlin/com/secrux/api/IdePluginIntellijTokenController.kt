package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.CreateIntellijTokenRequest
import com.secrux.dto.IntellijTokenCreatedResponse
import com.secrux.dto.IntellijTokenSummary
import com.secrux.security.SecruxPrincipal
import com.secrux.service.IntellijPluginTokenService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/ideplugins/intellij/tokens")
@Tag(name = "IDE Plugin APIs", description = "IntelliJ plugin integration endpoints")
@Validated
class IdePluginIntellijTokenController(
    private val tokenService: IntellijPluginTokenService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Create IntelliJ access token",
        description = "Create a long-lived token to paste into IntelliJ plugin settings",
    )
    @ApiOperationSupport(order = 10)
    fun createToken(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: CreateIntellijTokenRequest,
    ): ApiResponse<IntellijTokenCreatedResponse> {
        return ApiResponse(data = tokenService.createToken(principal.tenantId, principal.userId, request))
    }

    @GetMapping
    @Operation(summary = "List IntelliJ access tokens", description = "List tokens created by current user")
    @ApiOperationSupport(order = 11)
    fun listTokens(
        @AuthenticationPrincipal principal: SecruxPrincipal,
    ): ApiResponse<List<IntellijTokenSummary>> {
        return ApiResponse(data = tokenService.listTokens(principal.tenantId, principal.userId))
    }

    @DeleteMapping("/{tokenId}")
    @Operation(summary = "Revoke IntelliJ access token", description = "Revoke a token created by current user")
    @ApiOperationSupport(order = 12)
    fun revokeToken(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable tokenId: UUID,
    ): ApiResponse<Unit> {
        tokenService.revokeToken(principal.tenantId, principal.userId, tokenId)
        return ApiResponse(message = "Revoked")
    }
}


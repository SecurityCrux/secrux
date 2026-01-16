package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.AuthMeResponse
import com.secrux.dto.PasswordLoginRequest
import com.secrux.dto.RefreshTokenRequest
import com.secrux.dto.TokenResponse
import com.secrux.security.SecruxPrincipal
import com.secrux.service.LocalAuthService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Local authentication endpoints")
class AuthController(
    private val localAuthService: LocalAuthService
) {

    @PostMapping("/login")
    @Operation(summary = "Login (local mode)", description = "Issue access + refresh tokens for local auth mode")
    @ApiOperationSupport(order = 1)
    fun login(
        @Valid @RequestBody request: PasswordLoginRequest
    ): TokenResponse {
        return localAuthService.login(usernameOrEmail = request.username, password = request.password)
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh token (local mode)", description = "Rotate refresh token and issue new access token")
    @ApiOperationSupport(order = 2)
    fun refresh(
        @Valid @RequestBody request: RefreshTokenRequest
    ): TokenResponse {
        return localAuthService.refresh(refreshToken = request.refreshToken)
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout (local mode)", description = "Revoke refresh token")
    @ApiOperationSupport(order = 3)
    fun logout(
        @Valid @RequestBody request: RefreshTokenRequest
    ): ApiResponse<Unit> {
        localAuthService.logout(refreshToken = request.refreshToken)
        return ApiResponse(message = "Logged out")
    }

    @GetMapping("/me")
    @Operation(summary = "Current user", description = "Return current principal + permissions")
    @ApiOperationSupport(order = 4)
    fun me(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<AuthMeResponse> {
        return ApiResponse(
            data =
                AuthMeResponse(
                    userId = principal.userId,
                    tenantId = principal.tenantId,
                    username = principal.username,
                    email = principal.email,
                    roles = principal.roles.sorted()
                )
        )
    }
}


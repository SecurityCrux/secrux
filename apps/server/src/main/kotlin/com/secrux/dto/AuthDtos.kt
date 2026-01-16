package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

@Schema(name = "PasswordLoginRequest")
data class PasswordLoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String
)

@Schema(name = "RefreshTokenRequest")
data class RefreshTokenRequest(
    @field:NotBlank val refreshToken: String
)

data class TokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
    val refresh_expires_in: Int,
    val token_type: String,
    val scope: String
)

data class AuthMeResponse(
    val userId: UUID,
    val tenantId: UUID,
    val username: String?,
    val email: String?,
    val roles: List<String>
)


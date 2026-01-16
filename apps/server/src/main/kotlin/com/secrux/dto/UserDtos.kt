package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class UserSummary(
    val id: String,
    val username: String,
    val email: String?,
    val enabled: Boolean
)

data class UserListResponse(
    val items: List<UserSummary>,
    val total: Long,
    val limit: Int,
    val offset: Int
)

@Schema(name = "UserCreateRequest")
data class UserCreateRequest(
    @field:NotBlank val username: String,
    @field:Email val email: String? = null,
    val password: String? = null,
    val enabled: Boolean = true
)

@Schema(name = "UserStatusUpdateRequest")
data class UserStatusUpdateRequest(
    @field:NotNull val enabled: Boolean
)

@Schema(name = "UserPasswordResetRequest")
data class UserPasswordResetRequest(
    @field:NotBlank val password: String,
    val temporary: Boolean = false
)

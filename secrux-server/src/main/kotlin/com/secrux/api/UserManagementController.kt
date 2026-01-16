package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.UserDetailResponse
import com.secrux.dto.UserCreateRequest
import com.secrux.dto.UserListResponse
import com.secrux.dto.UserPasswordResetRequest
import com.secrux.dto.UserProfileUpdateRequest
import com.secrux.dto.UserRoleAssignmentRequest
import com.secrux.dto.UserRoleAssignmentResponse
import com.secrux.dto.UserStatusUpdateRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.userResource
import com.secrux.service.IamUserAdminService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/users")
@Tag(name = "User Management", description = "Manage users and roles in Secrux")
class UserManagementController(
    private val userService: IamUserAdminService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List users", description = "Search tenant users")
    @ApiOperationSupport(order = 1)
    fun listUsers(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<UserListResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_READ,
            resource = principal.userResource()
        )
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        return ApiResponse(
            data = userService.listUsers(principal.tenantId, search = search, limit = safeLimit, offset = safeOffset)
        )
    }

    @PostMapping
    @Operation(summary = "Create user", description = "Create local user")
    @ApiOperationSupport(order = 2)
    fun createUser(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: UserCreateRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = userService.createLocalUser(principal.tenantId, request))
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get user", description = "Get user profile + role assignments")
    @ApiOperationSupport(order = 3)
    fun getUser(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable userId: UUID
    ): ApiResponse<UserDetailResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_READ,
            resource = principal.userResource()
        )
        return ApiResponse(data = userService.getUser(principal.tenantId, userId))
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Update user profile", description = "Update username/email/name/phone")
    @ApiOperationSupport(order = 4)
    fun updateProfile(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: UserProfileUpdateRequest
    ): ApiResponse<UserDetailResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = userService.updateProfile(principal.tenantId, userId, request))
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Enable/disable user")
    @ApiOperationSupport(order = 5)
    fun updateStatus(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: UserStatusUpdateRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        userService.updateStatus(principal.tenantId, userId, request)
        return ApiResponse(data = mapOf("userId" to userId, "enabled" to request.enabled))
    }

    @PostMapping("/{userId}/reset-password")
    @Operation(summary = "Reset user password")
    @ApiOperationSupport(order = 6)
    fun resetPassword(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: UserPasswordResetRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        userService.resetPassword(principal.tenantId, userId, request)
        return ApiResponse(data = mapOf("userId" to userId, "temporary" to request.temporary))
    }

    @PutMapping("/{userId}/roles")
    @Operation(summary = "Assign roles", description = "Replace the user's role assignments")
    @ApiOperationSupport(order = 7)
    fun assignRoles(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: UserRoleAssignmentRequest
    ): ApiResponse<UserRoleAssignmentResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = userService.assignRoles(principal.tenantId, userId, request.roleIds))
    }
}

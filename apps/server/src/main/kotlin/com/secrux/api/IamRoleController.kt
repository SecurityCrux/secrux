package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.IamRoleCreateRequest
import com.secrux.dto.IamRoleDetailResponse
import com.secrux.dto.IamRolePermissionsUpdateRequest
import com.secrux.dto.IamRoleSummaryResponse
import com.secrux.dto.IamRoleUpdateRequest
import com.secrux.dto.PageResponse
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.userResource
import com.secrux.service.IamRoleService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
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
@RequestMapping("/iam/roles")
@Tag(name = "IAM Roles", description = "Role group management")
class IamRoleController(
    private val roleService: IamRoleService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List roles", description = "List role groups for the current tenant")
    @ApiOperationSupport(order = 1)
    fun listRoles(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<PageResponse<IamRoleSummaryResponse>> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(
            data = roleService.listRoles(principal.tenantId, search, limit, offset)
        )
    }

    @PostMapping
    @Operation(summary = "Create role", description = "Create a role group and optionally attach permissions")
    @ApiOperationSupport(order = 2)
    fun createRole(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: IamRoleCreateRequest
    ): ApiResponse<IamRoleDetailResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = roleService.createRole(principal.tenantId, request))
    }

    @GetMapping("/{roleId}")
    @Operation(summary = "Get role", description = "Fetch role metadata + permissions")
    @ApiOperationSupport(order = 3)
    fun getRole(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable roleId: UUID
    ): ApiResponse<IamRoleDetailResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = roleService.getRole(principal.tenantId, roleId))
    }

    @PatchMapping("/{roleId}")
    @Operation(summary = "Update role", description = "Update role metadata")
    @ApiOperationSupport(order = 4)
    fun updateRole(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable roleId: UUID,
        @Valid @RequestBody request: IamRoleUpdateRequest
    ): ApiResponse<IamRoleSummaryResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = roleService.updateRole(principal.tenantId, roleId, request))
    }

    @PutMapping("/{roleId}/permissions")
    @Operation(summary = "Set role permissions", description = "Replace role permissions list")
    @ApiOperationSupport(order = 5)
    fun setPermissions(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable roleId: UUID,
        @Valid @RequestBody request: IamRolePermissionsUpdateRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = mapOf("roleId" to roleId, "permissions" to roleService.setRolePermissions(principal.tenantId, roleId, request)))
    }

    @DeleteMapping("/{roleId}")
    @Operation(summary = "Delete role", description = "Soft delete role group")
    @ApiOperationSupport(order = 6)
    fun deleteRole(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable roleId: UUID
    ): ApiResponse<Unit> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        roleService.deleteRole(principal.tenantId, roleId)
        return ApiResponse(message = "Role deleted")
    }
}


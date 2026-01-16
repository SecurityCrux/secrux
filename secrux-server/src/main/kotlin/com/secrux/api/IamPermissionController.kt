package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.PermissionCatalogResponse
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.userResource
import com.secrux.service.IamPermissionCatalogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/iam/permissions")
@Tag(name = "IAM Permissions", description = "List available permission values")
class IamPermissionController(
    private val permissionCatalogService: IamPermissionCatalogService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List permissions", description = "List permission strings that can be assigned to roles")
    @ApiOperationSupport(order = 1)
    fun listPermissions(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<PermissionCatalogResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = permissionCatalogService.catalog())
    }
}


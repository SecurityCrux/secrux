package com.secrux.service

import com.secrux.dto.PermissionCatalogResponse
import com.secrux.dto.PermissionGroup
import com.secrux.security.AuthorizationAction
import org.springframework.stereotype.Service

@Service
class IamPermissionCatalogService {

    fun catalog(): PermissionCatalogResponse {
        val permissions = AuthorizationAction.entries.map { it.value }.sorted()
        val groups =
            permissions
                .groupBy { it.substringBefore(":") }
                .toSortedMap()
                .map { (group, items) ->
                    PermissionGroup(group = group, permissions = items.sorted())
                }
        return PermissionCatalogResponse(
            permissions = permissions,
            groups = groups
        )
    }
}


package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Tenant
import com.secrux.dto.TenantUpdateRequest
import com.secrux.repo.TenantRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TenantService(
    private val tenantRepository: TenantRepository
) {

    fun getOrCreate(tenantId: UUID): Tenant {
        tenantRepository.findById(tenantId)?.let { return it }
        tenantRepository.insertIfMissing(
            tenantId = tenantId,
            name = "Tenant $tenantId"
        )
        return tenantRepository.findById(tenantId)
            ?: throw SecruxException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found")
    }

    fun update(tenantId: UUID, request: TenantUpdateRequest): Tenant {
        val existing = getOrCreate(tenantId)
        val updated =
            tenantRepository.update(
                tenantId = tenantId,
                name = request.name.trim(),
                contactEmail = request.contactEmail?.trim()?.takeIf { it.isNotBlank() }
            )
        return updated ?: existing
    }
}


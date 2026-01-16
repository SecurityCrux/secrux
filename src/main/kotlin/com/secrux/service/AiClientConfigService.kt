package com.secrux.service

import com.secrux.domain.AiClientConfig
import com.secrux.dto.AiClientConfigRequest
import com.secrux.dto.AiClientConfigResponse
import com.secrux.repo.AiClientConfigRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class AiClientConfigService(
    private val repository: AiClientConfigRepository,
    private val clock: Clock
) {

    fun listConfigs(tenantId: UUID): List<AiClientConfigResponse> =
        repository.list(tenantId).map { it.toResponse() }

    fun createConfig(tenantId: UUID, request: AiClientConfigRequest): AiClientConfigResponse {
        if (request.isDefault) {
            repository.clearDefault(tenantId)
        }
        val config = AiClientConfig(
            configId = UUID.randomUUID(),
            tenantId = tenantId,
            name = request.name,
            provider = request.provider,
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            model = request.model,
            isDefault = request.isDefault,
            enabled = request.enabled,
            createdAt = OffsetDateTime.now(clock),
            updatedAt = null
        )
        repository.insert(config)
        return config.toResponse()
    }

    fun updateConfig(tenantId: UUID, configId: UUID, request: AiClientConfigRequest): AiClientConfigResponse {
        val existing = repository.findById(configId, tenantId)
            ?: throw IllegalArgumentException("Config not found")
        if (request.isDefault) {
            repository.clearDefault(tenantId)
        }
        val resolvedApiKey = request.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: existing.apiKey
        val updated = existing.copy(
            name = request.name,
            provider = request.provider,
            baseUrl = request.baseUrl,
            apiKey = resolvedApiKey,
            model = request.model,
            isDefault = request.isDefault,
            enabled = request.enabled,
            updatedAt = OffsetDateTime.now(clock)
        )
        repository.update(updated)
        return updated.toResponse()
    }

    fun deleteConfig(tenantId: UUID, configId: UUID) {
        repository.delete(configId, tenantId)
    }

    private fun AiClientConfig.toResponse() =
        AiClientConfigResponse(
            configId = configId,
            name = name,
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            isDefault = isDefault,
            enabled = enabled,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt?.toString()
        )
}

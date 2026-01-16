package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.TicketIssueType
import com.secrux.domain.TicketProviderConfig
import com.secrux.dto.TicketProviderConfigResponse
import com.secrux.dto.TicketProviderConfigUpsertRequest
import com.secrux.repo.TicketProviderConfigRepository
import com.secrux.security.SecretCrypto
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class TicketProviderConfigService(
    private val repository: TicketProviderConfigRepository,
    private val secretCrypto: SecretCrypto,
    private val clock: Clock
) {

    fun listConfigs(tenantId: UUID): List<TicketProviderConfigResponse> =
        repository.list(tenantId).map { it.toResponse() }

    fun upsertConfig(tenantId: UUID, provider: String, request: TicketProviderConfigUpsertRequest): TicketProviderConfigResponse {
        val normalizedProvider = provider.trim()
        if (normalizedProvider.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "provider cannot be blank")
        }
        val baseUrl = normalizeBaseUrl(request.baseUrl)
        val projectKey = request.projectKey.trim()
        if (projectKey.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "projectKey cannot be blank")
        }
        val email = request.email.trim()
        if (email.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "email cannot be blank")
        }

        val existing = repository.findByProvider(tenantId, normalizedProvider)
        val tokenCipher =
            if (!request.apiToken.isNullOrBlank()) {
                secretCrypto.encrypt(request.apiToken)
            } else {
                existing?.apiTokenCipher
                    ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "apiToken is required for this provider")
            }
        val issueTypeNames =
            resolveIssueTypeNames(
                requested = request.issueTypeNames,
                existing = existing?.issueTypeNames
            )
        val now = OffsetDateTime.now(clock)
        val config =
            TicketProviderConfig(
                tenantId = tenantId,
                provider = normalizedProvider,
                baseUrl = baseUrl,
                projectKey = projectKey,
                email = email,
                apiTokenCipher = tokenCipher,
                issueTypeNames = issueTypeNames,
                enabled = request.enabled,
                createdAt = existing?.createdAt ?: now,
                updatedAt = existing?.let { now }
            )
        if (existing == null) {
            repository.insert(config)
        } else {
            repository.update(config)
        }
        return config.toResponse()
    }

    fun deleteConfig(tenantId: UUID, provider: String) {
        val normalizedProvider = provider.trim()
        if (normalizedProvider.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "provider cannot be blank")
        }
        repository.delete(tenantId, normalizedProvider)
    }

    private fun TicketProviderConfig.toResponse() =
        TicketProviderConfigResponse(
            provider = provider,
            baseUrl = baseUrl,
            projectKey = projectKey,
            email = email,
            issueTypeNames = issueTypeNames,
            enabled = enabled,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt?.toString()
        )

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "baseUrl cannot be blank")
        }
        val cleaned = trimmed.removeSuffix("/")
        if (!(cleaned.startsWith("http://") || cleaned.startsWith("https://"))) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "baseUrl must start with http:// or https://")
        }
        return cleaned
    }

    private fun resolveIssueTypeNames(
        requested: Map<String, String>?,
        existing: Map<String, String>?
    ): Map<String, String> {
        if (requested == null) {
            return existing ?: defaultIssueTypeNames()
        }
        val defaults = defaultIssueTypeNames()
        val normalized =
            requested
                .mapKeys { it.key.trim().uppercase() }
                .mapValues { it.value.trim() }
                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        return defaults + normalized
    }

    private fun defaultIssueTypeNames(): Map<String, String> =
        mapOf(
            TicketIssueType.BUG.name to "Bug",
            TicketIssueType.TASK.name to "Task",
            TicketIssueType.STORY.name to "Story"
        )
}


package com.secrux.service

import com.secrux.domain.IdePluginToken
import com.secrux.dto.CreateIntellijTokenRequest
import com.secrux.dto.IntellijTokenCreatedResponse
import com.secrux.dto.IntellijTokenSummary
import com.secrux.repo.IdePluginTokenRepository
import com.secrux.security.IdePluginTokenFormat
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

@Service
class IntellijPluginTokenService(
    private val idePluginTokenRepository: IdePluginTokenRepository,
    private val clock: Clock,
) {

    fun createToken(
        tenantId: UUID,
        userId: UUID,
        request: CreateIntellijTokenRequest,
    ): IntellijTokenCreatedResponse {
        val token = generateToken()
        val tokenHash = hashToken(token)
        val tokenHint = token.takeLast(TOKEN_HINT_LENGTH)
        val name = request.name?.trim()?.takeIf { it.isNotBlank() }

        val record = idePluginTokenRepository.insert(
            tenantId = tenantId,
            userId = userId,
            tokenHash = tokenHash,
            tokenHint = tokenHint,
            name = name,
        )

        return IntellijTokenCreatedResponse(
            tokenId = record.tokenId,
            name = record.name,
            token = token,
            tokenHint = record.tokenHint,
            createdAt = record.createdAt,
        )
    }

    fun listTokens(tenantId: UUID, userId: UUID): List<IntellijTokenSummary> =
        idePluginTokenRepository
            .listByUser(tenantId, userId)
            .map { it.toSummary() }

    fun revokeToken(tenantId: UUID, userId: UUID, tokenId: UUID): Boolean =
        idePluginTokenRepository.revokeById(tenantId, userId, tokenId)

    private fun IdePluginToken.toSummary(): IntellijTokenSummary =
        IntellijTokenSummary(
            tokenId = tokenId,
            name = name,
            tokenHint = tokenHint,
            lastUsedAt = lastUsedAt,
            revokedAt = revokedAt,
            createdAt = createdAt,
        )

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "${IdePluginTokenFormat.PREFIX}$raw"
    }

    private fun hashToken(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hashForLookup(raw: String): String = hashToken(raw.trim())

    fun touchTokenUsage(tokenId: UUID, now: OffsetDateTime) {
        idePluginTokenRepository.touchLastUsedAt(tokenId, now, TOKEN_TOUCH_THRESHOLD_SECONDS)
    }

    fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    companion object {
        private const val TOKEN_BYTES = 48
        private const val TOKEN_HINT_LENGTH = 6
        private const val TOKEN_TOUCH_THRESHOLD_SECONDS = 60L
        private val random = SecureRandom()
    }
}

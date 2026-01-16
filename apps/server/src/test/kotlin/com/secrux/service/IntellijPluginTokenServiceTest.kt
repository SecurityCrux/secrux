package com.secrux.service

import com.secrux.domain.IdePluginToken
import com.secrux.dto.CreateIntellijTokenRequest
import com.secrux.repo.IdePluginTokenRepository
import com.secrux.security.IdePluginTokenFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class IntellijPluginTokenServiceTest {

    @Mock
    private lateinit var tokenRepository: IdePluginTokenRepository

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: IntellijPluginTokenService

    @BeforeEach
    fun setUp() {
        service = IntellijPluginTokenService(tokenRepository, fixedClock)
    }

    @Test
    fun `createToken stores sha256 hash and returns raw token once`() {
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val createdAt = OffsetDateTime.now(fixedClock)
        val tokenId = UUID.randomUUID()

        whenever(tokenRepository.insert(eq(tenantId), eq(userId), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenAnswer { invocation ->
                val tokenHash = invocation.getArgument<String>(2)
                val tokenHint = invocation.getArgument<String>(3)
                val name = invocation.getArgument<String?>(4)
                IdePluginToken(
                    tokenId = tokenId,
                    tenantId = tenantId,
                    userId = userId,
                    tokenHash = tokenHash,
                    tokenHint = tokenHint,
                    name = name,
                    lastUsedAt = null,
                    revokedAt = null,
                    createdAt = createdAt,
                )
            }

        val response = service.createToken(tenantId, userId, CreateIntellijTokenRequest(name = "  IntelliJ  "))

        assertNotNull(response.token)
        assertTrue(response.token.startsWith(IdePluginTokenFormat.PREFIX))
        assertEquals(response.token.takeLast(6), response.tokenHint)
        assertEquals("IntelliJ", response.name)
        assertEquals(createdAt, response.createdAt)

        val tokenHashCaptor = argumentCaptor<String>()
        val tokenHintCaptor = argumentCaptor<String>()
        val nameCaptor = argumentCaptor<String>()
        verify(tokenRepository).insert(eq(tenantId), eq(userId), tokenHashCaptor.capture(), tokenHintCaptor.capture(), nameCaptor.capture())

        assertEquals(response.tokenHint, tokenHintCaptor.firstValue)
        assertEquals(hashSha256Hex(response.token), tokenHashCaptor.firstValue)
        assertEquals("IntelliJ", nameCaptor.firstValue)
    }

    private fun hashSha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

package com.secrux.security

import com.secrux.domain.AppUser
import com.secrux.domain.IdePluginToken
import com.secrux.repo.AppUserDirectoryRepository
import com.secrux.repo.IamPermissionRepository
import com.secrux.repo.IdePluginTokenRepository
import com.secrux.service.IntellijPluginTokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
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
class IdePluginTokenAuthenticationServiceTest {

    @Mock
    private lateinit var tokenRepository: IdePluginTokenRepository

    @Mock
    private lateinit var userDirectoryRepository: AppUserDirectoryRepository

    @Mock
    private lateinit var permissionRepository: IamPermissionRepository

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)

    private lateinit var tokenService: IntellijPluginTokenService
    private lateinit var service: IdePluginTokenAuthenticationService

    @BeforeEach
    fun setUp() {
        tokenService = IntellijPluginTokenService(tokenRepository, fixedClock)
        service =
            IdePluginTokenAuthenticationService(
                tokenRepository = tokenRepository,
                userDirectoryRepository = userDirectoryRepository,
                permissionRepository = permissionRepository,
                tokenService = tokenService,
            )
    }

    @Test
    fun `authenticate returns null for non plugin tokens`() {
        val authentication = service.authenticate("not-a-plugin-token")
        assertNull(authentication)
    }

    @Test
    fun `authenticate prefers effective permissions over legacy roles`() {
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val tokenId = UUID.randomUUID()
        val createdAt = OffsetDateTime.now(fixedClock)

        val rawToken = "${IdePluginTokenFormat.PREFIX}example"
        val tokenHash = hashSha256Hex(rawToken)
        val tokenRecord =
            IdePluginToken(
                tokenId = tokenId,
                tenantId = tenantId,
                userId = userId,
                tokenHash = tokenHash,
                tokenHint = rawToken.takeLast(6),
                name = null,
                lastUsedAt = null,
                revokedAt = null,
                createdAt = createdAt,
            )

        whenever(tokenRepository.findActiveByHash(eq(tokenHash))).thenReturn(tokenRecord)
        whenever(tokenRepository.touchLastUsedAt(eq(tokenId), any(), any())).thenReturn(true)

        val user =
            AppUser(
                userId = userId,
                tenantId = tenantId,
                username = "alice",
                email = "alice@example.com",
                phone = null,
                name = "Alice",
                enabled = true,
                roles = listOf("task:create"),
                lastLoginAt = null,
                createdAt = createdAt,
                updatedAt = null,
        )
        whenever(userDirectoryRepository.findById(eq(tenantId), eq(userId))).thenReturn(user)
        whenever(permissionRepository.listEffectivePermissions(eq(tenantId), eq(userId))).thenReturn(setOf("task:read", "finding:ingest"))

        val authentication = service.authenticate(rawToken)
        assertNotNull(authentication)

        val principal = authentication!!.principal as SecruxPrincipal
        assertEquals(tenantId, principal.tenantId)
        assertEquals(userId, principal.userId)
        assertEquals("alice@example.com", principal.email)
        assertEquals("alice", principal.username)
        assertEquals(setOf("task:read", "finding:ingest"), principal.roles)

        verify(tokenRepository).touchLastUsedAt(eq(tokenId), any(), any())
    }

    private fun hashSha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

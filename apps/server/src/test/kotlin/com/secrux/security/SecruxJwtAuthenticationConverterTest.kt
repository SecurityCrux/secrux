package com.secrux.security

import com.secrux.domain.AppUser
import com.secrux.repo.AppUserDirectoryRepository
import com.secrux.repo.IamPermissionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.jwt.Jwt
import java.time.OffsetDateTime
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class SecruxJwtAuthenticationConverterTest {

    @Mock
    private lateinit var userDirectoryRepository: AppUserDirectoryRepository

    @Mock
    private lateinit var permissionRepository: IamPermissionRepository

    private lateinit var converter: SecruxJwtAuthenticationConverter

    @BeforeEach
    fun setup() {
        converter =
            SecruxJwtAuthenticationConverter(
                authProperties = SecruxAuthProperties(),
                userDirectoryRepository = userDirectoryRepository,
                permissionRepository = permissionRepository
            )
    }

    @Test
    fun `missing tenant_id claim falls back to user directory`() {
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()

        whenever(userDirectoryRepository.findTenantIdByUserId(userId)).thenReturn(tenantId)
        whenever(userDirectoryRepository.findById(tenantId, userId)).thenReturn(
            AppUser(
                userId = userId,
                tenantId = tenantId,
                username = "custom3",
                email = "custom3@example.com",
                phone = null,
                name = "custom3",
                enabled = true,
                roles = emptyList(),
                lastLoginAt = null,
                createdAt = now,
                updatedAt = now
            )
        )
        whenever(permissionRepository.listEffectivePermissions(tenantId, userId)).thenReturn(emptySet())

        val jwt =
            Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", userId.toString())
                .claim("preferred_username", "custom3")
                .build()

        val auth = converter.convert(jwt)
        val principal = auth.principal as SecruxPrincipal

        assertEquals(tenantId, principal.tenantId)
        assertEquals(userId, principal.userId)
        verify(userDirectoryRepository).findTenantIdByUserId(eq(userId))
    }

    @Test
    fun `tenant_id claim mismatch is rejected`() {
        val tenantIdFromToken = UUID.randomUUID()
        val tenantIdFromDb = UUID.randomUUID()
        val userId = UUID.randomUUID()

        whenever(userDirectoryRepository.findTenantIdByUserId(userId)).thenReturn(tenantIdFromDb)

        val jwt =
            Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", userId.toString())
                .claim("tenant_id", tenantIdFromToken.toString())
                .build()

        assertThrows(OAuth2AuthenticationException::class.java) {
            converter.convert(jwt)
        }
    }

    @Test
    fun `missing tenant_id claim without directory mapping is rejected`() {
        val userId = UUID.randomUUID()
        whenever(userDirectoryRepository.findTenantIdByUserId(userId)).thenReturn(null)

        val jwt =
            Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", userId.toString())
                .build()

        assertThrows(OAuth2AuthenticationException::class.java) {
            converter.convert(jwt)
        }
    }
}

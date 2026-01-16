package com.secrux.service

import com.secrux.repo.AppUserRepository
import com.secrux.security.SecruxPrincipal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class UserServiceTest {

    @Mock
    private lateinit var appUserRepository: AppUserRepository

    private lateinit var service: UserService

    private val tenantId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        service = UserService(appUserRepository)
    }

    @Test
    fun `syncFromPrincipal uses email when provided`() {
        val principal = SecruxPrincipal(
            tenantId = tenantId,
            userId = userId,
            email = "user@example.com",
            username = "user",
            roles = setOf("admin")
        )

        service.syncFromPrincipal(principal)

        verify(appUserRepository).upsert(
            userId = userId,
            tenantId = tenantId,
            email = "user@example.com",
            username = "user",
            name = "user",
            roles = listOf("admin")
        )
    }

    @Test
    fun `syncFromPrincipal falls back to username when email absent`() {
        val principal = SecruxPrincipal(
            tenantId = tenantId,
            userId = userId,
            email = null,
            username = "user",
            roles = emptySet()
        )

        service.syncFromPrincipal(principal)

        verify(appUserRepository).upsert(
            userId = userId,
            tenantId = tenantId,
            email = "user@$tenantId",
            username = "user",
            name = "user",
            roles = emptyList()
        )
    }

    @Test
    fun `syncFromPrincipal falls back to synthetic email when claims missing`() {
        val principal = SecruxPrincipal(
            tenantId = tenantId,
            userId = userId,
            email = null,
            username = null,
            roles = setOf("viewer")
        )

        service.syncFromPrincipal(principal)

        verify(appUserRepository).upsert(
            userId = userId,
            tenantId = tenantId,
            email = "$userId@$tenantId",
            username = null,
            name = null,
            roles = listOf("viewer")
        )
    }
}

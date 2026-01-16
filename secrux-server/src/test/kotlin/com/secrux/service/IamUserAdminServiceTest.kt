package com.secrux.service

import com.secrux.dto.UserCreateRequest
import com.secrux.dto.UserPasswordResetRequest
import com.secrux.dto.UserSummary
import com.secrux.repo.AppUserDirectoryRepository
import com.secrux.repo.IamPermissionRepository
import com.secrux.repo.IamRoleRepository
import com.secrux.repo.IamUserRoleRepository
import com.secrux.repo.LocalCredentialRepository
import com.secrux.security.AuthMode
import com.secrux.security.SecruxAuthProperties
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class IamUserAdminServiceTest {

    @Mock
    private lateinit var dsl: DSLContext

    @Mock
    private lateinit var passwordHashService: PasswordHashService

    @Mock
    private lateinit var userDirectoryRepository: AppUserDirectoryRepository

    @Mock
    private lateinit var localCredentialRepository: LocalCredentialRepository

    @Mock
    private lateinit var keycloakAdminService: KeycloakAdminService

    @Mock
    private lateinit var roleRepository: IamRoleRepository

    @Mock
    private lateinit var userRoleRepository: IamUserRoleRepository

    @Mock
    private lateinit var permissionRepository: IamPermissionRepository

    private lateinit var service: IamUserAdminService

    private val tenantId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        service =
            IamUserAdminService(
                dsl = dsl,
                clock = Clock.systemUTC(),
                authProperties = SecruxAuthProperties(mode = AuthMode.KEYCLOAK),
                passwordHashService = passwordHashService,
                userDirectoryRepository = userDirectoryRepository,
                localCredentialRepository = localCredentialRepository,
                keycloakAdminService = keycloakAdminService,
                roleRepository = roleRepository,
                userRoleRepository = userRoleRepository,
                permissionRepository = permissionRepository
            )
    }

    @Test
    fun `resetPassword in KEYCLOAK mode forces non-temporary`() {
        service.resetPassword(tenantId, userId, UserPasswordResetRequest(password = "new-pass", temporary = true))

        val requestCaptor = argumentCaptor<UserPasswordResetRequest>()
        verify(keycloakAdminService).resetPassword(eq(userId.toString()), requestCaptor.capture())
        assertFalse(requestCaptor.firstValue.temporary)
        assertEquals("new-pass", requestCaptor.firstValue.password)
        verify(localCredentialRepository, never()).upsert(any(), any(), any(), any())
    }

    @Test
    fun `createLocalUser in KEYCLOAK mode sets password even if user exists`() {
        val keycloakUserId = UUID.randomUUID().toString()
        whenever(keycloakAdminService.findByUsername("dev-custom"))
            .thenReturn(UserSummary(id = keycloakUserId, username = "dev-custom", email = null, enabled = true))
        whenever(userDirectoryRepository.findByUsername(tenantId, "dev-custom")).thenReturn(null)

        service.createLocalUser(
            tenantId,
            UserCreateRequest(username = "dev-custom", email = null, password = "pw", enabled = true)
        )

        val requestCaptor = argumentCaptor<UserPasswordResetRequest>()
        verify(keycloakAdminService).resetPassword(eq(keycloakUserId), requestCaptor.capture())
        assertFalse(requestCaptor.firstValue.temporary)
        assertEquals("pw", requestCaptor.firstValue.password)
        verify(userDirectoryRepository).insert(any())
    }
}


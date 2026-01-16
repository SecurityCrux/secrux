package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Project
import com.secrux.domain.Repository
import com.secrux.domain.RepositoryGitAuthMode
import com.secrux.domain.RepositorySourceMode
import com.secrux.dto.ProjectUpsertRequest
import com.secrux.dto.RepositoryUpsertRequest
import com.secrux.repo.ProjectRepository
import com.secrux.repo.RepositoryRepository
import com.secrux.config.SecruxCryptoProperties
import com.secrux.security.SecretCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper

@ExtendWith(MockitoExtension::class)
class ProjectServiceTest {

    @Mock
    private lateinit var projectRepository: ProjectRepository

    @Mock
    private lateinit var repositoryRepository: RepositoryRepository

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val secretCrypto = SecretCrypto(SecruxCryptoProperties(secret = "unit-test-secret-32-bytes!!"))
    private val objectMapper = ObjectMapper()
    private lateinit var service: ProjectService

    @BeforeEach
    fun setUp() {
        service = ProjectService(projectRepository, repositoryRepository, secretCrypto, objectMapper, fixedClock)
    }

    @Test
    fun `create project persists entity`() {
        val tenantId = UUID.randomUUID()
        val request = ProjectUpsertRequest(name = "sec", codeOwners = listOf("a", "a", "b"))

        service.createProject(tenantId, request)

        val captor = argumentCaptor<Project>()
        verify(projectRepository).insert(captor.capture())
        val saved = captor.firstValue
        assertEquals("sec", saved.name)
        assertEquals(listOf("a", "b"), saved.codeOwners)
        assertEquals(OffsetDateTime.now(fixedClock), saved.createdAt)
    }

    @Test
    fun `update project uses repository`() {
        val tenantId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val existing = Project(
            projectId = projectId,
            tenantId = tenantId,
            name = "old",
            codeOwners = listOf("x"),
            createdAt = OffsetDateTime.now(fixedClock).minusDays(1),
            updatedAt = null
        )
        whenever(projectRepository.findById(projectId, tenantId)).thenReturn(existing)

        service.updateProject(tenantId, projectId, ProjectUpsertRequest("new", listOf("m")))

        val captor = argumentCaptor<Project>()
        verify(projectRepository).update(captor.capture())
        val updated = captor.firstValue
        assertEquals("new", updated.name)
        assertEquals(listOf("m"), updated.codeOwners)
    }

    @Test
    fun `create repository verifies project existence`() {
        val tenantId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        whenever(projectRepository.findById(projectId, tenantId)).thenReturn(
            Project(projectId, tenantId, "p", emptyList(), OffsetDateTime.now(fixedClock), null)
        )
        val req = RepositoryUpsertRequest(
            projectId = projectId,
            sourceMode = RepositorySourceMode.REMOTE,
            remoteUrl = "https://git",
            scmType = "github"
        )

        service.createRepository(tenantId, req)

        val captor = argumentCaptor<Repository>()
        verify(repositoryRepository).insert(captor.capture())
        assertEquals("https://git", captor.firstValue.remoteUrl)
        assertEquals(RepositoryGitAuthMode.NONE, captor.firstValue.gitAuth.mode)
    }

    @Test
    fun `create repository fails when project missing`() {
        val tenantId = UUID.randomUUID()
        val req = RepositoryUpsertRequest(
            projectId = UUID.randomUUID(),
            sourceMode = RepositorySourceMode.REMOTE
        )

        val ex = assertThrows(SecruxException::class.java) {
            service.createRepository(tenantId, req)
        }
        assertEquals(ErrorCode.PROJECT_NOT_FOUND, ex.errorCode)
        verify(repositoryRepository, org.mockito.kotlin.never()).insert(any())
    }
}

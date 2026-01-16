package com.secrux.service

import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.SourceRefType
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.dto.CreateIntellijTaskRequest
import com.secrux.repo.TaskRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class IntellijTaskCommandServiceTest {

    @Mock
    private lateinit var taskRepository: TaskRepository

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: IntellijTaskCommandService

    @BeforeEach
    fun setUp() {
        service = IntellijTaskCommandService(taskRepository, fixedClock)
    }

    @Test
    fun `createTask persists IDE audit task`() {
        val tenantId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val repoId = UUID.randomUUID()
        val request =
            CreateIntellijTaskRequest(
                projectId = projectId,
                repoId = repoId,
                name = "  My IDE Task  ",
                branch = " main ",
                commitSha = " 0123456789abcdef ",
            )

        val summary = service.createTask(tenantId, ownerId, request)

        val captor = argumentCaptor<com.secrux.domain.Task>()
        verify(taskRepository).insert(captor.capture())
        val saved = captor.firstValue
        assertEquals(tenantId, saved.tenantId)
        assertEquals(projectId, saved.projectId)
        assertEquals(repoId, saved.repoId)
        assertEquals(TaskType.IDE_AUDIT, saved.type)
        assertEquals(TaskStatus.PENDING, saved.status)
        assertEquals(ownerId, saved.owner)
        assertEquals("My IDE Task", saved.name)
        assertEquals(saved.taskId.toString(), saved.correlationId)
        assertEquals(SourceRefType.BRANCH, saved.sourceRefType)
        assertEquals("main", saved.sourceRef)
        assertEquals("0123456789abcdef", saved.commitSha)
        assertEquals(OffsetDateTime.now(fixedClock), saved.createdAt)
        assertNull(saved.executorId)
        assertEquals(RuleSelectorMode.AUTO, saved.spec.ruleSelector.mode)

        assertEquals(saved.taskId, summary.taskId)
        assertEquals(TaskType.IDE_AUDIT, summary.type)
        assertEquals("My IDE Task", summary.name)
        assertEquals("main", summary.sourceRef)
        assertEquals("0123456789abcdef", summary.commitSha)
    }

    @Test
    fun `createTask defaults name from branch and commit`() {
        val tenantId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val request =
            CreateIntellijTaskRequest(
                projectId = projectId,
                branch = "feature/x",
                commitSha = "abcdef0123456789",
            )

        service.createTask(tenantId, owner = null, request = request)

        val captor = argumentCaptor<com.secrux.domain.Task>()
        verify(taskRepository).insert(captor.capture())
        val saved = captor.firstValue
        assertEquals("IDE Audit feature/x@abcdef0", saved.name)
    }
}


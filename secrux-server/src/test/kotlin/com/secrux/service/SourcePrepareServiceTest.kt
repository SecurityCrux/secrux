package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.GitSourceSpec
import com.secrux.domain.SourceSpec
import com.secrux.domain.Stage
import com.secrux.domain.StageType
import com.secrux.domain.Task
import com.secrux.domain.TaskSpec
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.domain.RuleSelectorSpec
import com.secrux.domain.SourceRefType
import com.secrux.repo.TaskRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class SourcePrepareServiceTest {

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Mock
    private lateinit var workspaceService: WorkspaceService

    @Mock
    private lateinit var stageLifecycle: StageLifecycle

    @Mock
    private lateinit var taskLogService: TaskLogService

    private val clock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: SourcePrepareService

    @BeforeEach
    fun setup() {
        service = SourcePrepareService(taskRepository, workspaceService, stageLifecycle, taskLogService, clock)
    }

    @Test
    fun `run source prepare writes stage`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = buildTask(taskId, tenantId)
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)
        val workspace = WorkspaceHandle(Files.createTempDirectory("workspace"))
        whenever(workspaceService.prepare(task)).thenReturn(workspace)

        service.run(tenantId, taskId, stageId)

        val stageCaptor = argumentCaptor<Stage>()
        verify(stageLifecycle).persist(stageCaptor.capture(), eq(task.correlationId))
        val stage = stageCaptor.firstValue
        assertEquals(StageType.SOURCE_PREPARE, stage.type)
        assertEquals("source_bundle:$taskId", stage.artifacts.first())
    }

    @Test
    fun `missing task throws`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(null)

        val ex = assertThrows(SecruxException::class.java) {
            service.run(tenantId, taskId, stageId)
        }
        assertEquals(ErrorCode.TASK_NOT_FOUND, ex.errorCode)
    }

    private fun buildTask(taskId: UUID, tenantId: UUID): Task {
        val source = SourceSpec(
            git = GitSourceSpec(repo = "git@github.com:org/repo.git", ref = "main")
        )
        return Task(
            taskId = taskId,
            tenantId = tenantId,
            projectId = UUID.randomUUID(),
            type = TaskType.CODE_CHECK,
            spec = TaskSpec(
                source = source,
                ruleSelector = RuleSelectorSpec(mode = com.secrux.domain.RuleSelectorMode.AUTO)
            ),
            status = TaskStatus.PENDING,
            owner = null,
            correlationId = "corr",
            sourceRefType = SourceRefType.BRANCH,
            sourceRef = "main",
            commitSha = null,
            createdAt = OffsetDateTime.now(clock),
            updatedAt = null
        )
    }
}

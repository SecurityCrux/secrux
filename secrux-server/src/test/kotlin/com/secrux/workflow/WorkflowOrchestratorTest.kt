package com.secrux.workflow

import com.secrux.dto.ResultProcessRequest
import com.secrux.dto.ResultReviewRequest
import com.secrux.dto.ScanExecRequest
import com.secrux.domain.GitSourceSpec
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.RuleSelectorSpec
import com.secrux.domain.SourceRefType
import com.secrux.domain.SourceSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.domain.Task
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskSpec
import com.secrux.domain.TaskType
import com.secrux.events.PlatformEvent
import com.secrux.events.PlatformEventPublisher
import com.secrux.repo.TaskRepository
import com.secrux.service.ResultProcessService
import com.secrux.service.ResultReviewService
import com.secrux.service.RulePrepareService
import com.secrux.service.ScanExecService
import com.secrux.service.ScaResultProcessService
import com.secrux.service.ScaResultReviewService
import com.secrux.service.ScaScanExecService
import com.secrux.service.StageLifecycle
import com.secrux.service.SourcePrepareService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WorkflowOrchestratorTest {

    @Mock
    private lateinit var sourcePrepareService: SourcePrepareService

    @Mock
    private lateinit var rulePrepareService: RulePrepareService

    @Mock
    private lateinit var scanExecService: ScanExecService

    @Mock
    private lateinit var scaScanExecService: ScaScanExecService

    @Mock
    private lateinit var resultProcessService: ResultProcessService

    @Mock
    private lateinit var scaResultProcessService: ScaResultProcessService

    @Mock
    private lateinit var resultReviewService: ResultReviewService

    @Mock
    private lateinit var scaResultReviewService: ScaResultReviewService

    @Mock
    private lateinit var stageLifecycle: StageLifecycle

    @Mock
    private lateinit var eventPublisher: PlatformEventPublisher

    @Mock
    private lateinit var taskRepository: TaskRepository

    private val clock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var orchestrator: WorkflowOrchestrator

    @BeforeEach
    fun setup() {
        val stagePlanner = WorkflowStagePlanner()
        val stageExecutor =
            WorkflowStageExecutor(
                sourcePrepareService = sourcePrepareService,
                rulePrepareService = rulePrepareService,
                scanExecService = scanExecService,
                scaScanExecService = scaScanExecService,
                resultProcessService = resultProcessService,
                scaResultProcessService = scaResultProcessService,
                resultReviewService = resultReviewService,
                scaResultReviewService = scaResultReviewService,
                stageLifecycle = stageLifecycle,
                clock = clock
            )
        orchestrator = WorkflowOrchestrator(
            stagePlanner,
            stageExecutor,
            eventPublisher,
            taskRepository,
            clock
        )
    }

    @Test
    fun `run cycle executes pipeline`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(testTask(taskId, tenantId, TaskType.SECURITY_SCAN))
        orchestrator.startWorkflow(tenantId, taskId)

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(emptyList())
        orchestrator.runCycle()

        val sourceStageIdCaptor = argumentCaptor<UUID>()
        verify(sourcePrepareService).run(any(), any(), sourceStageIdCaptor.capture())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = sourceStageIdCaptor.firstValue,
                    stageType = StageType.SOURCE_PREPARE,
                    status = StageStatus.SUCCEEDED
                )
            )
        )
        orchestrator.runCycle()

        val rulesStageIdCaptor = argumentCaptor<UUID>()
        verify(rulePrepareService).run(any(), any(), rulesStageIdCaptor.capture())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = rulesStageIdCaptor.firstValue,
                    stageType = StageType.RULES_PREPARE,
                    status = StageStatus.SUCCEEDED
                )
            )
        )
        orchestrator.runCycle()

        val scanStageIdCaptor = argumentCaptor<UUID>()
        verify(scanExecService).run(any(), any(), scanStageIdCaptor.capture(), any())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = scanStageIdCaptor.firstValue,
                    stageType = StageType.SCAN_EXEC,
                    status = StageStatus.SUCCEEDED
                )
            )
        )
        orchestrator.runCycle()

        val processStageIdCaptor = argumentCaptor<UUID>()
        verify(resultProcessService).run(any(), any(), processStageIdCaptor.capture(), any<ResultProcessRequest>())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = processStageIdCaptor.firstValue,
                    stageType = StageType.RESULT_PROCESS,
                    status = StageStatus.SUCCEEDED
                )
            )
        )
        orchestrator.runCycle()

        val reviewStageIdCaptor = argumentCaptor<UUID>()
        verify(resultReviewService).run(any(), any(), reviewStageIdCaptor.capture(), any<ResultReviewRequest>())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = reviewStageIdCaptor.firstValue,
                    stageType = StageType.RESULT_REVIEW,
                    status = StageStatus.SUCCEEDED
                )
            )
        )
        orchestrator.runCycle()

        verify(scaScanExecService, never()).run(any(), any(), any())
        verify(scaResultProcessService, never()).run(any(), any(), any())
        verify(taskRepository).updateStatus(taskId, tenantId, TaskStatus.SUCCEEDED.name)
    }

    @Test
    fun `sca check skips rules stage`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(testTask(taskId, tenantId, TaskType.SCA_CHECK))
        orchestrator.startWorkflow(tenantId, taskId)

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(emptyList())
        orchestrator.runCycle()

        val sourceStageIdCaptor = argumentCaptor<UUID>()
        verify(sourcePrepareService).run(any(), any(), sourceStageIdCaptor.capture())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = sourceStageIdCaptor.firstValue,
                    stageType = StageType.SOURCE_PREPARE,
                    status = StageStatus.SUCCEEDED
                )
            )
        )
        orchestrator.runCycle()

        val scanStageIdCaptor = argumentCaptor<UUID>()
        verify(scaScanExecService).run(any(), any(), scanStageIdCaptor.capture())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = scanStageIdCaptor.firstValue,
                    stageType = StageType.SCAN_EXEC,
                    status = StageStatus.SUCCEEDED
                )
            )
        )
        orchestrator.runCycle()

        val processStageIdCaptor = argumentCaptor<UUID>()
        verify(scaResultProcessService).run(any(), any(), processStageIdCaptor.capture())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = processStageIdCaptor.firstValue,
                    stageType = StageType.RESULT_PROCESS,
                    status = StageStatus.SUCCEEDED
                )
            )
        )
        orchestrator.runCycle()

        val reviewStageIdCaptor = argumentCaptor<UUID>()
        verify(scaResultReviewService).run(any(), any(), reviewStageIdCaptor.capture(), anyOrNull())

        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(
            listOf(
                stageEvent(
                    tenantId = tenantId,
                    taskId = taskId,
                    stageId = reviewStageIdCaptor.firstValue,
                    stageType = StageType.RESULT_REVIEW,
                    status = StageStatus.SKIPPED
                )
            )
        )
        orchestrator.runCycle()

        verify(rulePrepareService, never()).run(any(), any(), any())
        verify(scanExecService, never()).run(any(), any(), any(), any<ScanExecRequest>())
        verify(resultProcessService, never()).run(any(), any(), any(), any<ResultProcessRequest>())
        verify(resultReviewService, never()).run(any(), any(), any(), any<ResultReviewRequest>())
        verify(taskRepository).updateStatus(taskId, tenantId, TaskStatus.SUCCEEDED.name)
    }

    @Test
    fun `initial poll starts at epoch`() {
        whenever(eventPublisher.fetchAfter(any(), any())).thenReturn(emptyList())
        orchestrator.runCycle()

        val captor = argumentCaptor<OffsetDateTime>()
        verify(eventPublisher).fetchAfter(captor.capture(), any())
        assertEquals(OffsetDateTime.ofInstant(Instant.EPOCH, clock.zone), captor.firstValue)
    }

    private fun stageEvent(
        tenantId: UUID,
        taskId: UUID,
        stageId: UUID,
        stageType: StageType,
        status: StageStatus
    ): PlatformEvent =
        PlatformEvent(
            tenantId = tenantId,
            event = if (status == StageStatus.FAILED) "StageFailed" else "StageCompleted",
            correlationId = "corr",
            payload =
                mapOf(
                    "stage_id" to stageId.toString(),
                    "task_id" to taskId.toString(),
                    "tenant_id" to tenantId.toString(),
                    "type" to stageType.name,
                    "status" to status.name,
                ),
            createdAt = OffsetDateTime.now(clock)
        )

    private fun testTask(taskId: UUID, tenantId: UUID, type: TaskType): Task {
        val now = OffsetDateTime.ofInstant(Instant.EPOCH, clock.zone)
        return Task(
            taskId = taskId,
            tenantId = tenantId,
            projectId = UUID.randomUUID(),
            type = type,
            spec =
                TaskSpec(
                    source = SourceSpec(git = GitSourceSpec(repo = "/tmp/repo", ref = "main")),
                    ruleSelector = RuleSelectorSpec(mode = RuleSelectorMode.AUTO)
                ),
            status = TaskStatus.PENDING,
            owner = null,
            correlationId = "corr",
            sourceRefType = SourceRefType.BRANCH,
            sourceRef = "main",
            commitSha = null,
            createdAt = now,
            updatedAt = null
        )
    }
}

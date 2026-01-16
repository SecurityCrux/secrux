package com.secrux.service

import com.secrux.domain.Finding
import com.secrux.domain.FindingStatus
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.Severity
import com.secrux.domain.AiReviewSpec
import com.secrux.domain.SourceRefType
import com.secrux.domain.SourceSpec
import com.secrux.domain.Task
import com.secrux.domain.TaskSpec
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.dto.AiJobTicketResponse
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.dto.TicketCreationRequest
import com.secrux.ai.AiJobStatus
import com.secrux.ai.AiJobType
import com.secrux.repo.FindingRepository
import com.secrux.repo.AiClientConfigRepository
import com.secrux.repo.TaskRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ResultReviewServiceTest {

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Mock
    private lateinit var findingRepository: FindingRepository

    @Mock
    private lateinit var findingService: FindingService

    @Mock
    private lateinit var stageLifecycle: StageLifecycle

    @Mock
    private lateinit var ticketService: TicketService

    @Mock
    private lateinit var taskLogService: TaskLogService

    @Mock
    private lateinit var aiClientConfigRepository: AiClientConfigRepository

    private val clock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: ResultReviewService

    @BeforeEach
    fun setup() {
        service = ResultReviewService(
            taskRepository,
            findingRepository,
            findingService,
            stageLifecycle,
            ticketService,
            taskLogService,
            aiClientConfigRepository,
            clock
        )
    }

    @Test
    fun `high severity findings queued for ai and ticket created`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = buildTask(taskId, tenantId)
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)
        whenever(aiClientConfigRepository.findDefaultEnabled(tenantId)).thenReturn(buildAiClientConfig(tenantId))
        val highFinding = buildFinding(Severity.HIGH)
        val mediumFinding = buildFinding(Severity.MEDIUM)
        whenever(findingRepository.listByTask(tenantId, taskId)).thenReturn(listOf(highFinding, mediumFinding))
        whenever(findingService.triggerAiReview(eq(tenantId), any(), any())).thenReturn(dummyAiJob())

        service.run(tenantId, taskId, stageId, com.secrux.dto.ResultReviewRequest())

        val ticketCaptor = argumentCaptor<TicketCreationRequest>()
        verify(ticketService).createTickets(eq(tenantId), ticketCaptor.capture())
        verify(findingService, times(2)).triggerAiReview(eq(tenantId), any(), any())
        verify(findingService).triggerAiReview(eq(tenantId), eq(highFinding.findingId), eq(AiReviewTriggerRequest(mode = "simple")))
        verify(findingService).triggerAiReview(eq(tenantId), eq(mediumFinding.findingId), eq(AiReviewTriggerRequest(mode = "simple")))
        assertEquals(listOf(highFinding.findingId), ticketCaptor.firstValue.findingIds)
        verify(stageLifecycle).persist(any(), eq(task.correlationId))
    }

    @Test
    fun `no high severity skips ticket creation`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = buildTask(taskId, tenantId)
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)
        whenever(aiClientConfigRepository.findDefaultEnabled(tenantId)).thenReturn(buildAiClientConfig(tenantId))
        val lowFinding = buildFinding(Severity.LOW)
        whenever(findingRepository.listByTask(tenantId, taskId)).thenReturn(listOf(lowFinding))
        whenever(findingService.triggerAiReview(eq(tenantId), any(), any())).thenReturn(dummyAiJob())

        service.run(tenantId, taskId, stageId, com.secrux.dto.ResultReviewRequest())

        verify(ticketService, never()).createTickets(eq(tenantId), any())
        verify(findingService).triggerAiReview(eq(tenantId), eq(lowFinding.findingId), eq(AiReviewTriggerRequest(mode = "simple")))
    }

    private fun buildTask(taskId: UUID, tenantId: UUID): Task {
        return Task(
            taskId = taskId,
            tenantId = tenantId,
            projectId = UUID.randomUUID(),
            type = TaskType.CODE_CHECK,
            spec = TaskSpec(
                source = SourceSpec(),
                ruleSelector = com.secrux.domain.RuleSelectorSpec(mode = RuleSelectorMode.AUTO),
                aiReview = AiReviewSpec(enabled = true)
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

    private fun buildFinding(severity: Severity): Finding {
        return Finding(
            findingId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            taskId = UUID.randomUUID(),
            projectId = UUID.randomUUID(),
            sourceEngine = "semgrep",
            ruleId = "rule",
            location = mapOf("path" to "file", "line" to 1),
            evidence = null,
            severity = severity,
            fingerprint = UUID.randomUUID().toString(),
            status = FindingStatus.OPEN,
            introducedBy = null,
            fixVersion = null,
            exploitMaturity = null,
            createdAt = OffsetDateTime.now(clock),
            updatedAt = OffsetDateTime.now(clock)
        )
    }

    private fun dummyAiJob(): AiJobTicketResponse =
        AiJobTicketResponse(
            jobId = "job-1",
            status = AiJobStatus.QUEUED,
            jobType = AiJobType.FINDING_REVIEW,
            createdAt = OffsetDateTime.now(clock).toString(),
            updatedAt = OffsetDateTime.now(clock).toString(),
            result = null,
            error = null
        )

    private fun buildAiClientConfig(tenantId: UUID): com.secrux.domain.AiClientConfig =
        com.secrux.domain.AiClientConfig(
            configId = UUID.randomUUID(),
            tenantId = tenantId,
            name = "default",
            provider = "openai",
            baseUrl = "http://localhost",
            apiKey = null,
            model = "gpt-4o-mini",
            isDefault = true,
            enabled = true,
            createdAt = OffsetDateTime.now(clock),
            updatedAt = null
        )
}

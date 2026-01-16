package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Rule
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.Ruleset
import com.secrux.domain.Severity
import com.secrux.domain.SourceSpec
import com.secrux.domain.Stage
import com.secrux.domain.StageType
import com.secrux.domain.Task
import com.secrux.domain.TaskSpec
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.domain.RuleSelectorSpec
import com.secrux.repo.RuleRepository
import com.secrux.repo.RulesetRepository
import com.secrux.repo.TaskRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
class RulePrepareServiceTest {

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Mock
    private lateinit var stageLifecycle: StageLifecycle

    @Mock
    private lateinit var ruleRepository: RuleRepository

    @Mock
    private lateinit var rulesetRepository: RulesetRepository

    @Mock
    private lateinit var taskLogService: TaskLogService

    private val clock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: RulePrepareService

    @BeforeEach
    fun setup() {
        service = RulePrepareService(
            taskRepository,
            ruleRepository,
            rulesetRepository,
            stageLifecycle,
            taskLogService,
            clock
        )
    }

    @Test
    fun `explicit mode creates new ruleset`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val ruleId = UUID.randomUUID()
        val task = buildTask(taskId, tenantId, RuleSelectorMode.EXPLICIT, listOf(ruleId.toString()))
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)
        val rule = Rule(
            ruleId = ruleId,
            tenantId = tenantId,
            scope = "tenant",
            key = "rule",
            name = "Rule",
            engine = "semgrep",
            langs = listOf("py"),
            severityDefault = Severity.HIGH,
            tags = emptyList(),
            pattern = mapOf("pattern" to "code"),
            docs = null,
            enabled = true,
            hash = "hash",
            signature = null,
            createdAt = now(),
            updatedAt = now(),
            deprecatedAt = null
        )
        whenever(ruleRepository.findByIds(tenantId, listOf(ruleId))).thenReturn(listOf(rule))

        service.run(tenantId, taskId, stageId)

        verify(rulesetRepository).insert(any(), any())
        val stageCaptor = argumentCaptor<Stage>()
        verify(stageLifecycle).persist(stageCaptor.capture(), eq(task.correlationId))
        val stage = stageCaptor.firstValue
        assertEquals(StageType.RULES_PREPARE, stage.type)
        assertTrue(stage.artifacts.first().startsWith("ruleset:"))
    }

    @Test
    fun `profile mode uses latest ruleset`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = buildTask(taskId, tenantId, RuleSelectorMode.PROFILE, profile = "strict")
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)
        val ruleset = Ruleset(
            rulesetId = UUID.randomUUID(),
            tenantId = tenantId,
            source = "group:strict",
            version = "1",
            profile = "strict",
            langs = listOf("py"),
            hash = "hash",
            signature = null,
            uri = null,
            deletedAt = null
        )
        whenever(rulesetRepository.findLatestByProfile(tenantId, "strict")).thenReturn(ruleset)

        service.run(tenantId, taskId, stageId)

        verify(rulesetRepository, never()).insert(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        val stageCaptor = argumentCaptor<Stage>()
        verify(stageLifecycle).persist(stageCaptor.capture(), eq(task.correlationId))
        assertEquals(ruleset.rulesetId.toString(), stageCaptor.firstValue.spec.params["rulesetId"])
    }

    private fun buildTask(
        taskId: UUID,
        tenantId: UUID,
        mode: RuleSelectorMode,
        explicitRules: List<String>? = null,
        profile: String? = null
    ): Task {
        val selector = RuleSelectorSpec(
            mode = mode,
            profile = profile,
            explicitRules = explicitRules
        )
        val spec = TaskSpec(
            source = SourceSpec(),
            ruleSelector = selector
        )
        return Task(
            taskId = taskId,
            tenantId = tenantId,
            projectId = UUID.randomUUID(),
            type = TaskType.CODE_CHECK,
            spec = spec,
            status = TaskStatus.PENDING,
            owner = null,
            correlationId = "corr",
            sourceRefType = com.secrux.domain.SourceRefType.BRANCH,
            sourceRef = "main",
            commitSha = null,
            createdAt = now(),
            updatedAt = null
        )
    }

    private fun now() = OffsetDateTime.now(clock)
}

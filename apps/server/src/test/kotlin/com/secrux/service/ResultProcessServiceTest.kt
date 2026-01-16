package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.SourceRefType
import com.secrux.domain.SourceSpec
import com.secrux.domain.Stage
import com.secrux.domain.StageType
import com.secrux.domain.Task
import com.secrux.domain.TaskSpec
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.repo.FindingRepository
import com.secrux.repo.TaskRepository
import com.fasterxml.jackson.module.kotlin.kotlinModule
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
class ResultProcessServiceTest {

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Mock
    private lateinit var stageLifecycle: StageLifecycle

    @Mock
    private lateinit var findingRepository: FindingRepository

    @Mock
    private lateinit var taskLogService: TaskLogService

    private val clock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: ResultProcessService
    private lateinit var converter: SemgrepSarifConverter
    private lateinit var tempSarif: java.nio.file.Path

    @BeforeEach
    fun setup() {
        val mapper = ObjectMapper().registerModule(kotlinModule())
        converter = SemgrepSarifConverter(mapper)
        val sarifArtifactResolver = SarifArtifactResolver(converter)
        val sarifFindingParser = SarifFindingParser(mapper, clock)
        service = ResultProcessService(
            taskRepository,
            findingRepository,
            stageLifecycle,
            taskLogService,
            sarifArtifactResolver,
            sarifFindingParser,
            clock
        )
        tempSarif = Files.createTempFile("test-sarif", ".sarif.json")
        Files.writeString(
            tempSarif,
            """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "semgrep"}},
                "results": [{
                  "ruleId": "test.rule",
                  "level": "warning",
                  "message": {"text": "issue"},
                  "locations": [{
                    "physicalLocation": {
                      "artifactLocation": {"uri": "src/App.kt"},
                      "region": {"startLine": 10}
                    }
                  }],
                  "fingerprints": {"primary": "fp-1"}
                }]
              }]
            }
            """.trimIndent()
        )
    }

    @Test
    fun `process sarif creates findings and stage`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = buildTask(taskId, tenantId)
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)

        service.run(tenantId, taskId, stageId, com.secrux.dto.ResultProcessRequest(listOf(tempSarif.toString())))

        verify(findingRepository).upsertAll(argumentCaptor<List<com.secrux.domain.Finding>>().capture())
        val stageCaptor = argumentCaptor<Stage>()
        verify(stageLifecycle).persist(stageCaptor.capture(), eq(task.correlationId))
        assertEquals(StageType.RESULT_PROCESS, stageCaptor.firstValue.type)
    }

    @Test
    fun `semgrep json is converted to sarif`() {
        val jsonFile = Files.createTempFile("semgrep-results", ".json")
        Files.writeString(
            jsonFile,
            """
            {
              "version": "1.54.0",
              "results": [{
                "check_id": "rule.json",
                "path": "src/App.kt",
                "start": { "line": 2, "col": 3 },
                "extra": {
                  "message": "sample issue",
                  "severity": "WARNING",
                  "fingerprint": "fp-json"
                }
              }]
            }
            """.trimIndent()
        )
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = buildTask(taskId, tenantId)
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)

        service.run(tenantId, taskId, stageId, com.secrux.dto.ResultProcessRequest(listOf(jsonFile.toString())))

        verify(findingRepository).upsertAll(argumentCaptor<List<com.secrux.domain.Finding>>().capture())
    }

    @Test
    fun `no sarif throws validation error`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = buildTask(taskId, tenantId)
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)

        val ex = assertThrows(SecruxException::class.java) {
            service.run(tenantId, taskId, stageId, com.secrux.dto.ResultProcessRequest(emptyList()))
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode)
    }

    private fun buildTask(taskId: UUID, tenantId: UUID): Task {
        return Task(
            taskId = taskId,
            tenantId = tenantId,
            projectId = UUID.randomUUID(),
            type = TaskType.CODE_CHECK,
            spec = TaskSpec(
                source = SourceSpec(),
                ruleSelector = com.secrux.domain.RuleSelectorSpec(mode = RuleSelectorMode.AUTO)
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

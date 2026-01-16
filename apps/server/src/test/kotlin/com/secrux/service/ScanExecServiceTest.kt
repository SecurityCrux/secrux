package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.GitSourceSpec
import com.secrux.domain.SourceRefType
import com.secrux.domain.SourceSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.domain.Task
import com.secrux.domain.TaskSpec
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.RuleSelectorSpec
import com.secrux.dto.ScanExecRequest
import com.secrux.repo.TaskRepository
import com.secrux.security.SecretCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ScanExecServiceTest {

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Mock
    private lateinit var semgrepEngine: SemgrepEngine

    @Mock
    private lateinit var workspaceService: WorkspaceService

    @Mock
    private lateinit var executorTaskDispatchService: ExecutorTaskDispatchService

    @Mock
    private lateinit var stageLifecycle: StageLifecycle

    @Mock
    private lateinit var taskLogService: TaskLogService

    @Mock
    private lateinit var secretCrypto: SecretCrypto

    private val clock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: ScanExecService

    @BeforeEach
    fun setUp() {
        service =
            ScanExecService(
                taskRepository,
                semgrepEngine,
                workspaceService,
                executorTaskDispatchService,
                stageLifecycle,
                taskLogService,
                secretCrypto,
                clock
            )
    }

    @Test
    fun `run executes semgrep and persists sarif`(@TempDir tempDir: Path) {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = testTask(taskId, tenantId)
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)
        val workspace = Files.createTempDirectory(tempDir, "ws")
        val src = workspace.resolve("src").also { Files.createDirectories(it) }
        Files.writeString(src.resolve("App.kt"), "class App")
        val sarifFile = Files.createTempFile(tempDir, "semgrep", ".sarif.json")
        Files.writeString(sarifFile, """{"version":"2.1.0","runs":[]}""")
        val executionResult = SemgrepExecutionResult(
            sarifFile = sarifFile,
            sarif = com.fasterxml.jackson.databind.ObjectMapper().readTree("""{"version":"2.1.0"}"""),
            results = emptyList()
        )
        whenever(semgrepEngine.run(any(), any())).thenReturn(executionResult)
        whenever(workspaceService.resolve(taskId)).thenReturn(workspace)

        service.run(tenantId, taskId, stageId, ScanExecRequest())

        verify(semgrepEngine).run(eq(listOf(workspace.toString())), any())
        val stageCaptor = argumentCaptor<com.secrux.domain.Stage>()
        val correlationCaptor = argumentCaptor<String>()
        verify(stageLifecycle).persist(stageCaptor.capture(), correlationCaptor.capture())
        val saved = stageCaptor.firstValue
        assertEquals(task.correlationId, correlationCaptor.firstValue)
        assertEquals(StageType.SCAN_EXEC, saved.type)
        assertEquals(StageStatus.SUCCEEDED, saved.status)
        val artifact = saved.artifacts.first()
        assertTrue(artifact.startsWith("sarif:"))
        assertFalse(Files.exists(sarifFile))
    }

    @Test
    fun `non semgrep engine rejected`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val stageId = UUID.randomUUID()
        val task = testTask(taskId, tenantId)
        whenever(taskRepository.findById(taskId, tenantId)).thenReturn(task)

        val ex = assertThrows(SecruxException::class.java) {
            service.run(tenantId, taskId, stageId, ScanExecRequest(engine = "codeql"))
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode)
    }

    private fun testTask(taskId: UUID, tenantId: UUID): Task {
        val now = OffsetDateTime.now(clock)
        return Task(
            taskId = taskId,
            tenantId = tenantId,
            projectId = UUID.randomUUID(),
            type = TaskType.SECURITY_SCAN,
            spec = TaskSpec(
                source = SourceSpec(git = GitSourceSpec(repo = "git", ref = "main")),
                ruleSelector = RuleSelectorSpec(mode = RuleSelectorMode.AUTO)
            ),
            status = TaskStatus.PENDING,
            owner = null,
            correlationId = "corr-${taskId}",
            sourceRefType = SourceRefType.BRANCH,
            sourceRef = "main",
            commitSha = "abc123",
            createdAt = now,
            updatedAt = null
        )
    }
}

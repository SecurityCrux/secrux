package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.SecruxException
import com.secrux.domain.GitSourceSpec
import com.secrux.domain.RuleSelectorMode
import com.secrux.domain.RuleSelectorSpec
import com.secrux.domain.SourceRefType
import com.secrux.domain.SourceSpec
import com.secrux.domain.Task
import com.secrux.domain.TaskSpec
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.repo.RepositoryRepository
import com.secrux.config.SecruxCryptoProperties
import com.secrux.config.UploadProperties
import com.secrux.security.SecretCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
@ExtendWith(MockitoExtension::class)
class WorkspaceServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Mock
    private lateinit var repositoryRepository: RepositoryRepository

    private val secretCrypto = SecretCrypto(SecruxCryptoProperties(secret = "unit-test-secret-32-byte-key!!"))
    private val objectMapper = ObjectMapper()
    private lateinit var service: WorkspaceService

    @BeforeEach
    fun setup() {
        val uploadStorageService = UploadStorageService(UploadProperties(root = tempDir.resolve("uploads").toString()))
        service = WorkspaceService(
            tempDir.resolve("workspaces").toString(),
            repositoryRepository,
            secretCrypto,
            objectMapper,
            uploadStorageService
        )
    }

    @Test
    fun `prepare copies git workspace`() {
        val repo = Files.createDirectory(tempDir.resolve("repo"))
        Files.writeString(repo.resolve("Main.kt"), "class Main")
        val task = testTask(repo.toString())

        val handle = service.prepare(task)

        val copied = handle.root.resolve("Main.kt")
        assertTrue(Files.exists(copied))
        assertEquals(handle.root, service.resolve(task.taskId))
    }

    @Test
    fun `resolve without prepare throws`() {
        assertThrows(SecruxException::class.java) {
            service.resolve(UUID.randomUUID())
        }
    }

    private fun testTask(repo: String): Task {
        val now = OffsetDateTime.now()
        return Task(
            taskId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            projectId = UUID.randomUUID(),
            type = TaskType.SECURITY_SCAN,
            spec = TaskSpec(
                source = SourceSpec(git = GitSourceSpec(repo = repo, ref = "main")),
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

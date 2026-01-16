package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.config.ExecutorDispatchProperties
import com.secrux.domain.ExecutorStatus
import com.secrux.domain.GitSourceSpec
import com.secrux.domain.RepositoryGitAuthMode
import com.secrux.domain.SourceSpec
import com.secrux.domain.StageType
import com.secrux.domain.Task
import com.secrux.repo.RepositoryRepository
import com.secrux.security.SecretCrypto
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

data class ExecutorTaskAssignMessage(
    val type: String = "task_assign",
    val taskId: String,
    val stageId: String,
    val stageType: String,
    val engine: String,
    val image: String? = null,
    val command: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cpuLimit: Double = 0.0,
    val memoryLimitMb: Int = 0,
    val timeoutSec: Int = 0,
    val usePro: Boolean = false,
    val semgrepToken: String = "",
    val apiBaseUrl: String? = null,
    val source: SourceSpec? = null
)

@Service
class ExecutorTaskDispatchService(
    private val sessionRegistry: ExecutorSessionRegistry,
    private val executorService: ExecutorService,
    private val repositoryRepository: RepositoryRepository,
    private val secretCrypto: SecretCrypto,
    private val objectMapper: ObjectMapper,
    private val props: ExecutorDispatchProperties,
    private val clock: Clock
) {

    private val log = LoggerFactory.getLogger(ExecutorTaskDispatchService::class.java)

    fun dispatchScanExec(task: Task, stageId: UUID) {
        LogContext.with(
            LogContext.TENANT_ID to task.tenantId,
            LogContext.TASK_ID to task.taskId,
            LogContext.CORRELATION_ID to task.correlationId,
            LogContext.STAGE_ID to stageId,
            LogContext.STAGE_TYPE to StageType.SCAN_EXEC.name
        ) {
            val executorId =
                task.executorId
                    ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Task ${task.taskId} has no assigned executor")
            val channel = sessionRegistry.get(executorId)
            if (channel == null || !channel.isActive) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "Executor $executorId is not connected")
            }

            val engine = task.engine ?: "semgrep"
            val (usePro, token) = resolveSemgrepPro(task)
            val enrichedSource = enrichSourceAuth(task, task.spec.source)
            val timeoutSeconds = props.defaultTimeoutSeconds.coerceAtLeast(30)

            val command =
                if (engine.lowercase() == "semgrep") {
                    listOf("scan", "/src")
                } else {
                    emptyList()
                }

            val message =
                ExecutorTaskAssignMessage(
                    taskId = task.taskId.toString(),
                    stageId = stageId.toString(),
                    stageType = StageType.SCAN_EXEC.name,
                    engine = engine,
                    command = command,
                    usePro = usePro,
                    semgrepToken = token ?: "",
                    timeoutSec = timeoutSeconds,
                    apiBaseUrl = props.apiBaseUrl,
                    source = enrichedSource
                )
            val payload = objectMapper.writeValueAsString(message)
            channel.writeAndFlush(payload)
            executorService.updateStatus(task.tenantId, executorId, ExecutorStatus.BUSY)
            log.info("event=executor_task_dispatched executorId={} engine={}", executorId, engine)
        }
    }

    private fun resolveSemgrepPro(task: Task): Pair<Boolean, String?> {
        if (!task.semgrepProEnabled) return false to null
        val cipher = task.semgrepTokenCipher ?: return false to null
        val expiresAt = task.semgrepTokenExpiresAt ?: return false to null
        if (expiresAt.isBefore(OffsetDateTime.now(clock))) return false to null
        val token =
            runCatching { secretCrypto.decrypt(cipher) }
                .getOrElse { ex ->
                    throw SecruxException(ErrorCode.INTERNAL_ERROR, "Failed to decrypt Semgrep token", cause = ex)
                }
        return true to token
    }

    private fun enrichSourceAuth(task: Task, source: SourceSpec): SourceSpec {
        val git = source.git ?: return source
        if (!git.auth.isNullOrEmpty()) {
            return source
        }
        val repoId = task.repoId ?: return source
        val repository =
            repositoryRepository.findById(repoId, task.tenantId)
                ?: return source
        val cipher = repository.gitAuth.credentialCipher ?: return source
        val payload =
            runCatching { secretCrypto.decrypt(cipher) }.getOrElse { ex ->
                throw SecruxException(ErrorCode.INTERNAL_ERROR, "Failed to decrypt repository credential", cause = ex)
            }
        val node =
            runCatching { objectMapper.readTree(payload) }.getOrElse { ex ->
                throw SecruxException(ErrorCode.INTERNAL_ERROR, "Repository credential payload is invalid", cause = ex)
            }
        val auth =
            when (repository.gitAuth.mode) {
                RepositoryGitAuthMode.BASIC -> {
                    val username = node.path("username").asText(null)
                    val password = node.path("password").asText(null)
                    if (username.isNullOrBlank() || password.isNullOrBlank()) null
                    else mapOf("username" to username, "password" to password)
                }

                RepositoryGitAuthMode.TOKEN -> {
                    val token = node.path("token").asText(null)
                    if (token.isNullOrBlank()) {
                        null
                    } else {
                        val username = node.path("username").asText(null).takeUnless { it.isNullOrBlank() } ?: "token"
                        mapOf("username" to username, "token" to token)
                    }
                }

                RepositoryGitAuthMode.NONE -> null
            }
        if (auth.isNullOrEmpty()) return source
        val enrichedGit = GitSourceSpec(
            repo = git.repo,
            ref = git.ref,
            refType = git.refType,
            auth = auth
        )
        return source.copy(git = enrichedGit)
    }
}

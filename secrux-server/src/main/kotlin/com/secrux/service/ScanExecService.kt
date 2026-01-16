package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.LogLevel
import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.dto.ScanExecRequest
import com.secrux.dto.StageSummary
import com.secrux.repo.TaskRepository
import com.secrux.security.SecretCrypto
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ScanExecService(
    private val taskRepository: TaskRepository,
    private val semgrepEngine: SemgrepEngine,
    private val workspaceService: WorkspaceService,
    private val executorTaskDispatchService: ExecutorTaskDispatchService,
    private val stageLifecycle: StageLifecycle,
    private val taskLogService: TaskLogService,
    private val secretCrypto: SecretCrypto,
    private val clock: Clock
) {

    private val log = LoggerFactory.getLogger(ScanExecService::class.java)

    fun run(tenantId: UUID, taskId: UUID, stageId: UUID, request: ScanExecRequest): StageSummary {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found for scan exec")
        return LogContext.with(
            LogContext.TENANT_ID to tenantId,
            LogContext.TASK_ID to taskId,
            LogContext.CORRELATION_ID to task.correlationId,
            LogContext.STAGE_ID to stageId,
            LogContext.STAGE_TYPE to StageType.SCAN_EXEC.name
        ) {
            if (request.engine.lowercase() != "semgrep") {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "Only semgrep engine is supported")
            }

            if (task.executorId != null) {
                val startedAt = OffsetDateTime.now(clock)
                val proStatus = resolveSemgrepProStatus(task)
                val stage = Stage(
                    stageId = stageId,
                    tenantId = tenantId,
                    taskId = taskId,
                    type = StageType.SCAN_EXEC,
                    spec = StageSpec(
                        version = "v1",
                        inputs = mapOf(
                            "engine" to request.engine,
                            "executorId" to task.executorId.toString()
                        ),
                        params = mapOf(
                            "paths" to request.paths,
                            "semgrepProRequested" to task.semgrepProEnabled,
                            "semgrepProStatus" to proStatus
                        )
                    ),
                    status = StageStatus.RUNNING,
                    metrics = StageMetrics(durationMs = 0),
                    signals = StageSignals(needsAiReview = false),
                    artifacts = emptyList(),
                    startedAt = startedAt,
                    endedAt = null
                )
                stageLifecycle.persist(stage, task.correlationId)
                taskLogService.logStageEvent(
                    taskId = taskId,
                    stageId = stageId,
                    stageType = StageType.SCAN_EXEC,
                    message = "Dispatched Semgrep scan to executor ${task.executorId} (proStatus=$proStatus)"
                )
                executorTaskDispatchService.dispatchScanExec(task, stageId)
                stage.toSummary()
            } else {
                val workspace = workspaceService.resolve(taskId)
                val startedAt = OffsetDateTime.now(clock)
                val relativePaths = if (request.paths.isNotEmpty()) request.paths else listOf(".")
                val proStatus = resolveSemgrepProStatus(task)
                val semgrepOptions = resolveSemgrepRunOptions(task)
                if (task.semgrepProEnabled && !semgrepOptions.usePro) {
                    log.warn("event=semgrep_pro_token_unavailable proStatus={}", proStatus)
                    taskLogService.logStageEvent(
                        taskId = taskId,
                        stageId = stageId,
                        stageType = StageType.SCAN_EXEC,
                        message = "Semgrep Pro requested but token unavailable (status=$proStatus). Running without Pro.",
                        level = LogLevel.WARN
                    )
                }
                val execution = semgrepEngine.run(resolvePaths(workspace, relativePaths), semgrepOptions)
                val codeFlowCount = countSarifCodeFlows(execution.sarif)
                val sarifPath = persistSarif(taskId, stageId, execution.sarifFile)
                Files.deleteIfExists(execution.sarifFile)
                val endedAt = OffsetDateTime.now(clock)
                val stage = Stage(
                    stageId = stageId,
                    tenantId = tenantId,
                    taskId = taskId,
                    type = StageType.SCAN_EXEC,
                    spec = StageSpec(
                        version = "v1",
                        inputs = mapOf("engine" to request.engine),
                        params = mapOf(
                            "paths" to relativePaths,
                            "workspace" to workspace.toString(),
                            "findings" to execution.results.size,
                            "semgrepProRequested" to task.semgrepProEnabled,
                            "semgrepProEnabled" to semgrepOptions.usePro,
                            "semgrepDataflowTraces" to (semgrepOptions.usePro && semgrepOptions.enableDataflowTraces),
                            "semgrepProStatus" to proStatus,
                            "sarifCodeFlows" to codeFlowCount
                        )
                    ),
                    status = StageStatus.SUCCEEDED,
                    metrics = StageMetrics(durationMs = Duration.between(startedAt, endedAt).toMillis()),
                    signals = StageSignals(needsAiReview = execution.results.isNotEmpty()),
                    artifacts = listOf("sarif:${sarifPath}", "workspace:${workspace}"),
                    startedAt = startedAt,
                    endedAt = endedAt
                )
                stageLifecycle.persist(stage, task.correlationId)
                taskLogService.logStageEvent(
                    taskId = taskId,
                    stageId = stageId,
                    stageType = StageType.SCAN_EXEC,
                    message = "Semgrep completed with ${execution.results.size} findings (pro=${semgrepOptions.usePro}, dataflowTraces=${semgrepOptions.usePro && semgrepOptions.enableDataflowTraces}, sarifCodeFlows=$codeFlowCount)",
                    level = if (execution.results.isEmpty()) LogLevel.INFO else LogLevel.WARN
                )
                stage.toSummary()
            }
        }
    }

    private fun countSarifCodeFlows(sarif: com.fasterxml.jackson.databind.JsonNode): Int {
        val runs = sarif.path("runs")
        if (!runs.isArray) return 0
        var count = 0
        runs.forEach { run ->
            val results = run.path("results")
            if (!results.isArray) return@forEach
            results.forEach { result ->
                val cfs = result.path("codeFlows")
                if (cfs.isArray) count += cfs.size()
            }
        }
        return count
    }

    private fun resolveSemgrepRunOptions(task: com.secrux.domain.Task): SemgrepRunOptions {
        if (!task.semgrepProEnabled) {
            return SemgrepRunOptions(usePro = false, appToken = null)
        }
        val cipher = task.semgrepTokenCipher
            ?: return SemgrepRunOptions(usePro = false, appToken = null, enableDataflowTraces = false)
        val expiresAt = task.semgrepTokenExpiresAt
        if (expiresAt == null || expiresAt.isBefore(OffsetDateTime.now(clock))) {
            return SemgrepRunOptions(usePro = false, appToken = null, enableDataflowTraces = false)
        }
        val token =
            runCatching { secretCrypto.decrypt(cipher) }
                .getOrElse { ex ->
                    throw SecruxException(ErrorCode.INTERNAL_ERROR, "Failed to decrypt Semgrep token", cause = ex)
                }
        return SemgrepRunOptions(usePro = true, appToken = token, enableDataflowTraces = true)
    }

    private fun resolveSemgrepProStatus(task: com.secrux.domain.Task): String {
        if (!task.semgrepProEnabled) return "disabled"
        val cipher = task.semgrepTokenCipher ?: return "missing_token"
        if (cipher.isBlank()) return "missing_token"
        val expiresAt = task.semgrepTokenExpiresAt ?: return "missing_expiry"
        if (expiresAt.isBefore(OffsetDateTime.now(clock))) return "expired_token"
        return "enabled"
    }

    private fun resolvePaths(workspace: Path, relativePaths: List<String>): List<String> {
        return relativePaths.map { path ->
            val candidate = workspace.resolve(path).normalize()
            if (!candidate.startsWith(workspace)) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "Scan path $path escapes workspace")
            }
            if (!Files.exists(candidate)) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "Scan path ${candidate} not found")
            }
            candidate.toString()
        }
    }

    private fun persistSarif(taskId: UUID, stageId: UUID, source: Path): String {
        val dir = Path.of("build", "sarif", taskId.toString())
        Files.createDirectories(dir)
        val file = dir.resolve("$stageId.sarif.json")
        Files.copy(source, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return file.toAbsolutePath().toString()
    }
}

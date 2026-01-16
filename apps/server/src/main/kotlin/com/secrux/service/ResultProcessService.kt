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
import com.secrux.dto.ResultProcessRequest
import com.secrux.dto.StageSummary
import com.secrux.repo.FindingRepository
import com.secrux.repo.TaskRepository
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ResultProcessService(
    private val taskRepository: TaskRepository,
    private val findingRepository: FindingRepository,
    private val stageLifecycle: StageLifecycle,
    private val taskLogService: TaskLogService,
    private val sarifArtifactResolver: SarifArtifactResolver,
    private val sarifFindingParser: SarifFindingParser,
    private val clock: Clock
) {

    fun run(tenantId: UUID, taskId: UUID, stageId: UUID, request: ResultProcessRequest): StageSummary {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found for result process")
        val startedAt = OffsetDateTime.now(clock)
        val sarifPaths = sarifArtifactResolver.resolveSarifPaths(taskId, request)
        if (sarifPaths.isEmpty()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "No SARIF artifacts found")
        }
        val findings = mutableListOf<com.secrux.domain.Finding>()
        sarifPaths.forEach { path ->
            findings += sarifFindingParser.parseSarif(tenantId, task, path)
        }
        findingRepository.upsertAll(findings)
        val endedAt = OffsetDateTime.now(clock)
        val stage = Stage(
            stageId = stageId,
            tenantId = tenantId,
            taskId = taskId,
            type = StageType.RESULT_PROCESS,
            spec = StageSpec(
                version = "v1",
                inputs = mapOf("sarif" to sarifPaths)
            ),
            status = StageStatus.SUCCEEDED,
            metrics = StageMetrics(
                durationMs = Duration.between(startedAt, endedAt).toMillis(),
                artifactSizeBytes = sarifPaths.sumOf { Files.size(Path.of(it)) }
            ),
            signals = StageSignals(needsAiReview = findings.isNotEmpty()),
            artifacts = listOf("findings:${findings.size}"),
            startedAt = startedAt,
            endedAt = endedAt
        )
        stageLifecycle.persist(stage, task.correlationId)
        taskLogService.logStageEvent(
            taskId = taskId,
            stageId = stageId,
            stageType = StageType.RESULT_PROCESS,
            message = "Processed ${findings.size} findings from ${sarifPaths.size} SARIF artifact(s)",
            level = if (findings.isEmpty()) LogLevel.INFO else LogLevel.WARN
        )
        return stage.toSummary()
    }
}

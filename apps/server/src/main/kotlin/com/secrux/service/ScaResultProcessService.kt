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
import com.secrux.domain.TaskType
import com.secrux.dto.StageSummary
import com.secrux.repo.ScaIssueRepository
import com.secrux.repo.StageRepository
import com.secrux.repo.TaskRepository
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
class ScaResultProcessService(
    private val taskRepository: TaskRepository,
    private val stageRepository: StageRepository,
    private val scaIssueRepository: ScaIssueRepository,
    private val stageLifecycle: StageLifecycle,
    private val taskLogService: TaskLogService,
    private val dependencyGraphService: CycloneDxDependencyGraphService,
    private val scaIssueParser: TrivyScaIssueParser,
    private val clock: Clock
) {

    private val log = LoggerFactory.getLogger(ScaResultProcessService::class.java)

    fun run(tenantId: UUID, taskId: UUID, stageId: UUID): StageSummary {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found for SCA result process")
        if (task.type != TaskType.SCA_CHECK) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Task ${task.taskId} is not an SCA task")
        }
        return LogContext.with(
            LogContext.TENANT_ID to tenantId,
            LogContext.TASK_ID to taskId,
            LogContext.CORRELATION_ID to task.correlationId,
            LogContext.STAGE_ID to stageId,
            LogContext.STAGE_TYPE to StageType.RESULT_PROCESS.name
        ) {
        val startedAt = OffsetDateTime.now(clock)

        val scanStage = resolveLatestScanStage(taskId, tenantId)
        val vulnPath = extractArtifactPath(scanStage.artifacts, "sca:vulns:")
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "No SCA vulnerability artifact found")
        val sbomPath = extractArtifactPath(scanStage.artifacts, "sca:sbom:")?.let { Path.of(it) }

        val issues = scaIssueParser.parse(task, Path.of(vulnPath))
        if (issues.isNotEmpty()) {
            scaIssueRepository.upsertAll(issues)
        }

        val graphPath =
            sbomPath?.takeIf { Files.exists(it) && !Files.isDirectory(it) }?.let {
                val output = Path.of("build", "sca", taskId.toString(), "dependency-graph.json")
                runCatching { dependencyGraphService.writeGraph(it, output) }.getOrNull()
                output
            }

        val endedAt = OffsetDateTime.now(clock)
        val artifactBytes = if (Files.exists(Path.of(vulnPath))) Files.size(Path.of(vulnPath)) else 0L
        val stage =
            Stage(
                stageId = stageId,
                tenantId = tenantId,
                taskId = taskId,
                type = StageType.RESULT_PROCESS,
                spec =
                    StageSpec(
                        version = "v1",
                        inputs =
                            mapOf(
                                "trivy" to vulnPath,
                                "sbom" to (sbomPath?.toString() ?: "")
                            ),
                        params =
                            mapOf(
                                "scanStageId" to scanStage.stageId.toString(),
                                "issues" to issues.size
                            )
                    ),
                status = StageStatus.SUCCEEDED,
                metrics = StageMetrics(durationMs = Duration.between(startedAt, endedAt).toMillis(), artifactSizeBytes = artifactBytes),
                signals = StageSignals(needsAiReview = false),
                artifacts =
                    buildList {
                        add("sca:issues:${issues.size}")
                        graphPath?.takeIf { Files.exists(it) }?.let { add("sca:graph:${it.toAbsolutePath()}") }
                    },
                startedAt = startedAt,
                endedAt = endedAt
            )
        stageLifecycle.persist(stage, task.correlationId)
        taskLogService.logStageEvent(
            taskId = taskId,
            stageId = stageId,
            stageType = StageType.RESULT_PROCESS,
            message = "Processed ${issues.size} SCA issues",
            level = if (issues.isEmpty()) LogLevel.INFO else LogLevel.WARN
        )
        log.info("event=sca_result_processed issues={}", issues.size)
        stage.toSummary()
        }
    }

    private fun resolveLatestScanStage(taskId: UUID, tenantId: UUID): com.secrux.domain.Stage {
        val stages = stageRepository.listByTask(taskId, tenantId)
        val candidates = stages.filter { it.type == StageType.SCAN_EXEC && it.status == StageStatus.SUCCEEDED }
        return candidates.maxByOrNull { it.endedAt ?: OffsetDateTime.MIN }
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "No successful SCAN_EXEC stage found for task")
    }

    private fun extractArtifactPath(artifacts: List<String>, prefix: String): String? =
        artifacts.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()?.takeIf { it.isNotBlank() }
}

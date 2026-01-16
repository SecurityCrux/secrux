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
class ScaScanExecService(
    private val taskRepository: TaskRepository,
    private val workspaceService: WorkspaceService,
    private val stageLifecycle: StageLifecycle,
    private val taskLogService: TaskLogService,
    private val scaEngineRegistry: ScaEngineRegistry,
    private val dependencyGraphService: CycloneDxDependencyGraphService,
    private val executorTaskDispatchService: ExecutorTaskDispatchService,
    private val clock: Clock
) {

    private val log = LoggerFactory.getLogger(ScaScanExecService::class.java)

    fun run(tenantId: UUID, taskId: UUID, stageId: UUID): StageSummary {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found for SCA scan exec")
        if (task.type != TaskType.SCA_CHECK) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Task ${task.taskId} is not an SCA task")
        }
        return LogContext.with(
            LogContext.TENANT_ID to tenantId,
            LogContext.TASK_ID to taskId,
            LogContext.CORRELATION_ID to task.correlationId,
            LogContext.STAGE_ID to stageId,
            LogContext.STAGE_TYPE to StageType.SCAN_EXEC.name
        ) {
            if (task.executorId != null) {
                val engineId = task.engine ?: "trivy"
                val startedAt = OffsetDateTime.now(clock)
                val stage =
                    Stage(
                        stageId = stageId,
                        tenantId = tenantId,
                        taskId = taskId,
                        type = StageType.SCAN_EXEC,
                        spec =
                            StageSpec(
                                version = "v1",
                                inputs =
                                    mapOf(
                                        "engine" to engineId,
                                        "executorId" to task.executorId.toString()
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
                    message = "Dispatched SCA scan to executor ${task.executorId} (engine=$engineId)",
                    level = LogLevel.INFO
                )
                executorTaskDispatchService.dispatchScanExec(task, stageId)
                stage.toSummary()
            } else {
                val startedAt = OffsetDateTime.now(clock)
                val workspace = workspaceService.resolve(taskId)
                val outputDir = Path.of("build", "sca", taskId.toString(), stageId.toString())
                val engine = scaEngineRegistry.resolve(task.engine)
                val target = resolveTarget(task, workspace)
                val artifacts =
                    engine.scan(
                        ScaScanRequest(
                            target = target,
                            outputDir = outputDir
                        )
                    )

                val graphPath =
                    artifacts.sbomJson?.let { sbom ->
                        val path = outputDir.resolve("dependency-graph.json")
                        runCatching { dependencyGraphService.writeGraph(sbom, path) }.getOrNull()
                        path.takeIf { Files.exists(it) && !Files.isDirectory(it) }
                    }

                val endedAt = OffsetDateTime.now(clock)
                val artifactBytes =
                    listOfNotNull(artifacts.vulnerabilitiesJson, artifacts.sbomJson, graphPath)
                        .filter { Files.exists(it) && !Files.isDirectory(it) }
                        .sumOf { Files.size(it) }

                val stage =
                    Stage(
                        stageId = stageId,
                        tenantId = tenantId,
                        taskId = taskId,
                        type = StageType.SCAN_EXEC,
                        spec =
                            StageSpec(
                                version = "v1",
                                inputs =
                                    mapOf(
                                        "engine" to engine.id,
                                        "targetType" to target.javaClass.simpleName,
                                        "target" to describeTarget(target)
                                    ),
                                params =
                                    mapOf(
                                        "workspace" to workspace.toString(),
                                        "outputDir" to outputDir.toString()
                                    )
                            ),
                        status = StageStatus.SUCCEEDED,
                        metrics = StageMetrics(durationMs = Duration.between(startedAt, endedAt).toMillis(), artifactSizeBytes = artifactBytes),
                        signals = StageSignals(needsAiReview = false),
                        artifacts =
                            buildList {
                                add("sca:vulns:${artifacts.vulnerabilitiesJson.toAbsolutePath()}")
                                artifacts.sbomJson?.let { add("sca:sbom:${it.toAbsolutePath()}") }
                                graphPath?.let { add("sca:graph:${it.toAbsolutePath()}") }
                                add("workspace:${workspace}")
                            },
                        startedAt = startedAt,
                        endedAt = endedAt
                    )
                stageLifecycle.persist(stage, task.correlationId)
                taskLogService.logStageEvent(
                    taskId = taskId,
                    stageId = stageId,
                    stageType = StageType.SCAN_EXEC,
                    message = "SCA scan completed (engine=${engine.id}, target=${describeTarget(target)})",
                    level = LogLevel.INFO
                )
                log.info("event=sca_scan_completed engine={} outputDir={}", engine.id, outputDir)
                stage.toSummary()
            }
        }
    }

    private fun resolveTarget(task: com.secrux.domain.Task, workspace: Path): ScaScanTarget {
        val source = task.spec.source
        source.sbom?.let {
            val sbomPath = workspace.resolve("__sbom__").resolve("input.json").normalize()
            return ScaScanTarget.Sbom(sbomPath)
        }
        source.image?.let {
            return ScaScanTarget.Image(it.ref)
        }
        val fsPath = source.filesystem?.path?.takeIf { it.isNotBlank() }?.let { Path.of(it).normalize() }
        if (fsPath != null) {
            return ScaScanTarget.Filesystem(fsPath)
        }
        return ScaScanTarget.Filesystem(workspace)
    }

    private fun describeTarget(target: ScaScanTarget): String =
        when (target) {
            is ScaScanTarget.Filesystem -> target.path.toString()
            is ScaScanTarget.Image -> target.ref
            is ScaScanTarget.Sbom -> target.path.toString()
        }
}

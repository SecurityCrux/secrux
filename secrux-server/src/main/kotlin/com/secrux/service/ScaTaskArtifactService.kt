package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.domain.TaskType
import com.secrux.repo.StageRepository
import com.secrux.repo.TaskRepository
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

data class SbomArtifactInfo(
    val fileName: String,
    val sizeBytes: Long
)

@Service
class ScaTaskArtifactService(
    private val taskRepository: TaskRepository,
    private val stageRepository: StageRepository,
    private val objectMapper: ObjectMapper,
    private val dependencyGraphService: CycloneDxDependencyGraphService
) {

    fun loadLatestSbomInfo(tenantId: UUID, taskId: UUID): SbomArtifactInfo {
        val sbomPath = resolveLatestSbomPath(tenantId, taskId)
        val size = runCatching { Files.size(sbomPath) }.getOrNull() ?: 0L
        return SbomArtifactInfo(fileName = sbomPath.fileName.toString(), sizeBytes = size)
    }

    fun resolveLatestSbomPath(tenantId: UUID, taskId: UUID): Path {
        val task = requireScaTask(tenantId, taskId)
        val scanStage = latestScanStage(taskId, tenantId)
        val sbomPath =
            extractArtifactPath(scanStage.artifacts, "sca:sbom:")?.let { Path.of(it) }
                ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "No SBOM artifact found for task ${task.taskId}")
        if (!Files.exists(sbomPath) || Files.isDirectory(sbomPath)) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "SBOM artifact not accessible for task ${task.taskId}")
        }
        return sbomPath
    }

    fun loadLatestDependencyGraph(tenantId: UUID, taskId: UUID): DependencyGraph {
        requireScaTask(tenantId, taskId)
        val scanStage = latestScanStage(taskId, tenantId)
        val graphPath = extractArtifactPath(scanStage.artifacts, "sca:graph:")?.let { Path.of(it) }
        if (graphPath != null && Files.exists(graphPath) && !Files.isDirectory(graphPath)) {
            runCatching {
                return objectMapper.readValue(graphPath.toFile(), DependencyGraph::class.java)
            }
        }
        val sbomPath = resolveLatestSbomPath(tenantId, taskId)
        val output = Path.of("build", "sca", taskId.toString(), "dependency-graph.json")
        return runCatching {
            dependencyGraphService.writeGraph(sbomPath, output)
        }.getOrElse {
            dependencyGraphService.buildFromSbom(sbomPath)
        }
    }

    fun resolveLatestUsageIndexPathOrNull(tenantId: UUID, taskId: UUID): Path? {
        requireScaTask(tenantId, taskId)
        val scanStage = latestScanStage(taskId, tenantId)
        val usagePath = extractArtifactPath(scanStage.artifacts, "sca:usage:")?.let { Path.of(it) } ?: return null
        if (!Files.exists(usagePath) || Files.isDirectory(usagePath)) {
            return null
        }
        return usagePath
    }

    private fun requireScaTask(tenantId: UUID, taskId: UUID): com.secrux.domain.Task {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        if (task.type != TaskType.SCA_CHECK) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Task ${task.taskId} is not an SCA task")
        }
        return task
    }

    private fun latestScanStage(taskId: UUID, tenantId: UUID): com.secrux.domain.Stage {
        val stages = stageRepository.listByTask(taskId, tenantId)
        val candidates = stages.filter { it.type == StageType.SCAN_EXEC && it.status == StageStatus.SUCCEEDED }
        return candidates.maxByOrNull { it.endedAt ?: OffsetDateTime.MIN }
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "No successful SCAN_EXEC stage found for task")
    }

    private fun extractArtifactPath(artifacts: List<String>, prefix: String): String? =
        artifacts.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()?.takeIf { it.isNotBlank() }
}

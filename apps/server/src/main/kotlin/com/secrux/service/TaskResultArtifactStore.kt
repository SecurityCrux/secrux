package com.secrux.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.StageType
import com.secrux.domain.Task
import com.secrux.domain.TaskType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

data class PersistedExecutionArtifacts(
    val resultPath: Path?,
    val totalSize: Long,
    val artifactList: List<String>
)

@Component
class TaskResultArtifactStore(
    @Value("\${secrux.workspace.root:build/workspaces}") private val workspaceRootDir: String,
    private val dependencyGraphService: CycloneDxDependencyGraphService,
    private val objectMapper: ObjectMapper
) {

    private val fileWriter = TextFileWriter()
    private val sarifPersister = SarifArtifactPersister(workspaceRootDir, objectMapper, fileWriter)
    private val scaPersister = ScaArtifactPersister(dependencyGraphService, fileWriter)

    fun persist(task: Task, stageId: UUID, stageType: StageType, payload: ExecutorTaskResultPayload): PersistedExecutionArtifacts {
        val baseDir = Path.of("build", "executions", task.taskId.toString(), stageId.toString()).createDirectories()
        val stdoutPath = payload.log?.takeIf { it.isNotBlank() }?.let { fileWriter.write(baseDir.resolve("stdout.log"), it) }
        val runLogPath = payload.runLog?.takeIf { it.isNotBlank() }?.let { fileWriter.write(baseDir.resolve("engine-log.json"), it) }
        val errorPath = payload.error?.takeIf { it.isNotBlank() }?.let { fileWriter.write(baseDir.resolve("error.txt"), it) }

        val scanArtifacts =
            if (stageType == StageType.SCAN_EXEC && (payload.success || task.type == TaskType.SCA_CHECK)) {
                persistScanArtifacts(task, stageId, payload)
            } else {
                ScanArtifactPaths()
            }

        val totalSize =
            listOfNotNull(
                stdoutPath,
                runLogPath,
                errorPath,
                scanArtifacts.resultPath,
                scanArtifacts.sbomPath,
                scanArtifacts.graphPath,
                scanArtifacts.usageIndexPath
            )
                .filter { it.exists() && it.isRegularFile() }
                .sumOf { Files.size(it) }

        val artifactList =
            buildList {
                stdoutPath?.let { add("stdout:${it.absolutePathString()}") }
                runLogPath?.let { add("engine:${it.absolutePathString()}") }
                errorPath?.let { add("error:${it.absolutePathString()}") }
                addAll(scanArtifacts.stageArtifactEntries)
                scanArtifacts.resultPath?.let { add("result:${it.absolutePathString()}") }
                scanArtifacts.sbomPath?.let { add("result:sbom:${it.absolutePathString()}") }
            }

        return PersistedExecutionArtifacts(
            resultPath = scanArtifacts.resultPath,
            totalSize = totalSize,
            artifactList = artifactList
        )
    }

    private fun persistScanArtifacts(task: Task, stageId: UUID, payload: ExecutorTaskResultPayload): ScanArtifactPaths {
        return when (task.type) {
            TaskType.SCA_CHECK -> scaPersister.persist(task, stageId, payload)
            else -> sarifPersister.persist(task.taskId, stageId, payload)
        }
    }
}

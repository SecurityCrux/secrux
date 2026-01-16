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
import com.secrux.dto.StageSummary
import com.secrux.repo.TaskRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SourcePrepareService(
    private val taskRepository: TaskRepository,
    private val workspaceService: WorkspaceService,
    private val stageLifecycle: StageLifecycle,
    private val taskLogService: TaskLogService,
    private val clock: Clock
) {

    fun run(tenantId: UUID, taskId: UUID, stageId: UUID): StageSummary {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found for source prepare")
        val source = task.spec.source
        val workspace = workspaceService.prepare(task)
        val startedAt = OffsetDateTime.now(clock)
        val params = mutableMapOf<String, Any?>()
        val artifacts = mutableListOf<String>()
        source.git?.let {
            params["gitRepo"] = it.repo
            params["gitRef"] = it.ref
            artifacts.add("source_bundle:${taskId}")
            artifacts.add("lang_fingerprint:${taskId}")
        }
        source.archive?.let {
            params["archive"] = it.uploadId ?: it.url
        }
        source.filesystem?.let {
            params["filesystemPath"] = it.path
            params["filesystemUploadId"] = it.uploadId
        }
        source.image?.let {
            params["image"] = it.ref
        }
        source.sbom?.let {
            params["sbom"] = it.uploadId ?: it.url
        }
        source.url?.let {
            params["url"] = it.url
        }
        source.baselineFingerprints?.let {
            params["baselineFingerprintsCount"] = it.size
        }
        val endedAt = OffsetDateTime.now(clock)
        val stage = Stage(
            stageId = stageId,
            tenantId = tenantId,
            taskId = taskId,
            type = StageType.SOURCE_PREPARE,
            spec = StageSpec(
                version = "v1",
                inputs = mapOf(
                    "sourceRefType" to task.sourceRefType.name,
                    "sourceRef" to (task.sourceRef ?: "")
                ),
                params = params + mapOf("workspace" to workspace.root.toString())
            ),
            status = StageStatus.SUCCEEDED,
            metrics = StageMetrics(durationMs = Duration.between(startedAt, endedAt).toMillis()),
            signals = StageSignals(needsAiReview = false),
            artifacts = artifacts + "workspace:${workspace.root}",
            startedAt = startedAt,
            endedAt = endedAt
        )
        stageLifecycle.persist(stage, task.correlationId)
        val sourceSummary =
            buildString {
                source.git?.let {
                    append("Git repo=${it.repo} ref=${it.ref} ")
                }
                source.archive?.uploadId?.let { append("archive=$it ") }
                source.filesystem?.path?.let { append("filesystem=$it ") }
                source.filesystem?.uploadId?.let { append("filesystemUpload=$it ") }
                source.image?.ref?.let { append("image=$it ") }
                source.sbom?.uploadId?.let { append("sbom=$it ") }
                source.url?.url?.let { append("url=$it ") }
                source.baselineFingerprints?.let { append("baseline=${it.size}") }
            }.ifBlank { "Source workspace prepared at ${workspace.root}" }
        taskLogService.logStageEvent(
            taskId = taskId,
            stageId = stageId,
            stageType = StageType.SOURCE_PREPARE,
            message = sourceSummary.trim()
        )
        return stage.toSummary()
    }
}

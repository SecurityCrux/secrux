package com.secrux.workflow

import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.domain.TaskType
import com.secrux.dto.ResultProcessRequest
import com.secrux.dto.ResultReviewRequest
import com.secrux.dto.ScanExecRequest
import com.secrux.service.ResultProcessService
import com.secrux.service.ResultReviewService
import com.secrux.service.RulePrepareService
import com.secrux.service.ScanExecService
import com.secrux.service.ScaResultProcessService
import com.secrux.service.ScaResultReviewService
import com.secrux.service.ScaScanExecService
import com.secrux.service.StageLifecycle
import com.secrux.service.SourcePrepareService
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Component
class WorkflowStageExecutor(
    private val sourcePrepareService: SourcePrepareService,
    private val rulePrepareService: RulePrepareService,
    private val scanExecService: ScanExecService,
    private val scaScanExecService: ScaScanExecService,
    private val resultProcessService: ResultProcessService,
    private val scaResultProcessService: ScaResultProcessService,
    private val resultReviewService: ResultReviewService,
    private val scaResultReviewService: ScaResultReviewService,
    private val stageLifecycle: StageLifecycle,
    private val clock: Clock
) {

    fun executeStage(
        tenantId: UUID,
        taskId: UUID,
        taskType: TaskType,
        stageType: StageType,
        stageId: UUID
    ) {
        when (stageType) {
            StageType.SOURCE_PREPARE -> sourcePrepareService.run(tenantId, taskId, stageId)
            StageType.RULES_PREPARE -> rulePrepareService.run(tenantId, taskId, stageId)
            StageType.SCAN_EXEC ->
                if (taskType == TaskType.SCA_CHECK) {
                    scaScanExecService.run(tenantId, taskId, stageId)
                } else {
                    scanExecService.run(tenantId, taskId, stageId, ScanExecRequest())
                }

            StageType.RESULT_PROCESS ->
                if (taskType == TaskType.SCA_CHECK) {
                    scaResultProcessService.run(tenantId, taskId, stageId)
                } else {
                    resultProcessService.run(tenantId, taskId, stageId, ResultProcessRequest())
                }

            StageType.RESULT_REVIEW ->
                if (taskType == TaskType.SCA_CHECK) {
                    scaResultReviewService.run(tenantId, taskId, stageId)
                } else {
                    resultReviewService.run(tenantId, taskId, stageId, ResultReviewRequest())
                }
            else -> {}
        }
    }

    fun recordUnhandledStageFailure(
        tenantId: UUID,
        taskId: UUID,
        correlationId: String,
        stageType: StageType,
        stageId: UUID,
        ex: Exception
    ) {
        val now = OffsetDateTime.now(clock)
        val stage =
            Stage(
                stageId = stageId,
                tenantId = tenantId,
                taskId = taskId,
                type = stageType,
                spec =
                    StageSpec(
                        version = "v1",
                        params = mapOf(
                            "error" to (ex.message ?: ex.javaClass.simpleName)
                        )
                    ),
                status = StageStatus.FAILED,
                metrics = StageMetrics(durationMs = 0),
                signals = StageSignals(needsAiReview = false),
                artifacts = emptyList(),
                startedAt = now,
                endedAt = now
            )
        stageLifecycle.persist(stage, correlationId)
    }
}

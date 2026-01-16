package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.dto.ResultProcessRequest
import com.secrux.dto.ResultReviewRequest
import com.secrux.dto.ScanExecRequest
import com.secrux.dto.StageStatusUpdateRequest
import com.secrux.dto.StageUpsertRequest
import com.secrux.service.ResultProcessService
import com.secrux.service.ResultReviewService
import com.secrux.service.RulePrepareService
import com.secrux.service.ScanExecService
import com.secrux.service.ScaResultReviewService
import com.secrux.service.SourcePrepareService
import com.secrux.service.StageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.requireStage
import com.secrux.domain.TaskType
import com.secrux.repo.TaskRepository

@RestController
@RequestMapping("/tasks/{taskId}/stages")
@Tag(name = "Stage APIs", description = "Stage lifecycle management")
@Validated
class StageController(
    private val stageService: StageService,
    private val rulePrepareService: RulePrepareService,
    private val sourcePrepareService: SourcePrepareService,
    private val scanExecService: ScanExecService,
    private val resultProcessService: ResultProcessService,
    private val resultReviewService: ResultReviewService,
    private val scaResultReviewService: ScaResultReviewService,
    private val taskRepository: TaskRepository,
    private val authorizationService: AuthorizationService
) {

    @PutMapping("/{stageId}")
    @Operation(summary = "Upsert stage", description = "Create or replace a stage definition")
    @ApiOperationSupport(order = 1)
    fun upsertStage(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID,
        @Valid @RequestBody request: StageUpsertRequest
    ): ApiResponse<*> {
        authorizationService.requireStage(principal, taskId, stageId, AuthorizationAction.STAGE_MANAGE)
        return ApiResponse(data = stageService.upsertStage(principal.tenantId, taskId, stageId, request))
    }

    @PatchMapping("/{stageId}")
    @Operation(summary = "Update stage status", description = "Update status, metrics, signals for a stage")
    @ApiOperationSupport(order = 2)
    fun updateStage(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID,
        @Valid @RequestBody request: StageStatusUpdateRequest
    ): ApiResponse<*> {
        authorizationService.requireStage(principal, taskId, stageId, AuthorizationAction.STAGE_MANAGE)
        return ApiResponse(data = stageService.updateStageStatus(principal.tenantId, taskId, stageId, request))
    }

    @GetMapping("/{stageId}")
    @Operation(summary = "Get stage", description = "Retrieve a stage summary")
    @ApiOperationSupport(order = 3)
    fun getStage(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID
    ): ApiResponse<*> {
        authorizationService.requireStage(principal, taskId, stageId, AuthorizationAction.STAGE_READ)
        return ApiResponse(data = stageService.getStage(principal.tenantId, taskId, stageId))
    }

    @PostMapping("/{stageId}/ai-review")
    @Operation(summary = "Trigger AI review", description = "Trigger AI post-processing for a stage")
    @ApiOperationSupport(order = 4)
    fun triggerAi(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID,
        @Valid @RequestBody request: AiReviewTriggerRequest
    ): ApiResponse<*> {
        authorizationService.requireStage(
            principal = principal,
            taskId = taskId,
            stageId = stageId,
            action = AuthorizationAction.STAGE_EXECUTE,
            context = mapOf("action" to "ai-review")
        )
        return ApiResponse(data = stageService.triggerAiReview(principal.tenantId, taskId, stageId, request))
    }

    @PostMapping("/{stageId}/rule-prepare")
    @Operation(summary = "Run rule preparation", description = "Execute rule prepare stage")
    @ApiOperationSupport(order = 5)
    fun runRulePrepare(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID
    ): ApiResponse<*> {
        authorizationService.requireStage(
            principal = principal,
            taskId = taskId,
            stageId = stageId,
            action = AuthorizationAction.STAGE_EXECUTE,
            context = mapOf("action" to "rule-prepare")
        )
        return ApiResponse(data = rulePrepareService.run(principal.tenantId, taskId, stageId))
    }

    @PostMapping("/{stageId}/source-prepare")
    @Operation(summary = "Run source preparation", description = "Execute source prepare stage")
    @ApiOperationSupport(order = 6)
    fun runSourcePrepare(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID
    ): ApiResponse<*> {
        authorizationService.requireStage(
            principal = principal,
            taskId = taskId,
            stageId = stageId,
            action = AuthorizationAction.STAGE_EXECUTE,
            context = mapOf("action" to "source-prepare")
        )
        return ApiResponse(data = sourcePrepareService.run(principal.tenantId, taskId, stageId))
    }

    @PostMapping("/{stageId}/scan-exec")
    @Operation(summary = "Run scan execution", description = "Execute semgrep scanning stage")
    @ApiOperationSupport(order = 7)
    fun runScanExec(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID,
        @Valid @RequestBody request: ScanExecRequest
    ): ApiResponse<*> {
        authorizationService.requireStage(
            principal = principal,
            taskId = taskId,
            stageId = stageId,
            action = AuthorizationAction.STAGE_EXECUTE,
            context = mapOf("action" to "scan-exec")
        )
        return ApiResponse(data = scanExecService.run(principal.tenantId, taskId, stageId, request))
    }

    @PostMapping("/{stageId}/result-process")
    @Operation(summary = "Run result processing", description = "Process SARIF outputs into findings")
    @ApiOperationSupport(order = 8)
    fun runResultProcess(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID,
        @Valid @RequestBody request: ResultProcessRequest
    ): ApiResponse<*> {
        authorizationService.requireStage(
            principal = principal,
            taskId = taskId,
            stageId = stageId,
            action = AuthorizationAction.STAGE_EXECUTE,
            context = mapOf("action" to "result-process")
        )
        return ApiResponse(data = resultProcessService.run(principal.tenantId, taskId, stageId, request))
    }

    @PostMapping("/{stageId}/result-review")
    @Operation(summary = "Run result review", description = "AI/Manual result evaluation stage")
    @ApiOperationSupport(order = 9)
    fun runResultReview(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID,
        @Valid @RequestBody request: ResultReviewRequest
    ): ApiResponse<*> {
        authorizationService.requireStage(
            principal = principal,
            taskId = taskId,
            stageId = stageId,
            action = AuthorizationAction.STAGE_EXECUTE,
            context = mapOf("action" to "result-review")
        )
        val taskType = taskRepository.findById(taskId, principal.tenantId)?.type
        return if (taskType == TaskType.SCA_CHECK) {
            ApiResponse(data = scaResultReviewService.run(principal.tenantId, taskId, stageId, request))
        } else {
            ApiResponse(data = resultReviewService.run(principal.tenantId, taskId, stageId, request))
        }
    }
}

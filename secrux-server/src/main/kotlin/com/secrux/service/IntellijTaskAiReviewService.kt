package com.secrux.service

import com.secrux.dto.IntellijTaskAiReviewRequest
import com.secrux.dto.ResultReviewRequest
import com.secrux.dto.StageSummary
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class IntellijTaskAiReviewService(
    private val resultReviewService: ResultReviewService,
) {

    fun runAiReviewStage(
        tenantId: UUID,
        taskId: UUID,
        request: IntellijTaskAiReviewRequest,
    ): StageSummary {
        val normalizedMode = request.mode.trim().lowercase()
        val normalizedDataFlowMode = request.dataFlowMode?.trim()?.lowercase()
        val stageId = UUID.randomUUID()
        return resultReviewService.run(
            tenantId = tenantId,
            taskId = taskId,
            stageId = stageId,
            request =
                ResultReviewRequest(
                    autoTicket = false,
                    ticketProvider = "stub",
                    labels = emptyList(),
                    aiReviewEnabled = true,
                    aiReviewMode = normalizedMode,
                    aiReviewDataFlowMode = normalizedDataFlowMode,
                ),
        )
    }
}


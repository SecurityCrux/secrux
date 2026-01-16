package com.secrux.service

import com.secrux.dto.IntellijTaskAiReviewRequest
import com.secrux.dto.ResultReviewRequest
import com.secrux.dto.StageSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class IntellijTaskAiReviewServiceTest {

    @Mock
    private lateinit var resultReviewService: ResultReviewService

    private lateinit var service: IntellijTaskAiReviewService

    @BeforeEach
    fun setUp() {
        service = IntellijTaskAiReviewService(resultReviewService)
    }

    @Test
    fun `runAiReviewStage normalizes modes and disables tickets`() {
        val tenantId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val expected =
            StageSummary(
                stageId = UUID.randomUUID(),
                taskId = taskId,
                type = "RESULT_REVIEW",
                status = "SUCCEEDED",
                artifacts = emptyList(),
                startedAt = null,
                endedAt = null,
            )
        whenever(resultReviewService.run(eq(tenantId), eq(taskId), any(), any())).thenReturn(expected)

        val response =
            service.runAiReviewStage(
                tenantId = tenantId,
                taskId = taskId,
                request = IntellijTaskAiReviewRequest(mode = " Precise ", dataFlowMode = " SIMPLE "),
            )

        val requestCaptor = argumentCaptor<ResultReviewRequest>()
        verify(resultReviewService).run(eq(tenantId), eq(taskId), any(), requestCaptor.capture())
        val saved = requestCaptor.firstValue
        assertEquals(false, saved.autoTicket)
        assertEquals(true, saved.aiReviewEnabled)
        assertEquals("precise", saved.aiReviewMode)
        assertEquals("simple", saved.aiReviewDataFlowMode)

        assertEquals(expected, response)
    }
}


package com.secrux.service

import com.secrux.ai.AiJobStatus
import com.secrux.ai.AiJobTicket
import com.secrux.ai.AiJobType
import com.secrux.domain.Finding
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.repo.AiReviewRecord
import com.secrux.repo.AiReviewRepository
import com.secrux.repo.FindingRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class FindingReviewServiceTest {

    @Mock
    private lateinit var findingRepository: FindingRepository

    @Mock
    private lateinit var aiReviewRepository: AiReviewRepository

    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: FindingReviewService

    @BeforeEach
    fun setup() {
        service = FindingReviewService(findingRepository, aiReviewRepository, clock)
    }

    @Test
    fun `failed ai review marks queued record as applied`() {
        val tenantId = UUID.randomUUID()
        val findingId = UUID.randomUUID()
        val jobId = "job-1"
        val now = OffsetDateTime.now(clock)
        val error = "invalid api key"

        whenever(aiReviewRepository.findByJobId(tenantId, jobId)).thenReturn(
            AiReviewRecord(
                reviewId = UUID.randomUUID(),
                tenantId = tenantId,
                findingId = findingId,
                reviewType = "AI",
                reviewer = "AI",
                reviewerUserId = null,
                jobId = jobId,
                verdict = "QUEUED",
                reason = null,
                confidence = null,
                statusBefore = FindingStatus.OPEN,
                statusAfter = null,
                payload = null,
                createdAt = now,
                appliedAt = null,
                updatedAt = now,
            )
        )
        whenever(
            aiReviewRepository.updateByJobId(
                eq(tenantId),
                eq(jobId),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(1)

        service.applyAiReviewIfReady(
            tenantId = tenantId,
            ticket =
                AiJobTicket(
                    jobId = jobId,
                    status = AiJobStatus.FAILED,
                    jobType = AiJobType.FINDING_REVIEW,
                    tenantId = tenantId.toString(),
                    targetId = findingId.toString(),
                    createdAt = now,
                    updatedAt = now,
                    result = null,
                    error = error
                )
        )

        val payloadCaptor = argumentCaptor<Map<String, Any?>>()
        verify(aiReviewRepository).updateByJobId(
            tenantId = eq(tenantId),
            jobId = eq(jobId),
            verdict = eq("FAILED"),
            reason = eq(error),
            confidence = eq(null),
            statusBefore = eq(FindingStatus.OPEN),
            statusAfter = eq(null),
            payload = payloadCaptor.capture(),
            appliedAt = eq(now),
            updatedAt = eq(now)
        )
        val payload = payloadCaptor.firstValue
        val opinionI18n = payload["opinionI18n"] as Map<*, *>
        val en = opinionI18n["en"] as Map<*, *>
        assertEquals("AI review failed", en["summary"])

        verify(findingRepository, never()).findById(any(), any())
        verify(findingRepository, never()).updateStatus(any(), any(), any(), any())
        verify(aiReviewRepository, never()).insert(any())
    }

    @Test
    fun `failed ai review inserts record when update misses`() {
        val tenantId = UUID.randomUUID()
        val findingId = UUID.randomUUID()
        val jobId = "job-2"
        val now = OffsetDateTime.now(clock)
        val error = "bad base url"
        val finding = buildFinding(tenantId, findingId, now)

        whenever(aiReviewRepository.findByJobId(tenantId, jobId)).thenReturn(null)
        whenever(
            aiReviewRepository.updateByJobId(
                eq(tenantId),
                eq(jobId),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(0)
        whenever(findingRepository.findById(findingId, tenantId)).thenReturn(finding)

        service.applyAiReviewIfReady(
            tenantId = tenantId,
            ticket =
                AiJobTicket(
                    jobId = jobId,
                    status = AiJobStatus.FAILED,
                    jobType = AiJobType.FINDING_REVIEW,
                    tenantId = tenantId.toString(),
                    targetId = findingId.toString(),
                    createdAt = now,
                    updatedAt = now,
                    result = null,
                    error = error
                )
        )

        val recordCaptor = argumentCaptor<AiReviewRecord>()
        verify(aiReviewRepository).insert(recordCaptor.capture())
        val record = recordCaptor.firstValue
        assertEquals("FAILED", record.verdict)
        assertEquals(now, record.appliedAt)
        assertEquals(error, record.reason)
        verify(findingRepository, never()).updateStatus(any(), any(), any(), any())
    }

    private fun buildFinding(tenantId: UUID, findingId: UUID, now: OffsetDateTime): Finding {
        return Finding(
            findingId = findingId,
            tenantId = tenantId,
            taskId = UUID.randomUUID(),
            projectId = UUID.randomUUID(),
            sourceEngine = "semgrep",
            ruleId = "rule",
            location = mapOf("path" to "file", "line" to 1),
            evidence = null,
            severity = Severity.LOW,
            fingerprint = UUID.randomUUID().toString(),
            status = FindingStatus.OPEN,
            introducedBy = null,
            fixVersion = null,
            exploitMaturity = null,
            createdAt = now,
            updatedAt = now
        )
    }
}

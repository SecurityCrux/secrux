package com.secrux.service

import com.secrux.ai.AiJobStatus
import com.secrux.ai.AiJobTicket
import com.secrux.ai.AiJobType
import com.secrux.domain.FindingStatus
import com.secrux.domain.ScaIssue
import com.secrux.domain.Severity
import com.secrux.repo.ScaIssueRepository
import com.secrux.repo.ScaIssueReviewRecord
import com.secrux.repo.ScaIssueReviewRepository
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
class ScaIssueReviewServiceTest {

    @Mock
    private lateinit var scaIssueRepository: ScaIssueRepository

    @Mock
    private lateinit var scaIssueReviewRepository: ScaIssueReviewRepository

    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: ScaIssueReviewService

    @BeforeEach
    fun setup() {
        service = ScaIssueReviewService(scaIssueRepository, scaIssueReviewRepository, false, clock)
    }

    @Test
    fun `failed sca ai review marks queued record as applied`() {
        val tenantId = UUID.randomUUID()
        val issueId = UUID.randomUUID()
        val jobId = "job-1"
        val now = OffsetDateTime.now(clock)
        val error = "invalid api key"

        whenever(scaIssueReviewRepository.findByJobId(tenantId, jobId)).thenReturn(
            ScaIssueReviewRecord(
                reviewId = UUID.randomUUID(),
                tenantId = tenantId,
                issueId = issueId,
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
            scaIssueReviewRepository.updateByJobId(
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
                    jobType = AiJobType.SCA_ISSUE_REVIEW,
                    tenantId = tenantId.toString(),
                    targetId = issueId.toString(),
                    createdAt = now,
                    updatedAt = now,
                    result = null,
                    error = error
                )
        )

        val payloadCaptor = argumentCaptor<Map<String, Any?>>()
        verify(scaIssueReviewRepository).updateByJobId(
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

        verify(scaIssueRepository, never()).findById(any(), any())
        verify(scaIssueRepository, never()).updateStatus(any(), any(), any())
        verify(scaIssueReviewRepository, never()).insert(any())
    }

    @Test
    fun `failed sca ai review inserts record when update misses`() {
        val tenantId = UUID.randomUUID()
        val issueId = UUID.randomUUID()
        val jobId = "job-2"
        val now = OffsetDateTime.now(clock)
        val error = "bad base url"
        val issue = buildIssue(tenantId, issueId, now)

        whenever(scaIssueReviewRepository.findByJobId(tenantId, jobId)).thenReturn(null)
        whenever(
            scaIssueReviewRepository.updateByJobId(
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
        whenever(scaIssueRepository.findById(tenantId, issueId)).thenReturn(issue)

        service.applyAiReviewIfReady(
            tenantId = tenantId,
            ticket =
                AiJobTicket(
                    jobId = jobId,
                    status = AiJobStatus.FAILED,
                    jobType = AiJobType.SCA_ISSUE_REVIEW,
                    tenantId = tenantId.toString(),
                    targetId = issueId.toString(),
                    createdAt = now,
                    updatedAt = now,
                    result = null,
                    error = error
                )
        )

        val recordCaptor = argumentCaptor<ScaIssueReviewRecord>()
        verify(scaIssueReviewRepository).insert(recordCaptor.capture())
        val record = recordCaptor.firstValue
        assertEquals("FAILED", record.verdict)
        assertEquals(now, record.appliedAt)
        assertEquals(error, record.reason)
        verify(scaIssueRepository, never()).updateStatus(any(), any(), any())
    }

    private fun buildIssue(tenantId: UUID, issueId: UUID, now: OffsetDateTime): ScaIssue =
        ScaIssue(
            issueId = issueId,
            tenantId = tenantId,
            taskId = UUID.randomUUID(),
            projectId = UUID.randomUUID(),
            sourceEngine = "trivy",
            vulnId = "CVE-2026-0001",
            severity = Severity.LOW,
            status = FindingStatus.OPEN,
            issueKey = "issue",
            createdAt = now,
            updatedAt = now
        )
}

package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.FindingStatus
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

data class ScaIssueReviewRecord(
    val reviewId: UUID,
    val tenantId: UUID,
    val issueId: UUID,
    val reviewType: String?,
    val reviewer: String,
    val reviewerUserId: UUID?,
    val jobId: String?,
    val verdict: String,
    val reason: String?,
    val confidence: Double?,
    val statusBefore: FindingStatus?,
    val statusAfter: FindingStatus?,
    val payload: Map<String, Any?>?,
    val createdAt: OffsetDateTime,
    val appliedAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
)

data class PendingScaIssueReviewJob(
    val tenantId: UUID,
    val jobId: String
)

@Repository
class ScaIssueReviewRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    private val table = DSL.table("sca_issue_review")
    private val reviewIdField = DSL.field("review_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val issueIdField = DSL.field("issue_id", UUID::class.java)
    private val reviewTypeField = DSL.field("review_type", String::class.java)
    private val reviewerField = DSL.field("reviewer", String::class.java)
    private val reviewerUserIdField = DSL.field("reviewer_user_id", UUID::class.java)
    private val jobIdField = DSL.field("job_id", String::class.java)
    private val verdictField = DSL.field("verdict", String::class.java)
    private val reasonField = DSL.field("reason", String::class.java)
    private val confidenceField = DSL.field("confidence", Float::class.javaObjectType)
    private val statusBeforeField = DSL.field("status_before", String::class.java)
    private val statusAfterField = DSL.field("status_after", String::class.java)
    private val payloadField = DSL.field("payload", JSONB::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val appliedField = DSL.field("applied_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)

    fun insert(review: ScaIssueReviewRecord) {
        dsl.insertInto(table)
            .set(reviewIdField, review.reviewId)
            .set(tenantField, review.tenantId)
            .set(issueIdField, review.issueId)
            .set(reviewTypeField, review.reviewType)
            .set(reviewerField, review.reviewer)
            .set(reviewerUserIdField, review.reviewerUserId)
            .set(jobIdField, review.jobId)
            .set(verdictField, review.verdict)
            .set(reasonField, review.reason)
            .set(confidenceField, review.confidence?.toFloat())
            .set(statusBeforeField, review.statusBefore?.name)
            .set(statusAfterField, review.statusAfter?.name)
            .set(payloadField, objectMapper.toJsonbOrNull(review.payload))
            .set(createdField, review.createdAt)
            .set(appliedField, review.appliedAt)
            .set(updatedField, review.updatedAt)
            .execute()
    }

    fun findByJobId(tenantId: UUID, jobId: String): ScaIssueReviewRecord? =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(jobIdField.eq(jobId))
            .and(deletedAtField.isNull)
            .fetchOne { mapRecord(it) }

    fun updateByJobId(
        tenantId: UUID,
        jobId: String,
        verdict: String,
        reason: String?,
        confidence: Double?,
        statusBefore: FindingStatus?,
        statusAfter: FindingStatus?,
        payload: Map<String, Any?>?,
        appliedAt: OffsetDateTime?,
        updatedAt: OffsetDateTime?,
    ): Int =
        dsl.update(table)
            .set(verdictField, verdict)
            .set(reasonField, reason)
            .set(confidenceField, confidence?.toFloat())
            .set(statusBeforeField, statusBefore?.name)
            .set(statusAfterField, statusAfter?.name)
            .set(payloadField, objectMapper.toJsonbOrNull(payload))
            .set(appliedField, appliedAt)
            .set(updatedField, updatedAt)
            .where(tenantField.eq(tenantId))
            .and(jobIdField.eq(jobId))
            .and(deletedAtField.isNull)
            .execute()

    fun findLatestByIssueId(tenantId: UUID, issueId: UUID): ScaIssueReviewRecord? =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(issueIdField.eq(issueId))
            .and(deletedAtField.isNull)
            .orderBy(createdField.desc())
            .limit(1)
            .fetchOne { mapRecord(it) }

    fun findLatestByIssueIds(tenantId: UUID, issueIds: Collection<UUID>): Map<UUID, ScaIssueReviewRecord> {
        if (issueIds.isEmpty()) {
            return emptyMap()
        }
        val records =
            dsl.selectFrom(table)
                .where(tenantField.eq(tenantId))
                .and(issueIdField.`in`(issueIds))
                .and(deletedAtField.isNull)
                .orderBy(issueIdField.asc(), createdField.desc())
                .fetch { mapRecord(it) }
        val latest = LinkedHashMap<UUID, ScaIssueReviewRecord>()
        for (record in records) {
            if (latest.containsKey(record.issueId)) {
                continue
            }
            latest[record.issueId] = record
        }
        return latest
    }

    fun listPendingAiJobs(limit: Int): List<PendingScaIssueReviewJob> {
        val safeLimit = limit.coerceIn(1, 200)
        val records =
            dsl.select(tenantField, jobIdField)
                .from(table)
                .where(deletedAtField.isNull)
                .and(reviewTypeField.eq("AI"))
                .and(jobIdField.isNotNull)
                .and(appliedField.isNull)
                .orderBy(createdField.asc())
                .limit(safeLimit)
                .fetch()
        return records.mapNotNull { r ->
            val tenantId = r.get(tenantField) ?: return@mapNotNull null
            val jobId = r.get(jobIdField) ?: return@mapNotNull null
            PendingScaIssueReviewJob(tenantId = tenantId, jobId = jobId)
        }
    }

    private fun mapRecord(record: Record): ScaIssueReviewRecord {
        val rawPayload = record.get(payloadField)?.data()
        return ScaIssueReviewRecord(
            reviewId = record.get(reviewIdField),
            tenantId = record.get(tenantField),
            issueId = record.get(issueIdField),
            reviewType = record.get(reviewTypeField),
            reviewer = record.get(reviewerField),
            reviewerUserId = record.get(reviewerUserIdField),
            jobId = record.get(jobIdField),
            verdict = record.get(verdictField),
            reason = record.get(reasonField),
            confidence = record.get(confidenceField)?.toDouble(),
            statusBefore = record.get(statusBeforeField)?.let { runCatching { FindingStatus.valueOf(it) }.getOrNull() },
            statusAfter = record.get(statusAfterField)?.let { runCatching { FindingStatus.valueOf(it) }.getOrNull() },
            payload = objectMapper.readMapOrEmpty(rawPayload).takeIf { it.isNotEmpty() },
            createdAt = record.get(createdField),
            appliedAt = record.get(appliedField),
            updatedAt = record.get(updatedField),
        )
    }
}


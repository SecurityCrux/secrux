package com.secrux.service

import com.secrux.dto.FindingReviewOpinionI18n
import com.secrux.dto.FindingReviewOpinionText
import com.secrux.dto.FindingReviewSummary
import java.time.OffsetDateTime

internal const val REVIEW_TYPE_AI = "AI"
internal const val REVIEW_TYPE_HUMAN = "HUMAN"

internal fun buildReviewSummary(
    reviewType: String?,
    reviewer: String,
    verdict: String,
    confidence: Double?,
    statusBefore: com.secrux.domain.FindingStatus?,
    statusAfter: com.secrux.domain.FindingStatus?,
    payload: Map<String, Any?>?,
    createdAt: OffsetDateTime,
    appliedAt: OffsetDateTime?
): FindingReviewSummary {
    val resolvedType =
        if (!reviewType.isNullOrBlank()) reviewType else if (reviewer.equals("AI", ignoreCase = true)) REVIEW_TYPE_AI else REVIEW_TYPE_HUMAN
    val opinion = extractOpinionI18n(payload)
    return FindingReviewSummary(
        reviewType = resolvedType,
        reviewer = reviewer,
        verdict = verdict,
        confidence = confidence,
        statusBefore = statusBefore,
        statusAfter = statusAfter,
        opinionI18n = opinion,
        createdAt = createdAt.toString(),
        appliedAt = appliedAt?.toString()
    )
}

internal fun extractOpinionI18n(payload: Map<String, Any?>?): FindingReviewOpinionI18n? {
    if (payload == null || payload.isEmpty()) return null

    fun parseText(value: Any?): FindingReviewOpinionText? {
        val map = value as? Map<*, *> ?: return null
        return FindingReviewOpinionText(
            summary = map["summary"]?.toString(),
            fixHint = map["fixHint"]?.toString(),
            rationale = map["rationale"]?.toString()
        )
    }

    val opinionMap = payload["opinionI18n"] as? Map<*, *>
    if (opinionMap != null) {
        val zh = parseText(opinionMap["zh"])
        val en = parseText(opinionMap["en"])
        if (zh != null || en != null) {
            return FindingReviewOpinionI18n(zh = zh, en = en)
        }
    }

    val summary = payload["summary"]?.toString()
    val fixHint = payload["fixHint"]?.toString()
    if (!summary.isNullOrBlank() || !fixHint.isNullOrBlank()) {
        return FindingReviewOpinionI18n(
            en = FindingReviewOpinionText(summary = summary, fixHint = fixHint)
        )
    }

    return null
}

internal fun buildAiReviewFailurePayload(error: String?): Map<String, Any?> =
    mapOf(
        "opinionI18n" to
            mapOf(
                "zh" to
                    mapOf(
                        "summary" to "AI 复核失败",
                        "fixHint" to "请检查 AI Client 配置（baseUrl/model/apiKey）以及 AI 服务日志。"
                    ),
                "en" to
                    mapOf(
                        "summary" to "AI review failed",
                        "fixHint" to "Check AI client baseUrl/model/apiKey and AI service logs."
                    )
            ),
        "error" to error?.take(2000)
    )

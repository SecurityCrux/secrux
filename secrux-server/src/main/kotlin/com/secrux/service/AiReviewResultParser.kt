package com.secrux.service

import com.secrux.ai.AiJobTicket
import com.secrux.domain.FindingStatus

internal data class ParsedAiReviewResult(
    val verdict: String?,
    val confidence: Double?,
    val reason: String?,
    val suggestedStatus: FindingStatus?,
    val payload: Map<String, Any?>?,
)

internal fun parseAiReviewResult(ticket: AiJobTicket): ParsedAiReviewResult {
    val payload = ticket.result
    if (payload == null) {
        return ParsedAiReviewResult(
            verdict = null,
            confidence = null,
            reason = ticket.error,
            suggestedStatus = null,
            payload = null
        )
    }
    val verdict = payload["verdict"]?.toString()
    val reason = payload["summary"]?.toString() ?: payload["reason"]?.toString()
    val confidence = (payload["confidence"] as? Number)?.toDouble()
    val suggestedStatus = payload["suggestedStatus"]?.toString()?.let { parseFindingStatus(it) }
        ?: extractStatusFromRecommendation(payload["recommendation"])
    return ParsedAiReviewResult(
        verdict = verdict,
        confidence = confidence,
        reason = reason,
        suggestedStatus = suggestedStatus,
        payload = payload
    )
}

internal fun normalizeAiSuggestedStatus(status: FindingStatus?): FindingStatus? =
    when (status) {
        FindingStatus.CONFIRMED,
        FindingStatus.FALSE_POSITIVE,
        -> status
        else -> null
    }

private fun extractStatusFromRecommendation(recommendation: Any?): FindingStatus? {
    val map = recommendation as? Map<*, *> ?: return null
    val findings = map["findings"] as? List<*> ?: return null
    val first = findings.firstOrNull() as? Map<*, *> ?: return null
    val status = first["status"]?.toString() ?: return null
    return parseFindingStatus(status)
}

private fun parseFindingStatus(value: String): FindingStatus? =
    runCatching { FindingStatus.valueOf(value) }.getOrNull()


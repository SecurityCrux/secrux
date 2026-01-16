package com.secrux.service

import com.secrux.ai.AiClient
import com.secrux.ai.AiJobStatus
import com.secrux.ai.AiJobType
import com.secrux.ai.AiReviewRequest
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Finding
import com.secrux.domain.ScaIssue
import com.secrux.domain.TicketDraft
import com.secrux.domain.TicketDraftItemType
import com.secrux.dto.AiJobTicketResponse
import com.secrux.dto.TicketDraftAiApplyRequest
import com.secrux.dto.TicketDraftAiRequest
import com.secrux.repo.AiClientConfigRepository
import com.secrux.repo.FindingRepository
import com.secrux.repo.ScaIssueRepository
import com.secrux.repo.TicketDraftRepository
import com.secrux.security.SecruxPrincipal
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class TicketDraftAiService(
    private val ticketDraftCurrentService: TicketDraftCurrentService,
    private val ticketDraftRepository: TicketDraftRepository,
    private val findingRepository: FindingRepository,
    private val scaIssueRepository: ScaIssueRepository,
    private val aiClient: AiClient,
    private val aiClientConfigRepository: AiClientConfigRepository,
    private val ticketProviderCatalog: TicketProviderCatalog,
    private val clock: Clock
) {

    fun triggerAiGenerate(principal: SecruxPrincipal, request: TicketDraftAiRequest): AiJobTicketResponse {
        val (draft, findings, issues, projectId) = resolveDraftItems(principal)
        val provider = resolveProvider(request.provider, draft.provider)
        val template =
            ticketProviderCatalog.listEnabledProviders().firstOrNull { it.provider == provider }
                ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket provider '$provider' is not enabled")
        val aiClientConfig = aiClientConfigRepository.findDefaultEnabled(principal.tenantId)
        val payload =
            buildAiPayload(
                action = "generate",
                provider = provider,
                projectId = projectId,
                template = template,
                draft = draft,
                findings = findings,
                issues = issues
            ) + (aiClientConfig?.let {
                mapOf(
                    "aiClient" to mapOf(
                        "provider" to it.provider,
                        "baseUrl" to it.baseUrl,
                        "model" to it.model,
                        "apiKey" to it.apiKey
                    )
                )
            } ?: emptyMap())
        val ticket =
            aiClient.review(
                AiReviewRequest(
                    tenantId = principal.tenantId.toString(),
                    jobType = AiJobType.STAGE_REVIEW,
                    targetId = draft.draftId.toString(),
                    agent = "ticket-copy",
                    payload = payload,
                    context = mapOf("mode" to "generate")
                )
            )
        val now = OffsetDateTime.now(clock)
        ticketDraftRepository.updateDraft(
            draftId = draft.draftId,
            tenantId = principal.tenantId,
            projectId = null,
            provider = provider,
            titleI18n = draft.titleI18n,
            descriptionI18n = draft.descriptionI18n,
            lastAiJobId = ticket.jobId,
            status = null,
            updatedAt = now
        )
        return ticket.toResponse()
    }

    fun triggerAiPolish(principal: SecruxPrincipal, request: TicketDraftAiRequest): AiJobTicketResponse {
        val (draft, findings, issues, projectId) = resolveDraftItems(principal)
        val provider = resolveProvider(request.provider, draft.provider)
        val template =
            ticketProviderCatalog.listEnabledProviders().firstOrNull { it.provider == provider }
                ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket provider '$provider' is not enabled")
        val aiClientConfig = aiClientConfigRepository.findDefaultEnabled(principal.tenantId)
        val payload =
            buildAiPayload(
                action = "polish",
                provider = provider,
                projectId = projectId,
                template = template,
                draft = draft,
                findings = findings,
                issues = issues
            ) + (aiClientConfig?.let {
                mapOf(
                    "aiClient" to mapOf(
                        "provider" to it.provider,
                        "baseUrl" to it.baseUrl,
                        "model" to it.model,
                        "apiKey" to it.apiKey
                    )
                )
            } ?: emptyMap())
        val ticket =
            aiClient.review(
                AiReviewRequest(
                    tenantId = principal.tenantId.toString(),
                    jobType = AiJobType.STAGE_REVIEW,
                    targetId = draft.draftId.toString(),
                    agent = "ticket-copy",
                    payload = payload,
                    context = mapOf("mode" to "polish")
                )
            )
        val now = OffsetDateTime.now(clock)
        ticketDraftRepository.updateDraft(
            draftId = draft.draftId,
            tenantId = principal.tenantId,
            projectId = null,
            provider = provider,
            titleI18n = draft.titleI18n,
            descriptionI18n = draft.descriptionI18n,
            lastAiJobId = ticket.jobId,
            status = null,
            updatedAt = now
        )
        return ticket.toResponse()
    }

    fun applyAiResult(principal: SecruxPrincipal, request: TicketDraftAiApplyRequest): TicketDraft {
        val draft = ticketDraftCurrentService.getOrCreate(principal)
        val ticket = aiClient.fetch(request.jobId)
        if (ticket.tenantId != principal.tenantId.toString()) {
            throw SecruxException(ErrorCode.FORBIDDEN, "Job does not belong to this tenant")
        }
        if (ticket.status != AiJobStatus.COMPLETED) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "AI job is not completed")
        }
        val result = ticket.result ?: emptyMap()
        val content =
            extractTicketContent(result)
                ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "AI job result does not contain ticket content")
        val titleI18n = content.stringAnyMap("titleI18n")
        val descriptionI18n = content.stringAnyMap("descriptionI18n")

        val now = OffsetDateTime.now(clock)
        ticketDraftRepository.updateDraft(
            draftId = draft.draftId,
            tenantId = principal.tenantId,
            projectId = null,
            provider = draft.provider,
            titleI18n = titleI18n,
            descriptionI18n = descriptionI18n,
            lastAiJobId = ticket.jobId,
            status = null,
            updatedAt = now
        )
        return draft.copy(
            titleI18n = titleI18n ?: draft.titleI18n,
            descriptionI18n = descriptionI18n ?: draft.descriptionI18n,
            lastAiJobId = ticket.jobId,
            updatedAt = now
        )
    }

    private fun Map<String, Any?>.stringAnyMap(key: String): Map<String, Any?>? {
        return get(key).asStringAnyMapOrNull()
    }

    private fun Any?.asStringAnyMapOrNull(): Map<String, Any?>? {
        val raw = this as? Map<*, *> ?: return null
        if (raw.isEmpty()) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in raw) {
            val stringKey = key as? String ?: return null
            result[stringKey] = value
        }
        return result
    }

    private fun resolveDraftItems(principal: SecruxPrincipal): DraftItems {
        val draft = ticketDraftCurrentService.getOrCreate(principal)
        val refs = ticketDraftRepository.listItems(draft.draftId, principal.tenantId)
        if (refs.isEmpty()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket draft is empty")
        }
        val findingIds = refs.filter { it.type == TicketDraftItemType.FINDING }.map { it.id }
        val scaIssueIds = refs.filter { it.type == TicketDraftItemType.SCA_ISSUE }.map { it.id }
        val findings = findingRepository.findByIds(principal.tenantId, findingIds)
        if (findings.size != findingIds.size) {
            throw SecruxException(ErrorCode.FINDING_NOT_FOUND, "Findings not found for ticket draft")
        }
        val issues = scaIssueRepository.findByIds(principal.tenantId, scaIssueIds)
        if (issues.size != scaIssueIds.size) {
            throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issues not found for ticket draft")
        }
        val projectId =
            findings.firstOrNull()?.projectId
                ?: issues.firstOrNull()?.projectId
                ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket draft is empty")
        if (findings.any { it.projectId != projectId } || issues.any { it.projectId != projectId }) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "All draft items must belong to the same project")
        }
        return DraftItems(draft, findings, issues, projectId)
    }

    private data class DraftItems(
        val draft: TicketDraft,
        val findings: List<Finding>,
        val issues: List<ScaIssue>,
        val projectId: UUID
    )

    private fun resolveProvider(requested: String?, draftProvider: String?): String {
        val provider =
            requested?.trim()?.takeIf { it.isNotBlank() }
                ?: draftProvider?.trim()?.takeIf { it.isNotBlank() }
        if (provider != null) return provider

        return ticketProviderCatalog.listEnabledProviders().firstOrNull()?.provider
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "No ticket providers configured")
    }

    private fun buildAiPayload(
        action: String,
        provider: String,
        projectId: UUID,
        template: TicketProviderTemplate,
        draft: TicketDraft,
        findings: List<Finding>,
        issues: List<ScaIssue>
    ): Map<String, Any?> {
        val findingItems = mutableListOf<Map<String, Any?>>()
        findings.take(50).forEach { finding ->
            findingItems.add(
                mapOf(
                    "findingId" to finding.findingId.toString(),
                    "ruleId" to finding.ruleId,
                    "severity" to finding.severity.name,
                    "status" to finding.status.name,
                    "location" to finding.location,
                    "introducedBy" to finding.introducedBy
                )
            )
        }
        issues.take(50).forEach { issue ->
            findingItems.add(
                mapOf(
                    "findingId" to issue.issueId.toString(),
                    "ruleId" to "SCA:${issue.vulnId}",
                    "severity" to issue.severity.name,
                    "status" to issue.status.name,
                    "location" to issue.location,
                    "introducedBy" to issue.introducedBy
                )
            )
        }
        return mapOf(
            "action" to action,
            "ticket" to mapOf(
                "provider" to provider,
                "projectId" to projectId.toString(),
                "ticketProject" to template.defaultPolicy.project,
                "assigneeStrategy" to template.defaultPolicy.assigneeStrategy,
                "labels" to template.defaultPolicy.labels,
                "titleI18n" to draft.titleI18n,
                "descriptionI18n" to draft.descriptionI18n
            ),
            "findings" to findingItems
        )
    }

    private fun extractTicketContent(result: Map<String, Any?>): Map<String, Any?>? {
        val recommendation = result["recommendation"] as? Map<*, *> ?: return null
        val findings = recommendation["findings"] as? List<*> ?: return null
        val top = findings.firstOrNull() as? Map<*, *> ?: return null
        val details = top["details"] as? Map<*, *> ?: return null
        return details["ticketContent"].asStringAnyMapOrNull()
    }
}

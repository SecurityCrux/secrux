package com.secrux.service

import com.secrux.adapter.TicketAdapter
import com.secrux.adapter.TicketPayload
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Ticket
import com.secrux.domain.TicketDraftItemType
import com.secrux.domain.TicketStatus
import com.secrux.dto.TicketCreateFromDraftRequest
import com.secrux.dto.TicketCreationRequest
import com.secrux.dto.TicketResponse
import com.secrux.dto.TicketStatusUpdateRequest
import com.secrux.repo.FindingRepository
import com.secrux.repo.ScaIssueRepository
import com.secrux.repo.TicketDraftRepository
import com.secrux.repo.TicketFindingRepository
import com.secrux.repo.TicketRepository
import com.secrux.repo.TicketScaIssueRepository
import com.secrux.security.SecruxPrincipal
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class TicketCommandService(
    private val ticketRepository: TicketRepository,
    private val ticketAdapter: TicketAdapter,
    private val ticketFindingRepository: TicketFindingRepository,
    private val ticketScaIssueRepository: TicketScaIssueRepository,
    private val ticketDraftRepository: TicketDraftRepository,
    private val findingRepository: FindingRepository,
    private val scaIssueRepository: ScaIssueRepository,
    private val ticketProviderCatalog: TicketProviderCatalog,
    private val clock: Clock
) {

    fun createTickets(tenantId: UUID, request: TicketCreationRequest): List<TicketResponse> {
        val summary = request.summary ?: "Security findings (${request.findingIds.size})"
        val description =
            buildString {
                append("Findings: ")
                append(request.findingIds.joinToString())
            }
        val payload = TicketPayload(
            summary = summary,
            description = description,
            severity = request.severity.name,
            assignee = request.ticketPolicy.assigneeStrategy,
            labels = (request.labels + request.ticketPolicy.labels).distinct(),
            issueType = request.issueType
        )
        val dedupeKey =
            TicketDedupeKeys.compute(
                projectId = request.projectId,
                provider = request.provider,
                findingIds = request.findingIds
            )
        val existing = ticketRepository.findByDedupeKey(tenantId, request.projectId, request.provider, dedupeKey)
        if (existing != null) {
            return listOf(existing.toResponse())
        }

        val externalKeys = ticketAdapter.createTickets(tenantId, request.provider, listOf(payload))
        val now = OffsetDateTime.now(clock)
        val ticket = Ticket(
            ticketId = UUID.randomUUID(),
            tenantId = tenantId,
            projectId = request.projectId,
            externalKey = externalKeys.firstOrNull() ?: "PENDING",
            provider = request.provider,
            dedupeKey = dedupeKey,
            payload = mapOf(
                "summary" to summary,
                "description" to description,
                "findingIds" to request.findingIds,
                "issueType" to request.issueType.name
            ),
            status = TicketStatus.OPEN,
            createdAt = now,
            updatedAt = null
        )
        ticketRepository.insert(ticket)
        ticketFindingRepository.insertMappings(ticket.ticketId, tenantId, request.findingIds, now)
        return listOf(ticket.toResponse())
    }

    fun createFromCurrentDraft(
        principal: SecruxPrincipal,
        request: TicketCreateFromDraftRequest
    ): List<TicketResponse> {
        val draft =
            ticketDraftRepository.findCurrent(principal.tenantId, principal.userId)
                ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket draft is empty")
        val itemRefs = ticketDraftRepository.listItems(draft.draftId, principal.tenantId)
        if (itemRefs.isEmpty()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket draft is empty")
        }

        val findingIds = itemRefs.filter { it.type == TicketDraftItemType.FINDING }.map { it.id }
        val scaIssueIds = itemRefs.filter { it.type == TicketDraftItemType.SCA_ISSUE }.map { it.id }
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

        val provider = request.provider.trim()
        val template =
            ticketProviderCatalog.listEnabledProviders().firstOrNull { it.provider == provider }
                ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Ticket provider '$provider' is not enabled")

        val now = OffsetDateTime.now(clock)
        val dedupeKey =
            TicketDedupeKeys.compute(
                projectId = projectId,
                provider = provider,
                findingIds = findingIds,
                scaIssueIds = scaIssueIds
            )
        val existing = ticketRepository.findByDedupeKey(principal.tenantId, projectId, provider, dedupeKey)
        if (existing != null) {
            if (request.clearDraft) {
                ticketDraftRepository.clearItems(draft.draftId, principal.tenantId)
                ticketDraftRepository.resetDraft(draft.draftId, principal.tenantId, now)
            }
            return listOf(existing.toResponse())
        }

        val severity = TicketSeverities.maxSeverityName(findings, issues)
        val summary = TicketDraftText.resolveSummary(draft.titleI18n, projectId, itemRefs.size)
        val description = TicketDraftText.resolveDescription(draft.descriptionI18n, findings, issues)
        val payload = TicketPayload(
            summary = summary,
            description = description,
            severity = severity,
            assignee = template.defaultPolicy.assigneeStrategy,
            labels = template.defaultPolicy.labels,
            issueType = request.issueType
        )
        val externalKeys = ticketAdapter.createTickets(principal.tenantId, provider, listOf(payload))
        val ticket =
            Ticket(
                ticketId = UUID.randomUUID(),
                tenantId = principal.tenantId,
                projectId = projectId,
                externalKey = externalKeys.firstOrNull() ?: "PENDING",
                provider = provider,
                dedupeKey = dedupeKey,
                payload =
                    mapOf(
                        "summary" to summary,
                        "description" to description,
                        "findingIds" to findingIds,
                        "scaIssueIds" to scaIssueIds,
                        "issueType" to request.issueType.name,
                        "titleI18n" to draft.titleI18n,
                        "descriptionI18n" to draft.descriptionI18n,
                        "providerPolicy" to mapOf(
                            "project" to template.defaultPolicy.project,
                            "assigneeStrategy" to template.defaultPolicy.assigneeStrategy,
                            "labels" to template.defaultPolicy.labels
                        )
                    ),
                status = TicketStatus.OPEN,
                createdAt = now,
                updatedAt = null
            )
        ticketRepository.insert(ticket)
        ticketFindingRepository.insertMappings(ticket.ticketId, principal.tenantId, findingIds, now)
        ticketScaIssueRepository.insertMappings(ticket.ticketId, principal.tenantId, scaIssueIds, now)

        if (request.clearDraft) {
            ticketDraftRepository.clearItems(draft.draftId, principal.tenantId)
            ticketDraftRepository.resetDraft(draft.draftId, principal.tenantId, now)
        }

        return listOf(ticket.toResponse())
    }

    fun updateStatus(
        tenantId: UUID,
        ticketId: UUID,
        request: TicketStatusUpdateRequest
    ): com.secrux.dto.TicketSummary {
        val ticket = ticketRepository.findById(ticketId, tenantId)
            ?: throw SecruxException(ErrorCode.TICKET_NOT_FOUND, "Ticket not found")
        if (ticket.status != request.status) {
            ticketRepository.updateStatus(ticketId, tenantId, request.status)
        }
        return ticket.copy(status = request.status, updatedAt = OffsetDateTime.now(clock)).toSummary()
    }

    private fun Ticket.toResponse(): TicketResponse =
        TicketResponse(
            ticketId = ticketId,
            externalKey = externalKey,
            provider = provider,
            status = status
        )
}

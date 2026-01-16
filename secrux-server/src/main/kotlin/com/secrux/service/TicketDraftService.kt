package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.TicketDraft
import com.secrux.domain.TicketDraftItemRef
import com.secrux.domain.TicketDraftItemType
import com.secrux.dto.AiJobTicketResponse
import com.secrux.dto.TicketDraftDetailResponse
import com.secrux.dto.TicketDraftAiApplyRequest
import com.secrux.dto.TicketDraftAiRequest
import com.secrux.dto.TicketDraftItem
import com.secrux.dto.TicketDraftItemRefPayload
import com.secrux.dto.TicketDraftUpdateRequest
import com.secrux.repo.FindingRepository
import com.secrux.repo.ScaIssueRepository
import com.secrux.repo.TicketDraftRepository
import com.secrux.security.SecruxPrincipal
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class TicketDraftService(
    private val ticketDraftRepository: TicketDraftRepository,
    private val findingRepository: FindingRepository,
    private val scaIssueRepository: ScaIssueRepository,
    private val ticketDraftCurrentService: TicketDraftCurrentService,
    private val ticketDraftAiService: TicketDraftAiService,
    private val clock: Clock
) {

    fun getOrCreateCurrent(principal: SecruxPrincipal): TicketDraftDetailResponse {
        val draft = ticketDraftCurrentService.getOrCreate(principal)
        return toDetail(draft)
    }

    fun addItems(principal: SecruxPrincipal, items: List<TicketDraftItemRefPayload>): TicketDraftDetailResponse {
        val refs = items.map { TicketDraftItemRef(type = it.type, id = it.id) }.distinctBy { it.type to it.id }
        if (refs.isEmpty()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "items cannot be empty")
        }

        val findingIds = refs.filter { it.type == TicketDraftItemType.FINDING }.map { it.id }
        val scaIssueIds = refs.filter { it.type == TicketDraftItemType.SCA_ISSUE }.map { it.id }

        val findings = findingRepository.findByIds(principal.tenantId, findingIds)
        if (findings.size != findingIds.size) {
            throw SecruxException(ErrorCode.FINDING_NOT_FOUND, "One or more findings not found")
        }
        val issues = scaIssueRepository.findByIds(principal.tenantId, scaIssueIds)
        if (issues.size != scaIssueIds.size) {
            throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "One or more SCA issues not found")
        }

        val projectId =
            findings.firstOrNull()?.projectId
                ?: issues.firstOrNull()?.projectId
                ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "items cannot be empty")
        if (findings.any { it.projectId != projectId } || issues.any { it.projectId != projectId }) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "All items in a ticket draft must belong to the same project")
        }

        val draft = getOrCreateCurrentDraft(principal)
        if (draft.projectId != null && draft.projectId != projectId) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Current ticket draft belongs to a different project; clear it first")
        }

        val now = OffsetDateTime.now(clock)
        ticketDraftRepository.updateDraft(
            draftId = draft.draftId,
            tenantId = principal.tenantId,
            projectId = projectId,
            provider = null,
            titleI18n = null,
            descriptionI18n = null,
            lastAiJobId = null,
            status = null,
            updatedAt = now
        )
        ticketDraftRepository.addItems(draft.draftId, principal.tenantId, refs, now)
        val updated =
            draft.copy(
                projectId = projectId,
                provider = null,
                titleI18n = null,
                descriptionI18n = null,
                lastAiJobId = null,
                updatedAt = now
            )
        return toDetail(updated)
    }

    fun removeItems(principal: SecruxPrincipal, items: List<TicketDraftItemRefPayload>): TicketDraftDetailResponse {
        val refs = items.map { TicketDraftItemRef(type = it.type, id = it.id) }.distinctBy { it.type to it.id }
        val draft = getOrCreateCurrentDraft(principal)
        ticketDraftRepository.removeItems(draft.draftId, principal.tenantId, refs)
        val now = OffsetDateTime.now(clock)
        val remaining = ticketDraftRepository.listItems(draft.draftId, principal.tenantId)
        val updated =
            if (remaining.isEmpty()) {
                ticketDraftRepository.resetDraft(draft.draftId, principal.tenantId, now)
                draft.copy(
                    projectId = null,
                    provider = null,
                    titleI18n = null,
                    descriptionI18n = null,
                    lastAiJobId = null,
                    updatedAt = now
                )
            } else {
                ticketDraftRepository.updateDraft(
                    draftId = draft.draftId,
                    tenantId = principal.tenantId,
                    projectId = null,
                    provider = null,
                    titleI18n = null,
                    descriptionI18n = null,
                    lastAiJobId = null,
                    status = null,
                    updatedAt = now
                )
                draft.copy(
                    provider = null,
                    titleI18n = null,
                    descriptionI18n = null,
                    lastAiJobId = null,
                    updatedAt = now
                )
            }
        return toDetail(updated)
    }

    fun clearCurrent(principal: SecruxPrincipal): TicketDraftDetailResponse {
        val draft = getOrCreateCurrentDraft(principal)
        ticketDraftRepository.clearItems(draft.draftId, principal.tenantId)
        val now = OffsetDateTime.now(clock)
        ticketDraftRepository.resetDraft(draft.draftId, principal.tenantId, now)
        return toDetail(draft.copy(projectId = null, provider = null, titleI18n = null, descriptionI18n = null, lastAiJobId = null, updatedAt = now))
    }

    fun updateCurrent(principal: SecruxPrincipal, request: TicketDraftUpdateRequest): TicketDraftDetailResponse {
        val draft = getOrCreateCurrentDraft(principal)
        val now = OffsetDateTime.now(clock)
        val provider =
            if (request.provider == null) {
                draft.provider
            } else {
                request.provider.trim().takeIf { it.isNotBlank() }
            }
        val titleI18n =
            when {
                request.titleI18n == null -> draft.titleI18n
                request.titleI18n.isEmpty() -> null
                else -> request.titleI18n
            }
        val descriptionI18n =
            when {
                request.descriptionI18n == null -> draft.descriptionI18n
                request.descriptionI18n.isEmpty() -> null
                else -> request.descriptionI18n
            }
        ticketDraftRepository.updateDraft(
            draftId = draft.draftId,
            tenantId = principal.tenantId,
            projectId = null,
            provider = provider,
            titleI18n = titleI18n,
            descriptionI18n = descriptionI18n,
            lastAiJobId = null,
            status = null,
            updatedAt = now
        )
        val updated =
            draft.copy(
                provider = provider,
                titleI18n = titleI18n,
                descriptionI18n = descriptionI18n,
                updatedAt = now
            )
        return toDetail(updated)
    }

    fun triggerAiGenerate(principal: SecruxPrincipal, request: TicketDraftAiRequest): AiJobTicketResponse {
        return ticketDraftAiService.triggerAiGenerate(principal, request)
    }

    fun triggerAiPolish(principal: SecruxPrincipal, request: TicketDraftAiRequest): AiJobTicketResponse {
        return ticketDraftAiService.triggerAiPolish(principal, request)
    }

    fun applyAiResult(principal: SecruxPrincipal, request: TicketDraftAiApplyRequest): TicketDraftDetailResponse {
        val updated = ticketDraftAiService.applyAiResult(principal, request)
        return toDetail(updated)
    }

    private fun getOrCreateCurrentDraft(principal: SecruxPrincipal): TicketDraft =
        ticketDraftCurrentService.getOrCreate(principal)

    private fun toDetail(draft: TicketDraft): TicketDraftDetailResponse {
        val refs = ticketDraftRepository.listItems(draft.draftId, draft.tenantId)
        val findingIds = refs.filter { it.type == TicketDraftItemType.FINDING }.map { it.id }
        val scaIssueIds = refs.filter { it.type == TicketDraftItemType.SCA_ISSUE }.map { it.id }
        val findings = findingRepository.findByIds(draft.tenantId, findingIds)
        val issues = scaIssueRepository.findByIds(draft.tenantId, scaIssueIds)
        val findingById = findings.associateBy { it.findingId }
        val issueById = issues.associateBy { it.issueId }

        val items =
            refs.mapNotNull { ref ->
                when (ref.type) {
                    TicketDraftItemType.FINDING -> {
                        val finding = findingById[ref.id] ?: return@mapNotNull null
                        TicketDraftItem(
                            type = TicketDraftItemType.FINDING,
                            id = finding.findingId,
                            title = finding.ruleId,
                            severity = finding.severity,
                            status = finding.status,
                            location = finding.location
                        )
                    }
                    TicketDraftItemType.SCA_ISSUE -> {
                        val issue = issueById[ref.id] ?: return@mapNotNull null
                        TicketDraftItem(
                            type = TicketDraftItemType.SCA_ISSUE,
                            id = issue.issueId,
                            title = issue.vulnId,
                            severity = issue.severity,
                            status = issue.status,
                            location = issue.location
                        )
                    }
                }
            }
        return TicketDraftDetailResponse(
            draftId = draft.draftId,
            projectId = draft.projectId,
            provider = draft.provider,
            items = items,
            itemCount = items.size,
            titleI18n = draft.titleI18n,
            descriptionI18n = draft.descriptionI18n,
            lastAiJobId = draft.lastAiJobId,
            createdAt = draft.createdAt.toString(),
            updatedAt = draft.updatedAt?.toString()
        )
    }
}

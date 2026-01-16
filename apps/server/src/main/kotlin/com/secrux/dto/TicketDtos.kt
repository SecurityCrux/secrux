package com.secrux.dto

import com.secrux.domain.Severity
import com.secrux.domain.TicketIssueType
import com.secrux.domain.TicketStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(name = "TicketCreationRequest")
data class TicketCreationRequest(
    @field:NotNull val projectId: UUID,
    @field:NotBlank val provider: String,
    @field:NotEmpty val findingIds: List<UUID>,
    @field:NotNull val ticketPolicy: TicketPolicyRequest,
    val issueType: TicketIssueType = TicketIssueType.BUG,
    val summary: String? = null,
    val severity: Severity = Severity.MEDIUM,
    val labels: List<String> = emptyList()
)

@Schema(name = "TicketResponse")
data class TicketResponse(
    val ticketId: UUID,
    val externalKey: String,
    val provider: String,
    val status: TicketStatus
)

@Schema(name = "TicketSummary")
data class TicketSummary(
    val ticketId: UUID,
    val projectId: UUID,
    val provider: String,
    val externalKey: String,
    val status: TicketStatus,
    val payload: Map<String, Any?>,
    val createdAt: String,
    val updatedAt: String?
)

@Schema(name = "TicketStatusUpdateRequest")
data class TicketStatusUpdateRequest(
    @field:NotNull val status: TicketStatus
)


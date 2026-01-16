package com.secrux.adapter

import com.secrux.adapter.jira.JiraCloudTicketClient
import com.secrux.domain.TicketIssueType
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

data class TicketPayload(
    val summary: String,
    val description: String,
    val severity: String,
    val assignee: String?,
    val labels: List<String>,
    val issueType: TicketIssueType = TicketIssueType.BUG
)

@Component
class TicketAdapter(
    private val jiraCloudTicketClient: JiraCloudTicketClient
) {

    private val log = LoggerFactory.getLogger(TicketAdapter::class.java)

    fun createTickets(tenantId: UUID, provider: String, payloads: List<TicketPayload>): List<String> {
        LogContext.with(LogContext.TENANT_ID to tenantId) {
            log.info("event=ticket_create_requested provider={} count={}", provider, payloads.size)
        }
        val normalized = provider.trim().lowercase()
        return when {
            normalized.startsWith("jira") -> jiraCloudTicketClient.createTickets(tenantId, provider.trim(), payloads)
            else -> createStubTickets(provider, payloads.size)
        }
    }

    private fun createStubTickets(provider: String, count: Int): List<String> {
        val prefix =
            provider
                .trim()
                .uppercase()
                .replace(Regex("[^A-Z0-9]"), "")
                .takeIf { it.isNotBlank() }
                ?: "SEC"
        return (0 until count).map { index -> "$prefix-${1000 + index}" }
    }
}

package com.secrux.repo

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TicketScaIssueRepository(
    private val dsl: DSLContext
) {

    private val table = DSL.table("ticket_sca_issue")
    private val ticketIdField = DSL.field("ticket_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val issueIdField = DSL.field("issue_id", UUID::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)

    fun insertMappings(ticketId: UUID, tenantId: UUID, issueIds: List<UUID>, createdAt: OffsetDateTime) {
        val ids = issueIds.distinct()
        if (ids.isEmpty()) return
        val queries =
            ids.map { issueId ->
                dsl.insertInto(table)
                    .columns(ticketIdField, tenantField, issueIdField, createdField)
                    .values(ticketId, tenantId, issueId, createdAt)
                    .onConflict(ticketIdField, issueIdField)
                    .doNothing()
            }
        dsl.batch(queries).execute()
    }
}


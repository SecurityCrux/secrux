package com.secrux.repo

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TicketFindingRepository(
    private val dsl: DSLContext
) {

    private val table = DSL.table("ticket_finding")
    private val ticketIdField = DSL.field("ticket_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val findingIdField = DSL.field("finding_id", UUID::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)

    fun insertMappings(ticketId: UUID, tenantId: UUID, findingIds: List<UUID>, createdAt: OffsetDateTime) {
        val ids = findingIds.distinct()
        if (ids.isEmpty()) return
        val queries =
            ids.map { findingId ->
                dsl.insertInto(table)
                    .columns(ticketIdField, tenantField, findingIdField, createdField)
                    .values(ticketId, tenantId, findingId, createdAt)
                    .onConflict(ticketIdField, findingIdField)
                    .doNothing()
            }
        dsl.batch(queries).execute()
    }
}


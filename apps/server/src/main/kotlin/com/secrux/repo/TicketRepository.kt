package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.Ticket
import com.secrux.domain.TicketStatus
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TicketRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val table = DSL.table("ticket")
    private val ticketIdField = DSL.field("ticket_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val projectField = DSL.field("project_id", UUID::class.java)
    private val externalField = DSL.field("external_key", String::class.java)
    private val providerField = DSL.field("provider", String::class.java)
    private val dedupeKeyField = DSL.field("dedupe_key", String::class.java)
    private val payloadField = DSL.field("payload", JSONB::class.java)
    private val statusField = DSL.field("status", String::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun insert(ticket: Ticket) {
        dsl.insertInto(table)
            .set(ticketIdField, ticket.ticketId)
            .set(tenantField, ticket.tenantId)
            .set(projectField, ticket.projectId)
            .set(externalField, ticket.externalKey)
            .set(providerField, ticket.provider)
            .set(dedupeKeyField, ticket.dedupeKey)
            .set(payloadField, objectMapper.toJsonb(ticket.payload))
            .set(statusField, ticket.status.name)
            .set(createdField, ticket.createdAt)
            .set(updatedField, ticket.updatedAt)
            .execute()
    }

    fun listByTenant(
        tenantId: UUID,
        projectId: UUID?,
        provider: String?,
        status: TicketStatus?,
        search: String?,
        limit: Int,
        offset: Int
    ): List<Ticket> {
        var condition = tenantField.eq(tenantId)
        projectId?.let { condition = condition.and(projectField.eq(it)) }
        provider?.takeIf { it.isNotBlank() }?.let { condition = condition.and(providerField.eq(it)) }
        status?.let { condition = condition.and(statusField.eq(it.name)) }
        search?.takeIf { it.isNotBlank() }?.let { term ->
            val like = "%${term.lowercase()}%"
            condition = condition.and(DSL.lower(externalField).like(like))
        }
        return dsl.selectFrom(table)
            .where(condition)
            .orderBy(createdField.desc())
            .limit(limit)
            .offset(offset)
            .fetch { mapTicket(it) }
    }

    fun updateStatus(ticketId: UUID, tenantId: UUID, status: TicketStatus) {
        dsl.update(table)
            .set(statusField, status.name)
            .set(updatedField, DSL.currentOffsetDateTime())
            .where(ticketIdField.eq(ticketId))
            .and(tenantField.eq(tenantId))
            .execute()
    }

    fun findById(ticketId: UUID, tenantId: UUID): Ticket? =
        dsl.selectFrom(table)
            .where(ticketIdField.eq(ticketId))
            .and(tenantField.eq(tenantId))
            .fetchOne { mapTicket(it) }

    private fun mapTicket(record: Record): Ticket {
        val payloadRaw = record.get(payloadField)?.data() ?: "{}"
        return Ticket(
            ticketId = record.get(ticketIdField),
            tenantId = record.get(tenantField),
            projectId = record.get(projectField),
            externalKey = record.get(externalField),
            provider = record.get(providerField),
            dedupeKey = record.get(dedupeKeyField),
            payload = objectMapper.readMapOrEmpty(payloadRaw),
            status = TicketStatus.valueOf(record.get(statusField)),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField)
        )
    }

    fun findByDedupeKey(
        tenantId: UUID,
        projectId: UUID,
        provider: String,
        dedupeKey: String
    ): Ticket? =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(projectField.eq(projectId))
            .and(providerField.eq(provider))
            .and(dedupeKeyField.eq(dedupeKey))
            .fetchOne { mapTicket(it) }
}

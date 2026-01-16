package com.secrux.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.repo.readMapOrEmpty
import com.secrux.repo.toJsonb
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class OutboxRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) : OutboxPort {

    private val table = DSL.table("outbox_event")
    private val eventIdField = DSL.field("event_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val correlationField = DSL.field("correlation_id", String::class.java)
    private val eventTypeField = DSL.field("event_type", String::class.java)
    private val payloadField = DSL.field("payload", JSONB::class.java)
    private val statusField = DSL.field("status", String::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val processedField = DSL.field("processed_at", OffsetDateTime::class.java)

    override fun insert(event: PlatformEvent) {
        dsl.insertInto(table)
            .set(eventIdField, event.eventId)
            .set(tenantField, event.tenantId)
            .set(correlationField, event.correlationId)
            .set(eventTypeField, event.event)
            .set(payloadField, objectMapper.toJsonb(event.payload))
            .set(statusField, OutboxStatus.PENDING.name)
            .set(createdField, event.createdAt)
            .onConflict(eventIdField)
            .doNothing()
            .execute()
    }

    override fun fetchAfter(timestamp: OffsetDateTime, limit: Int): List<PlatformEvent> {
        return dsl.selectFrom(table)
            .where(statusField.eq(OutboxStatus.PENDING.name))
            .and(createdField.ge(timestamp))
            .orderBy(createdField.asc())
            .limit(limit)
            .fetch { mapEvent(it) }
    }

    override fun markProcessed(eventId: UUID) {
        dsl.update(table)
            .set(statusField, OutboxStatus.PROCESSED.name)
            .set(processedField, OffsetDateTime.now(clock))
            .where(eventIdField.eq(eventId))
            .execute()
    }

    private fun mapEvent(record: Record): PlatformEvent {
        val payloadMap = objectMapper.readMapOrEmpty(record.get(payloadField))
        return PlatformEvent(
            eventId = record.get(eventIdField),
            tenantId = record.get(tenantField),
            event = record.get(eventTypeField),
            correlationId = record.get(correlationField),
            payload = payloadMap,
            createdAt = record.get(createdField)
        )
    }
}

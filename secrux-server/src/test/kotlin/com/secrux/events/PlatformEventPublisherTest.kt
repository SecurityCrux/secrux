package com.secrux.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.OffsetDateTime
import java.util.UUID

class PlatformEventPublisherTest {

    @Test
    fun `publishing same event twice is idempotent`() {
        val fakeOutbox = FakeOutboxPort()
        val kafkaTemplate = mock<org.springframework.kafka.core.KafkaTemplate<String, String>>()
        val mapper = ObjectMapper()
            .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
            .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val publisher = PlatformEventPublisher(fakeOutbox, kafkaTemplate, mapper)
        val eventId = UUID.randomUUID()
        val createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val event = PlatformEvent(
            eventId = eventId,
            event = "StageCompleted",
            correlationId = "corr-1",
            payload = mapOf("stage" to "TEST"),
            createdAt = createdAt
        )

        publisher.publish(event)
        publisher.publish(event)

        assertEquals(1, fakeOutbox.events.size)
        assertEquals(eventId, fakeOutbox.events.first().eventId)
    }

    private class FakeOutboxPort : OutboxPort {
        val events = mutableListOf<PlatformEvent>()
        override fun insert(event: PlatformEvent) {
            if (events.none { it.eventId == event.eventId }) {
                events.add(event)
            }
        }

        override fun fetchAfter(timestamp: OffsetDateTime, limit: Int): List<PlatformEvent> = events

        override fun markProcessed(eventId: UUID) {}
    }
}

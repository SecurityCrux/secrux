package com.secrux.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.secrux.workflow.WorkflowOrchestrator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.OffsetDateTime
import java.util.UUID

class PlatformEventConsumerTest {

    @Test
    fun `consumes kafka message and hands to orchestrator`() {
        val orchestrator = mock<WorkflowOrchestrator>()
        val mapper = ObjectMapper()
            .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
            .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val consumer = PlatformEventConsumer(orchestrator, mapper)
        val createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val event = PlatformEvent(
            eventId = UUID.randomUUID(),
            event = "StageCompleted",
            correlationId = "corr",
            payload = mapOf("type" to "SOURCE_PREPARE", "status" to "SUCCEEDED"),
            createdAt = createdAt
        )

        consumer.onMessage(mapper.writeValueAsString(event))

        val captor = argumentCaptor<PlatformEvent>()
        verify(orchestrator).ingest(captor.capture())
        assert(event.eventId == captor.firstValue.eventId)
    }
}

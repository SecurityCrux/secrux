package com.secrux.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

data class PlatformEvent(
    val eventId: UUID = UUID.randomUUID(),
    val tenantId: UUID? = null,
    val event: String,
    val correlationId: String,
    val payload: Map<String, Any?>,
    val createdAt: OffsetDateTime
)

@Component
class PlatformEventPublisher(
    private val outboxPort: OutboxPort,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(PlatformEventPublisher::class.java)

    fun publish(event: PlatformEvent) {
        LogContext.with(
            LogContext.TENANT_ID to event.tenantId,
            LogContext.CORRELATION_ID to event.correlationId
        ) {
            log.info("event=platform_event_publish_requested eventType={}", event.event)
            outboxPort.insert(event)
            try {
                val message = objectMapper.writeValueAsString(event)
                kafkaTemplate.sendDefault(event.correlationId, message)
            } catch (ex: Exception) {
                log.error("event=platform_event_publish_failed_kafka eventType={}", event.event, ex)
            }
        }
    }

    fun fetchAfter(timestamp: OffsetDateTime, limit: Int = 100): List<PlatformEvent> =
        outboxPort.fetchAfter(timestamp, limit)

    fun markProcessed(eventId: UUID) = outboxPort.markProcessed(eventId)
}

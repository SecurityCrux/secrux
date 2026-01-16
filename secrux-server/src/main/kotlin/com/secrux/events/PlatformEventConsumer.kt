package com.secrux.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.support.LogContext
import com.secrux.workflow.WorkflowOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "secrux.workflow",
    name = ["kafka-consumer-enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class PlatformEventConsumer(
    private val workflowOrchestrator: WorkflowOrchestrator,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(PlatformEventConsumer::class.java)

    @KafkaListener(topics = ["secrux.events"], groupId = "secrux-workflow")
    fun onMessage(message: String) {
        val event =
            runCatching { objectMapper.readValue(message, PlatformEvent::class.java) }
                .getOrElse { ex ->
                    log.error("event=platform_event_deserialize_failed", ex)
                    return
                }
        LogContext.with(
            LogContext.TENANT_ID to event.tenantId,
            LogContext.CORRELATION_ID to event.correlationId
        ) {
            runCatching { workflowOrchestrator.ingest(event) }
                .onFailure { ex ->
                    log.error("event=platform_event_consume_failed eventType={}", event.event, ex)
                }
        }
    }
}

package com.secrux.events

import java.time.OffsetDateTime

interface OutboxPort {
    fun insert(event: PlatformEvent)
    fun fetchAfter(timestamp: OffsetDateTime, limit: Int = 100): List<PlatformEvent>
    fun markProcessed(eventId: java.util.UUID)
}


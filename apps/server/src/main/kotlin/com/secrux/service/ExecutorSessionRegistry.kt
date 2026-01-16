package com.secrux.service

import io.netty.channel.Channel
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class ExecutorSessionRegistry {

    private val sessions = ConcurrentHashMap<UUID, Channel>()

    fun register(executorId: UUID, channel: Channel) {
        sessions[executorId] = channel
    }

    fun remove(executorId: UUID) {
        sessions.remove(executorId)
    }

    fun get(executorId: UUID): Channel? = sessions[executorId]

    fun broadcast(payload: String) {
        sessions.values.forEach { ch ->
            if (ch.isActive) {
                ch.writeAndFlush(payload)
            }
        }
    }
}


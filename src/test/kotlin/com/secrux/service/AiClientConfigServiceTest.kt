package com.secrux.service

import com.secrux.domain.AiClientConfig
import com.secrux.dto.AiClientConfigRequest
import com.secrux.repo.AiClientConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class AiClientConfigServiceTest {

    @Mock
    private lateinit var repository: AiClientConfigRepository

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: AiClientConfigService

    @BeforeEach
    fun setUp() {
        service = AiClientConfigService(repository, fixedClock)
    }

    @Test
    fun `updateConfig keeps existing apiKey when request apiKey is blank`() {
        val tenantId = UUID.randomUUID()
        val configId = UUID.randomUUID()
        val existing =
            AiClientConfig(
                configId = configId,
                tenantId = tenantId,
                name = "Default",
                provider = "openai",
                baseUrl = "https://api.example.com",
                apiKey = "sk-old",
                model = "gpt-4o-mini",
                isDefault = true,
                enabled = true,
                createdAt = OffsetDateTime.now(fixedClock).minusDays(1),
                updatedAt = null
            )
        whenever(repository.findById(eq(configId), eq(tenantId))).thenReturn(existing)

        service.updateConfig(
            tenantId = tenantId,
            configId = configId,
            request =
                AiClientConfigRequest(
                    name = "Default",
                    provider = "openai",
                    baseUrl = "https://api.example.com",
                    apiKey = "   ",
                    model = "gpt-4o-mini",
                    isDefault = true,
                    enabled = true
                )
        )

        val captor = argumentCaptor<AiClientConfig>()
        verify(repository).update(captor.capture())
        assertEquals("sk-old", captor.firstValue.apiKey)
    }

    @Test
    fun `updateConfig overwrites apiKey when request apiKey is provided`() {
        val tenantId = UUID.randomUUID()
        val configId = UUID.randomUUID()
        val existing =
            AiClientConfig(
                configId = configId,
                tenantId = tenantId,
                name = "Default",
                provider = "openai",
                baseUrl = "https://api.example.com",
                apiKey = "sk-old",
                model = "gpt-4o-mini",
                isDefault = false,
                enabled = true,
                createdAt = OffsetDateTime.now(fixedClock).minusDays(1),
                updatedAt = null
            )
        whenever(repository.findById(eq(configId), eq(tenantId))).thenReturn(existing)

        service.updateConfig(
            tenantId = tenantId,
            configId = configId,
            request =
                AiClientConfigRequest(
                    name = "Default",
                    provider = "openai",
                    baseUrl = "https://api.example.com",
                    apiKey = "  sk-new  ",
                    model = "gpt-4o-mini",
                    isDefault = false,
                    enabled = true
                )
        )

        val captor = argumentCaptor<AiClientConfig>()
        verify(repository).update(captor.capture())
        assertEquals("sk-new", captor.firstValue.apiKey)
    }
}


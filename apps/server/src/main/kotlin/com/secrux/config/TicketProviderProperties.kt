package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "secrux.ticket")
data class TicketProviderProperties(
    val providers: List<TicketProviderTemplateProperties> = emptyList()
)

data class TicketProviderTemplateProperties(
    val provider: String = "stub",
    val name: String = "Stub",
    val enabled: Boolean = true,
    val defaultPolicy: TicketProviderPolicyDefaultsProperties = TicketProviderPolicyDefaultsProperties()
)

data class TicketProviderPolicyDefaultsProperties(
    val project: String = "AUTO",
    val assigneeStrategy: String = "OWNER",
    val labels: List<String> = emptyList()
)


package com.secrux.service

import com.secrux.config.TicketProviderProperties
import com.secrux.config.TicketProviderTemplateProperties
import org.springframework.stereotype.Component

data class TicketProviderTemplate(
    val provider: String,
    val name: String,
    val enabled: Boolean,
    val defaultPolicy: TicketProviderPolicyDefaults
)

data class TicketProviderPolicyDefaults(
    val project: String,
    val assigneeStrategy: String,
    val labels: List<String>
)

@Component
class TicketProviderCatalog(
    private val properties: TicketProviderProperties
) {

    fun listProviders(): List<TicketProviderTemplate> =
        properties.providers.map { it.toTemplate() }

    fun listEnabledProviders(): List<TicketProviderTemplate> =
        listProviders().filter { it.enabled }

    private fun TicketProviderTemplateProperties.toTemplate() =
        TicketProviderTemplate(
            provider = provider,
            name = name,
            enabled = enabled,
            defaultPolicy =
                TicketProviderPolicyDefaults(
                    project = defaultPolicy.project,
                    assigneeStrategy = defaultPolicy.assigneeStrategy,
                    labels = defaultPolicy.labels
                )
        )
}


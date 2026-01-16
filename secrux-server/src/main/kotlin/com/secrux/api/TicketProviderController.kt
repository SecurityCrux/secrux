package com.secrux.api

import com.secrux.common.ApiResponse
import com.secrux.dto.TicketProviderPolicyDefaultsPayload
import com.secrux.dto.TicketProviderTemplateResponse
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.ticketResource
import com.secrux.service.TicketProviderCatalog
import com.secrux.service.TicketProviderTemplate
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tickets/providers")
@Tag(name = "Ticket Provider APIs", description = "Ticket provider templates")
class TicketProviderController(
    private val ticketProviderCatalog: TicketProviderCatalog,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List ticket provider templates")
    fun listProviders(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_READ,
            resource = principal.ticketResource(projectId = null)
        )
        val providers = ticketProviderCatalog.listEnabledProviders().map { it.toResponse() }
        return ApiResponse(data = providers)
    }
}

private fun TicketProviderTemplate.toResponse() =
    TicketProviderTemplateResponse(
        provider = provider,
        name = name,
        enabled = enabled,
        defaultPolicy =
            TicketProviderPolicyDefaultsPayload(
                project = defaultPolicy.project,
                assigneeStrategy = defaultPolicy.assigneeStrategy,
                labels = defaultPolicy.labels
            )
    )


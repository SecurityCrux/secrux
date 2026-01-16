package com.secrux.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import reactor.core.publisher.Mono

@Configuration
class AiServiceClientConfiguration(
    private val properties: AiServiceProperties
) {

    @Bean("aiServiceWebClient")
    fun aiServiceWebClient(builder: WebClient.Builder): WebClient =
        builder
            .baseUrl(properties.baseUrl.removeSuffix("/"))
            .filter(platformTokenFilter())
            .build()

    private fun platformTokenFilter(): ExchangeFilterFunction =
        ExchangeFilterFunction.ofRequestProcessor { request ->
            val mutated: ClientRequest = ClientRequest.from(request)
                .header("x-platform-token", properties.token)
                .build()
            Mono.just(mutated)
        }
}

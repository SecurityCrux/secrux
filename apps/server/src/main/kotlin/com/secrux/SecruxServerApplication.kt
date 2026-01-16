package com.secrux

import com.secrux.config.AiServiceProperties
import com.secrux.config.ExecutorDispatchProperties
import com.secrux.config.KeycloakAdminProperties
import com.secrux.config.SemgrepProperties
import com.secrux.config.SecruxCryptoProperties
import com.secrux.config.TicketProviderProperties
import com.secrux.config.TrivyProperties
import com.secrux.config.UploadProperties
import com.secrux.security.AuthorizationProperties
import com.secrux.security.SecruxAuthProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(
    value = [
        SemgrepProperties::class,
        SecruxAuthProperties::class,
        AuthorizationProperties::class,
        SecruxCryptoProperties::class,
        AiServiceProperties::class,
        KeycloakAdminProperties::class,
        TicketProviderProperties::class,
        TrivyProperties::class,
        UploadProperties::class,
        ExecutorDispatchProperties::class,
    ]
)
class SecruxServerApplication

fun main(args: Array<String>) {
    runApplication<SecruxServerApplication>(*args)
}

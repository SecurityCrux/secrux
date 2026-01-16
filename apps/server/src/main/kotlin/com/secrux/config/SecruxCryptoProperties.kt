package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "secrux.crypto")
data class SecruxCryptoProperties(
    val secret: String
)

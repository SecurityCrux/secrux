package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "secrux.upload")
data class UploadProperties(
    val root: String = "build/uploads"
)


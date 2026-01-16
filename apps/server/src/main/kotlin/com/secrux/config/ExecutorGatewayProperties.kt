package com.secrux.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "executor.gateway")
class ExecutorGatewayProperties {
    var enabled: Boolean = false
    var port: Int = 5155
    var certificatePath: String = ""
    var privateKeyPath: String = ""
    var maxFrameBytes: Int = 5 * 1024 * 1024
}

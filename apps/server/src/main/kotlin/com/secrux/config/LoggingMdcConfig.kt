package com.secrux.config

import com.secrux.support.RequestPathMdcInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class LoggingMdcConfig(
    private val requestPathMdcInterceptor: RequestPathMdcInterceptor
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(requestPathMdcInterceptor)
    }
}


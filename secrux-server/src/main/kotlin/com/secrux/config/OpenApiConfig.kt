package com.secrux.config

import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Contact
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.servers.Server
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableKnife4j
@OpenAPIDefinition(
    info = Info(
        title = "Secrux Platform API",
        version = "1.0.0",
        description = "Enterprise security orchestration platform APIs.",
        contact = Contact(name = "Secrux", email = "platform@secrux.io"),
        license = io.swagger.v3.oas.annotations.info.License(name = "Apache-2.0")
    ),
    servers = [
        Server(url = "https://api.secrux.local", description = "Default")
    ]
)
class OpenApiConfig {

    @Bean
    fun openAPI(): OpenAPI {
        val securityScheme = SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
        return OpenAPI()
            .components(Components().addSecuritySchemes("bearer", securityScheme))
            .addSecurityItem(SecurityRequirement().addList("bearer"))
            .info(
                io.swagger.v3.oas.models.info.Info()
                    .title("Secrux Platform API")
                    .version("1.0.0")
                    .description("End-to-end security automation APIs")
                    .license(License().name("Apache-2.0"))
            )
    }

    @Bean
    fun platformApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("platform")
        .packagesToScan("com.secrux.api")
        .pathsToMatch("/**")
        .build()

    @Bean
    fun taskApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("task")
        .pathsToMatch("/tasks/**")
        .build()
}

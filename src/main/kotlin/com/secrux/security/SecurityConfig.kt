package com.secrux.security

import com.secrux.repo.AppUserDirectoryRepository
import com.secrux.repo.IamPermissionRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val authProperties: SecruxAuthProperties,
    private val principalSyncFilter: PrincipalSyncFilter,
    private val requestMdcFilter: RequestMdcFilter,
    private val idePluginTokenAuthenticationFilter: IdePluginTokenAuthenticationFilter,
    private val authenticationEntryPoint: ApiAuthenticationEntryPoint,
    private val accessDeniedHandler: ApiAccessDeniedHandler,
    private val userDirectoryRepository: AppUserDirectoryRepository,
    private val permissionRepository: IamPermissionRepository,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/doc.html",
                        "/webjars/**",
                        "/auth/login",
                        "/auth/refresh",
                        "/auth/logout",
                        "/executor/uploads/**",
                    ).permitAll()
                it.anyRequest().authenticated()
            }.oauth2ResourceServer { resourceServer ->
                resourceServer
                    .bearerTokenResolver(bearerTokenResolver())
                    .jwt { jwtConfigurer ->
                        jwtConfigurer.jwtAuthenticationConverter(secruxJwtAuthenticationConverter())
                }
            }.addFilterAfter(requestMdcFilter, AuthorizationFilter::class.java)
            .addFilterAfter(principalSyncFilter, RequestMdcFilter::class.java)
            .addFilterBefore(idePluginTokenAuthenticationFilter, AuthorizationFilter::class.java)
        return http.build()
    }

    @Bean
    fun bearerTokenResolver(): BearerTokenResolver =
        DefaultBearerTokenResolver()
            .apply {
                setAllowUriQueryParameter(false)
                setAllowFormEncodedBodyParameter(false)
            }.let { delegate ->
                BearerTokenResolver { request ->
                    val token = delegate.resolve(request)
                    token?.takeUnless { IdePluginTokenFormat.isIdePluginToken(it) }
                }
            }

    @Bean
    fun jwtDecoder(): JwtDecoder =
        when (authProperties.mode) {
            AuthMode.LOCAL -> authProperties.buildLocalDecoder()
            AuthMode.KEYCLOAK -> authProperties.buildKeycloakDecoder()
        }

    @Bean
    fun secruxJwtAuthenticationConverter() =
        SecruxJwtAuthenticationConverter(
            authProperties = authProperties,
            userDirectoryRepository = userDirectoryRepository,
            permissionRepository = permissionRepository
        )

    @Bean
    fun corsConfigurationSource(): org.springframework.web.cors.CorsConfigurationSource {
        val configuration = org.springframework.web.cors.CorsConfiguration()
        // Allow any local dev port for Vite/preview servers.
        configuration.allowedOriginPatterns = listOf("http://localhost:*", "http://127.0.0.1:*")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders =
            listOf(
                "Authorization",
                "Content-Type",
                "Accept",
                "Origin",
                "X-Requested-With",
                "X-Executor-Token",
                "X-Request-Id"
            )
        configuration.exposedHeaders = listOf("X-Request-Id", "X-Secrux-Retryable", "X-Upstream-Status")
        configuration.allowCredentials = true
        val source = org.springframework.web.cors.UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}

private fun SecruxAuthProperties.buildKeycloakDecoder(): JwtDecoder {
    val issuer = issuerUri ?: throw IllegalStateException("secrux.auth.issuer-uri must be configured for KEYCLOAK mode")
    val decoder = JwtDecoders.fromIssuerLocation(issuer) as NimbusJwtDecoder

    val allowedIssuers =
        (listOf(issuer) + issuerAliases)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toSet()

    val validators =
        mutableListOf<OAuth2TokenValidator<Jwt>>(
            JwtValidators.createDefault(),
            AllowedIssuerValidator(allowedIssuers),
        )
    audience?.let {
        validators.add(AudienceValidator(it))
    }
    decoder.setJwtValidator(DelegatingOAuth2TokenValidator(*validators.toTypedArray()))
    return decoder
}

private fun SecruxAuthProperties.buildLocalDecoder(): JwtDecoder {
    val key = SecretKeySpec(localSecret.toByteArray(), "HmacSHA256")
    return NimbusJwtDecoder.withSecretKey(key).build()
}

private class AllowedIssuerValidator(
    private val allowedIssuers: Set<String>,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val issuer = token.issuer?.toString()
        return if (issuer != null && allowedIssuers.contains(issuer)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(
                    "invalid_token",
                    "The issuer '$issuer' is not allowed",
                    null,
                ),
            )
        }
    }
}

private class AudienceValidator(
    private val audience: String,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult =
        if ((token.audience ?: emptyList()).contains(audience)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(
                    "invalid_token",
                    "The required audience '$audience' is missing",
                    null,
                ),
            )
        }
}
